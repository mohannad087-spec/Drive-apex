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

    /**
     * DiPlus's own approach, and the cheapest one: register an engine listener from
     * this process and read what it delivers. DiPlus is a self-signed third-party APK
     * with no sharedUserId and no privileged install, and it does exactly this -- no
     * ADB, no daemon -- so if the declared BYD permissions are grantable by
     * declaration alone this is all that was ever needed.
     */
    private val inProcess = context?.let { runCatching { FrontMotorSpeedReader(it) }.getOrNull() }
    @Volatile private var inProcessStarted = false

    /**
     * The DiPlus API by way of the shell UID. A direct socket from this process
     * to 127.0.0.1:8988 is met with a TCP reset while the identical request from
     * the shell UID returns the value, so this is the path proven to work on the
     * vehicle. It polls on its own thread and the loop only reads a cached value.
     */
    private val diPlusAdb = context?.let { runCatching { DiPlusAdbBridge(it) }.getOrNull() }
    @Volatile private var diPlusAdbStarted = false

    @Volatile private var running = false
    @Volatile private var useDirect = false
    @Volatile private var useByd = false
    @Volatile private var diPlusCached: Int? = null
    @Volatile private var diPlusReadAtMs = 0L
    private val diPlusCacheMs = 30_000L
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
        diPlusAdb?.let {
            diPlusAdbStarted = true
            runCatching { it.start() }
        }
        thread = Thread(::liveLoop, "DriveApex-LiveTelemetry").also { it.start() }
    }

    fun stop() {
        running = false
        useDirect = false
        useByd = false
        byd?.stop()
        if (inProcessStarted) runCatching { inProcess?.stop() }
        inProcessStarted = false
        if (diPlusAdbStarted) runCatching { diPlusAdb?.stop() }
        diPlusAdbStarted = false
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
                // The direct reader gets speed/throttle/brake but its front-motor read
                // can fail silently and default motorSpeed to 0, which still passes
                // isUsable() -- so that 0 must never be allowed to win on its own.
                // Preference for RPM: the direct reader's own value, then DiPlus's
                // local API (verified working on the vehicle: 200 OK, no auth), then
                // the in-process listener, then the ADB daemon.
                // ADB first, deliberately. On this vehicle nothing in this app's
                // own process can read the motor: the HAL listener registers and
                // is never dispatched to, and a direct socket to the DiPlus API is
                // met with a TCP reset while the identical request from the shell
                // UID returns the value. ADB is the read path, not a fallback.
                val diPlusAdbRpm = if (!frame.motorSpeedAvailable) diPlusAdbRpm() else null
                val diPlusRpm =
                    if (!frame.motorSpeedAvailable && diPlusAdbRpm == null) diPlusRpm() else null
                val inProcessRpm =
                    if (!frame.motorSpeedAvailable && diPlusAdbRpm == null && diPlusRpm == null)
                        inProcessRpm() else null
                // The shell-UID daemon is deliberately not started from here any
                // more. It has never delivered a value on this vehicle, and its
                // start path holds the ADB lock across many round trips, which
                // starved the one source that does work: every 15s the bridge's
                // reading went stale and the dashboard fell back to zero. Only
                // consume a frame if some other caller has it running already.
                val daemonFrame =
                    if (!frame.motorSpeedAvailable && diPlusAdbRpm == null && diPlusRpm == null &&
                        inProcessRpm == null
                    ) byd?.latest() else null
                useByd = daemonFrame != null
                val rpm = frame.motorSpeed.takeIf { frame.motorSpeedAvailable }
                    ?: diPlusAdbRpm?.toFloat()
                    ?: diPlusRpm?.toFloat()
                    ?: inProcessRpm?.toFloat()
                    ?: daemonFrame?.rpm
                    ?: frame.motorSpeed
                publish(
                    TelemetryFrame(
                        timestampMs = frame.timestampMs,
                        rpm = rpm,
                        speedKph = frame.speedKph,
                        throttle = frame.throttle,
                        brake = frame.brake,
                        regen = 0f,
                        source = when {
                            frame.motorSpeedAvailable -> frame.source
                            diPlusAdbRpm != null -> "${frame.source}+DIPLUS_ADB_RPM"
                            diPlusRpm != null -> "${frame.source}+DIPLUS_RPM"
                            inProcessRpm != null -> "${frame.source}+INPROCESS_RPM"
                            daemonFrame != null -> "${frame.source}+BYD_DAEMON_RPM"
                            else -> frame.source
                        }
                    )
                )
                sleep50()
                continue
            }

            directFailures++
            invalidPacketCount++
            if (directFailures >= 5) {
                useDirect = false
                diPlusAdbRpm()
                inProcessRpm()
            }
            sleep50()
        }
    }

    /**
     * Front motor RPM straight from DiPlus's local API, cached briefly so the 50 ms
     * loop does not hammer it. DiPlus already holds the value the BYD HAL will not
     * hand this app, and serves it unauthenticated on the loopback interface, so
     * consuming it is far more reliable than re-deriving the HAL read.
     */
    /**
     * The DiPlus API over a direct socket. Kept only as an opportunistic probe on
     * a long interval: this app's socket to the service is currently reset every
     * time, so retrying it at loop rate would burn a connection attempt per frame
     * for nothing. If it ever starts answering it is preferred over ADB, being
     * far cheaper.
     */
    private fun diPlusRpm(): Int? {
        val now = SystemClock.elapsedRealtime()
        if (now - diPlusReadAtMs < diPlusCacheMs) return diPlusCached
        diPlusReadAtMs = now
        diPlusCached = runCatching { DiPlusMotorSpeedReader.readFrontMotorRpm()?.toInt() }.getOrNull()
        return diPlusCached
    }

    /**
     * Same value via the shell UID, used when the direct socket is refused.
     * Starting the poller is cheap and idempotent; reading is a cached field.
     */
    private fun diPlusAdbRpm(): Int? {
        val bridge = diPlusAdb ?: return null
        if (!diPlusAdbStarted) {
            diPlusAdbStarted = true
            runCatching { bridge.start() }
        }
        return runCatching { bridge.latestRpm() }.getOrNull()
    }

    /**
     * Latest RPM from the in-process engine listener, registering it on first use.
     * Unlike the daemon bridge this costs nothing -- getInstance() plus
     * registerListener(), no ADB round-trip -- so it is safe to call from the loop.
     * Returns null until a plausible value has actually been delivered, which keeps
     * a silently-inert listener from reporting a fake 0.
     */
    private fun inProcessRpm(): Int? {
        val reader = inProcess ?: return null
        if (!inProcessStarted) {
            inProcessStarted = true
            runCatching { reader.start() }
        }
        return runCatching { reader.frontMotorRpm }.getOrNull()
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
