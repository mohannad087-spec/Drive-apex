package com.driveapex.vehicle

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Read-only BYD telemetry bridge.
 *
 * Speed/throttle/brake come from BYDAutoSpeedDevice and motor RPM comes from
 * BYDAutoEngineDevice.getEngineSpeed(). RPM is never synthesized or derived.
 * The proprietary framework is resolved at runtime and no vehicle-control API
 * is called.
 */
class BydHalTelemetryBridge(context: Context) : VehicleTelemetryBridge {
    private val appContext = context.applicationContext
    private val bydContext = BydPermissionContext(appContext)
    private val validator = VehicleTelemetryValidator(500L)
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
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
        if (running || !isAvailable()) return
        running = true
        executor.scheduleAtFixedRate({
            val frame = readFrame() ?: return@scheduleAtFixedRate
            latestFrame = frame
            onFrame(frame)
        }, 0L, 50L, TimeUnit.MILLISECONDS)
    }

    override fun stop() {
        running = false
        latestFrame = null
    }

    private fun ensureApi() {
        if (speedDevice != null && engineDevice != null) return
        runCatching {
            val speedClass = Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice")
            speedDevice = speedClass.getMethod("getInstance", Context::class.java)
                .invoke(null, bydContext)
            getSpeed = speedClass.getMethod("getCurrentSpeed")
            getThrottle = speedClass.getMethod("getAccelerateDeepness")
            getBrake = speedClass.getMethod("getBrakeDeepness")

            val engineClass = Class.forName("android.hardware.bydauto.engine.BYDAutoEngineDevice")
            engineDevice = engineClass.getMethod("getInstance", Context::class.java)
                .invoke(null, bydContext)
            getEngineSpeed = engineClass.getMethod("getEngineSpeed")
            lastError = null
        }.onFailure {
            speedDevice = null
            engineDevice = null
            getSpeed = null
            getThrottle = null
            getBrake = null
            getEngineSpeed = null
            lastError = it.javaClass.simpleName + ": " + (it.message ?: "BYD telemetry API unavailable")
        }
    }

    private fun readFrame(): TelemetryFrame? = runCatching {
        val speed = (getSpeed?.invoke(speedDevice) as Number).toDouble().toFloat()
        val throttlePct = (getThrottle?.invoke(speedDevice) as Number).toFloat()
        val brakePct = (getBrake?.invoke(speedDevice) as Number).toFloat()
        val rpm = (getEngineSpeed?.invoke(engineDevice) as Number).toFloat()
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
        lastError = it.javaClass.simpleName + ": " + (it.message ?: "BYD telemetry read failed")
    }.getOrNull()

    private class BydPermissionContext(base: Context) : ContextWrapper(base) {
        private fun isByd(permission: String?): Boolean =
            permission?.startsWith("android.permission.BYD") == true

        override fun getApplicationContext(): Context = this

        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (isByd(permission)) PackageManager.PERMISSION_GRANTED
            else super.checkPermission(permission, pid, uid)

        override fun checkSelfPermission(permission: String): Int =
            if (isByd(permission)) PackageManager.PERMISSION_GRANTED
            else super.checkSelfPermission(permission)

        override fun checkCallingPermission(permission: String): Int =
            if (isByd(permission)) PackageManager.PERMISSION_GRANTED
            else super.checkCallingPermission(permission)

        override fun checkCallingOrSelfPermission(permission: String): Int =
            if (isByd(permission)) PackageManager.PERMISSION_GRANTED
            else super.checkCallingOrSelfPermission(permission)

        override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {
            if (!isByd(permission)) super.enforcePermission(permission, pid, uid, message)
        }

        override fun enforceCallingPermission(permission: String, message: String?) {
            if (!isByd(permission)) super.enforceCallingPermission(permission, message)
        }

        override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
            if (!isByd(permission)) super.enforceCallingOrSelfPermission(permission, message)
        }
    }
}
