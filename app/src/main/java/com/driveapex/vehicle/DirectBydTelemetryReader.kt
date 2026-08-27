package com.driveapex.vehicle

import android.content.Context
import java.lang.reflect.Method
import java.util.Locale

/**
 * Direct in-process reader for the vendor BYD HAL classes.
 *
 * The classes are intentionally resolved by reflection so the APK does not
 * need to package BYD's private framework. On compatible head units these
 * classes are exposed by the ROM and use getInstance(Context).
 */
class DirectBydTelemetryReader(private val context: Context) {
    data class Frame(
        val timestampMs: Long,
        val rpm: Float,
        val speedKph: Float,
        val throttle: Float,
        val brake: Float,
        val source: String = "BYD_HAL_DIRECT"
    )

    private data class Device(val name: String, val instance: Any)

    private var speed: Device? = null
    private var motor: Device? = null
    private var engine: Device? = null

    @Volatile private var ready = false
    @Volatile private var lastFrame: Frame? = null

    fun isAvailable(): Boolean {
        if (ready) return true
        initialize()
        return ready
    }

    fun latest(): Frame? = lastFrame

    fun start(onFrame: (Frame) -> Unit) {
        initialize()
        if (!ready) return
        onFrame(readOnce() ?: return)
    }

    fun readOnce(): Frame? {
        if (!ready) initialize()
        val speedDevice = speed?.instance
        val motorDevice = motor?.instance
        val engineDevice = engine?.instance

        val speedKph = speedDevice?.invokeNumber("getCurrentSpeed")
        val throttlePct = speedDevice?.invokeNumber("getAccelerateDeepness")
        val brakePct = speedDevice?.invokeNumber("getBrakeDeepness")
        val motorRpm = motorDevice?.invokeNumber("getMotorSpeed")
        val engineRpm = engineDevice?.invokeNumber("getEngineSpeed")

        val hasAny = speedKph != null || throttlePct != null || brakePct != null || motorRpm != null || engineRpm != null
        if (!hasAny) return null

        val frame = Frame(
            timestampMs = System.currentTimeMillis(),
            rpm = firstNonNegative(motorRpm, engineRpm, 0.0).toFloat(),
            speedKph = finiteOrZero(speedKph).coerceIn(0.0, 300.0).toFloat(),
            throttle = (finiteOrZero(throttlePct) / 100.0).coerceIn(0.0, 1.0).toFloat(),
            brake = (finiteOrZero(brakePct) / 100.0).coerceIn(0.0, 1.0).toFloat()
        )
        lastFrame = frame
        return frame
    }

    private fun initialize() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            speed = createDevice("android.hardware.bydauto.speed.BYDAutoSpeedDevice")
            motor = createDevice("android.hardware.bydauto.motor.BYDAutoMotorDevice")
            engine = createDevice("android.hardware.bydauto.engine.BYDAutoEngineDevice")
            ready = speed != null || motor != null || engine != null
        }
    }

    private fun createDevice(className: String): Device? = runCatching {
        val clazz = Class.forName(className)
        val factory = clazz.getDeclaredMethod("getInstance", Context::class.java).apply { isAccessible = true }
        val instance = factory.invoke(null, context)
        if (instance == null) null else Device(className, instance)
    }.getOrElse {
        // Some firmware revisions expose a static singleton/factory under a
        // different name. Try common no-arg/static factories as a fallback.
        runCatching {
            val clazz = Class.forName(className)
            val names = arrayOf("getInstance", "getSingleton", "getDevice", "get")
            for (name in names) {
                for (method in clazz.declaredMethods) {
                    if (method.name != name || !java.lang.reflect.Modifier.isStatic(method.modifiers)) continue
                    val params = method.parameterTypes
                    method.isAccessible = true
                    val value = when {
                        params.isEmpty() -> method.invoke(null)
                        params.size == 1 && params[0].isAssignableFrom(context.javaClass) -> method.invoke(null, context)
                        params.size == 1 && params[0] == Context::class.java -> method.invoke(null, context)
                        else -> null
                    }
                    if (value != null && clazz.isInstance(value)) return@runCatching Device(className, value)
                }
            }
            null
        }.getOrNull()
    }

    private fun Any.invokeNumber(name: String): Double? = runCatching {
        val method = findNoArgMethod(javaClass, name) ?: return@runCatching null
        method.isAccessible = true
        when (val value = method.invoke(this)) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }.getOrNull()

    private fun findNoArgMethod(clazz: Class<*>, name: String): Method? {
        for (c in generateSequence(clazz) { it.superclass }) {
            c.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }?.let { return it }
        }
        return clazz.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
    }

    private fun finiteOrZero(value: Double?): Double = value?.takeIf { it.isFinite() } ?: 0.0

    private fun firstNonNegative(first: Double?, second: Double?, fallback: Double): Double {
        return sequenceOf(first, second).filter { it != null && it.isFinite() && it >= 0.0 }.firstOrNull() ?: fallback
    }
}
