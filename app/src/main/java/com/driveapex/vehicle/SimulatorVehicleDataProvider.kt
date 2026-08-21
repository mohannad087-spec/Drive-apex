package com.driveapex.vehicle

/** Deterministic provider used until live BYD data is wired in. */
class SimulatorVehicleDataProvider : VehicleDataProvider {
    private var rpm = 900f
    private var speedKph = 0f
    private var throttle = 0f

    override fun current(): VehicleData = VehicleData(
        rpm = rpm,
        speedKph = speedKph,
        throttle = throttle,
        isDriving = speedKph > 1f
    )

    fun setRpm(value: Float) { rpm = value.coerceIn(700f, 7000f) }
    fun setSpeed(value: Float) { speedKph = value.coerceIn(0f, 300f) }
    fun setThrottle(value: Float) { throttle = value.coerceIn(0f, 1f) }
}
