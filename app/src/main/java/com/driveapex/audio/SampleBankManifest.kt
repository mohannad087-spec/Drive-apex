package com.driveapex.audio

/** Registry for future recorded sample assets. No copyrighted vehicle samples are bundled. */
object SampleBankManifest {
    val premiumGt: List<SampleDefinition> = listOf(
        SampleDefinition("motor_low", "audio/gt/motor_low.wav", "motor_core", 1200f, 0.25f),
        SampleDefinition("motor_mid", "audio/gt/motor_mid.wav", "motor_core", 3000f, 0.45f),
        SampleDefinition("motor_high", "audio/gt/motor_high.wav", "motor_core", 5200f, 0.65f),
        SampleDefinition("inverter_low", "audio/gt/inverter_low.wav", "inverter", 1800f, 0.35f),
        SampleDefinition("inverter_high", "audio/gt/inverter_high.wav", "inverter", 5600f, 0.75f),
        SampleDefinition("air_speed", "audio/gt/air_speed.wav", "air", 3500f, 0.20f, 80f, 320f),
        SampleDefinition("regen", "audio/gt/regen.wav", "regen", 2200f, 0.10f),
        SampleDefinition("launch", "audio/gt/launch.wav", "launch", 2500f, 0.95f, 0f, 80f, 1f, 2500f, 0, null, true)
    )
}
