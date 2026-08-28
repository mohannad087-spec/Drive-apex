package com.driveapex.vehicle

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Live telemetry selection for BYD DiLink 3: proven Overdrive collector first, BYD daemon second. */
class LiveVehicleTelemetrySource(context: Context) {
    private val overdrive = OverdriveTelemetryReader(context)
    private val byd = BydHalTelemetryBridge(context)
    private val udp = UdpTelemetryReceiver()

    @Volatile private var mode = Mode.NONE
    @Volatile private var running = false
    @Volatile private var latestData: VehicleData? = null
    @Volatile private var packetCount = 0L
    @Volatile private var invalidCount = 0L
    @Volatile private var lastFrameAt = 0L
    @Volatile private var lastSource = "NONE"
    @Volatile private var executor: ScheduledExecutorService? = null

    private enum class Mode { NONE, OVERDRIVE, BYD_DAEMON, UDP }

    fun start() {
        if (running) return
        running = true
        packetCount = 0L
        invalidCount = 0L
        lastFrameAt = 0L
        lastSource = "NONE"
        latestData = null

        mode = when {
            overdrive.isInstalled() && overdrive.readOnce() != null -> Mode.OVERDRIVE
            byd.isAvailable() -> Mode.BYD_DAEMON
            else -> Mode.UDP
        }

        when (mode) {
            Mode.OVERDRIVE, Mode.BYD_DAEMON -> {
                val worker = Executors.newSingleThreadScheduledExecutor { r ->
                    Thread(r, "driveapex-live-telemetry").apply { isDaemon = true }
                }
                executor = worker
                worker.scheduleAtFixedRate({ sampleLive() }, 0L, 50L, TimeUnit.MILLISECONDS)
            }
            Mode.UDP -> udp.start()
            Mode.NONE -> Unit
        }
    }

    private fun sampleLive() {
        if (!running) return
        when (mode) {
            Mode.OVERDRIVE -> {
                val frame = overdrive.readOnce()
                if (frame == null) {
                    invalidCount++
                    switchToBydDaemonIfAvailable()
                    return
                }
                latestData = VehicleData(
                    rpm = frame.rpm,
                    speedKph = frame.speedKph,
                    throttle = frame.throttle,
                    isDriving = frame.speedKph > 1f,
                    brake = frame.brake,
                    regen = 0f
                )
                packetCount++
                lastFrameAt = SystemClock.elapsedRealtime()
                lastSource = frame.source
            }
            Mode.BYD_DAEMON -> Unit
            Mode.UDP, Mode.NONE -> Unit
        }
    }

    private fun switchToBydDaemonIfAvailable() {
        if (!running || mode != Mode.OVERDRIVE) return
        if (!byd.isAvailable()) return
        mode = Mode.BYD_DAEMON
        byd.start { frame -> publish(frame) }
    }

    private fun publish(frame: TelemetryFrame) {
        latestData = VehicleData(
            rpm = frame.rpm,
            speedKph = frame.speedKph,
            throttle = frame.throttle,
            isDriving = frame.speedKph > 1f,
            brake = frame.brake,
            regen = frame.regen
        )
        packetCount++
        lastFrameAt = SystemClock.elapsedRealtime()
        lastSource = frame.source
    }

    fun stop() {
        running = false
        executor?.shutdownNow()
        executor = null
        byd.stop()
        udp.stop()
        latestData = null
        mode = Mode.NONE
    }

    fun latest(): LiveTelemetry? {
        return when (mode) {
            Mode.OVERDRIVE, Mode.BYD_DAEMON -> {
                val data = latestData ?: return null
                if (SystemClock.elapsedRealtime() - lastFrameAt > 500L) return null
                LiveTelemetry(data, TelemetrySource.LIVE_BRIDGE)
            }
            Mode.UDP -> udp.latest()
            Mode.NONE -> null
        }
    }

    fun diagnostics(): TelemetryDiagnostics {
        if (mode == Mode.UDP) return udp.diagnostics()
        val age = if (lastFrameAt == 0L) Long.MAX_VALUE
        else SystemClock.elapsedRealtime() - lastFrameAt
        return TelemetryDiagnostics(
            packetCount = packetCount,
            invalidPacketCount = invalidCount,
            ageMs = age,
            source = lastSource.ifBlank { "BYD_LIVE_UNAVAILABLE" },
            port = 0
        )
    }

    fun bydError(): String? = byd.error()
    fun overdriveError(): String? = overdrive.error()
}
