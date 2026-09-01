package com.driveapex.vehicle

import android.content.Context
import com.driveapex.diag.DriveApexLog
import com.driveapex.update.VehicleAdbConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * Live BYD telemetry bridge.
 *
 * The UI/app process never touches BYD HAL directly. After ADB authorization this
 * class starts the shell-UID telemetry daemon and consumes its local CSV stream.
 */
class BydHalTelemetryBridge(context: Context) : VehicleTelemetryBridge {
    private val appContext = context.applicationContext
    private val adb = VehicleAdbConnection(appContext)
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "driveapex-byd-client").apply { isDaemon = true }
    }

    @Volatile private var running = false
    @Volatile private var latestFrame: TelemetryFrame? = null
    @Volatile private var lastError: String? = null
    @Volatile private var lastDiPlusStatus: String = ""

    fun isAvailable(): Boolean = adb.ensureTelemetryDaemon() || isDaemonReachable()

    fun isDaemonReachable(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", 18765), 250) }
        true
    }.getOrDefault(false)

    fun error(): String? = lastError ?: VehicleAdbConnection.lastError()
    fun diPlusStatus(): String = lastDiPlusStatus
    fun latest(): TelemetryFrame? = latestFrame
    fun adbState(): String = VehicleAdbConnection.state().name

    override fun start(onFrame: (TelemetryFrame) -> Unit) {
        if (running) return
        running = true
        executor.execute {
            // The daemon binary is copied from the installed DriveApex APK.
            // ensureTelemetryDaemon() replaces any daemon not stamped with this
            // build, so an app update cannot leave an older telemetry
            // implementation serving port 18765.
            adb.close()
            if (!adb.ensureTelemetryDaemon()) {
                lastError = VehicleAdbConnection.lastError() ?: "ADB telemetry daemon unavailable"
                // Hand the guard back. Leaving it set marked a bridge that had
                // already given up as running, so every later start() returned
                // immediately and the session never got a vehicle frame again.
                running = false
                return@execute
            }
            var failures = 0
            while (running) {
                try {
                    // A stream that opens and ends without delivering anything is
                    // a fault, not a completed read; counting it as success here
                    // would spin this loop with no backoff at all.
                    if (consumeStream(onFrame) > 0) failures = 0 else failures++
                    if (running) Thread.sleep(500L)
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    failures++
                    // The daemon is a process on the head unit and it can die --
                    // the log showed one stamped as serving and then gone. Simply
                    // reconnecting forever to a port nothing holds is a session
                    // stuck at zero, so after a few seconds of that, put it back.
                    if (failures % 10 == 0 && failures <= 60) {
                        DriveApexLog.i("daemon", "stream down x$failures, relaunching daemon")
                        runCatching { adb.ensureTelemetryDaemon() }
                    }
                    if (running) Thread.sleep(500L)
                }
            }
        }
    }

    override fun stop() {
        running = false
        latestFrame = null
    }

    /** Returns how many frames this connection delivered. */
    private fun consumeStream(onFrame: (TelemetryFrame) -> Unit): Int {
        var delivered = 0
        Socket("127.0.0.1", 18765).use { socket ->
            socket.soTimeout = 5_000
            BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).use { reader ->
                while (running) {
                    val line = reader.readLine() ?: break
                    parse(line)?.let { frame ->
                        if (latestFrame == null) {
                            DriveApexLog.i("daemon", "first frame: rpm=${frame.rpm} speed=${frame.speedKph}")
                        }
                        latestFrame = frame
                        lastError = null
                        delivered++
                        onFrame(frame)
                    }
                }
            }
        }
        return delivered
    }

    private fun parse(line: String): TelemetryFrame? = runCatching {
        val p = line.split(',')
        if (p.size < 6) return null
        // The daemon's own account of the DiPlus read. Reading zero because the
        // service refused us and reading zero because the motor is stopped look
        // identical in the number; here they do not.
        if (p.size >= 7) {
            val status = p[6].trim()
            if (status.isNotEmpty() && status != lastDiPlusStatus) {
                lastDiPlusStatus = status
                DriveApexLog.i("daemon", "diplus: $status")
            }
        }
        val rawRpm = p[1].toFloat()
        val rpm = when {
            !rawRpm.isFinite() -> return null
            rawRpm == 8191f || rawRpm == -8191f || rawRpm == 32767f || rawRpm == -32768f || rawRpm == 65535f -> 0f
            else -> rawRpm.coerceIn(0f, 7000f)
        }
        val frame = TelemetryFrame(
            timestampMs = p[0].toLong(),
            rpm = rpm,
            speedKph = p[2].toFloat(),
            throttle = (p[3].toFloat() / 100f).coerceIn(0f, 1f),
            brake = (p[4].toFloat() / 100f).coerceIn(0f, 1f),
            regen = 0f,
            source = p[5]
        )
        frame.takeIf { frame.timestampMs > 0L && frame.speedKph.isFinite() }
    }.getOrNull()
}
