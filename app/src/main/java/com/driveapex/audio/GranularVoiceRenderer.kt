package com.driveapex.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/**
 * Plays a real engine at any rpm by reading grains out of a rev recording.
 *
 * This is the technique the professional engine used in racing games is built
 * on: instead of looping a recording and stretching its pitch, the player keeps
 * jumping to the place in a ten-second rev where the engine was at the rpm now
 * being asked for, takes a few dozen milliseconds of it, and crossfades that
 * into the last piece it took. Nothing is repitched while the requested rpm
 * falls inside the range the recording covers, so what comes out is the
 * recording -- combustion, exhaust, resonance and all -- rather than a
 * synthesiser's idea of it.
 *
 * Three things decide whether this sounds like an engine or like a broken CD:
 *
 *  - **Two streams, always overlapping.** A grain is faded in while its
 *    predecessor is faded out, on a raised cosine. Two Hann windows overlapping
 *    by exactly half sum to one, so the crossfade neither dips nor peaks.
 *  - **The read position moves.** Taking every grain from the same handful of
 *    samples at a steady rpm makes the grain rate itself audible as a buzz;
 *    jittering the position inside the moment removes it.
 *  - **Grains are whole cycles.** A grain that is a whole number of firing
 *    periods long starts and ends at the same point of the engine's cycle, so
 *    the crossfade lines combustion up with combustion instead of smearing two
 *    unrelated moments together.
 */
class GranularVoiceRenderer(private val sampleRate: Int) {

    /** One of the two overlapping readers. */
    private class Stream {
        var position = 0.0
        var rate = 1.0
        var length = 0
        var index = 0
        var active = false
    }

    private val streams = arrayOf(Stream(), Stream())
    private var nextSpawn = 0

    @Volatile private var source: GrainSource? = null

    /** Simple deterministic noise for the position jitter; no allocation. */
    private var noise = 0x2545F491L

    fun setSource(value: GrainSource?) {
        source = value?.takeIf { it.isPlayable() }
        if (source == null) {
            streams.forEach { it.active = false }
        }
    }

    fun currentSource(): GrainSource? = source

    fun isReady(): Boolean = source != null

    /**
     * Fills one stereo buffer. Returns false when there is nothing to play, in
     * which case nothing has been written.
     *
     * @param rpm the rpm the voice should sound, after any gearbox.
     * @param idleRpm and @param limiterRpm the range that recording is mapped
     *   onto: the bottom of the recording sounds at idle, the top at the
     *   limiter, and everything between lands where it falls.
     */
    fun render(
        pcm: ShortArray,
        frames: Int,
        rpm: Float,
        throttle: Float,
        idleRpm: Float,
        limiterRpm: Float,
        level: Float
    ): Boolean {
        val src = source ?: return false
        val span = (limiterRpm - idleRpm).coerceAtLeast(1f)
        val fraction = ((rpm - idleRpm) / span).coerceIn(0f, 1f)

        // Where in the recording's own range that lands, and what the engine
        // was doing there. Anything outside the recorded band is the nearest
        // end repitched, which is the one place this behaves like a stretcher.
        val targetHz = src.lowHz + fraction * (src.highHz - src.lowHz)

        // Lifting off should not silence a real recording -- an engine on the
        // overrun is quieter, not absent.
        val loadGain = 0.72f + 0.28f * throttle.coerceIn(0f, 1f)
        val gain = (level * loadGain).toDouble()

        for (i in 0 until frames) {
            var sum = 0.0
            for (stream in streams) {
                if (!stream.active) continue
                sum += read(src, stream) * window(stream)
                stream.index++
                stream.position += stream.rate
                if (stream.index >= stream.length) stream.active = false
            }

            // A new grain starts halfway through the running one, which is what
            // makes the two windows overlap by exactly half.
            if (nextSpawn <= 0) {
                spawn(src, fraction, targetHz)
            }
            nextSpawn--

            val out = (sum * gain * 32_600.0).coerceIn(-32_768.0, 32_767.0).toInt().toShort()
            pcm[i * 2] = out
            pcm[i * 2 + 1] = out
        }
        return true
    }

    /**
     * Starts the next grain in whichever stream is free.
     *
     * Its length is a whole number of firing periods at the frequency it is
     * being played back at, clamped to something between a fiftieth and a
     * twelfth of a second: shorter and the grain rate becomes a tone of its
     * own, longer and the voice stops following the pedal.
     */
    private fun spawn(src: GrainSource, fraction: Float, targetHz: Float) {
        val stream = streams.firstOrNull { !it.active } ?: streams[0]
        val start = src.pick(fraction, nextJitter())
        val sourceHz = src.hzAtSample(start)
        // Bounded, so a request far outside the recorded range degrades into a
        // stretch rather than into a chipmunk.
        val rate = (targetHz / sourceHz).coerceIn(MIN_RATE, MAX_RATE)

        val periodSamples = sampleRate / targetHz.coerceAtLeast(20f)
        val periods = (MIN_GRAIN / periodSamples).toInt().coerceAtLeast(3)
        val length = (periods * periodSamples).toInt()
            .coerceIn(MIN_GRAIN, GrainSource.MAX_GRAIN)

        stream.position = start.toDouble()
        stream.rate = rate.toDouble()
        stream.length = length
        stream.index = 0
        stream.active = true
        nextSpawn = length / 2
    }

    /** Raised cosine over the grain. Two of these at half overlap sum to one. */
    private fun window(stream: Stream): Double {
        val t = stream.index.toDouble() / stream.length
        return 0.5 - 0.5 * cos(2.0 * PI * t)
    }

    /** Catmull-Rom through the four samples around the read position. */
    private fun read(src: GrainSource, stream: Stream): Double {
        val pcm = src.pcm
        val i = floor(stream.position).toInt()
        if (i < 1 || i + 2 >= pcm.size) return 0.0
        val f = stream.position - i
        val a = pcm[i - 1].toDouble()
        val b = pcm[i].toDouble()
        val c = pcm[i + 1].toDouble()
        val d = pcm[i + 2].toDouble()
        return 0.5 * ((2.0 * b) + (-a + c) * f +
            (2.0 * a - 5.0 * b + 4.0 * c - d) * f * f +
            (-a + 3.0 * b - 3.0 * c + d) * f * f * f)
    }

    /** -0.5 to 0.5, from a cheap xorshift; the audio thread allocates nothing. */
    private fun nextJitter(): Float {
        noise = noise xor (noise shl 13)
        noise = noise xor (noise ushr 7)
        noise = noise xor (noise shl 17)
        return ((noise ushr 40).toFloat() / 16_777_216f) - 0.5f
    }

    private companion object {
        /** About 23ms at 44.1kHz. */
        const val MIN_GRAIN = 1024
        const val MIN_RATE = 0.55f
        const val MAX_RATE = 1.9f
    }
}
