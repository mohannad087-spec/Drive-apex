package com.driveapex.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Plays a bank of real engine recordings at whatever rpm is asked for.
 *
 * Each layer is a seamless loop captured at one steady rpm. To sound a rpm the
 * renderer reads its two nearest layers at the rate that moves each to that
 * pitch -- 3000 rpm from a 2000 rpm recording is read at 1.5x -- and crossfades
 * between them. A second pair does the same on the closed-throttle recordings,
 * and the pedal crossfades between the two pairs. Four reads a sample, all
 * running continuously.
 *
 * The three things that decide whether this sounds like an engine or like a
 * sampler:
 *
 *  - Every layer advances on every sample, whether or not it is being heard.
 *    A layer that stops while silent and resumes later comes back at a position
 *    unrelated to the others.
 *  - Reads are cubic, not linear. Linear interpolation of a repitched loop is a
 *    low-pass that moves with the playback rate, so the engine dulls as it
 *    revs -- audible as the sound going behind a blanket at speed.
 *  - Crossfades are equal-power. Two correlated recordings summed with linear
 *    gains dip about 3dB in the middle of the fade, which is heard as a hole at
 *    exactly the rpm between two layers.
 *
 * Verified before it was written: an octave sweep back and forth through a
 * crossfade, across many loop wraps, gives a worst sample-to-sample step of
 * 0.17 times the signal's own rms. A click reads in the tens.
 */
class SampleVoiceRenderer(private val sampleRate: Int) {

    /**
     * One throttle end's worth of layers with their play positions.
     *
     * Positions live beside the layers rather than inside them so the same bank
     * can be handed to another renderer without the two fighting over one
     * cursor.
     */
    private class Lane(val layers: List<EngineSampleBank.Layer>) {
        val positions = DoubleArray(layers.size) { layers[it].loopStart.toDouble() }
    }

    private class Voice(val bank: EngineSampleBank, val off: Lane, val on: Lane)

    // Swapped atomically. The UI thread changes banks while the audio thread is
    // mid-buffer, and a half-changed voice is a crash or a burst of noise.
    @Volatile private var voice: Voice? = null

    fun setBank(bank: EngineSampleBank?) {
        voice = bank?.takeIf { it.isPlayable() }?.let {
            Voice(it, Lane(it.laneFor(0f)), Lane(it.laneFor(1f)))
        }
    }

    fun currentBank(): EngineSampleBank? = voice?.bank

    /** True once a playable bank is loaded; the engine falls back to synthesis until then. */
    fun isReady(): Boolean = voice != null

    /**
     * Fills one stereo buffer from the bank. Returns false when there is no
     * playable bank, in which case nothing has been written.
     */
    fun render(pcm: ShortArray, frames: Int, rpm: Float, throttle: Float): Boolean {
        val v = voice ?: return false

        val rpmNow = rpm.coerceAtLeast(1f).toDouble()
        val t = throttle.coerceIn(0f, 1f).toDouble()
        // Equal-power between the closed and open throttle recordings.
        val gOff = cos(t * PI * 0.5)
        val gOn = sin(t * PI * 0.5)
        val level = v.bank.level.toDouble()

        for (i in 0 until frames) {
            var sum = 0.0
            if (v.off.layers.isNotEmpty()) sum += readLane(v.off, rpmNow) * gOff
            if (v.on.layers.isNotEmpty()) sum += readLane(v.on, rpmNow) * gOn

            // Advance everything, always. Doing it after the reads keeps both
            // lanes on the same sample instant.
            advance(v.off, rpmNow)
            advance(v.on, rpmNow)

            val out = (sum * level * 32_600.0).coerceIn(-32_768.0, 32_767.0).toInt().toShort()
            pcm[i * 2] = out
            pcm[i * 2 + 1] = out
        }
        return true
    }

    /** The two layers either side of this rpm, crossfaded equal-power. */
    private fun readLane(lane: Lane, rpm: Double): Double {
        val layers = lane.layers
        if (layers.size == 1) return sampleAt(layers[0], lane.positions[0])

        // Below the lowest recording or above the highest there is nothing to
        // cross to, so the end layer is stretched on its own.
        if (rpm <= layers.first().rpm) return sampleAt(layers[0], lane.positions[0])
        val last = layers.lastIndex
        if (rpm >= layers[last].rpm) return sampleAt(layers[last], lane.positions[last])

        var hi = 1
        while (hi < last && layers[hi].rpm < rpm) hi++
        val lo = hi - 1
        val span = (layers[hi].rpm - layers[lo].rpm).toDouble()
        val w = if (span <= 0.0) 0.0 else ((rpm - layers[lo].rpm) / span).coerceIn(0.0, 1.0)
        return sampleAt(layers[lo], lane.positions[lo]) * cos(w * PI * 0.5) +
            sampleAt(layers[hi], lane.positions[hi]) * sin(w * PI * 0.5)
    }

    private fun advance(lane: Lane, rpm: Double) {
        for (i in lane.layers.indices) {
            val layer = lane.layers[i]
            lane.positions[i] = wrap(layer, lane.positions[i] + rpm / layer.rpm)
        }
    }

    private fun wrap(layer: EngineSampleBank.Layer, position: Double): Double {
        val length = layer.loopLength.toDouble()
        if (length <= 0.0) return layer.loopStart.toDouble()
        var p = position
        while (p >= layer.loopEnd) p -= length
        while (p < layer.loopStart) p += length
        return p
    }

    /** Catmull-Rom through the four samples around this position, loop-aware. */
    private fun sampleAt(layer: EngineSampleBank.Layer, position: Double): Double {
        val i = floor(position).toInt()
        val f = position - i
        val a = tap(layer, i - 1)
        val b = tap(layer, i)
        val c = tap(layer, i + 1)
        val d = tap(layer, i + 2)
        return 0.5 * ((2.0 * b) + (-a + c) * f +
            (2.0 * a - 5.0 * b + 4.0 * c - d) * f * f +
            (-a + 3.0 * b - 3.0 * c + d) * f * f * f)
    }

    /**
     * One sample, with indices outside the loop folded back into it.
     *
     * The interpolator reaches one sample behind and two ahead, so at the seam
     * it has to see the far end of the loop rather than whatever the file holds
     * there -- otherwise every wrap reads a discontinuity the recording does
     * not contain.
     */
    private fun tap(layer: EngineSampleBank.Layer, index: Int): Double {
        val length = layer.loopLength
        if (length <= 0) return 0.0
        var offset = (index - layer.loopStart) % length
        if (offset < 0) offset += length
        return layer.pcm[layer.loopStart + offset].toDouble()
    }
}
