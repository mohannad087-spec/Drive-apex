package com.driveapex.audio

/** Original EV sport profile inspired by premium electric-GT sound design principles. */
object ETronInspiredSoundProfile {
    val layers = listOf(
        // Dominant rotor/body tone: the foundation should remain warm and physical.
        SoundLayer(
            "motor_core", 700f, 7000f, 0.05f, 1.5f, 1.00f, 1, 0.34f,
            sceneBias = mapOf(AudioScene.IDLE to 1.05f, AudioScene.ACCELERATION to 1.05f, AudioScene.HARD_ACCELERATION to 1.12f, AudioScene.LAUNCH to 1.16f)
        ),
        SoundLayer(
            "motor_harmonic", 1000f, 7000f, 0.10f, 1.5f, 2.00f, 2, 0.15f,
            sceneBias = mapOf(AudioScene.ACCELERATION to 1.08f, AudioScene.HARD_ACCELERATION to 1.16f, AudioScene.LAUNCH to 1.20f)
        ),
        // Keep the inverter whine present but subordinate to the motor/body layers.
        SoundLayer(
            "electric_whine", 1800f, 7000f, 0.18f, 1.5f, 4.20f, 1, 0.055f,
            sceneBias = mapOf(AudioScene.ACCELERATION to 0.90f, AudioScene.HARD_ACCELERATION to 0.98f, AudioScene.LAUNCH to 1.02f, AudioScene.REGENERATION to 0.72f)
        ),
        SoundLayer(
            "inverter_tone", 2200f, 7000f, 0.30f, 1.5f, 6.80f, 1, 0.022f,
            sceneBias = mapOf(AudioScene.HARD_ACCELERATION to 0.90f, AudioScene.LAUNCH to 0.96f, AudioScene.REGENERATION to 0.82f)
        ),
        // Lower mechanical mass: this is what prevents the sound from becoming a pure electronic whistle.
        SoundLayer(
            "low_body", 700f, 4200f, 0.08f, 1.5f, 0.50f, 1, 0.24f,
            sceneBias = mapOf(AudioScene.IDLE to 1.10f, AudioScene.COAST to 0.96f, AudioScene.ACCELERATION to 1.08f, AudioScene.HARD_ACCELERATION to 1.14f, AudioScene.REGENERATION to 1.08f)
        ),
        SoundLayer(
            "rotor_body", 800f, 6000f, 0.12f, 1.5f, 1.50f, 3, 0.055f,
            sceneBias = mapOf(AudioScene.IDLE to 0.70f, AudioScene.ACCELERATION to 1.10f, AudioScene.HARD_ACCELERATION to 1.18f, AudioScene.LAUNCH to 1.22f)
        ),
        SoundLayer(
            "load_pulse", 1200f, 7000f, 0.35f, 1.5f, 1.50f, 3, 0.055f,
            sceneBias = mapOf(AudioScene.ACCELERATION to 1.12f, AudioScene.HARD_ACCELERATION to 1.20f, AudioScene.LAUNCH to 1.26f)
        ),
        SoundLayer(
            "sport_presence", 2500f, 7000f, 0.45f, 1.5f, 3.00f, 1, 0.035f,
            sceneBias = mapOf(AudioScene.HARD_ACCELERATION to 1.10f, AudioScene.LAUNCH to 1.16f, AudioScene.HIGH_SPEED to 1.08f)
        ),
        SoundLayer(
            "speed_air", 700f, 7000f, 0.0f, 1.5f, 0.0f, 0, 0.035f,
            sceneBias = mapOf(AudioScene.HIGH_SPEED to 1.35f, AudioScene.COAST to 1.08f),
            minSpeedKph = 35f,
            maxSpeedKph = 250f
        )
    )
}
