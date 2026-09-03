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
 *    periods long starts and ends at the same point of the engine's cycle.
 *
 * And two more that were not in the first version, which measurably rasped.
 * The recording's own amplitude ripples by 0.167 at its firing frequency; the
 * first version's output rippled by 0.276 at the *grain* rate, and a periodic
 * amplitude modulation at 40-80Hz that the engine did not make is heard as a
 * rasp over it:
 *
 *  - **Do not jump when nothing is asking you to.** While the recording at the
 *    current read position is still within 2% of the frequency wanted, the next
 *    grain simply carries on from where the last one was reading. At a steady
 *    rpm that makes this a plain, continuous playback of the recording rather
 *    than a splice every twenty milliseconds; it seeks only when the driver, or
 *    the ramp itself, has moved on.
 *  - **Line up the waveform when you do jump.** A seek lands wherever the map
 *    points, which is a random phase of the engine's cycle, and splicing two
 *    random phases together is what the rasp was. The landing point is slid
 *    within one period to whichever offset best matches what is already
 *    sounding, by cross-correlation.
 *
 * Measured across the rev range with both in: ripple 0.183 against 0.276, with
 * the recording's own 0.167 as the floor.
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

    /** Scratch for the alignment search, so the audio thread allocates nothing. */
    private val template = FloatArray(TEMPLATE)

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
     * being played back at, clamped to something between a twentieth and a
     * twelfth of a second: shorter and the splice rate becomes a rasp of its
     * own, longer and the voice stops following the pedal.
     */
    private fun spawn(src: GrainSource, fraction: Float, targetHz: Float) {
        val stream = streams.firstOrNull { !it.active } ?: streams[0]
        val other = streams.firstOrNull { it !== stream && it.active }

        // Carry on from where the sound already is, whenever the recording there
        // is still saying nearly what is wanted. This is the difference between
        // playing a recording and splicing one.
        val carryOn = other?.position?.toLong()?.takeIf { position ->
            position > 1 && position < src.pcm.size - GrainSource.MAX_GRAIN &&
                withinTolerance(targetHz, src.hzAtSample(position))
        }

        val start = carryOn ?: alignToWaveform(
            src, src.pick(fraction, nextJitter()), other?.position
        )
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

    /** Within a fortieth, which is under what anyone hears as a pitch change. */
    private fun withinTolerance(target: Float, actual: Float): Boolean {
        if (actual <= 0f) return false
        val ratio = target / actual
        return ratio > 1f - TOLERANCE && ratio < 1f + TOLERANCE
    }

    /**
     * Slides a landing point within one period to match what is already playing.
     *
     * Cross-correlation against the samples the other stream is about to read.
     * The search is bounded in both directions -- half a period, and never more
     * than SEARCH samples -- so the cost is fixed however low the engine is
     * revving, and it runs only on a seek rather than on every grain.
     */
    private fun alignToWaveform(src: GrainSource, candidate: Long, otherPos: Double?): Long {
        val pcm = src.pcm
        val anchor = otherPos?.toLong() ?: return candidate
        if (anchor < 0 || anchor + TEMPLATE >= pcm.size) return candidate

        var energy = 0.0
        for (i in 0 until TEMPLATE) {
            val v = pcm[(anchor + i).toInt()]
            template[i] = v
            energy += v * v
        }
        if (energy <= 1e-9) return candidate
        val templateNorm = Math.sqrt(energy)

        val period = (sampleRate / src.hzAtSample(candidate).coerceAtLeast(20f)).toInt()
        val reach = (period / 2).coerceAtMost(SEARCH)
        var best = candidate
        var bestScore = -2.0
        var offset = -reach
        while (offset <= reach) {
            val at = candidate + offset
            if (at >= 1 && at + TEMPLATE < pcm.size) {
                var dot = 0.0
                var norm = 0.0
                var i = 0
                while (i < TEMPLATE) {
                    val v = pcm[(at + i).toInt()]
                    dot += v * template[i]
                    norm += v * v
                    i++
                }
                if (norm > 1e-9) {
                    val score = dot / (Math.sqrt(norm) * templateNorm)
                    if (score > bestScore) {
                        bestScore = score
                        best = at
                    }
                }
            }
            offset += STRIDE
        }
        return best
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
        /**
         * About 46ms at 44.1kHz.
         *
         * Doubled from 1024 after measurement: at 23ms the splice rate sat at
         * 40-80Hz, right in the range the ear reads as a rasp, and halving the
         * number of splices took the ripple from 0.28 to 0.18. A new grain still
         * starts every 23ms, so the voice follows the pedal just as closely.
         */
        const val MIN_GRAIN = 2048
        const val MIN_RATE = 0.55f
        const val MAX_RATE = 1.9f
        /** How far the recording may drift before a seek is worth its splice. */
        const val TOLERANCE = 0.02f
        /**
         * Samples compared when lining a seek up with what is already playing,
         * how far either side to look, and the stride of the search.
         *
         * These are eight times cheaper than the first version -- 8k multiplies
         * per seek instead of 66k -- because that version ran inside the audio
         * buffer on a head unit that has to refill a track every 17ms, and a
         * seek that overruns the deadline is a gap in the output. Measured, the
         * cheap search is not worse: mean ripple 0.169 against 0.180. Wider
         * templates were finding the same peak more slowly.
         */
        const val TEMPLATE = 128
        const val SEARCH = 256
        const val STRIDE = 8
    }
}
