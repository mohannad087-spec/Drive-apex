package com.driveapex.audio

/** Original DriveApex sound architecture: layers are placeholders for licensed/owned samples. */
object SoundLibrary {
    val apexPerformance = listOf(
        SoundLayer("motor-core", 700f, 12000f, 0f, 1f, 1f, 1, 0.55f, stereoPosition = -0.15f),
        SoundLayer("inverter-whine", 1500f, 12000f, 0.15f, 1f, 2.2f, 2, 0.20f, stereoPosition = 0.10f),
        SoundLayer("harmonic-rise", 2500f, 12000f, 0.25f, 1f, 3.0f, 3, 0.16f, stereoPosition = 0.18f,
            sceneBias = mapOf(AudioScene.HARD_ACCELERATION to 1.25f, AudioScene.LAUNCH to 1.35f)),
        SoundLayer("low-body", 700f, 6500f, 0f, 1f, 0.5f, 1, 0.24f, stereoPosition = -0.25f),
        SoundLayer("acceleration-presence", 1800f, 10000f, 0.35f, 1f, 1.35f, 2, 0.20f,
            sceneBias = mapOf(AudioScene.ACCELERATION to 1.15f, AudioScene.HARD_ACCELERATION to 1.45f)),
        SoundLayer("regen-character", 900f, 7000f, 0f, 0.25f, 0.8f, 2, 0.14f,
            sceneBias = mapOf(AudioScene.REGENERATION to 1.8f, AudioScene.COAST to 0.35f)),
        SoundLayer("high-speed-air", 4500f, 12000f, 0.1f, 1f, 0.7f, 1, 0.12f,
            sceneBias = mapOf(AudioScene.HIGH_SPEED to 1.6f)),
        SoundLayer("launch-impact", 700f, 4500f, 0.75f, 1f, 0.35f, 1, 0.10f,
            sceneBias = mapOf(AudioScene.LAUNCH to 2.0f))
    )
}
