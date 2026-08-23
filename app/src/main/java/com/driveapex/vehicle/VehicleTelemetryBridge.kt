package com.driveapex.vehicle

/**
 * Boundary for a verified vehicle telemetry source.
 * Implementations are read-only and must convert source-specific data into
 * DriveApex's normalized VehicleData model.
 */
interface VehicleTelemetryBridge {
    fun start(onFrame: (TelemetryFrame) -> Unit)
    fun stop()
}
