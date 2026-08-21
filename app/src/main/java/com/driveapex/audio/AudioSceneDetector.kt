package com.driveapex.audio

import com.driveapex.vehicle.VehicleData

/** Detects transient driving states from speed, pedal and regenerative-braking telemetry. */
class AudioSceneDetector {
    private var previousSpeed = 0f

    fun detect(data: VehicleData): AudioScene {
        val speedDelta = data.speedKph - previousSpeed
        val throttle = data.normalizedThrottle()
        val brake = data.normalizedBrake()
        val regen = data.normalizedRegen()

        val scene = when {
            data.speedKph < 3f && throttle < 0.08f -> AudioScene.IDLE
            data.speedKph < 10f && throttle > 0.82f -> AudioScene.LAUNCH
            regen > 0.35f || (brake > 0.15f && speedDelta < -0.20f) -> AudioScene.REGENERATION
            throttle > 0.78f && speedDelta > 1.2f -> AudioScene.HARD_ACCELERATION
            throttle > 0.18f && speedDelta > 0.15f -> AudioScene.ACCELERATION
            throttle < 0.06f && speedDelta < -0.10f -> AudioScene.COAST
            data.speedKph > 150f -> AudioScene.HIGH_SPEED
            throttle < 0.06f -> AudioScene.COAST
            else -> AudioScene.ACCELERATION
        }

        previousSpeed = data.speedKph
        return scene
    }
}
