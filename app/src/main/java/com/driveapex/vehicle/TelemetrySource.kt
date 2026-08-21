package com.driveapex.vehicle

/** Identifies where the current vehicle telemetry came from. */
enum class TelemetrySource {
    SIMULATOR,
    LIVE_UDP,
    LIVE_BRIDGE
}
