package com.driveapex.vehicle

import android.os.SystemClock
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/** Read-only local-network telemetry bridge for phone/vehicle testing. */
class UdpTelemetryReceiver(private val port: Int = 38901) {
    private val staleAfterMs = 250L
    private val validator = VehicleTelemetryValidator(staleAfterMs)
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    @Volatile private var latest: LiveTelemetry? = null
    @Volatile private var lastPacketAtMs = 0L

    fun start() {
        if (running) return
        running = true
        lastPacketAtMs = 0L
        latest = null
        thread = Thread(::receiveLoop, "DriveApex-TelemetryUDP").also { it.start() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        thread = null
        latest = null
        lastPacketAtMs = 0L
    }

    fun latest(): LiveTelemetry? {
        val snapshot = latest ?: return null
        val age = if (lastPacketAtMs == 0L) Long.MAX_VALUE
        else SystemClock.elapsedRealtime() - lastPacketAtMs
        return if (age <= staleAfterMs) snapshot else safeIdleSnapshot()
    }

    private fun safeIdleSnapshot(): LiveTelemetry = LiveTelemetry(
        data = VehicleData(
            rpm = 700f,
            speedKph = 0f,
            throttle = 0f,
            isDriving = false,
            brake = 0f,
            regen = 0f
        ),
        source = TelemetrySource.LIVE_UDP
    )

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
                    lastPacketAtMs = SystemClock.elapsedRealtime()
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
        if (validator.validate(frame, now) !is VehicleTelemetryValidator.Result.Valid) return null
        val data = VehicleData(
            rpm = frame.rpm.coerceIn(700f, 7000f),
            speedKph = frame.speedKph.coerceIn(0f, 300f),
            throttle = frame.throttle.coerceIn(0f, 1f),
            isDriving = frame.speedKph > 1f,
            brake = frame.brake.coerceIn(0f, 1f),
            regen = frame.regen.coerceIn(0f, 1f)
        )
        LiveTelemetry(data, TelemetrySource.LIVE_UDP)
    }.getOrNull()
}
