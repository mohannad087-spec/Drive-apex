package com.driveapex.vehicle

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Field
import kotlin.math.abs

/** Direct BYD HAL reader. Motor Speed is ONLY the verified Front Motor Speed feature. */
class DirectBydTelemetryReader(context: Context) {
    companion object {
        private const val TAG = "DirectBydTelemetry"
        private const val SPEED_DEVICE = "android.hardware.bydauto.speed.BYDAutoSpeedDevice"
        private const val ENGINE_DEVICE = "android.hardware.bydauto.engine.BYDAutoEngineDevice"
        // Verified from the working Overdrive/DiPlus collector path.
        private const val ENGINE_FRONT_MOTOR_SPEED = 1141899272
        private const val MAX_MOTOR_SPEED = 25_000
    }

    data class Frame(
        val timestampMs: Long,
        val motorSpeed: Float,
        val speedKph: Float,
        val throttle: Float,
        val brake: Float,
        val source: String = "BYD_FRONT_MOTOR_SPEED"
    )

    private val baseContext = context.applicationContext ?: context
    private val bydContext: Context = BydPermissionContext(baseContext)
    private var speedDevice: Any? = null
    private var engineDevice: Any? = null
    @Volatile private var initialized = false
    @Volatile private var lastFrame: Frame? = null

    fun latest(): Frame? = lastFrame
    fun isAvailable(): Boolean = readOnce()?.let(::isUsable) == true
    fun start(onFrame: (Frame) -> Unit) { readOnce()?.let(onFrame) }

