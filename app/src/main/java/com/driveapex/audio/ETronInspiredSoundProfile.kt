package com.driveapex.audio

/** Original EV sport profile inspired by premium electric-GT sound design principles. */
object ETronInspiredSoundProfile {
    val layers = listOf(
        SoundLayer("motor_core", 700f, 7000f, 0.05f, 1.5f, 1.00f, 1, 0.34f),
        SoundLayer("motor_harmonic", 1000f, 7000f, 0.10f, 1.5f, 2.00f, 2, 0.15f),
        SoundLayer("electric_whine", 1800f, 7000f, 0.18f, 1.5f, 4.20f, 1, 0.10f),
        SoundLayer("inverter_tone", 2200f, 7000f, 0.30f, 1.5f, 6.80f, 1, 0.06f),
        SoundLayer("low_body", 700f, 4200f, 0.08f, 1.5f, 0.50f, 1, 0.20f),
        SoundLayer("load_pulse", 1200f, 7000f, 0.35f, 1.5f, 1.50f, 3, 0.08f),
        SoundLayer("sport_presence", 2500f, 7000f, 0.45f, 1.5f, 3.00f, 1, 0.07f),
        SoundLayer("speed_air", 35f, 250f, 0.0f, 1.5f, 0.0f, 0, 0.05f)
    )
}
