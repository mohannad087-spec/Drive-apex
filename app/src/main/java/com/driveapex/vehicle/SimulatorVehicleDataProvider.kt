package com.driveapex.vehicle

/** Deterministic provider used until live BYD data is wired in. */
class SimulatorVehicleDataProvider : VehicleDataProvider {
    private var rpm = 900f
    private var speedKph = 0f
    private var throttle = 0f
    private var brake = 0f
    private var regeneration = 0f

    override fun current(): VehicleData = VehicleData(
        rpm = rpm,
        speedKph = speedKph,
        throttle = throttle,
        brake = brake,
        regeneration = regeneration,
        isDriving = speedKph > 1f
    )

    fun setRpm(value: Float) { rpm = value.coerceIn(700f, 7000f) }
    fun setSpeed(value: Float) { speedKph = value.coerceIn(0f, 300f) }
    fun setThrottle(value: Float) { throttle = value.coerceIn(0f, 1f) }
    fun setBrake(value: Float) { brake = value.coerceIn(0f, 1f) }
    fun setRegeneration(value: Float) { regeneration = value.coerceIn(0f, 1f) }
}
