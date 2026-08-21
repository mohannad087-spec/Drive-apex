package com.driveapex.audio

/** High-level driving state used to shape the sound scene. */
enum class AudioScene {
    IDLE,
    COAST,
    ACCELERATION,
    HARD_ACCELERATION,
    REGENERATION,
    LAUNCH,
    HIGH_SPEED
}
