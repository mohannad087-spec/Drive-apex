package com.driveapex.vehicle

import android.content.Context
import android.os.SystemClock

/** Selects verified in-car BYD HAL first, with UDP as a development fallback. */
class LiveVehicleTelemetrySource(context: Context) {
    private val byd = BydHalTelemetryBridge(context)
    private val udp = UdpTelemetryReceiver()
    @Volatile private var useByd = false
    @Volatile private var running = false
    @Volatile private var packetCount = 0L
    @Volatile private var invalidCount = 0L
    @Volatile private var lastFrameAt = 0L
    @Volatile private var lastSource = "NONE"

    fun start() {
        if (running) return
        running = true
        packetCount = 0L
        invalidCount = 0L
        lastFrameAt = 0L
        lastSource = "NONE"
        useByd = byd.isAvailable()
        if (useByd) {
            byd.start { frame ->
                packetCount += 1L
                lastFrameAt = SystemClock.elapsedRealtime()
                lastSource = frame.source
            }
        } else {
            udp.start()
        }
    }

    fun stop() {
        running = false
        byd.stop()
        udp.stop()
    }

    fun latest(): LiveTelemetry? {
        return if (useByd) {
            val frame = byd.latest() ?: return null
            if (SystemClock.elapsedRealtime() - lastFrameAt > 500L) return null
            val data = VehicleData(
                rpm = frame.rpm,
                speedKph = frame.speedKph,
                throttle = frame.throttle,
                isDriving = frame.speedKph > 1f,
                brake = frame.brake,
                regen = frame.regen
            )
            LiveTelemetry(data, TelemetrySource.LIVE_BRIDGE)
        } else {
            udp.latest()
        }
    }

    fun diagnostics(): TelemetryDiagnostics {
        if (!useByd) return udp.diagnostics()
        val age = if (lastFrameAt == 0L) Long.MAX_VALUE
        else SystemClock.elapsedRealtime() - lastFrameAt
        return TelemetryDiagnostics(
            packetCount = packetCount,
            invalidPacketCount = invalidCount,
            ageMs = age,
            source = lastSource.ifBlank { "BYD_HAL_UNAVAILABLE" },
            port = 0
        )
    }

    fun bydError(): String? = byd.error()
}
