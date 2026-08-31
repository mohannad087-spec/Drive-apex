package com.driveapex.vehicle

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/** Live vehicle telemetry receiver. On BYD units the direct HAL is authoritative. */
class UdpTelemetryReceiver(private val port: Int = 38901, context: Context? = null) {
    private val staleAfterMs = 1500L
    private val validator = VehicleTelemetryValidator(staleAfterMs)
    private val direct = context?.let { DirectBydTelemetryReader(it) }
    private val byd = context?.let { BydHalTelemetryBridge(it) }

    @Volatile private var running = false
    @Volatile private var useDirect = false
    @Volatile private var useByd = false
    @Volatile private var bydDaemonStarted = false
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
        thread = Thread(::liveLoop, "DriveApex-LiveTelemetry").also { it.start() }
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

    fun sourceError(): String? = byd?.error()

    private fun liveLoop() {
        var directFailures = 0
        while (running) {
            val frame = runCatching { direct?.readOnce() }.getOrNull()
            if (frame != null && isUsable(frame)) {
                useDirect = true
                directFailures = 0
                // The direct in-process reader can reliably get speed/throttle/brake
                // without elevated permissions, but front motor speed needs the
                // ADB-granted BYDAUTO_ENGINE_GET permission this process does not
                // have -- it silently fails and defaults motorSpeed to 0, which
                // still passes isUsable(). Without this check that 0 would always
                // win and the privileged daemon (which can actually read it) would
                // never even start.
                val daemonFrame = if (!frame.motorSpeedAvailable) startBydDaemonForRpm() else null
                useByd = daemonFrame != null
                publish(
                    TelemetryFrame(
                        timestampMs = frame.timestampMs,
                        rpm = daemonFrame?.rpm ?: frame.motorSpeed,
                        speedKph = frame.speedKph,
                        throttle = frame.throttle,
                        brake = frame.brake,
                        regen = 0f,
                        source = if (daemonFrame != null) "${frame.source}+BYD_DAEMON_RPM" else frame.source
                    )
                )
                sleep50()
                continue
            }

            directFailures++
            invalidPacketCount++
            if (directFailures >= 5) {
                useDirect = false
                useByd = startBydDaemonForRpm() != null
            }
            sleep50()
        }
    }

    /** Starts (if not already running) the privileged BYD daemon bridge and returns its latest frame. Idempotent. */
    private fun startBydDaemonForRpm(): TelemetryFrame? {
        val bridge = byd ?: return null
        if (!bydDaemonStarted) {
            bydDaemonStarted = runCatching {
                if (!bridge.isAvailable()) false
                else { bridge.start { publish(it) }; true }
            }.getOrDefault(false)
        }
        return bridge.latest()
    }

    private fun isUsable(frame: DirectBydTelemetryReader.Frame): Boolean =
        frame.speedKph.isFinite() && frame.throttle.isFinite() && frame.brake.isFinite() && frame.motorSpeed.isFinite() &&
            frame.speedKph in 0f..400f && frame.throttle in 0f..1f && frame.brake in 0f..1f && frame.motorSpeed in 0f..25000f

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
        if (validator.validate(frame, now) !is VehicleTelemetryValidator.Result.Valid) return null
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
