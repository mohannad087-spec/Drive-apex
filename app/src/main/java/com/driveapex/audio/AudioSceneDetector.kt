package com.driveapex.audio

import com.driveapex.vehicle.VehicleData

/** Detects transient driving states instead of mapping sound from RPM alone. */
class AudioSceneDetector {
    private var previousSpeed = 0f
    private var previousThrottle = 0f

    fun detect(data: VehicleData): AudioScene {
        val speedDelta = data.speedKph - previousSpeed
        val throttle = data.normalizedThrottle()
        val scene = when {
            data.speedKph < 3f && throttle < 0.08f -> AudioScene.IDLE
            data.speedKph < 8f && throttle > 0.82f -> AudioScene.LAUNCH
            throttle > 0.78f && speedDelta > 1.2f -> AudioScene.HARD_ACCELERATION
            throttle > 0.18f && speedDelta > 0.15f -> AudioScene.ACCELERATION
            throttle < 0.04f && speedDelta < -0.25f -> AudioScene.REGENERATION
            throttle < 0.06f -> AudioScene.COAST
            data.speedKph > 150f -> AudioScene.HIGH_SPEED
            else -> AudioScene.ACCELERATION
        }
        previousSpeed = data.speedKph
        previousThrottle = throttle
        return scene
    }
}
