package com.driveapex.audio

/** A single composable acoustic layer in the DriveApex sound model. */
data class SoundLayer(
    val id: String,
    val minRpm: Float,
    val maxRpm: Float,
    val minLoad: Float,
    val maxLoad: Float,
    val baseFrequencyMultiplier: Float,
    val harmonic: Int,
    val gain: Float,
    val attack: Float = 0.08f,
    val release: Float = 0.12f
)
