package com.driveapex.vehicle

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** Direct BYD HAL reader using the same context/permission pattern as Overdrive. */
class DirectBydTelemetryReader(context: Context) {
    companion object {
        private const val TAG = "DirectBydTelemetry"
        private const val SPEED_DEVICE = "android.hardware.bydauto.speed.BYDAutoSpeedDevice"
        private const val MOTOR_DEVICE = "android.hardware.bydauto.motor.BYDAutoMotorDevice"
        private const val ENGINE_DEVICE = "android.hardware.bydauto.engine.BYDAutoEngineDevice"
        private const val ENGINE_FRONT_MOTOR_SPEED = 1141899272
        private const val ENGINE_REAR_MOTOR_SPEED = 621805576
        private const val MAX_RPM = 25000.0
    }

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

    fun isAvailable(): Boolean = readOnce()?.let(::isUsable) == true
    fun latest(): Frame? = lastFrame
    fun start(onFrame: (Frame) -> Unit) { readOnce()?.let(onFrame) }

    fun readOnce(): Frame? {
        ensureInitialized()
        val speedKph = callGetter(speedDevice, "getCurrentSpeed").asDouble()
        val throttlePct = callGetter(speedDevice, "getAccelerateDeepness").asDouble()
        val brakePct = callGetter(speedDevice, "getBrakeDeepness").asDouble()

        val frontRaw = readFeatureInt(engineDevice, ENGINE_FRONT_MOTOR_SPEED)
        val rearRaw = readFeatureInt(engineDevice, ENGINE_REAR_MOTOR_SPEED)
        val rpm = selectFeatureMotorRpm(frontRaw, rearRaw)
            ?: sanitizeRpm(callGetter(motorDevice, "getMotorSpeed").asDouble())
            ?: sanitizeRpm(callGetter(engineDevice, "getEngineSpeed").asDouble())

        if (speedKph == null && throttlePct == null && brakePct == null && rpm == null) return null

        val frame = Frame(
            timestampMs = System.currentTimeMillis(),
            rpm = (rpm ?: 0.0).toFloat().coerceIn(0f, MAX_RPM.toFloat()),
            speedKph = (speedKph ?: 0.0).toFloat().coerceIn(0f, 400f),
            throttle = ((throttlePct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f),
            brake = ((brakePct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
        )
        lastFrame = frame
        return frame
    }

    private fun isUsable(frame: Frame): Boolean =
        frame.speedKph.isFinite() && frame.throttle.isFinite() && frame.brake.isFinite() && frame.rpm.isFinite() && frame.rpm in 0f..MAX_RPM.toFloat()

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            speedDevice = getDevice(SPEED_DEVICE)
            motorDevice = getDevice(MOTOR_DEVICE)
            engineDevice = getDevice(ENGINE_DEVICE)
            initialized = true
        }
    }

    private fun getDevice(className: String): Any? = try {
        val clazz = Class.forName(className)
        val getInstance = clazz.getMethod("getInstance", Context::class.java)
        val device = getInstance.invoke(null, bydContext)
        if (device != null) ensureDeviceContext(device)
        device
    } catch (_: InvocationTargetException) { null }
      catch (_: Throwable) { null }

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
        } catch (_: Throwable) { }
    }

    private fun callGetter(device: Any?, name: String): Any? {
        if (device == null) return null
        return try {
            val method = try { device.javaClass.getMethod(name) } catch (_: NoSuchMethodException) { findDeclaredNoArgMethod(device.javaClass, name) }
                ?: return null
            method.isAccessible = true
            method.invoke(device)
        } catch (e: InvocationTargetException) {
            Log.v(TAG, "BYD getter $name threw", e.cause)
            null
        } catch (t: Throwable) {
            Log.v(TAG, "BYD getter $name failed", t)
            null
        }
    }

    private fun readFeatureInt(device: Any?, featureId: Int): Int? {
        if (device == null) return null
        return runCatching {
            val method = findFeatureGetMethod(device.javaClass) ?: return@runCatching null
            val result = when {
                method.parameterTypes.size == 2 && method.parameterTypes[0] == IntArray::class.java -> method.invoke(device, intArrayOf(featureId), Int::class.javaPrimitiveType)
                method.parameterTypes.size == 2 && method.parameterTypes[0] == Int::class.javaPrimitiveType -> method.invoke(device, featureId, 0)
                else -> null
            }
            extractInt(result)
        }.getOrNull()?.takeUnless(::isInvalidFeatureInt)
    }

    private fun findFeatureGetMethod(clazz: Class<*>): Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            c.declaredMethods.firstOrNull { it.name == "get" && it.parameterTypes.size == 2 && (it.parameterTypes[0] == IntArray::class.java || it.parameterTypes[0] == Int::class.javaPrimitiveType) }?.let { return it }
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
            val fieldValue = runCatching { field.get(value) }.getOrNull() ?: continue
            if (fieldValue is Number) return fieldValue.toInt()
        }
        for (methodName in arrayOf("getIntValue", "getValue")) {
            val method = findDeclaredNoArgMethod(value.javaClass, methodName) ?: continue
            val v = runCatching { method.invoke(value) }.getOrNull()
            if (v is Number) return v.toInt()
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
        val values = listOfNotNull(front, rear).filter(::isPlausibleMotorRpm).map { kotlin.math.abs(it.toDouble()) }
        return values.firstOrNull { it <= MAX_RPM }
    }

    private fun sanitizeRpm(value: Double?): Double? {
        if (value == null || !value.isFinite()) return null
        if (value == 8191.0 || value == -8191.0 || value == 32767.0 || value == -32768.0 || value == 65535.0) return null
        return value.takeIf { it in 0.0..MAX_RPM }
    }

    private fun isPlausibleMotorRpm(value: Int): Boolean =
        !isInvalidFeatureInt(value) && value != 8191 && value != -8191 && value != 32767 && value != -32768 && value != 65535 && kotlin.math.abs(value.toDouble()) <= MAX_RPM

    private fun isInvalidFeatureInt(value: Int): Boolean = value == Int.MIN_VALUE || value == -10011 || value == -2147482645

    private fun findDeclaredNoArgMethod(clazz: Class<*>, name: String): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }?.let { return it }
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
        private fun isBydPermission(permission: String?): Boolean = permission?.startsWith("android.permission.BYD") == true
        override fun getApplicationContext(): Context = this
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int = if (isBydPermission(permission)) PackageManager.PERMISSION_GRANTED else super.checkPermission(permission, pid, uid)
        override fun checkCallingPermission(permission: String): Int = if (isBydPermission(permission)) PackageManager.PERMISSION_GRANTED else super.checkCallingPermission(permission)
        override fun checkCallingOrSelfPermission(permission: String): Int = if (isBydPermission(permission)) PackageManager.PERMISSION_GRANTED else super.checkCallingOrSelfPermission(permission)
        override fun checkSelfPermission(permission: String): Int = if (isBydPermission(permission)) PackageManager.PERMISSION_GRANTED else super.checkSelfPermission(permission)
        override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) { if (!isBydPermission(permission)) super.enforcePermission(permission, pid, uid, message) }
        override fun enforceCallingPermission(permission: String, message: String?) { if (!isBydPermission(permission)) super.enforceCallingPermission(permission, message) }
        override fun enforceCallingOrSelfPermission(permission: String, message: String?) { if (!isBydPermission(permission)) super.enforceCallingOrSelfPermission(permission, message) }
    }
}
