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
    /**
     * Whether this recording actually sounds different pulling than coasting.
     *
     * Measured by the builder, and true for almost none of them: a free rev in
     * neutral is not loaded either on the way up or the way down, so its rising
     * and falling stretches differ by a few percent of spectral centroid and
     * nothing more. Of the seven that ship, only the F-Type clears the bar, and
     * it does so because it crackles on the overrun.
     *
     * When it is false the two lanes are pooled and share a cursor, so pedal
     * position cannot pull unrelated material out of the same rpm and pretend
     * it is load.
     */
    val pedalLanes: Boolean,
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
     * Frame indices grouped by where they sit in the recording's own rev range,
     * and by whether the engine was pulling or coasting at the time.
     *
     * Two sets, because a rev recording contains both: the stretches where the
     * frequency is climbing are the engine under throttle, and the stretches
     * where it is falling are the engine on the overrun. Measured across the
     * seven recordings that ship, 25-40% of each is climbing and 26-56% is
     * falling, so both lanes are real material rather than a few frames scraped
     * together.
     *
     * This is what gives the pedal something to do. Without it the throttle
     * could only change the volume, which is why the recorded voices answered
     * the pedal so much worse than the synthesised ones -- those reshape every
     * harmonic with load, on every sample.
     *
     * Bucketed once at load, because the player asks "where was this engine at
     * four fifths of its range, pulling?" thousands of times a second and
     * scanning the map for it would be the most expensive thing on the audio
     * thread.
     */
    private val pullingBuckets: Array<IntArray>
    private val coastingBuckets: Array<IntArray>

    /**
     * Whether this rpm has enough of both to tell the two apart.
     *
     * Where it does not, both lanes hold the same pooled frames and must also
     * share a cursor -- otherwise pedal-down and pedal-up walk the same pool at
     * different offsets and land on unrelated material, which is a difference
     * with no meaning behind it. Measured on the 1970 Charger and the F-Type,
     * whose maps are only a quarter climbing, that showed up as pedal-down
     * coming out 40% darker than pedal-up: not the engine loading up, just two
     * cursors in different places.
     */
    private val laneIsReal = BooleanArray(BUCKETS)

    /** Rotating cursor per bucket, so consecutive seeks are not the same audio. */
    private val pullingCursors = IntArray(BUCKETS)
    private val coastingCursors = IntArray(BUCKETS)

    init {
        normaliseLevel()
        val pulling = Array(BUCKETS) { ArrayList<Int>() }
        val coasting = Array(BUCKETS) { ArrayList<Int>() }
        val span = (highHz - lowHz).coerceAtLeast(0.001f)
        val floor = loudnessFloor()
        for (i in hz.indices) {
            val f = hz[i]
            if (f <= 0f) continue
            if (i < rms.size && rms[i] < floor) continue
            val fraction = ((f - lowHz) / span).coerceIn(0f, 1f)
            val bucket = (fraction * (BUCKETS - 1)).roundToInt().coerceIn(0, BUCKETS - 1)
            // A read runs forward from here, so a frame too close to the end of
            // the file would run off it.
            if ((i + 1) * hopSamples + MAX_GRAIN >= pcm.size) continue

            val slope = slopeAt(i)
            // Flat stretches belong to both: an engine holding a steady rpm
            // sounds like neither pulling hard nor coasting, and it is the one
            // thing both lanes can honestly borrow.
            if (slope > SLOPE) pulling[bucket].add(i)
            else if (slope < -SLOPE) coasting[bucket].add(i)
            else { pulling[bucket].add(i); coasting[bucket].add(i) }
        }
        for (i in 0 until BUCKETS) {
            laneIsReal[i] = pedalLanes &&
                pulling[i].size >= MIN_LANE && coasting[i].size >= MIN_LANE
        }
        pullingBuckets = fill(pulling, coasting)
        coastingBuckets = fill(coasting, pulling)
    }

    /**
     * Brings every recording to the same loudness before anything else runs.
     *
     * These are seven recordings by seven people, and only their peaks happen
     * to match -- each was normalised to about 0.97 by whoever made it, while
     * their loudness spans 0.189 to 0.272 rms. That is audible as one voice
     * being noticeably bigger than the next, and it is also what made the 1970
     * Charger clip: measured through the player at SPORT's level scale it
     * peaked at 1.03, which is a hard-clipped sample every time it got there.
     *
     * Measured over the parts of the recording that are above the loudness
     * gate, so a long tail of silence in one clip does not make it come out
     * louder than the rest.
     */
    private fun normaliseLevel() {
        val floor = loudnessFloor()
        var sum = 0.0
        var count = 0L
        for (frame in rms.indices) {
            if (rms[frame] < floor) continue
            val from = frame * hopSamples
            val to = ((frame + 1) * hopSamples).coerceAtMost(pcm.size)
            for (i in from until to) { sum += pcm[i].toDouble() * pcm[i]; count++ }
        }
        if (count == 0L) return
        val level = Math.sqrt(sum / count)
        if (level <= 1e-6) return
        val gain = (TARGET_RMS / level).coerceIn(0.25, 4.0).toFloat()
        for (i in pcm.indices) pcm[i] *= gain
    }

    /** Frequency change per frame, as a fraction, over about 120ms. */
    private fun slopeAt(i: Int): Float {
        if (i < 3 || i + 3 >= hz.size || hz[i] <= 0f) return 0f
        val before = hz[i - 3]
        val after = hz[i + 3]
        if (before <= 0f || after <= 0f) return 0f
        return (after - before) / (6f * hz[i])
    }

    /**
     * Buckets with the gaps filled, rpm first.
     *
     * When a lane is thin at some point of the range, the fill takes the other
     * lane **at the same rpm** rather than the same lane at a different rpm.
     * Getting the rpm right matters more than getting the pedal right, and the
     * first version had this the wrong way round: on the 1970 Charger, whose
     * map is only a quarter climbing, the pulling lane borrowed from far up the
     * range and the pedal-down sound came out ten times darker than pedal-up.
     * Measured as a spectral centroid of 160Hz against 1631Hz -- not a subtle
     * difference in timbre, a different engine speed entirely.
     */
    private fun fill(lists: Array<ArrayList<Int>>, other: Array<ArrayList<Int>>): Array<IntArray> {
        val out = arrayOfNulls<IntArray>(BUCKETS)
        for (i in 0 until BUCKETS) {
            val own = lists[i]
            // Enough of its own to be worth using as a lane at all.
            if (own.size >= MIN_LANE) { out[i] = own.toIntArray(); continue }
            // Otherwise the same rpm from either lane, which is always the
            // better trade.
            val both = ArrayList(own)
            both.addAll(other[i])
            if (both.isNotEmpty()) { out[i] = both.toIntArray(); continue }
            // And only when this rpm is missing from the recording altogether,
            // the nearest rpm that is not.
            var radius = 1
            while (radius < BUCKETS && out[i] == null) {
                val low = i - radius
                val high = i + radius
                val near = ArrayList<Int>()
                if (low >= 0) { near.addAll(lists[low]); near.addAll(other[low]) }
                if (near.isEmpty() && high < BUCKETS) {
                    near.addAll(lists[high]); near.addAll(other[high])
                }
                if (near.isNotEmpty()) out[i] = near.toIntArray()
                radius++
            }
        }
        val fallback = out.firstOrNull { it != null && it.isNotEmpty() } ?: IntArray(0)
        return Array(BUCKETS) { out[it] ?: fallback }
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
        pcm.size > MAX_GRAIN * 2 && highHz > lowHz && pullingBuckets.any { it.isNotEmpty() }

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
    fun pick(fraction: Float, jitter: Float, pulling: Boolean): Long {
        val bucket = (fraction.coerceIn(0f, 1f) * (BUCKETS - 1)).roundToInt().coerceIn(0, BUCKETS - 1)
        // Only follow the pedal where the recording can actually answer it.
        val separate = laneIsReal[bucket] && pulling
        val buckets = if (separate) pullingBuckets else coastingBuckets
        val cursors = if (separate) pullingCursors else coastingCursors
        val frames = buckets[bucket]
        if (frames.isEmpty()) return 0L
        val cursor = cursors[bucket]
        cursors[bucket] = (cursor + 1) % frames.size
        val frame = frames[cursor]
        val offset = (jitter * hopSamples).toInt()
        return (frame.toLong() * hopSamples + offset).coerceIn(0L, (pcm.size - MAX_GRAIN - 1).toLong())
    }

    /**
     * Whether the pedal can be answered with different material at this point
     * of the range, rather than only with a different level and timbre.
     */
    fun lanesAt(fraction: Float): Boolean {
        val bucket = (fraction.coerceIn(0f, 1f) * (BUCKETS - 1)).roundToInt().coerceIn(0, BUCKETS - 1)
        return laneIsReal[bucket]
    }

    /** The firing frequency at a sample offset, for the rate correction. */
    fun hzAtSample(sample: Long): Float {
        val frame = (sample / hopSamples).toInt().coerceIn(0, hz.lastIndex)
        val value = hz[frame]
        return if (value > 0f) value else (lowHz + highHz) * 0.5f
    }

    companion object {
        const val BUCKETS = 64
        /**
         * How fast the recording's frequency must be moving to count as pulling
         * or coasting: 0.4% per 20ms frame, which is 20% per second.
         */
        const val SLOPE = 0.004f
        /**
         * How many frames a lane needs at one rpm before it is used as a lane.
         *
         * Below this the pedal is not worth the risk of fetching the wrong
         * engine speed, and both lanes are pooled at that rpm instead.
         */
        const val MIN_LANE = 3
        /**
         * The loudness every recording is brought to, chosen as the median of
         * the seven so the change is a level match rather than a level cut.
         * Through the player at SPORT this leaves the worst peak at 0.93.
         */
        const val TARGET_RMS = 0.17
        /** Longest grain the player will ask for, in samples. */
        const val MAX_GRAIN = 4096
    }
}
