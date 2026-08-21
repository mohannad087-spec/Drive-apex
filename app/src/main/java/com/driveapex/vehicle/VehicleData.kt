package com.driveapex.vehicle

/** Snapshot of vehicle state consumed by the DriveApex audio stack. */
data class VehicleData(
    val rpm: Float,
    val speedKph: Float,
    val throttle: Float,
    val brake: Float = 0f,
    val regeneration: Float = 0f,
    val isDriving: Boolean = speedKph > 0.5f
) {
    fun normalizedThrottle(): Float = throttle.coerceIn(0f, 1f)
    fun normalizedBrake(): Float = brake.coerceIn(0f, 1f)
    fun normalizedRegen(): Float = regeneration.coerceIn(0f, 1f)
    fun normalizedSpeed(): Float = speedKph.coerceAtLeast(0f)
}
