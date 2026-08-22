package com.driveapex.audio

import com.driveapex.vehicle.VehicleData

/**
 * Low-latency telemetry conditioning for audio control.
 * Filters noisy telemetry without adding a long control delay.
 */
class TelemetrySmoother(private val alpha: Float = 0.22f) {
    private var initialized = false
    private var rpm = 700f
    private var speed = 0f
    private var throttle = 0f
    private var brake = 0f
    private var regen = 0f

    fun reset() {
        initialized = false
        rpm = 700f
        speed = 0f
        throttle = 0f
        brake = 0f
        regen = 0f
    }

    fun filter(input: VehicleData): VehicleData {
        if (!initialized) {
            rpm = input.rpm
            speed = input.speedKph
            throttle = input.throttle
            brake = input.brake
            regen = input.regen
            initialized = true
        } else {
            rpm = smooth(rpm, input.rpm, alpha)
            speed = smooth(speed, input.speedKph, alpha)
            throttle = smooth(throttle, input.throttle, alpha)
            brake = smooth(brake, input.brake, alpha)
            regen = smooth(regen, input.regen, alpha)
        }
        return input.copy(
            rpm = rpm,
            speedKph = speed,
            throttle = throttle,
            brake = brake,
            regen = regen,
            isDriving = speed > 1f
        )
    }

    private fun smooth(current: Float, target: Float, amount: Float): Float =
        current + (target - current) * amount.coerceIn(0.01f, 1f)
}
