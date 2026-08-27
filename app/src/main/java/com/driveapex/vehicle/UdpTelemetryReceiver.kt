package com.driveapex.vehicle

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Live telemetry receiver.
 * On a BYD head unit it prefers the verified BYD Speed HAL;
 * otherwise it remains the UDP development receiver on port 38901.
 */
class UdpTelemetryReceiver(private val port: Int = 38901, context: Context? = null) {
    // The BYD daemon is a local TCP stream. Keep the last verified frame for
    // a short grace period so UI polling does not blank the live values during
    // a transient scheduling/socket gap.
    private val staleAfterMs = 1500L
    private val validator = VehicleTelemetryValidator(staleAfterMs)
    private val byd = context?.let { BydHalTelemetryBridge(it) }
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

        useByd = byd?.isAvailable() == true
        if (useByd) {
            byd?.start { frame ->
                val data = VehicleData(
                    rpm = frame.rpm,
                    speedKph = frame.speedKph,
                    throttle = frame.throttle,
                    isDriving = frame.speedKph > 1f,
                    brake = frame.brake,
                    regen = frame.regen
                )
                latest = LiveTelemetry(data, TelemetrySource.LIVE_BRIDGE, frame.timestampMs)
                packetCount += 1L
                lastPacketAtMs = SystemClock.elapsedRealtime()
                lastSource = frame.source
            }
        } else {
            thread = Thread(::receiveLoop, "DriveApex-TelemetryUDP").also { it.start() }
        }
    }

    fun stop() {
        running = false
        byd?.stop()
        runCatching { socket?.close() }
        socket = null
        thread = null
        latest = null
        lastPacketAtMs = 0L
    }

    fun latest(): LiveTelemetry? {
        val snapshot = latest ?: return null
        val age = if (useByd) {
            // The bridge owns the authoritative BYD stream. Use the bridge's
            // latest frame and its own connection health rather than requiring
            // a second, tighter local timestamp gate.
            val frame = byd?.latest() ?: return null
            val frameAge = System.currentTimeMillis() - frame.timestampMs
            if (frameAge > staleAfterMs) return null
            LiveTelemetry(
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
        } else {
            diagnostics().ageMs.let { localAge ->
                if (localAge > staleAfterMs) null else snapshot
            }
        }
        return age
    }

    fun diagnostics(): TelemetryDiagnostics {
        val age = if (lastPacketAtMs == 0L) Long.MAX_VALUE
        else SystemClock.elapsedRealtime() - lastPacketAtMs
        return TelemetryDiagnostics(
            packetCount = packetCount,
            invalidPacketCount = invalidPacketCount,
            ageMs = age,
            source = lastSource,
            port = if (useByd) 0 else port
        )
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
                parse(text)?.let {
                    latest = it
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
