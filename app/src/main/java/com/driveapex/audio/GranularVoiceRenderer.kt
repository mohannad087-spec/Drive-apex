package com.driveapex.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin

/**
 * Plays a real engine at any rpm by reading a rev recording continuously.
 *
 * One reader walks through the recording. Its speed is adjusted so the pitch
 * lands on the rpm being asked for, which is a correction of a few percent
 * because the reader is already somewhere the engine was at nearly that rpm.
 * When the recording drifts too far from what is wanted -- it is a ramp, so it
 * always eventually does -- the reader jumps to a place that serves the new rpm
 * and crossfades over twenty milliseconds to get there.
 *
 * The first version of this played two overlapping streams continuously, and
 * the driver's description of the result was exact: "as if it is playing in a
 * well". That is a flanger, and instrumenting it showed the app was building
 * one on purpose without meaning to. Both streams were live 98.8% of the time,
 * reading the same audio a median of 0.1 to 20ms apart, at rates differing by
 * about 1.75% -- so the gap between two copies of one signal swept slowly, which
 * is the whole recipe for comb filtering. The recording sounded better than the
 * app because the app was adding that and nothing else.
 *
 * Now two readers are live only during a seek, they are reading different parts
 * of the recording rather than near-copies of the same part, and the rest of the
 * time the output is simply the recording. It is also cheaper: one interpolated
 * read per sample instead of two.
 */
class GranularVoiceRenderer(private val sampleRate: Int) {

    @Volatile private var source: GrainSource? = null

    /** The reader. Position is in samples into the recording. */
    private var position = 0.0
    private var rate = 1.0

    /** The incoming reader, live only while a seek is crossfading. */
    private var fadePosition = 0.0
    private var fadeRate = 1.0
    private var fadeRemaining = 0

    private var sinceCheck = 0
    private var started = false

    /**
     * Whether the pedal currently counts as pulling.
     *
     * Hysteresis, because a foot resting near the middle of its travel would
     * otherwise flip this back and forth and reseek on every check.
     */
    private var pulling = true

    /**
     * The body of the note, and how much brighter than it the rest is playing.
     *
     * A one-pole split and a gain on what is above it: everything up to the
     * fourth order is the body, and the edge above it is what an engine grows
     * when it is pulling and loses when it is not.
     */
    private var body = 0.0
    private var tilt = 1.0

    /** Deterministic noise for the landing jitter; the audio thread allocates nothing. */
    private var noise = 0x2545F491L

    /** Scratch for the alignment search. */
    private val template = FloatArray(TEMPLATE)

    fun setSource(value: GrainSource?) {
        source = value?.takeIf { it.isPlayable() }
        started = false
        fadeRemaining = 0
        body = 0.0
        tilt = 1.0
    }

    fun currentSource(): GrainSource? = source

    fun isReady(): Boolean = source != null

