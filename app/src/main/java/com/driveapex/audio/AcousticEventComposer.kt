package com.driveapex.audio

import com.driveapex.vehicle.VehicleData
import kotlin.math.abs
import kotlin.math.max

/** Converts telemetry changes into short-lived acoustic event intensities. */
class AcousticEventComposer {
    private var previousSpeed = 0f
    private var previousThrottle = 0f

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

        val events = Events(
            launch = pulse(speed < 18f && throttle > 0.82f, 0.28f),
            accelerationHit = pulse(deltaSpeed > 2.5f && throttle > 0.55f, 0.20f),
            liftOff = pulse(deltaThrottle < -0.38f && speed > 8f, 0.18f),
            regenerationHit = pulse(regen > 0.55f || (brake > 0.35f && deltaSpeed < -1.5f), 0.22f),
            brakeHit = pulse(brake > 0.65f && deltaSpeed < -3f, 0.16f),
            speedRush = max(0f, (speed - 90f) / 150f).coerceIn(0f, 1f)
        )

        previousSpeed = speed
        previousThrottle = throttle
        return events
    }

    private fun pulse(condition: Boolean, decay: Float): Float = if (condition) {
        1f
    } else {
        decay.coerceIn(0f, 1f)
    }
}