    fun readOnce(): Frame? {
        ensureInitialized()
        val speedKph = callGetter(speedDevice, "getCurrentSpeed").asDouble()
        val throttlePct = callGetter(speedDevice, "getAccelerateDeepness").asDouble()
        val brakePct = callGetter(speedDevice, "getBrakeDeepness").asDouble()
        val frontRaw = readFrontMotorSpeed()
        val motorSpeed = frontRaw?.let { abs(it).toFloat() }

        Log.d(TAG, "FRONT_MOTOR_SPEED feature=$ENGINE_FRONT_MOTOR_SPEED raw=$frontRaw value=$motorSpeed speed=$speedKph throttle=$throttlePct brake=$brakePct")

        if (speedKph == null && throttlePct == null && brakePct == null && motorSpeed == null) return null
        val frame = Frame(
            timestampMs = System.currentTimeMillis(),
            motorSpeed = (motorSpeed ?: 0f).coerceIn(0f, MAX_MOTOR_SPEED.toFloat()),
            speedKph = (speedKph ?: 0.0).toFloat().coerceIn(0f, 400f),
            throttle = ((throttlePct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f),
            brake = ((brakePct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
        )
        lastFrame = frame
        return frame
    }

    /**
     * Read the exact Front Motor Speed feature used by the reference collector.
     * BYD's generic get() can return a BYDAutoEventValue wrapper rather than Number;
     * the previous implementation discarded that wrapper and therefore produced 0.
     */
    private fun readFrontMotorSpeed(): Int? {
        val device = engineDevice ?: return null
        val primitiveTypes = arrayOf(
            Int::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!,
            Double::class.javaPrimitiveType!!,
            Short::class.javaPrimitiveType!!
        )
        for (type in primitiveTypes) {
            val raw = runCatching {
                val method = findGetArrayClassMethod(device.javaClass) ?: return@runCatching null
                method.isAccessible = true
                val result = method.invoke(device, intArrayOf(ENGINE_FRONT_MOTOR_SPEED), type)
                extractNumeric(result, 0)
            }.getOrNull()
            if (raw != null && isPlausibleRaw(raw)) return raw.toInt()
        }
        return null
    }

    /** Unwrap Number, primitive arrays, and BYD event-value wrappers without inventing data. */
    private fun extractNumeric(value: Any?, depth: Int): Double? {
        if (value == null || depth > 4) return null
        if (value is Number) return value.toDouble().takeIf { it.isFinite() }
        val cls = value.javaClass
        if (cls.isArray) {
            if (java.lang.reflect.Array.getLength(value) == 0) return null
            return extractNumeric(java.lang.reflect.Array.get(value, 0), depth + 1)
        }

        val methodNames = arrayOf("getValue", "getIntValue", "getLongValue", "getFloatValue", "getDoubleValue", "getData")
        for (name in methodNames) {
            val method = findNoArgMethod(cls, name) ?: continue
            val nested = runCatching {
                method.isAccessible = true
                method.invoke(value)
            }.getOrNull() ?: continue
            extractNumeric(nested, depth + 1)?.let { return it }
        }

        val fieldNames = arrayOf("value", "mValue", "intValue", "mIntValue", "data", "mData")
        for (name in fieldNames) {
            val field = findField(cls, name) ?: continue
            val nested = runCatching {
                field.isAccessible = true
                field.get(value)
            }.getOrNull() ?: continue
            extractNumeric(nested, depth + 1)?.let { return it }
        }
        return null
    }

    private fun findGetArrayClassMethod(clazz: Class<*>): Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            c.declaredMethods.firstOrNull {
                it.name == "get" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == IntArray::class.java && it.parameterTypes[1] == Class::class.java
            }?.let { return it }
            c = c.superclass
        }
        return clazz.methods.firstOrNull {
            it.name == "get" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == IntArray::class.java && it.parameterTypes[1] == Class::class.java
        }
    }

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            speedDevice = getDevice(SPEED_DEVICE)
            engineDevice = getDevice(ENGINE_DEVICE)
            Log.d(TAG, "devices speed=${speedDevice != null} engine=${engineDevice != null}")
            initialized = true
        }
    }

    private fun getDevice(className: String): Any? = try {
        val clazz = Class.forName(className)
        val getInstance = clazz.getMethod("getInstance", Context::class.java)
        getInstance.invoke(null, bydContext)
    } catch (e: InvocationTargetException) {
        Log.w(TAG, "device $className failed: ${rootMessage(e)}")
        null
    } catch (t: Throwable) {
        Log.w(TAG, "device $className failed: ${rootMessage(t)}")
        null
    }

    private fun callGetter(device: Any?, name: String): Any? {
        if (device == null) return null
        return runCatching {
            val method = try { device.javaClass.getMethod(name) } catch (_: NoSuchMethodException) { findDeclaredNoArgMethod(device.javaClass, name) }
                ?: return@runCatching null
            method.isAccessible = true
            method.invoke(device)
        }.getOrNull()
    }

    private fun isPlausibleRaw(value: Double): Boolean =
        value.isFinite() && abs(value) <= MAX_MOTOR_SPEED &&
            value != -10011.0 && value != -2147482645.0 && value != 8191.0 &&
            value != -8191.0 && value != 32767.0 && value != -32768.0 &&
            value != 65535.0

    private fun findNoArgMethod(clazz: Class<*>, name: String): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }?.let { return it }
            current = current.superclass
        }
        return runCatching { clazz.getMethod(name) }.getOrNull()
    }

    private fun findDeclaredNoArgMethod(clazz: Class<*>, name: String): Method? = findNoArgMethod(clazz, name)

    private fun findField(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            runCatching { current.getDeclaredField(name) }.getOrNull()?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun isUsable(frame: Frame): Boolean =
        frame.speedKph.isFinite() && frame.throttle.isFinite() && frame.brake.isFinite() && frame.motorSpeed.isFinite() &&
            frame.speedKph in 0f..400f && frame.throttle in 0f..1f && frame.brake in 0f..1f && frame.motorSpeed in 0f..MAX_MOTOR_SPEED.toFloat()

    private fun Any?.asDouble(): Double? = when (this) {
        is Number -> toDouble().takeIf { it.isFinite() }
        is String -> toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }

    private fun rootMessage(t: Throwable): String {
        var current = t
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current.message?.takeIf { it.isNotBlank() } ?: current.javaClass.simpleName
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