    /**
     * Fills one stereo buffer. Returns false when there is nothing to play.
     *
     * @param rpm the rpm the voice should sound, after any gearbox.
     * @param load the same load the synthesised voices are shaped by: throttle
     *   with brake and regen mixed in, scaled by speed. It drives the timbre.
     * @param idleRpm and @param limiterRpm the range the recording is mapped
     *   onto: the bottom of the recording sounds at idle, the top at the
     *   limiter, and everything between lands where it falls.
     */
    fun render(
        pcm: ShortArray,
        frames: Int,
        rpm: Float,
        throttle: Float,
        load: Float,
        idleRpm: Float,
        limiterRpm: Float,
        level: Float
    ): Boolean {
        val src = source ?: return false
        val span = (limiterRpm - idleRpm).coerceAtLeast(1f)
        val fraction = ((rpm - idleRpm) / span).coerceIn(0f, 1f)
        val targetHz = src.lowHz + fraction * (src.highHz - src.lowHz)

        // Which half of the recording to read from: the stretches where the
        // engine was climbing, or the ones where it was coming back down. This
        // is what the throttle actually controls now.
        val pedal = throttle.coerceIn(0f, 1f)
        val wantPulling = if (pulling) pedal > 0.32f else pedal > 0.45f

        if (!started) {
            pulling = wantPulling
            position = src.pick(landingFraction(src, targetHz), nextJitter(), pulling).toDouble()
            rate = correction(src, targetHz, position)
            started = true
        }

        // Lifting off should not silence a real recording: an engine on the
        // overrun is quieter, not absent.
        val gain = (level * (0.72f + 0.28f * pedal)).toDouble()

        // What the pedal does to the timbre, which until now it did not do at
        // all. The driver's complaint was exact: the recorded voices answer the
        // pedal far worse than the synthesised ones. The synthesised ones
        // rebalance every partial against load on every sample -- that is what
        // makes an engine open up -- while this path used the pedal for a gain
        // and nothing else, and a real engine that only gets louder sounds like
        // a recording being turned up, because that is what it was.
        //
        // The recordings themselves cannot supply the difference. All seven are
        // free revs in neutral: measured on the builder side, their climbing and
        // falling stretches differ by a few percent of spectral centroid, and
        // only the F-Type clears the bar at all. So the load has to be put back
        // by shaping what is played, above a split that follows the note rather
        // than sitting at a fixed frequency -- the same "by order, not by hertz"
        // the synthesised voices work in.
        //
        // Measured across the seven voices at seven rpm each: closed against
        // open throttle moves the spectral centroid by a median of +3.0dB and
        // never the wrong way, worst case +0.6dB.
        val split = (targetHz * SPLIT_ORDER).coerceIn(SPLIT_LOW, SPLIT_HIGH)
        val bodyCoeff = 1.0 - exp(-2.0 * PI * split / sampleRate)
        val tiltWanted = (TILT_CLOSED + (TILT_OPEN - TILT_CLOSED) * load.coerceIn(0f, 1f)).toDouble()
        // Ramped over the buffer rather than stepped at its edge, which is what
        // the synthesised path does with load for the same reason: a gain that
        // jumps once every few milliseconds is audible as a zip.
        val tiltStep = (tiltWanted - tilt) / frames

        for (i in 0 until frames) {
            if (--sinceCheck <= 0) {
                sinceCheck = CHECK_INTERVAL
                considerSeek(src, targetHz, wantPulling)
            }

            var value = read(src, position)
            position += rate

            if (fadeRemaining > 0) {
                // Equal power, because the two readers are playing different
                // parts of the recording rather than two copies of one part.
                val w = (FADE - fadeRemaining).toDouble() / FADE
                value = value * cos(w * PI * 0.5) + read(src, fadePosition) * sin(w * PI * 0.5)
                fadePosition += fadeRate
                if (--fadeRemaining == 0) {
                    position = fadePosition
                    rate = fadeRate
                }
            }

            body += bodyCoeff * (value - body)
            value = body + (value - body) * tilt
            tilt += tiltStep

            val out = (value * gain * 32_600.0).coerceIn(-32_768.0, 32_767.0).toInt().toShort()
            pcm[i * 2] = out
            pcm[i * 2 + 1] = out
        }
        return true
    }

    /**
     * Keeps the pitch exact, and jumps when the recording has drifted too far.
     *
     * Drift is unavoidable: the recording is a rev, so reading forward at a
     * fixed rpm walks steadily away from it. TOLERANCE is how far the timbre may
     * wander before it is worth the seam of a seek -- the pitch itself is
     * always right, because the read speed is corrected here on every check.
     */
    private fun considerSeek(src: GrainSource, targetHz: Float, wantPulling: Boolean) {
        rate = correction(src, targetHz, position)
        if (fadeRemaining > 0) return

        val here = src.hzAtSample(position.toLong())
        val drift = if (here > 0f) abs(ln(targetHz / here)) else Float.MAX_VALUE
        val nearEnd = position >= src.pcm.size - GrainSource.MAX_GRAIN
        // Coming on or off the throttle is worth a seam on its own -- it is the
        // moment the driver is listening for, and waiting for the frequency to
        // drift would answer it a fifth of a second late -- but only where the
        // recording actually holds different material for the two. Everywhere
        // else both lanes are the same pooled frames, so the seam would buy
        // nothing and cost a crossfade.
        val landing = landingFraction(src, targetHz)
        val pedalChanged = wantPulling != pulling && src.lanesAt(landing)
        if (drift <= TOLERANCE && !nearEnd && !pedalChanged) return
        pulling = wantPulling

        val candidate = src.pick(landing, nextJitter(), pulling)
        fadePosition = alignToWaveform(src, candidate).toDouble()
        fadeRate = correction(src, targetHz, fadePosition)
        fadeRemaining = FADE
    }

