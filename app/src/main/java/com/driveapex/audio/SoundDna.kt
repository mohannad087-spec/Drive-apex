package com.driveapex.audio

/**
 * Continuous sonic-character controls. Values are normalized and intentionally
 * independent from any real vehicle brand or sound recording.
 */
data class SoundDna(
    val aggression: Float = 0.55f,
    val futuristic: Float = 0.70f,
    val mechanicalBody: Float = 0.35f,
    val inverterPresence: Float = 0.68f,
    val lowEnd: Float = 0.50f,
    val highFrequency: Float = 0.52f,
    val cabinFocus: Float = 0.65f
) {
    fun sanitized(): SoundDna = copy(
        aggression = aggression.coerceIn(0f, 1f),
        futuristic = futuristic.coerceIn(0f, 1f),
        mechanicalBody = mechanicalBody.coerceIn(0f, 1f),
        inverterPresence = inverterPresence.coerceIn(0f, 1f),
        lowEnd = lowEnd.coerceIn(0f, 1f),
        highFrequency = highFrequency.coerceIn(0f, 1f),
        cabinFocus = cabinFocus.coerceIn(0f, 1f)
    )
}

object SoundDnaPresets {
    val balanced = SoundDna()

    val gt = SoundDna(
        aggression = 0.48f,
        futuristic = 0.82f,
        mechanicalBody = 0.30f,
        inverterPresence = 0.74f,
        lowEnd = 0.46f,
        highFrequency = 0.62f,
        cabinFocus = 0.72f
    )

    val hyper = SoundDna(
        aggression = 0.92f,
        futuristic = 0.94f,
        mechanicalBody = 0.52f,
        inverterPresence = 0.86f,
        lowEnd = 0.66f,
        highFrequency = 0.78f,
        cabinFocus = 0.82f
    )
}
