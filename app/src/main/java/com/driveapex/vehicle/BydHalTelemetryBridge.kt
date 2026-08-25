package com.driveapex.vehicle

import android.content.Context
import com.driveapex.update.VehicleAdbConnection
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Read-only BYD telemetry bridge.
 *
 * ADB is used for authorization/permission bootstrap. Once that is complete,
 * vehicle values are read from the BYD framework itself. No fake permission
 * Context is used: the framework sees the real OS permission state granted by
 * the ADB bootstrap layer.
 */
class BydHalTelemetryBridge(context: Context) : VehicleTelemetryBridge {
    private val appContext = context.applicationContext
    private val adb = VehicleAdbConnection(appContext)
    private val validator = VehicleTelemetryValidator(500L)
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "driveapex-byd-telemetry").apply { isDaemon = true }
    }

    @Volatile private var running = false
    @Volatile private var latestFrame: TelemetryFrame? = null
    @Volatile private var lastError: String? = null

    private var speedDevice: Any? = null
    private var engineDevice: Any? = null
    private var getSpeed: java.lang.reflect.Method? = null
    private var getThrottle: java.lang.reflect.Method? = null
    private var getBrake: java.lang.reflect.Method? = null
    private var getEngineSpeed: java.lang.reflect.Method? = null

    fun isAvailable(): Boolean {
        ensureApi()
        return speedDevice != null && engineDevice != null &&
            getSpeed != null && getThrottle != null && getBrake != null && getEngineSpeed != null
    }

    fun error(): String? = lastError
    fun latest(): TelemetryFrame? = latestFrame

    override fun start(onFrame: (TelemetryFrame) -> Unit) {
        if (running) return
        running = true
        executor.execute {
            // Give the ADB bootstrap a chance to authorize/grant before the first HAL call.
            adb.connect()
            ensureApi()
        }
        executor.scheduleAtFixedRate({
            if (!isAvailable()) return@scheduleAtFixedRate
            val frame = readFrame() ?: return@scheduleAtFixedRate
            latestFrame = frame
            onFrame(frame)
        }, 250L, 50L, TimeUnit.MILLISECONDS)
    }

    override fun stop() {
        running = false
        latestFrame = null
    }

    private fun ensureApi() {
        if (speedDevice != null && engineDevice != null) return
        runCatching {
            val speedClass = Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice")
            val speedInstance = speedClass.getMethod("getInstance", Context::class.java)
            getSpeed = speedClass.getMethod("getCurrentSpeed")
            getThrottle = speedClass.getMethod("getAccelerateDeepness")
            getBrake = speedClass.getMethod("getBrakeDeepness")
            speedDevice = speedInstance.invoke(null, appContext)

            val engineClass = Class.forName("android.hardware.bydauto.engine.BYDAutoEngineDevice")
            val engineInstance = engineClass.getMethod("getInstance", Context::class.java)
            getEngineSpeed = engineClass.getMethod("getEngineSpeed")
            engineDevice = engineInstance.invoke(null, appContext)
            lastError = null
        }.onFailure {
            speedDevice = null
            engineDevice = null
            getSpeed = null
            getThrottle = null
            getBrake = null
            getEngineSpeed = null
            lastError = rootCause(it).let { e ->
                "${e.javaClass.simpleName}: ${e.message ?: "BYD telemetry API unavailable"}"
            }
        }
    }

    private fun readFrame(): TelemetryFrame? = runCatching {
        val speed = (getSpeed!!.invoke(speedDevice) as Number).toDouble().toFloat()
        val throttlePct = (getThrottle!!.invoke(speedDevice) as Number).toFloat()
        val brakePct = (getBrake!!.invoke(speedDevice) as Number).toFloat()
        val rpm = (getEngineSpeed!!.invoke(engineDevice) as Number).toFloat()
        val now = System.currentTimeMillis()

        val frame = TelemetryFrame(
            timestampMs = now,
            rpm = rpm,
            speedKph = speed,
            throttle = (throttlePct / 100f).coerceIn(0f, 1f),
            brake = (brakePct / 100f).coerceIn(0f, 1f),
            regen = 0f,
            source = "BYD_HAL_SPEED_ENGINE"
        )

        if (validator.validate(frame, now) is VehicleTelemetryValidator.Result.Valid) frame else null
    }.onFailure {
        lastError = rootCause(it).let { e ->
            "${e.javaClass.simpleName}: ${e.message ?: "BYD telemetry read failed"}"
        }
    }.getOrNull()

    private fun rootCause(error: Throwable): Throwable {
        var current = error
        val seen = HashSet<Throwable>()
        while (current is InvocationTargetException && current.targetException != null && seen.add(current)) {
            current = current.targetException
        }
        while (current.cause != null && current.cause !== current && seen.add(current)) {
            current = current.cause!!
        }
        return current
    }
}
