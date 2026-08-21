package com.driveapex.audio

/** A composable acoustic layer; each layer can later be backed by real samples. */
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
    val sceneBias: Map<AudioScene, Float> = emptyMap(),
    val minSpeedKph: Float = 0f,
    val maxSpeedKph: Float = 300f
)
