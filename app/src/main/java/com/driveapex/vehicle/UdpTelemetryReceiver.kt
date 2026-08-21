package com.driveapex.vehicle

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Local-network telemetry bridge for phone testing.
 * A future BYD/DiLink bridge can forward JSON packets here without changing the audio engine.
 */
class UdpTelemetryReceiver(private val port: Int = 38901) {
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    @Volatile private var latest: LiveTelemetry? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::receiveLoop, "DriveApex-TelemetryUDP").also { it.start() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        thread = null
    }

    fun latest(): LiveTelemetry? = latest

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
                parse(text)?.let { latest = it }
            }
        }
        runCatching { local.close() }
    }

    private fun parse(text: String): LiveTelemetry? = runCatching {
        val json = JSONObject(text)
        val rpm = json.optDouble("rpm", 700.0).toFloat().coerceIn(700f, 7000f)
        val speed = json.optDouble("speedKph", 0.0).toFloat().coerceIn(0f, 300f)
        val throttle = json.optDouble("throttle", 0.0).toFloat().coerceIn(0f, 1f)
        val brake = json.optDouble("brake", 0.0).toFloat().coerceIn(0f, 1f)
        val regen = json.optDouble("regen", 0.0).toFloat().coerceIn(0f, 1f)
        val data = VehicleData(
            rpm = rpm,
            speedKph = speed,
            throttle = throttle,
            isDriving = speed > 1f,
            brake = brake,
            regen = regen
        )
        LiveTelemetry(data, TelemetrySource.LIVE_UDP)
    }.getOrNull()
}
