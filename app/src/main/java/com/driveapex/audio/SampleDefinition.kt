package com.driveapex.audio

/** Metadata for one recorded or synthesized sample zone. */
data class SampleDefinition(
    val id: String,
    val assetPath: String,
    val layerId: String,
    val centerRpm: Float,
    val centerLoad: Float,
    val minSpeedKph: Float = 0f,
    val maxSpeedKph: Float = 320f,
    val gain: Float = 1f,
    val pitchReferenceRpm: Float = centerRpm,
    val loopStartMs: Int = 0,
    val loopEndMs: Int? = null,
    val transient: Boolean = false
)
