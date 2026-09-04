package com.driveapex.audio

import kotlin.math.roundToInt

/**
 * A rev recording with a map of where the engine was at each point in it.
 *
 * This is the material a granular voice plays. It is not a loop and is never
 * meant to be one: it is ten-odd seconds of a real engine climbing, plus the
 * firing frequency measured at every 20ms of it, so the player can go to the
 * place in the recording where the engine was at the rpm being asked for and
 * read a grain from there.
 *
 * The mapping is by band, not by absolute rpm. What rpm a recording was made at
 * is unknowable without knowing the engine -- a four cylinder fires twice per
 * revolution, a V12 six times -- and it was the wrong question anyway. What
 * matters is that the recording covers some stretch of its own rev range, and
 * that stretch is mapped onto the stretch the voice needs. Inside that band
 * nothing is repitched at all, which is the entire reason this exists rather
 * than another loop stretcher.
 */
class GrainSource(
    val id: String,
    val name: String,
    /** Who recorded it. Shown in the app: these are other people's recordings. */
    val credit: String,
    /** 1 for an acceleration ramp, 0 for overrun. */
    val load: Float,
    val pcm: FloatArray,
    val sampleRate: Int,
    /** Firing frequency per hop, in Hz. Zero where nothing was measurable. */
    private val hz: FloatArray,
    /**
     * Loudness per hop.
     *
     * The frequency map is carried across gaps so it stays continuous, which
     * means the silence after a rev inherits the frequency of the loudest
     * moment in it. Without this gate the top of the rev range read its grains
     * out of that silence: measured on the Corvette recording, the voice fell
     * from 0.19 rms to 0.02 above 6500 rpm -- all but gone at exactly the point
     * a driver is listening hardest.
     */
    private val rms: FloatArray,
    val hopMs: Float,
    val lowHz: Float,
    val highHz: Float
) {
    val hopSamples: Int = (sampleRate * hopMs / 1000f).roundToInt().coerceAtLeast(1)

    /**
     * Frame indices grouped by where they sit in the recording's own rev range.
     *
     * A granular player asks the same question thousands of times a second --
     * "where was this engine at four fifths of its range?" -- and answering it
     * by scanning the map each time would be the most expensive thing in the
     * audio thread. Bucketed once at load, it is an array index.
     */
    private val buckets: Array<IntArray>

    /** Rotating cursor per bucket, so consecutive grains are not the same audio. */
    private val cursors = IntArray(BUCKETS)

    init {
        val lists = Array(BUCKETS) { ArrayList<Int>() }
        val span = (highHz - lowHz).coerceAtLeast(0.001f)
        val floor = loudnessFloor()
        for (i in hz.indices) {
            val f = hz[i]
            if (f <= 0f) continue
            if (i < rms.size && rms[i] < floor) continue
            val fraction = ((f - lowHz) / span).coerceIn(0f, 1f)
            val bucket = (fraction * (BUCKETS - 1)).roundToInt().coerceIn(0, BUCKETS - 1)
            // A grain reads forward from here, so a frame too close to the end
            // of the file would run off it.
            if ((i + 1) * hopSamples + MAX_GRAIN < pcm.size) lists[bucket].add(i)
        }
        // Empty buckets borrow from the nearest neighbour that has anything, so
        // a recording that skipped part of its range still answers every ask.
        buckets = Array(BUCKETS) { lists[it].toIntArray() }
        for (i in buckets.indices) {
            if (buckets[i].isNotEmpty()) continue
            var radius = 1
            while (radius < BUCKETS) {
                val low = i - radius
                val high = i + radius
                if (low >= 0 && buckets[low].isNotEmpty()) { buckets[i] = buckets[low]; break }
                if (high < BUCKETS && buckets[high].isNotEmpty()) { buckets[i] = buckets[high]; break }
                radius++
            }
        }
    }

    /**
     * A quarter of the median loudness.
     *
     * A fraction of the median rather than a fixed number, because a recording's
     * own gain is arbitrary; a quarter is low enough to keep the quiet end of an
     * overrun and high enough to reject a room tone.
     */
    private fun loudnessFloor(): Float {
        val live = rms.filter { it > 0f }.sorted()
        if (live.isEmpty()) return 0f
        return live[live.size / 2] * 0.25f
    }

    /** True once there is enough audio and enough map to play from. */
    fun isPlayable(): Boolean =
        pcm.size > MAX_GRAIN * 2 && highHz > lowHz && buckets.any { it.isNotEmpty() }

    /** How much of a rev range this recording covers, as a ratio. */
    fun coverage(): Float = if (lowHz > 0f) highHz / lowHz else 1f

    /**
     * A sample offset in the recording where the engine was at this point of
     * its range, and the frequency it was actually at there.
     *
     * The jitter is deliberate. Reading the same few samples for every grain at
     * a steady rpm makes the loop period audible as a buzz at the grain rate;
     * moving the read around inside the moment removes it without changing what
     * is being read.
     */
    fun pick(fraction: Float, jitter: Float): Long {
        val bucket = (fraction.coerceIn(0f, 1f) * (BUCKETS - 1)).roundToInt().coerceIn(0, BUCKETS - 1)
        val frames = buckets[bucket]
        if (frames.isEmpty()) return 0L
        val cursor = cursors[bucket]
        cursors[bucket] = (cursor + 1) % frames.size
        val frame = frames[cursor]
        val offset = (jitter * hopSamples).toInt()
        return (frame.toLong() * hopSamples + offset).coerceIn(0L, (pcm.size - MAX_GRAIN - 1).toLong())
    }

    /** The firing frequency at a sample offset, for the rate correction. */
    fun hzAtSample(sample: Long): Float {
        val frame = (sample / hopSamples).toInt().coerceIn(0, hz.lastIndex)
        val value = hz[frame]
        return if (value > 0f) value else (lowHz + highHz) * 0.5f
    }

    companion object {
        const val BUCKETS = 64
        /** Longest grain the player will ask for, in samples. */
        const val MAX_GRAIN = 4096
    }
}
