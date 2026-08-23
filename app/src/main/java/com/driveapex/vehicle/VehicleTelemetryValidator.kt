package com.driveapex.vehicle

/** Validates live telemetry before it reaches the audio engine. */
class VehicleTelemetryValidator(
    private val staleAfterMs: Long = 250L
) {
    fun validate(frame: TelemetryFrame, nowMs: Long = System.currentTimeMillis()): Result {
        if (nowMs - frame.timestampMs > staleAfterMs) return Result.Stale
        if (frame.timestampMs > nowMs + 1000L) return Result.Invalid("future timestamp")
        if (!frame.rpm.isFinite() || frame.rpm < 0f || frame.rpm > 20_000f) {
            return Result.Invalid("rpm")
        }
        if (!frame.speedKph.isFinite() || frame.speedKph < 0f || frame.speedKph > 400f) {
            return Result.Invalid("speed")
        }
        if (!inRange(frame.throttle) || !inRange(frame.brake) || !inRange(frame.regen)) {
            return Result.Invalid("normalized control value")
        }
        return Result.Valid
    }

    private fun inRange(value: Float): Boolean = value.isFinite() && value in 0f..1f

    sealed interface Result {
        data object Valid : Result
        data object Stale : Result
        data class Invalid(val reason: String) : Result
    }
}

data class TelemetryFrame(
    val timestampMs: Long,
    val rpm: Float,
    val speedKph: Float,
    val throttle: Float,
    val brake: Float,
    val regen: Float,
    val source: String
)
