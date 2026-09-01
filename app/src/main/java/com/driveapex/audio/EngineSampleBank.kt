package com.driveapex.audio

/**
 * A set of real engine recordings, indexed by the rpm each was captured at.
 *
 * This is the data half of sample-based playback. Synthesis builds a note from
 * partials; this instead plays a recording of the real thing back at the rate
 * that puts it at the rpm being asked for, and crossfades between the two
 * recordings nearest that rpm. It is how racing games sound like cars.
 *
 * Two things make or break it, and both are properties of the recordings rather
 * than of the code:
 *
 *  - The loop region must be seamless. A loop whose end does not meet its start
 *    clicks once per revolution of the loop, which is the most obvious artefact
 *    there is. docs/SAMPLE_RECORDING_SPEC.md is the capture contract.
 *  - The rpm grid must be close enough that no layer is ever stretched far.
 *    Playing a 2000 rpm recording at 4000 halves its length and doubles every
 *    frequency in it, which no longer sounds like the engine it came from. The
 *    spec's grid keeps the worst stretch under about 1.4x.
 */
data class EngineSampleBank(
    val id: String,
    val name: String,
    /** Sustained loops, in any order; the renderer sorts them. */
    val layers: List<Layer>,
    /** Output trim, so a bank can be levelled without re-rendering the files. */
    val level: Float = 1f
) {
    /**
     * One recording, captured at a steady rpm under a known throttle.
     *
     * @param rpm the engine speed this was recorded at; the playback rate is
     *   the requested rpm divided by this.
     * @param pcm mono samples in -1..1. Stereo sources are folded down on load:
     *   the width here comes from the two load layers and the body of the mix,
     *   not from the source file, so that a layer can be repitched without its
     *   channels drifting apart.
     * @param loopStart first sample of the seamless region.
     * @param loopEnd one past the last sample of it.
     * @param load 0 for a closed throttle (overrun), 1 for full load. The
     *   renderer crossfades between the two by pedal position, which is what
     *   makes a real engine sound different coasting than pulling at the same
     *   rpm -- the single biggest thing a one-layer sample player gets wrong.
     */
    data class Layer(
        val rpm: Float,
        val pcm: FloatArray,
        val loopStart: Int,
        val loopEnd: Int,
        val load: Float
    ) {
        val loopLength: Int get() = loopEnd - loopStart

        /** Sound enough to play: a real loop region inside a real buffer. */
        fun isUsable(): Boolean =
            rpm > 0f && pcm.isNotEmpty() &&
                loopStart >= 0 && loopEnd <= pcm.size && loopLength >= MIN_LOOP_SAMPLES

        // Generated equals/hashCode would compare the whole sample buffer, which
        // is both slow and pointless; identity is what callers mean here.
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)

        companion object {
            /**
             * Shorter than this and the loop is a buzz at its own repeat rate
             * rather than a sustained note: 512 samples repeats at 86Hz.
             */
            const val MIN_LOOP_SAMPLES = 512
        }
    }

    /** The usable layers at a given throttle end, lowest rpm first. */
    fun laneFor(load: Float): List<Layer> =
        layers.filter { it.isUsable() && it.load == load }.sortedBy { it.rpm }

    /** A bank is playable once either throttle end has two rpm points to cross between. */
    fun isPlayable(): Boolean = laneFor(0f).size >= 2 || laneFor(1f).size >= 2

    /** The rpm range the recordings actually cover, or null when unplayable. */
    fun rpmRange(): ClosedFloatingPointRange<Float>? {
        val usable = layers.filter { it.isUsable() }
        if (usable.isEmpty()) return null
        return usable.minOf { it.rpm }..usable.maxOf { it.rpm }
    }
}
