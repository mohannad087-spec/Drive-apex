package com.driveapex.audio

/**
 * Tunable recipe for one DriveApex engine character.
 * Sample-backed profiles can reuse the same parameters when real recordings are added.
 */
data class SoundProfile(
    val id: String,
    val name: String,
    val maxRpm: Float = 7000f,
    val fundamentalGain: Float = 0.55f,
    val harmonic2Gain: Float = 0.18f,
    val harmonic3Gain: Float = 0.08f,
    val lowBodyGain: Float = 0.20f,
    val loadResponse: Float = 1.0f,
    val aggression: Float = 0.0f
)

object SoundProfiles {
    val apexSport = SoundProfile(
        id = "apex_sport",
        name = "Apex Sport",
        maxRpm = 7500f,
        fundamentalGain = 0.58f,
        harmonic2Gain = 0.20f,
        harmonic3Gain = 0.10f,
        lowBodyGain = 0.18f,
        loadResponse = 1.08f,
        aggression = 0.18f
    )

    val deepPerformance = SoundProfile(
        id = "deep_performance",
        name = "Deep Performance",
        maxRpm = 6800f,
        fundamentalGain = 0.52f,
        harmonic2Gain = 0.16f,
        harmonic3Gain = 0.06f,
        lowBodyGain = 0.30f,
        loadResponse = 0.95f,
        aggression = 0.04f
    )

    val profiles = listOf(apexSport, deepPerformance)
}
