package com.driveapex.vehicle

import android.content.Context
import com.driveapex.update.VehicleAdbConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Live BYD telemetry bridge.
 *
 * The UI/app process never touches BYD HAL directly. After ADB authorization this
 * class starts the shell-UID BydTelemetryDaemon and consumes its local CSV stream.
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

    fun isDaemonReachable(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", 18765), 250) }
        true
    }.getOrDefault(false)

    fun error(): String? = lastError ?: VehicleAdbConnection.lastError()
    fun latest(): TelemetryFrame? = latestFrame
    fun adbState(): String = VehicleAdbConnection.state().name

    override fun start(onFrame: (TelemetryFrame) -> Unit) {
        if (running) return
        running = true
        executor.execute {
            if (!adb.ensureTelemetryDaemon()) {
                lastError = adb.errorString()
                return@execute
            }
            while (running) {
                try {
                    consumeStream(onFrame)
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    if (running) Thread.sleep(500L)
                }
            }
        }
    }

    override fun stop() {
        running = false
        latestFrame = null
    }

    private fun consumeStream(onFrame: (TelemetryFrame) -> Unit) {
        Socket("127.0.0.1", 18765).use { socket ->
            socket.soTimeout = 5_000
            BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).use { reader ->
                while (running) {
                    val line = reader.readLine() ?: break
                    parse(line)?.let { frame ->
                        latestFrame = frame
                        lastError = null
                        onFrame(frame)
                    }
                }
            }
        }
    }

    private fun parse(line: String): TelemetryFrame? = runCatching {
        val p = line.split(',')
        if (p.size < 6) return null
        val frame = TelemetryFrame(
            timestampMs = p[0].toLong(),
            rpm = p[1].toFloat(),
            speedKph = p[2].toFloat(),
            throttle = (p[3].toFloat() / 100f).coerceIn(0f, 1f),
            brake = (p[4].toFloat() / 100f).coerceIn(0f, 1f),
            regen = 0f,
            source = p[5]
        )
        frame.takeIf { frame.timestampMs > 0L && frame.rpm.isFinite() && frame.speedKph.isFinite() }
    }.getOrNull()
}

private fun VehicleAdbConnection.errorString(): String = lastError() ?: "ADB telemetry daemon unavailable"
