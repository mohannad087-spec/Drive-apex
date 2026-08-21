package com.driveapex.vehicle

/** Snapshot of the vehicle state consumed by DriveApex. */
data class VehicleData(
    val rpm: Float,
    val speedKph: Float,
    val throttle: Float,
    val isDriving: Boolean
) {
    fun normalizedThrottle(): Float = throttle.coerceIn(0f, 1f)
    fun normalizedSpeed(): Float = speedKph.coerceAtLeast(0f)
}
