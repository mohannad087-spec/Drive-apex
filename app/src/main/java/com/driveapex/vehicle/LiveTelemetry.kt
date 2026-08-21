package com.driveapex.vehicle

data class LiveTelemetry(val data: VehicleData, val source: TelemetrySource, val timestampMs: Long = System.currentTimeMillis())
