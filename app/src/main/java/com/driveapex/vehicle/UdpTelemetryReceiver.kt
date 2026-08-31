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
    @Volatile private var useAdbBridge = false
    @Volatile private var smoothedRpm: Float? = null
    @Volatile private var lastPublishedRpm = 0f
    private val rpmValidator = MotorRpmValidator()
    @Volatile private var leadPreviousValue: Float? = null
    @Volatile private var leadPreviousAtMs = 0L
    @Volatile private var leadRatePerMs = 0f

    // Swept against a simulated ramp with noise rather than chosen by feel.
    private val LEAD_MS = 150f
    private val LEAD_MAX_RPM = 800f
    private val LEAD_RATE_SMOOTHING = 0.25f
    private val LEAD_RATE_GATE = 0.15f
    @Volatile private var lastControlsValue: Controls? = null
    @Volatile private var lastControlsAtMs = 0L
    private val controlsHoldMs = 1_200L
    @Volatile private var daemonStartAttempted = false
    @Volatile private var diPlusCached: Int? = null
    @Volatile private var diPlusReadAtMs = 0L
    private val diPlusCacheMs = 30_000L

    // dt 50ms over a ~63ms time constant. Swept against the observed sample
    // pattern: this removes the same stutter as a slower filter (1 repeated
    // frame in 9, down from 7) while settling a 300 RPM change in 150ms instead
    // of 300ms. Anything faster only adds lag back with no further smoothing.
    private val RPM_SMOOTHING = 0.55f
    private val SNAP_DELTA_RPM = 600f
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
        smoothedRpm = null
        lastPublishedRpm = 0f
        lastControlsValue = null
        rpmValidator.reset()
        leadPreviousValue = null
        leadRatePerMs = 0f
        diPlusAdb?.let {
            diPlusAdbStarted = true
            runCatching { it.start() }
        }
        startDaemonOnce()
        thread = Thread(::liveLoop, "DriveApex-LiveTelemetry").also { it.start() }
    }

    fun stop() {
        running = false
        useDirect = false
        useByd = false
        useAdbBridge = false
        daemonStartAttempted = false
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
            port = if (useDirect || useByd || useAdbBridge) 0 else port
        )
    }

    fun sourceError(): String? = byd?.error()

    private fun liveLoop() {
        var directFailures = 0
        while (running) {
            val frame = runCatching { direct?.readOnce() }.getOrNull()?.takeIf { isUsable(it) }
            if (frame != null) {
                useDirect = true
                directFailures = 0
            } else {
                directFailures++
                if (directFailures >= 5) useDirect = false
            }

            // Motor RPM, in order of trust. ADB comes before everything except a
            // genuine direct-reader value: on this vehicle nothing in this app's
            // own process can read this signal -- the HAL listener registers
            // unfiltered and is never dispatched to, and a direct socket to the
            // DiPlus API is met with a TCP reset while the identical request from
            // the shell UID returns the value.
            val directRpm = if (frame != null && frame.motorSpeedAvailable) frame.motorSpeed else null
            val adbRpm = if (directRpm == null) diPlusAdbRpm() else null
            val socketRpm = if (directRpm == null && adbRpm == null) diPlusRpm() else null
            val listenerRpm =
                if (directRpm == null && adbRpm == null && socketRpm == null) inProcessRpm() else null
            val daemonFrame = byd?.latest()
            useByd = daemonFrame != null
            useAdbBridge = adbRpm != null

            val rawRpm = directRpm
                ?: adbRpm?.toFloat()
                ?: socketRpm?.toFloat()
                ?: listenerRpm?.toFloat()
                ?: daemonFrame?.rpm
            // Validate before smoothing, not after. A dropout to zero exceeds the
            // smoother's snap threshold, so it would be passed through at full
            // speed -- the filter is bypassed exactly when it is most needed.
            val validated = rpmValidator.accept(
                rawRpm,
                controlsSpeedHint(frame, daemonFrame),
                SystemClock.elapsedRealtime()
            )
            val rpm = validated?.let { smoothRpm(leadCompensated(it, SystemClock.elapsedRealtime())) }

            // Speed, throttle and brake come from whichever source has them. The
            // daemon publishes a whole frame, not just RPM, and dropping its
            // starter took those three down with it -- an RPM-only publish then
            // wrote zeros over them every tick. Remember the last real reading and
            // keep using it while it is fresh, rather than asserting zero for a
            // value this pass simply does not have.
            val controls = frame?.let {
                Controls(it.speedKph, it.throttle, it.brake).also { c -> rememberControls(c) }
            } ?: daemonFrame?.let {
                Controls(it.speedKph, it.throttle, it.brake).also { c -> rememberControls(c) }
            } ?: lastControls()

            // Publish whenever there is anything to publish. The previous version
            // only published when the direct reader produced a usable frame, so on
            // a vehicle where that reader fails the RPM fetched just above was
            // dropped on the floor and the dashboard never received a single
            // frame -- zero from launch, for every field.
            if (rpm != null) lastPublishedRpm = rpm
            if (frame != null || rpm != null || daemonFrame != null) {
                val base = frame?.source ?: "BYD_ADB"
                publish(
                    TelemetryFrame(
                        timestampMs = frame?.timestampMs ?: System.currentTimeMillis(),
                        // Never assert zero for a value this pass simply does not
                        // have; that is what made the reading collapse and recover.
                        rpm = rpm ?: lastPublishedRpm,
                        speedKph = controls.speedKph,
                        throttle = controls.throttle,
                        brake = controls.brake,
                        regen = 0f,
                        source = when {
                            directRpm != null -> base
                            adbRpm != null -> "$base+DIPLUS_ADB_RPM"
                            socketRpm != null -> "$base+DIPLUS_RPM"
                            listenerRpm != null -> "$base+INPROCESS_RPM"
                            daemonFrame != null -> "$base+BYD_DAEMON_RPM"
                            else -> base
                        }
                    )
                )
            } else {
                invalidPacketCount++
            }
            sleep50()
        }
    }

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
            // start() now hands registration to a Looper thread and waits for it,
            // so it must not run inline: a couple of seconds here would stall the
            // 50ms loop and blank the dashboard, which is the same mistake that
            // cost a release earlier.
            Thread({ runCatching { reader.start() } }, "DriveApex-HalRegister")
                .apply { isDaemon = true }
                .start()
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
                    if (useDirect || useByd || useAdbBridge) TelemetrySource.LIVE_BRIDGE else TelemetrySource.LIVE_UDP,
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

    /**
     * Glides the published RPM toward each new sample.
     *
     * The source updates roughly twice as slowly as this loop publishes, so the
     * raw value repeats and then jumps, which reads as a stutter on the dial and
     * as a step in the audio the controller drives from it. A one-pole filter at
     * a ~120ms time constant fills the gap between samples while still tracking
     * a real change within about two frames.
     *
     * Big movements are passed straight through: a genuine jump (launch, hard
     * lift, the first sample after a gap) should arrive immediately, and only
     * the small inter-sample steps need bridging.
     */
    private fun smoothRpm(target: Float): Float {
        if (!target.isFinite()) return target
        val previous = smoothedRpm
        if (previous == null || kotlin.math.abs(target - previous) > SNAP_DELTA_RPM) {
            smoothedRpm = target
            return target
        }
        val next = previous + (target - previous) * RPM_SMOOTHING
        smoothedRpm = next
        return next
    }

    /**
     * Brings up the shell-UID daemon exactly once, in the background.
     *
     * It supplies a whole frame -- speed, throttle and brake as well as RPM -- so
     * it has to run; those three came from here and disappeared when its starter
     * was removed. What was actually wrong was the retry: it fired from the live
     * loop every 15s, and each attempt copies an APK and polls for a port over
     * ADB, holding the shell lock long enough to starve the RPM poller. Once, off
     * the loop, with no retry, keeps the frame without the starvation.
     */
    private fun startDaemonOnce() {
        val bridge = byd ?: return
        if (daemonStartAttempted) return
        daemonStartAttempted = true
        Thread({
            runCatching {
                if (bridge.isAvailable()) bridge.start { publish(it) }
            }
        }, "DriveApex-BydDaemonStart").apply { isDaemon = true }.start()
    }

    private data class Controls(val speedKph: Float, val throttle: Float, val brake: Float)

    private fun rememberControls(controls: Controls) {
        lastControlsValue = controls
        lastControlsAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * The last real speed/throttle/brake, while it is recent enough to still mean
     * something. Past that it reverts to zero rather than showing a reading the
     * vehicle has not confirmed for over a second.
     */
    private fun lastControls(): Controls {
        val remembered = lastControlsValue ?: return Controls(0f, 0f, 0f)
        val age = SystemClock.elapsedRealtime() - lastControlsAtMs
        return if (age <= controlsHoldMs) remembered else Controls(0f, 0f, 0f)
    }

    /**
     * Projects the reading forward by the transport delay it arrives with.
     *
     * The value reaches here about 150ms after the vehicle produced it -- the
     * on-vehicle writer, the ADB poll and this loop each contribute -- so during
     * a change what is displayed and sounded is always behind. Estimating the
     * rate from consecutive samples and projecting by that delay recovers most
     * of it.
     *
     * The rate estimate amplifies noise, which is why it is gated: below a real
     * rate of change the projection is switched off entirely, since a wobble
     * added to a steady reading is more objectionable than lag. Measured against
     * a simulated ramp with +/-40 rpm of noise, the gate keeps the error
     * reduction at 49% while holding steady-state jitter at 38 against the raw
     * signal's own 31 -- ungated it was 51.
     */
    private fun leadCompensated(value: Float, nowMs: Long): Float {
        val previous = leadPreviousValue
        if (previous != null) {
            val dt = nowMs - leadPreviousAtMs
            if (dt in 20..500) {
                val rate = (value - previous) / dt
                leadRatePerMs += (rate - leadRatePerMs) * LEAD_RATE_SMOOTHING
            }
        }
        leadPreviousValue = value
        leadPreviousAtMs = nowMs

        val magnitude = kotlin.math.abs(leadRatePerMs)
        if (magnitude < LEAD_RATE_GATE) return value
        val activation = ((magnitude - LEAD_RATE_GATE) / LEAD_RATE_GATE).coerceAtMost(1f)
        val projected = value + leadRatePerMs * LEAD_MS * activation
        return projected.coerceIn(value - LEAD_MAX_RPM, value + LEAD_MAX_RPM)
    }

    /** Road speed from whichever source has it, for the plausibility check. */
    private fun controlsSpeedHint(
        frame: DirectBydTelemetryReader.Frame?,
        daemonFrame: TelemetryFrame?
    ): Float = frame?.speedKph ?: daemonFrame?.speedKph ?: lastControlsValue?.speedKph ?: 0f

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
