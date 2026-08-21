package com.driveapex.vehicle

/** Snapshot of the vehicle state consumed by DriveApex. */
data class VehicleData(
    val rpm: Float,
    val speedKph: Float,
    val throttle: Float,
    val isDriving: Boolean,
    val brake: Float = 0f,
    val regen: Float = 0f
) {
    fun normalizedThrottle(): Float = throttle.coerceIn(0f, 1f)
    fun normalizedSpeed(): Float = speedKph.coerceAtLeast(0f)
    fun normalizedBrake(): Float = brake.coerceIn(0f, 1f)
    fun normalizedRegen(): Float = regen.coerceIn(0f, 1f)
}
