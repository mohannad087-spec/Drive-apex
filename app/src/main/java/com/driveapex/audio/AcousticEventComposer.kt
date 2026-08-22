package com.driveapex.audio

import com.driveapex.vehicle.VehicleData
import kotlin.math.max

/**
 * Turns telemetry deltas into short-lived acoustic events.
 * Each event has an attack/decay envelope so transient sounds do not become
 * permanent tones when a condition remains true for multiple frames.
 */
class AcousticEventComposer {
    private var previousSpeed = 0f
    private var previousThrottle = 0f
    private var launch = 0f
    private var accelerationHit = 0f
    private var liftOff = 0f
    private var regenerationHit = 0f
    private var brakeHit = 0f

    data class Events(
        val launch: Float,
        val accelerationHit: Float,
        val liftOff: Float,
        val regenerationHit: Float,
        val brakeHit: Float,
        val speedRush: Float
    )

    fun evaluate(data: VehicleData): Events {
        val speed = data.normalizedSpeed()
        val throttle = data.normalizedThrottle()
        val brake = data.normalizedBrake()
        val regen = data.normalizedRegen()
        val deltaSpeed = speed - previousSpeed
        val deltaThrottle = throttle - previousThrottle

        launch = tick(launch, speed < 18f && throttle > 0.82f, 0.11f)
        accelerationHit = tick(accelerationHit, deltaSpeed > 2.5f && throttle > 0.55f, 0.16f)
        liftOff = tick(liftOff, deltaThrottle < -0.38f && speed > 8f, 0.13f)
        regenerationHit = tick(
            regenerationHit,
            regen > 0.55f || (brake > 0.35f && deltaSpeed < -1.5f),
            0.12f
        )
        brakeHit = tick(brakeHit, brake > 0.65f && deltaSpeed < -3f, 0.10f)

        val events = Events(
            launch = launch,
            accelerationHit = accelerationHit,
            liftOff = liftOff,
            regenerationHit = regenerationHit,
            brakeHit = brakeHit,
            speedRush = max(0f, (speed - 90f) / 150f).coerceIn(0f, 1f)
        )

        previousSpeed = speed
        previousThrottle = throttle
        return events
    }

    private fun tick(current: Float, trigger: Boolean, decay: Float): Float {
        return if (trigger) 1f else (current - decay).coerceAtLeast(0f)
    }
}
