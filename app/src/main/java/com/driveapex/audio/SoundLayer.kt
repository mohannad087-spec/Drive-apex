package com.driveapex.audio

/** A composable acoustic layer; later each layer can be backed by real samples. */
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
    val release: Float = 0.12f,
    val stereoPosition: Float = 0f,
    val sceneBias: Map<AudioScene, Float> = emptyMap()
)