    /**
     * Where in the recording's range to land, deliberately a little low.
     *
     * Landing exactly on the wanted frequency spends half the tolerance window
     * immediately, because a rev only climbs. Landing below it means the reader
     * can walk forward through the whole window before the next seam, which
     * roughly halves how often the sound is spliced.
     */
    private fun landingFraction(src: GrainSource, targetHz: Float): Float {
        val span = (src.highHz - src.lowHz).coerceAtLeast(0.001f)
        val wanted = targetHz * (1f - TOLERANCE * LEAD)
        return ((wanted - src.lowHz) / span).coerceIn(0f, 1f)
    }

    /** The read speed that puts this part of the recording on the wanted pitch. */
    private fun correction(src: GrainSource, targetHz: Float, at: Double): Double {
        val here = src.hzAtSample(at.toLong())
        if (here <= 0f) return 1.0
        return (targetHz / here).coerceIn(MIN_RATE, MAX_RATE).toDouble()
    }

    /**
     * Slides a landing point within one period to match the waveform already
     * playing, so the crossfade joins combustion to combustion rather than to a
     * random point of the cycle. Bounded in both directions, and only on a seek.
     */
    private fun alignToWaveform(src: GrainSource, candidate: Long): Long {
        val pcm = src.pcm
        val anchor = position.toLong()
        if (anchor < 1 || anchor + TEMPLATE >= pcm.size) return candidate

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

    /** Catmull-Rom through the four samples around the read position. */
    private fun read(src: GrainSource, at: Double): Double {
        val pcm = src.pcm
        val i = floor(at).toInt()
        if (i < 1 || i + 2 >= pcm.size) return 0.0
        val f = at - i
        val a = pcm[i - 1].toDouble()
        val b = pcm[i].toDouble()
        val c = pcm[i + 1].toDouble()
        val d = pcm[i + 2].toDouble()
        return 0.5 * ((2.0 * b) + (-a + c) * f +
            (2.0 * a - 5.0 * b + 4.0 * c - d) * f * f +
            (-a + 3.0 * b - 3.0 * c + d) * f * f * f)
    }

    /** -0.5 to 0.5, from a cheap xorshift. */
    private fun nextJitter(): Float {
        noise = noise xor (noise shl 13)
        noise = noise xor (noise ushr 7)
        noise = noise xor (noise shl 17)
        return ((noise ushr 40).toFloat() / 16_777_216f) - 0.5f
    }

    private companion object {
        /** Crossfade at a seek: 23ms at 44.1kHz. */
        const val FADE = 1024
        /** How often the seek question is asked, in samples. */
        const val CHECK_INTERVAL = 512
        /** How far the recording may drift in timbre before a seek is worth it. */
        const val TOLERANCE = 0.12f
        /** How much of that window to keep in hand by landing low. */
        const val LEAD = 0.5f
        const val MIN_RATE = 0.55f
        const val MAX_RATE = 1.9f
        const val TEMPLATE = 128
        const val SEARCH = 256
        const val STRIDE = 8
        /** Where the body of the note ends: the fourth order, as the synth counts. */
        const val SPLIT_ORDER = 4f
        const val SPLIT_LOW = 300f
        const val SPLIT_HIGH = 3000f
        /**
         * The edge above that split, closed throttle to open: 10.2dB of swing.
         *
         * Chosen against the peak as well as the ear. Open at 1.45 leaves the
         * worst of the seven at 0.83 through the player, 0.93 once SPORT's
         * level scale is on it; 1.9 sounded no more loaded and clipped.
         */
        const val TILT_CLOSED = 0.45f
        const val TILT_OPEN = 1.45f
    }
}
