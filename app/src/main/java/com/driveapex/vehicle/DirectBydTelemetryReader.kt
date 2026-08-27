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

    fun isAvailable(): Boolean = readOnce()?.let { isUsable(it) } == true

    fun latest(): Frame? = lastFrame

    fun start(onFrame: (Frame) -> Unit) {
        readOnce()?.let(onFrame)
    }

    fun readOnce(): Frame? {
        ensureInitialized()
        val speed = speedDevice
        val motor = motorDevice
        val engine = engineDevice

        val speedKph = callGetter(speed, "getCurrentSpeed").asDouble()
        val throttlePct = callGetter(speed, "getAccelerateDeepness").asDouble()
        val brakePct = callGetter(speed, "getBrakeDeepness").asDouble()

        // The connected BYD reference app does not rely only on getMotorSpeed().
        // On some firmware getMotorSpeed() returns 8191 while the vehicle is stopped;
        // that value is a HAL sentinel, not 8191 real RPM. Prefer the engine feature
        // channels used by the reference implementation, then fall back to the getter.
        val frontRaw = readFeatureInt(engine, ENGINE_FRONT_MOTOR_SPEED)
        val rearRaw = readFeatureInt(engine, ENGINE_REAR_MOTOR_SPEED)
        val featureRpm = selectFeatureMotorRpm(frontRaw, rearRaw)
        val motorRpm = sanitizeRpm(callGetter(motor, "getMotorSpeed").asDouble())
        val engineRpm = sanitizeRpm(callGetter(engine, "getEngineSpeed").asDouble())
        val rpm = featureRpm ?: motorRpm ?: engineRpm

        if (speedKph == null && throttlePct == null && brakePct == null && rpm == null) return null

        val frame = Frame(
            timestampMs = System.currentTimeMillis(),
            rpm = (rpm ?: 0.0).toFloat().coerceIn(0f, MAX_RPM),
            speedKph = (speedKph ?: 0.0).toFloat().coerceIn(0f, 300f),
            throttle = ((throttlePct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f),
            brake = ((brakePct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
        )
        lastFrame = frame
        return frame
    }

    private fun isUsable(frame: Frame): Boolean {
        return frame.speedKph.isFinite() && frame.throttle.isFinite() && frame.brake.isFinite()
                && frame.rpm.isFinite() && frame.rpm in 0f..MAX_RPM
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
            if (device != null) ensureDeviceContext(device)
            device
        } catch (e: InvocationTargetException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun ensureDeviceContext(device: Any) {
        try {
            var clazz: Class<*>? = device.javaClass
            while (clazz != null) {
                val field = runCatching { clazz.getDeclaredField("mContext") }.getOrNull()
                if (field != null) {
                    field.isAccessible = true
                    if (field.get(device) == null) field.set(device, bydContext)
                    return
                }
                clazz = clazz.superclass
            }
        } catch (_: Throwable) {}
    }

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
            System.err.println("DriveApex BYD getter $methodName threw: ${cause?.javaClass?.simpleName ?: "unknown"}: ${cause?.message ?: ""}")
        } catch (t: Throwable) {
            System.err.println("DriveApex BYD getter $methodName failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        return null
    }

    /** Read the same engine feature IDs used by the known-good Overdrive collector. */
    private fun readFeatureInt(device: Any?, featureId: Int): Int? {
        if (device == null) return null
        return runCatching {
            val method = findFeatureGetMethod(device.javaClass) ?: return@runCatching null
            val result = when {
                method.parameterTypes.size == 2 && method.parameterTypes[0] == IntArray::class.java ->
                    method.invoke(device, intArrayOf(featureId), Int::class.javaPrimitiveType)
                method.parameterTypes.size == 2 && method.parameterTypes[0] == Int::class.javaPrimitiveType ->
                    method.invoke(device, featureId, 0)
                else -> null
            }
            extractInt(result)
        }.getOrNull()?.takeUnless { isInvalidFeatureInt(it) }
    }

    private fun findFeatureGetMethod(clazz: Class<*>): Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            c.declaredMethods.firstOrNull {
                it.name == "get" && it.parameterTypes.size == 2 &&
                    (it.parameterTypes[0] == IntArray::class.java || it.parameterTypes[0] == Int::class.javaPrimitiveType)
            }?.let { return it }
            c = c.superclass
        }
        return clazz.methods.firstOrNull { it.name == "get" && it.parameterTypes.size == 2 }
    }

    private fun extractInt(value: Any?): Int? {
        if (value is Number) return value.toInt()
        if (value is IntArray && value.isNotEmpty()) return value[0]
        if (value == null) return null
        for (fieldName in arrayOf("intValue", "value")) {
            val field = findField(value.javaClass, fieldName) ?: continue
            runCatching { return field.get(value).toString().toDouble().toInt() }
        }
        for (methodName in arrayOf("getIntValue", "getValue")) {
            val method = findDeclaredNoArgMethod(value.javaClass, methodName) ?: continue
            runCatching {
                val v = method.invoke(value)
                if (v is Number) return v.toInt()
            }
        }
        return null
    }

    private fun findField(clazz: Class<*>, name: String): Field? {
        var c: Class<*>? = clazz
        while (c != null) {
            runCatching { c.getDeclaredField(name) }.getOrNull()?.let { it.isAccessible = true; return it }
            c = c.superclass
        }
        return null
    }

    private fun selectFeatureMotorRpm(front: Int?, rear: Int?): Double? {
        val candidates = mutableListOf<Double>()
        if (front != null && isPlausibleMotorRpm(front)) candidates += -front.toDouble()
        if (rear != null && isPlausibleMotorRpm(rear)) candidates += rear.toDouble()
        return candidates.firstOrNull { it >= 0.0 } ?: candidates.map { kotlin.math.abs(it) }.firstOrNull()
    }

    private fun sanitizeRpm(value: Double?): Double? {
        if (value == null || !value.isFinite()) return null
        if (value == 8191.0 || value == -8191.0 || value == 32767.0 || value == -32768.0 || value == 65535.0) return null
        return value.takeIf { it in 0.0..MAX_RPM }
    }

    private fun isPlausibleMotorRpm(value: Int): Boolean {
        if (isInvalidFeatureInt(value)) return false
        if (value == 8191 || value == -8191 || value == 32767 || value == -32768 || value == 65535) return false
        return kotlin.math.abs(value) <= MAX_RPM
    }

    private fun isInvalidFeatureInt(value: Int): Boolean =
        value == Int.MIN_VALUE || value == -10011 || value == -2147482645

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

    private class BydPermissionContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        private fun isBydPermission(permission: String?): Boolean = permission?.startsWith("android.permission.BYD") == true
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int = if (isBydPermission(permission)) PackageManager.PERMISSION_GRANTED else super.checkPermission(permission, pid, uid)
        override fun checkCallingPermission(permission: String): Int = if (isBydPermission(permission)) PackageManager.PERMISSION_GRANTED else super.checkCallingPermission(permission)
        override fun checkCallingOrSelfPermission(permission: String): Int = if (isBydPermission(permission)) PackageManager.PERMISSION_GRANTED else super.checkCallingOrSelfPermission(permission)
        override fun checkSelfPermission(permission: String): Int = if (isBydPermission(permission)) PackageManager.PERMISSION_GRANTED else super.checkSelfPermission(permission)
        override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) { if (!isBydPermission(permission)) super.enforcePermission(permission, pid, uid, message) }
        override fun enforceCallingPermission(permission: String, message: String?) { if (!isBydPermission(permission)) super.enforceCallingPermission(permission, message) }
        override fun enforceCallingOrSelfPermission(permission: String, message: String?) { if (!isBydPermission(permission)) super.enforceCallingOrSelfPermission(permission, message) }
    }

    companion object {
        private const val ENGINE_FRONT_MOTOR_SPEED = 1141899272
        private const val ENGINE_REAR_MOTOR_SPEED = 621805576
        private const val MAX_RPM = 25000f
    }
}
