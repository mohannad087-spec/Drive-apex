package com.driveapex.vehicle

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** Direct BYD HAL reader using the same context/permission pattern as Overdrive. */
class DirectBydTelemetryReader(context: Context) {
    data class Frame(
        val timestampMs: Long,
        val rpm: Float,
        val speedKph: Float,
        val throttle: Float,
        val brake: Float,
        val source: String = "BYD_HAL_DIRECT"
    )

    private val rawContext = context.applicationContext ?: context
    private val bydContext: Context = BydPermissionContext(rawContext)

    private var speedDevice: Any? = null
    private var motorDevice: Any? = null
    private var engineDevice: Any? = null

    @Volatile private var initialized = false
    @Volatile private var lastFrame: Frame? = null

    fun isAvailable(): Boolean = readOnce() != null

    fun latest(): Frame? = lastFrame

    fun start(onFrame: (Frame) -> Unit) {
        val frame = readOnce() ?: return
        onFrame(frame)
    }

    fun readOnce(): Frame? {
        ensureInitialized()
        val speed = speedDevice
        val motor = motorDevice
        val engine = engineDevice

        val speedKph = callGetter(speed, "getCurrentSpeed").asDouble()
        val throttlePct = callGetter(speed, "getAccelerateDeepness").asDouble()
        val brakePct = callGetter(speed, "getBrakeDeepness").asDouble()
        val motorRpm = callGetter(motor, "getMotorSpeed").asDouble()
        val engineRpm = callGetter(engine, "getEngineSpeed").asDouble()

        val rpm = firstNonNegative(motorRpm, engineRpm)
        if (speedKph == null && throttlePct == null && brakePct == null && rpm == null) {
            return null
        }

        val frame = Frame(
            timestampMs = System.currentTimeMillis(),
            rpm = rpm?.toFloat()?.coerceAtLeast(0f) ?: 0f,
            speedKph = speedKph?.toFloat()?.coerceIn(0f, 300f) ?: 0f,
            throttle = ((throttlePct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f),
            brake = ((brakePct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
        )
        lastFrame = frame
        return frame
    }

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            speedDevice = getDevice("android.hardware.bydauto.speed.BYDAutoSpeedDevice")
            motorDevice = getDevice("android.hardware.bydauto.motor.BYDAutoMotorDevice")
            engineDevice = getDevice("android.hardware.bydauto.engine.BYDAutoEngineDevice")
            initialized = true
        }
    }

    private fun getDevice(className: String): Any? {
        return try {
            val clazz = Class.forName(className)
            val getInstance = clazz.getMethod("getInstance", Context::class.java)
            val device = getInstance.invoke(null, bydContext)
            if (device != null) {
                ensureDeviceContext(device)
            }
            device
        } catch (e: InvocationTargetException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    /** Mirrors Overdrive: repair a cached BYD object's mContext when the SDK leaves it empty. */
    private fun ensureDeviceContext(device: Any) {
        try {
            var clazz: Class<*>? = device.javaClass
            while (clazz != null) {
                val field = runCatching { clazz.getDeclaredField("mContext") }.getOrNull()
                if (field != null) {
                    field.isAccessible = true
                    val current = field.get(device)
                    if (current == null) field.set(device, bydContext)
                    return
                }
                clazz = clazz.superclass
            }
        } catch (_: Throwable) {
            // Non-critical: some firmware revisions do not expose mContext.
        }
    }

    /** Mirrors Overdrive's public getter first, then declared-method fallback. */
    private fun callGetter(device: Any?, methodName: String): Any? {
        if (device == null) return null
        try {
            val method = try {
                device.javaClass.getMethod(methodName)
            } catch (_: NoSuchMethodException) {
                findDeclaredNoArgMethod(device.javaClass, methodName)
            } ?: return null
            method.isAccessible = true
            return method.invoke(device)
        } catch (e: InvocationTargetException) {
            val cause = e.cause
            System.err.println(
                "DriveApex BYD getter $methodName threw: " +
                    (cause?.javaClass?.simpleName ?: "unknown") + ": " +
                    (cause?.message ?: "")
            )
        } catch (t: Throwable) {
            System.err.println("DriveApex BYD getter $methodName failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        return null
    }

    private fun findDeclaredNoArgMethod(clazz: Class<*>, name: String): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            for (method in current.declaredMethods) {
                if (method.name == name && method.parameterTypes.isEmpty()) return method
            }
            current = current.superclass
        }
        return null
    }

    private fun Any?.asDouble(): Double? = when (this) {
        is Number -> toDouble().takeIf { it.isFinite() }
        is String -> toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }

    private fun firstNonNegative(first: Double?, second: Double?): Double? {
        return sequenceOf(first, second)
            .filter { it != null && it.isFinite() && it >= 0.0 }
            .firstOrNull()
    }

    private class BydPermissionContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        private fun isBydPermission(permission: String?): Boolean =
            permission?.startsWith("android.permission.BYD") == true

        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (isBydPermission(permission)) PackageManager.PERMISSION_GRANTED
            else super.checkPermission(permission, pid, uid)

        override fun checkCallingPermission(permission: String): Int =
            if (isBydPermission(permission)) PackageManager.PERMISSION_GRANTED
            else super.checkCallingPermission(permission)

        override fun checkCallingOrSelfPermission(permission: String): Int =
            if (isBydPermission(permission)) PackageManager.PERMISSION_GRANTED
            else super.checkCallingOrSelfPermission(permission)

        override fun checkSelfPermission(permission: String): Int =
            if (isBydPermission(permission)) PackageManager.PERMISSION_GRANTED
            else super.checkSelfPermission(permission)

        override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {
            if (!isBydPermission(permission)) super.enforcePermission(permission, pid, uid, message)
        }

        override fun enforceCallingPermission(permission: String, message: String?) {
            if (!isBydPermission(permission)) super.enforceCallingPermission(permission, message)
        }

        override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
            if (!isBydPermission(permission)) super.enforceCallingOrSelfPermission(permission, message)
        }
    }
}
