package com.driveapex.vehicle

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Live telemetry receiver for BYD DiLink 3.
 *
 * Runtime priority is intentionally different from the old diagnostic-only path:
 * 1) direct BYD HAL polling (the same reader used by diagnostics),
 * 2) shell-UID BYD daemon stream,
 * 3) UDP development fallback.
 *
 * This keeps the dashboard fed continuously instead of making it depend on opening
 * the diagnostics dialog first.
 */
class UdpTelemetryReceiver(private val port: Int = 38901, context: Context? = null) {
    private val staleAfterMs = 1500L
    private val validator = VehicleTelemetryValidator(staleAfterMs)
    private val direct = context?.let { DirectBydTelemetryReader(it) }
    private val byd = context?.let { BydHalTelemetryBridge(it) }

    @Volatile private var running = false
    @Volatile private var useDirect = false
    @Volatile private var useByd = false
    @Volatile private var latest: LiveTelemetry? = null
    @Volatile private var lastPacketAtMs = 0L
    @Volatile private var packetCount = 0L
    @Volatile private var invalidPacketCount = 0L
    @Volatile private var lastSource = "NONE"

    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        latest = null
        lastPacketAtMs = 0L
        packetCount = 0L
        invalidPacketCount = 0L
        lastSource = "NONE"

        if (direct != null) {
            thread = Thread(::liveLoop, "DriveApex-LiveTelemetry").also { it.start() }
        } else {
            thread = Thread(::receiveLoop, "DriveApex-TelemetryUDP").also { it.start() }
        }
    }

    fun stop() {
        running = false
        useDirect = false
        useByd = false
        byd?.stop()
        runCatching { socket?.close() }
        socket = null
        thread = null
        latest = null
        lastPacketAtMs = 0L
    }

    fun latest(): LiveTelemetry? {
        val snapshot = latest ?: return null
        return snapshot.takeIf { System.currentTimeMillis() - it.timestampMs <= staleAfterMs }
    }

    fun diagnostics(): TelemetryDiagnostics {
        val age = if (lastPacketAtMs == 0L) Long.MAX_VALUE
        else SystemClock.elapsedRealtime() - lastPacketAtMs
        return TelemetryDiagnostics(
            packetCount = packetCount,
            invalidPacketCount = invalidPacketCount,
            ageMs = age,
            source = lastSource,
            port = if (useDirect || useByd) 0 else port
        )
    }

    fun sourceError(): String? = when {
        useByd -> byd?.error()
        direct != null -> null
        else -> null
    }

    /** Continuous in-process BYD HAL reader; diagnostics and dashboard use the same data path. */
    private fun liveLoop() {
        var directFailures = 0
        var daemonStarted = false

        while (running) {
            val frame = runCatching { direct?.readOnce() }.getOrNull()
            if (frame != null && isUsable(frame)) {
                useDirect = true
                useByd = false
                directFailures = 0
                daemonStarted = false
                publish(
                    TelemetryFrame(
                        timestampMs = frame.timestampMs,
                        rpm = frame.rpm,
                        speedKph = frame.speedKph,
                        throttle = frame.throttle,
                        brake = frame.brake,
                        regen = 0f,
                        source = frame.source
                    )
                )
                sleep50()
                continue
            }

            directFailures++
            invalidPacketCount++

            // Do not keep restarting the daemon on every failed direct sample.
            // Once direct HAL misses several consecutive samples, start the
            // shell-UID daemon as a fallback and let its callback publish frames.
            if (!daemonStarted && directFailures >= 5 && byd != null) {
                daemonStarted = true
                val started = runCatching {
                    if (!byd.isAvailable()) false
                    else {
                        useByd = true
                        byd.start { publish(it) }
                        true
                    }
                }.getOrDefault(false)
                if (!started) {
                    useByd = false
                    daemonStarted = false
                }
            }

            sleep50()
        }
    }

    private fun isUsable(frame: DirectBydTelemetryReader.Frame): Boolean =
        frame.speedKph.isFinite() && frame.throttle.isFinite() && frame.brake.isFinite() && frame.rpm.isFinite() &&
            frame.speedKph in 0f..400f && frame.throttle in 0f..1f && frame.brake in 0f..1f && frame.rpm in 0f..25000f

    private fun publish(frame: TelemetryFrame) {
        val now = System.currentTimeMillis()
        when (validator.validate(frame, now)) {
            is VehicleTelemetryValidator.Result.Valid -> {
                latest = LiveTelemetry(
                    VehicleData(
                        rpm = frame.rpm.coerceIn(0f, 25000f),
                        speedKph = frame.speedKph.coerceIn(0f, 300f),
                        throttle = frame.throttle.coerceIn(0f, 1f),
                        isDriving = frame.speedKph > 1f,
                        brake = frame.brake.coerceIn(0f, 1f),
                        regen = frame.regen.coerceIn(0f, 1f)
                    ),
                    if (useDirect || useByd) TelemetrySource.LIVE_BRIDGE else TelemetrySource.LIVE_UDP,
                    frame.timestampMs
                )
                packetCount++
                lastPacketAtMs = SystemClock.elapsedRealtime()
                lastSource = frame.source
            }
            else -> invalidPacketCount++
        }
    }

    private fun receiveLoop() {
        val buffer = ByteArray(4096)
        val local = DatagramSocket(null)
        socket = local
        runCatching {
            local.reuseAddress = true
            local.bind(InetSocketAddress(port))
            while (running) {
                val packet = DatagramPacket(buffer, buffer.size)
                local.receive(packet)
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                parse(text)?.let { value ->
                    latest = value
                    packetCount++
                    lastPacketAtMs = SystemClock.elapsedRealtime()
                    lastSource = "UDP"
                } ?: run { invalidPacketCount++ }
            }
        }
        runCatching { local.close() }
    }

    private fun parse(text: String): LiveTelemetry? = runCatching {
        val json = JSONObject(text)
        val now = System.currentTimeMillis()
        val frame = TelemetryFrame(
            timestampMs = json.optLong("timestampMs", now),
            rpm = json.optDouble("rpm", 0.0).toFloat(),
            speedKph = json.optDouble("speedKph", 0.0).toFloat(),
            throttle = json.optDouble("throttle", 0.0).toFloat(),
            brake = json.optDouble("brake", 0.0).toFloat(),
            regen = json.optDouble("regen", 0.0).toFloat(),
            source = json.optString("source", "udp")
        )
        when (validator.validate(frame, now)) {
            is VehicleTelemetryValidator.Result.Valid -> Unit
            else -> return null
        }
        LiveTelemetry(
            VehicleData(
                rpm = frame.rpm.coerceIn(0f, 25000f),
                speedKph = frame.speedKph.coerceIn(0f, 300f),
                throttle = frame.throttle.coerceIn(0f, 1f),
                isDriving = frame.speedKph > 1f,
                brake = frame.brake.coerceIn(0f, 1f),
                regen = frame.regen.coerceIn(0f, 1f)
            ),
            TelemetrySource.LIVE_UDP,
            frame.timestampMs
        )
    }.getOrNull()

    private fun sleep50() {
        try { Thread.sleep(50L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }
}

data class TelemetryDiagnostics(
    val packetCount: Long,
    val invalidPacketCount: Long,
    val ageMs: Long,
    val source: String,
    val port: Int
)
