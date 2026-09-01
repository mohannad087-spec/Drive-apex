package com.driveapex.vehicle

import android.content.Context
import java.lang.reflect.Method

/**
 * Asks the vehicle which drive mode it is in.
 *
 * BYD exposes energy and setting devices alongside the engine one, and DiPlus's
 * bytecode calls setEnergyMode and setOperationMode on them -- so the concept
 * exists in this HAL. What is not known yet is which getter returns it on this
 * vehicle, or what its numbers mean.
 *
 * So this does not guess. It calls every plausible no-argument getter it can
 * find, reports each name with the value it returned, and leaves the mapping to
 * be made once a screenshot from the car shows those values changing as the
 * driver moves the switch. That is the same approach that settled the front
 * motor speed, and it is faster than shipping a guess and finding out.
 *
 * Read-only by construction: only zero-argument methods whose name starts with
 * `get` or `is` are called. No setter is invoked, and nothing is written to the
 * car.
 */
class DriveModeReader(context: Context) {

    private val appContext = context.applicationContext

    /** One candidate: which device, which method, and what it returned. */
    data class Reading(val device: String, val method: String, val value: String)

    private val devices = listOf(
        "android.hardware.bydauto.energy.BYDAutoEnergyDevice",
        "android.hardware.bydauto.setting.BYDAutoSettingDevice",
        "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice",
        "android.hardware.bydauto.speed.BYDAutoSpeedDevice"
    )

    /**
     * Every getter on those devices whose name suggests a mode, with its value.
     *
     * The filter is deliberately loose. Naming across this HAL is inconsistent
     * enough that insisting on one spelling is how the reader would come back
     * empty from a vehicle that had the value all along.
     */
    fun probe(): List<Reading> {
        val hits = mutableListOf<Reading>()
        for (className in devices) {
            val device = instanceOf(className) ?: continue
            val simple = className.substringAfterLast('.')
            for (method in gettersOf(device)) {
                val name = method.name
                if (!looksLikeMode(name)) continue
                val value = try {
                    method.isAccessible = true
                    describe(method.invoke(device))
                } catch (t: Throwable) {
                    "!" + (t.cause ?: t).javaClass.simpleName
                }
                hits += Reading(simple, name, value)
            }
        }
        return hits
    }

    private fun looksLikeMode(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("mode") || lower.contains("sport") || lower.contains("eco") ||
            lower.contains("drive") || lower.contains("gear") || lower.contains("energy")
    }

    private fun gettersOf(device: Any): List<Method> =
        buildList {
            var c: Class<*>? = device.javaClass
            while (c != null && c != Any::class.java) {
                for (m in c.declaredMethods) {
                    if (m.parameterTypes.isNotEmpty()) continue
                    if (!m.name.startsWith("get") && !m.name.startsWith("is")) continue
                    if (m.name == "getInstance") continue
                    add(m)
                }
                c = c.superclass
            }
        }

    private fun instanceOf(className: String): Any? = runCatching {
        val cls = Class.forName(className)
        val getInstance = cls.methods.firstOrNull {
            it.name == "getInstance" && it.parameterTypes.size == 1
        } ?: return null
        getInstance.invoke(null, appContext)
    }.getOrNull()

    private fun describe(value: Any?): String = when (value) {
        null -> "null"
        is Number, is Boolean -> value.toString()
        else -> value.toString().take(40)
    }
}
