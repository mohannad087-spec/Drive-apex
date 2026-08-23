package com.driveapex.vehicle

/**
 * Adapter boundary for a verified BYD/DiLink telemetry source.
 *
 * This class deliberately contains no guessed OEM API, undocumented property,
 * ADB command, or CAN signal. The concrete source is injected once verified
 * on the target vehicle/software version.
 */
class BydTelemetryAdapter(
    private val source: VehicleTelemetryBridge
) : VehicleTelemetryBridge {
    override fun start(onFrame: (TelemetryFrame) -> Unit) = source.start { frame ->
        // Keep this adapter read-only and normalized at the boundary.
        onFrame(frame.copy(source = "BYD_VERIFIED"))
    }

    override fun stop() = source.stop()
}
