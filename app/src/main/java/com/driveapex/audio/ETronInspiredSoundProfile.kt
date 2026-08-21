package com.driveapex.audio

/** Original EV sport profile inspired by premium electric-GT sound design principles. */
object ETronInspiredSoundProfile {
    val layers = listOf(
        SoundLayer(
            "motor_core", 700f, 7000f, 0.05f, 1.5f, 1.00f, 1, 0.34f,
            sceneBias = mapOf(AudioScene.IDLE to 1.05f, AudioScene.ACCELERATION to 1.05f, AudioScene.HARD_ACCELERATION to 1.12f, AudioScene.LAUNCH to 1.16f)
        ),
        SoundLayer(
            "motor_harmonic", 1000f, 7000f, 0.10f, 1.5f, 2.00f, 2, 0.15f,
            sceneBias = mapOf(AudioScene.ACCELERATION to 1.08f, AudioScene.HARD_ACCELERATION to 1.16f, AudioScene.LAUNCH to 1.20f)
        ),
        SoundLayer(
            "electric_whine", 1800f, 7000f, 0.18f, 1.5f, 4.20f, 1, 0.10f,
            sceneBias = mapOf(AudioScene.ACCELERATION to 1.10f, AudioScene.HARD_ACCELERATION to 1.24f, AudioScene.LAUNCH to 1.28f, AudioScene.REGENERATION to 0.82f)
        ),
        SoundLayer(
            "inverter_tone", 2200f, 7000f, 0.30f, 1.5f, 6.80f, 1, 0.06f,
            sceneBias = mapOf(AudioScene.HARD_ACCELERATION to 1.24f, AudioScene.LAUNCH to 1.30f, AudioScene.REGENERATION to 1.08f)
        ),
        SoundLayer(
            "low_body", 700f, 4200f, 0.08f, 1.5f, 0.50f, 1, 0.20f,
            sceneBias = mapOf(AudioScene.IDLE to 1.10f, AudioScene.COAST to 0.88f, AudioScene.REGENERATION to 1.06f)
        ),
        SoundLayer(
            "load_pulse", 1200f, 7000f, 0.35f, 1.5f, 1.50f, 3, 0.08f,
            sceneBias = mapOf(AudioScene.ACCELERATION to 1.18f, AudioScene.HARD_ACCELERATION to 1.32f, AudioScene.LAUNCH to 1.38f)
        ),
        SoundLayer(
            "sport_presence", 2500f, 7000f, 0.45f, 1.5f, 3.00f, 1, 0.07f,
            sceneBias = mapOf(AudioScene.HARD_ACCELERATION to 1.20f, AudioScene.LAUNCH to 1.30f, AudioScene.HIGH_SPEED to 1.14f)
        ),
        SoundLayer(
            "speed_air", 700f, 7000f, 0.0f, 1.5f, 0.0f, 0, 0.05f,
            sceneBias = mapOf(AudioScene.HIGH_SPEED to 1.45f, AudioScene.COAST to 1.10f),
            minSpeedKph = 35f,
            maxSpeedKph = 250f
        )
    )
}
