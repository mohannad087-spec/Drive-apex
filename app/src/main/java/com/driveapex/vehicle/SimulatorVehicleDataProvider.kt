package com.driveapex.vehicle

/** Deterministic provider used until live BYD data is wired in. */
class SimulatorVehicleDataProvider : VehicleDataProvider {
    private var rpm = 900f
    private var speedKph = 0f
    private var throttle = 0f
    private var brake = 0f
    private var regen = 0f

    override fun current(): VehicleData = VehicleData(
        rpm = rpm,
        speedKph = speedKph,
        throttle = throttle,
        brake = brake,
        regen = regen,
        isDriving = speedKph > 1f
    )

    /**
     * The motor's range, not an engine's.
     *
     * This was clamped to 7000, which silently pinned the top third of the
     * slider: the front motor on this car passes 10000 on the road, and the
     * gearbox above it is geared for 10500.
     */
    fun setRpm(value: Float) { rpm = value.coerceIn(0f, 12_000f) }
    fun setSpeed(value: Float) { speedKph = value.coerceIn(0f, 300f) }
    fun setThrottle(value: Float) { throttle = value.coerceIn(0f, 1f) }
    fun setBrake(value: Float) { brake = value.coerceIn(0f, 1f) }
    fun setRegen(value: Float) { regen = value.coerceIn(0f, 1f) }
}
