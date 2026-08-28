package com.driveapex.vehicle

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import java.lang.reflect.Method
import kotlin.math.abs

/**
 * Compatibility reader for BYD units where the installed Overdrive package already
 * has a proven, working BYD HAL collector. This does not fabricate values: it reads
 * the collector's current BydVehicleData snapshot through the package class loader.
 * The existing direct-HAL and shell-daemon paths remain available as fallbacks.
 */
class OverdriveTelemetryReader(context: Context) {
    companion object {
        private const val PACKAGE = "com.overdrive.app"
        private const val COLLECTOR = "com.overdrive.app.byd.BydDataCollector"
        private const val DATA = "com.overdrive.app.byd.BydVehicleData"
    }

    data class Frame(
        val timestampMs: Long,
        val rpm: Float,
        val speedKph: Float,
        val throttle: Float,
        val brake: Float,
        val source: String = "OVERDRIVE_BYD_COLLECTOR"
    )

    private val appContext = context.applicationContext ?: context
    private var overdriveContext: Context? = null
    private var collector: Any? = null
    private var getData: Method? = null
    @Volatile private var initialized = false
    @Volatile private var lastFrame: Frame? = null
    @Volatile private var lastError: String? = null

    fun isInstalled(): Boolean = runCatching {
        appContext.packageManager.getApplicationInfo(PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun isAvailable(): Boolean = readOnce() != null
    fun latest(): Frame? = lastFrame
    fun error(): String? = lastError

    fun readOnce(): Frame? {
        if (!ensureInitialized()) return null
        return runCatching {
            val data = getData?.invoke(collector) ?: return null
            val speed = number(data, "speedKmh") ?: number(data, "speedKph")
            val throttle = number(data, "accelPercent")
            val brake = number(data, "brakePercent")
            val front = number(data, "frontMotorSpeed")
            val rear = number(data, "rearMotorSpeed")
            val engine = number(data, "engineSpeedRpm")
            val rpm = listOfNotNull(front, rear, engine)
                .map { abs(it) }
                .firstOrNull { it.isFinite() && it in 0.0..25000.0 }

            if (speed == null && throttle == null && brake == null && rpm == null) {
                lastError = "Overdrive collector returned no drivetrain values"
                return null
            }

            val frame = Frame(
                timestampMs = System.currentTimeMillis(),
                rpm = (rpm ?: 0.0).toFloat().coerceIn(0f, 25000f),
                speedKph = (speed ?: 0.0).toFloat().coerceIn(0f, 400f),
                throttle = ((throttle ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f),
                brake = ((brake ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
            )
            lastFrame = frame
            lastError = null
            frame
        }.getOrElse {
            lastError = "Overdrive collector: ${rootMessage(it)}"
            null
        }
    }

    private fun ensureInitialized(): Boolean {
        if (initialized) return collector != null
        synchronized(this) {
            if (initialized) return collector != null
            initialized = true
            return runCatching {
                val base = appContext.createPackageContext(
                    PACKAGE,
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
                )
                val clazz = base.classLoader.loadClass(COLLECTOR)
                val instance = clazz.getMethod("getInstance").invoke(null)
                val init = clazz.getMethod("init", Context::class.java)
                init.invoke(instance, base)
                overdriveContext = base
                collector = instance
                getData = clazz.getMethod("getData")
                true
            }.getOrElse {
                lastError = "Overdrive collector init: ${rootMessage(it)}"
                false
            }
        }
    }

    private fun number(data: Any, field: String): Double? = runCatching {
        val f = findField(data.javaClass, field) ?: return null
        val value = f.get(data)
        when (value) {
            is Number -> value.toDouble().takeIf { it.isFinite() }
            else -> null
        }
    }.getOrNull()

    private fun findField(clazz: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            runCatching { current.getDeclaredField(name) }.getOrNull()?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        return null
    }

    private fun rootMessage(t: Throwable): String {
        var current = t
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current.message?.takeIf { it.isNotBlank() } ?: current.javaClass.simpleName
    }
}
