package com.driveapex.audio

/** Original aggressive EV-GT profile for DriveApex testing. */
object ApexSoundProfile {
    val layers = listOf(
        SoundLayer("apex_motor", 700f, 7000f, 0.03f, 1.5f, 1.00f, 1, 0.38f, sceneBias = mapOf(AudioScene.IDLE to 0.75f)),
        SoundLayer("apex_harmonic", 1200f, 7000f, 0.08f, 1.5f, 2.15f, 2, 0.18f),
        SoundLayer("apex_inverter", 1800f, 7000f, 0.12f, 1.5f, 5.70f, 1, 0.12f),
        SoundLayer("apex_high_whine", 3200f, 7000f, 0.25f, 1.5f, 8.20f, 1, 0.08f, stereoPosition = 0.18f),
        SoundLayer("apex_low_body", 700f, 4600f, 0.08f, 1.5f, 0.48f, 1, 0.22f, stereoPosition = -0.08f),
        SoundLayer("apex_accel_presence", 2200f, 7000f, 0.35f, 1.5f, 3.20f, 1, 0.10f, sceneBias = mapOf(AudioScene.HARD_ACCELERATION to 1.25f, AudioScene.LAUNCH to 1.35f)),
        SoundLayer("apex_regen", 900f, 4500f, 0.18f, 1.5f, 1.40f, 2, 0.08f, sceneBias = mapOf(AudioScene.REGENERATION to 1.55f, AudioScene.COAST to 0.55f)),
        SoundLayer("apex_speed_air", 700f, 7000f, 0.0f, 1.5f, 0.0f, 0, 0.06f, minSpeedKph = 45f, maxSpeedKph = 300f, sceneBias = mapOf(AudioScene.HIGH_SPEED to 1.35f))
    )
}
