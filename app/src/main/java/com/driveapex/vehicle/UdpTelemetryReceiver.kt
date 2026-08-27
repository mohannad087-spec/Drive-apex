package com.driveapex.vehicle

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Live telemetry receiver.
 * On a BYD head unit it prefers the shell-UID BYD daemon, then direct HAL
 * access, and finally the UDP development receiver.
 */
class UdpTelemetryReceiver(private val port: Int = 38901, context: Context? = null) {
    private val staleAfterMs = 1500L
    private val validator = VehicleTelemetryValidator(staleAfterMs)
    private val direct = context?.let { DirectBydTelemetryReader(it) }
    private val byd = context?.let { BydHalTelemetryBridge(it) }
    @Volatile private var useDirect = false
    @Volatile private var useByd = false
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    @Volatile private var latest: LiveTelemetry? = null
    @Volatile private var lastPacketAtMs = 0L
    @Volatile private var packetCount = 0L
    @Volatile private var invalidPacketCount = 0L
    @Volatile private var lastSource = "NONE"

    fun start() {
        if (running) return
        running = true
        lastPacketAtMs = 0L
        latest = null
        packetCount = 0L
        invalidPacketCount = 0L
        lastSource = "NONE"

        // The shell-UID daemon is the verified path on the target BYD firmware.
        // Direct in-process HAL access is only a fallback because the app UID
        // may not be allowed to cross the vendor Binder permission boundary.
        useByd = byd?.isAvailable() == true
        if (useByd) {
            byd?.start { frame ->
                publish(
                    TelemetryFrame(
                        timestampMs = frame.timestampMs,
                        rpm = frame.rpm,
                        speedKph = frame.speedKph,
                        throttle = frame.throttle,
                        brake = frame.brake,
                        regen = frame.regen,
                        source = frame.source
                    )
                )
            }
            return
        }

        useDirect = direct?.isAvailable() == true
        if (useDirect) {
            thread = Thread(::directLoop, "DriveApex-BYDHAL").also { it.start() }
            return
        }

        thread = Thread(::receiveLoop, "DriveApex-TelemetryUDP").also { it.start() }
    }

    fun stop() {
        running = false
        byd?.stop()
        runCatching { socket?.close() }
        socket = null
        thread = null
        latest = null
        lastPacketAtMs = 0L
        useDirect = false
        useByd = false
    }

    fun latest(): LiveTelemetry? {
        if (useByd) {
            val frame = byd?.latest() ?: return null
            val frameAge = System.currentTimeMillis() - frame.timestampMs
            if (frameAge > staleAfterMs) return null
            return LiveTelemetry(
                VehicleData(
                    rpm = frame.rpm,
                    speedKph = frame.speedKph,
                    throttle = frame.throttle,
                    isDriving = frame.speedKph > 1f,
                    brake = frame.brake,
                    regen = frame.regen
                ),
                TelemetrySource.LIVE_BRIDGE,
                frame.timestampMs
            )
        }
        if (useDirect) {
            val snapshot = latest ?: return null
            return snapshot.takeIf { System.currentTimeMillis() - it.timestampMs <= staleAfterMs }
        }
        val snapshot = latest ?: return null
        return snapshot.takeIf { diagnostics().ageMs <= staleAfterMs }
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

    private fun directLoop() {
        while (running && useDirect) {
            val frame = runCatching { direct?.readOnce() }.getOrNull()
            if (frame != null) {
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
            } else {
                invalidPacketCount += 1L
            }
            Thread.sleep(50L)
        }
    }

    private fun publish(frame: TelemetryFrame) {
        val now = System.currentTimeMillis()
        when (validator.validate(frame, now)) {
            is VehicleTelemetryValidator.Result.Valid -> {
                val data = VehicleData(
                    rpm = frame.rpm.coerceIn(0f, 7000f),
                    speedKph = frame.speedKph.coerceIn(0f, 300f),
                    throttle = frame.throttle.coerceIn(0f, 1f),
                    isDriving = frame.speedKph > 1f,
                    brake = frame.brake.coerceIn(0f, 1f),
                    regen = frame.regen.coerceIn(0f, 1f)
                )
                latest = LiveTelemetry(
                    data,
                    if (useDirect || useByd) TelemetrySource.LIVE_BRIDGE else TelemetrySource.LIVE_UDP,
                    frame.timestampMs
                )
                packetCount += 1L
                lastPacketAtMs = SystemClock.elapsedRealtime()
                lastSource = frame.source
            }
            else -> invalidPacketCount += 1L
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
                    packetCount += 1L
                    lastPacketAtMs = SystemClock.elapsedRealtime()
                } ?: run {
                    invalidPacketCount += 1L
                }
            }
        }
        runCatching { local.close() }
    }

    private fun parse(text: String): LiveTelemetry? = runCatching {
        val json = JSONObject(text)
        val now = System.currentTimeMillis()
        val frame = TelemetryFrame(
            timestampMs = json.optLong("timestampMs", now),
            rpm = json.optDouble("rpm", 700.0).toFloat(),
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
        lastSource = frame.source
        val data = VehicleData(
            rpm = frame.rpm.coerceIn(700f, 7000f),
            speedKph = frame.speedKph.coerceIn(0f, 300f),
            throttle = frame.throttle.coerceIn(0f, 1f),
            isDriving = frame.speedKph > 1f,
            brake = frame.brake.coerceIn(0f, 1f),
            regen = frame.regen.coerceIn(0f, 1f)
        )
        LiveTelemetry(data, TelemetrySource.LIVE_UDP, frame.timestampMs)
    }.getOrNull()
}

data class TelemetryDiagnostics(
    val packetCount: Long,
    val invalidPacketCount: Long,
    val ageMs: Long,
    val source: String,
    val port: Int
)