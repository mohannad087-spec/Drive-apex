package com.driveapex.audio

/** Runtime state for one sample voice. Actual PCM decoding is delegated to the audio I/O layer. */
data class SampleVoice(
    val sample: SampleDefinition,
    var gain: Float = 0f,
    var targetGain: Float = 0f,
    var pitchRatio: Float = 1f,
    var positionFrames: Long = 0L,
    var active: Boolean = false
) {
    fun stepGain(smoothing: Float = 0.08f) {
        gain += (targetGain - gain) * smoothing.coerceIn(0.001f, 1f)
        if (gain < 0.0005f && targetGain <= 0f) active = false
    }

    fun reset() {
        gain = 0f
        targetGain = 0f
        pitchRatio = 1f
        positionFrames = 0L
        active = false
    }
}
