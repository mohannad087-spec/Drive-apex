package com.driveapex.vehicle

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** BYD telemetry daemon launched through ADB/app_process (UID 2000). */
object BydTelemetryDaemon {
    private const val PACKAGE_NAME = "com.driveapex"
    private const val HOST = "127.0.0.1"
    private const val PORT = 18765
    private const val SCAN_PORT = 18766
    private const val POLL_MS = 50L

    @JvmStatic
    fun main(args: Array<String>) {
        val context = createPackageContext()
        val speed = ReflectDevice("android.hardware.bydauto.speed.BYDAutoSpeedDevice", context)
        val motor = ReflectDevice("android.hardware.bydauto.motor.BYDAutoMotorDevice", context)
        val engine = ReflectDevice("android.hardware.bydauto.engine.BYDAutoEngineDevice", context)
        val server = ServerSocket(PORT, 4, InetAddress.getByName(HOST))
        val scanServer = ServerSocket(SCAN_PORT, 2, InetAddress.getByName(HOST))
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { server.close() }
            runCatching { scanServer.close() }
        })
        Thread {
            while (!scanServer.isClosed) {
                val client = runCatching { scanServer.accept() }.getOrNull() ?: continue
                Thread { serveScan(client, motor, engine) }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: continue
            Thread { serveClient(client, speed, motor, engine) }.apply { isDaemon = true; start() }
        }
    }

    private fun serveClient(socket: Socket, speed: ReflectDevice, motor: ReflectDevice, engine: ReflectDevice) {
        socket.use {
            val writer = BufferedWriter(OutputStreamWriter(it.getOutputStream(), Charsets.UTF_8))
            while (!it.isClosed) {
                val timestamp = System.currentTimeMillis()
                val speedKph = speed.readNumber("getCurrentSpeed")
                val throttlePct = speed.readNumber("getAccelerateDeepness")
                val brakePct = speed.readNumber("getBrakeDeepness")
                val motorRpm = motor.readNumber("getMotorSpeed")
                val engineRpm = engine.readNumber("getEngineSpeed")
                val rpm = when {
                    motorRpm != null && motorRpm.isFinite() && motorRpm >= 0.0 -> motorRpm
                    engineRpm != null && engineRpm.isFinite() && engineRpm >= 0.0 -> engineRpm
                    else -> 0.0
                }
                writer.write("$timestamp,$rpm,${speedKph ?: 0.0},${throttlePct ?: 0.0},${brakePct ?: 0.0},BYD_DAEMON")
                writer.newLine(); writer.flush(); Thread.sleep(POLL_MS)
            }
        }
    }

    /** Read-only generic HAL scanner based on OverDrive's get(int[], Class) pattern. */
    private fun serveScan(socket: Socket, motor: ReflectDevice, engine: ReflectDevice) {
        socket.use {
            val writer = BufferedWriter(OutputStreamWriter(it.getOutputStream(), Charsets.UTF_8))
            writer.write("SCAN_READY\n"); writer.flush()
            val candidates = (4080..4120)
            val types = listOf(
                Integer.TYPE to "int", Float.TYPE to "float", Double.TYPE to "double",
                Long.TYPE to "long", Short.TYPE to "short"
            )
            for ((name, device) in listOf("MOTOR" to motor, "ENGINE" to engine)) {
                for (id in candidates) for ((type, typeName) in types) {
                    device.genericGet(id, type)?.let { result ->
                        writer.write("HIT,$name,$id,$typeName,${result.second}\n"); writer.flush()
                    }
                }
            }
            writer.write("SCAN_DONE\n"); writer.flush()
        }
    }

    private fun createPackageContext(): Context {
        val c = Class.forName("android.app.ActivityThread")
        val current = c.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }
        var thread = runCatching { current.invoke(null) }.getOrNull()
        if (thread == null) thread = c.getDeclaredMethod("systemMain").apply { isAccessible = true }.invoke(null)
        val scm = c.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
        val systemContext = scm.invoke(thread) as Context
        val packageContext = systemContext.createPackageContext(PACKAGE_NAME, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        return BydPermissionContext(packageContext)
    }

    private class BydPermissionContext(base: Context) : ContextWrapper(base) {
        override fun checkCallingOrSelfPermission(permission: String): Int = PackageManager.PERMISSION_GRANTED
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int = PackageManager.PERMISSION_GRANTED
        override fun checkSelfPermission(permission: String): Int = PackageManager.PERMISSION_GRANTED
    }

    private class ReflectDevice(private val className: String, private val context: Context) {
        @Volatile private var device: Any? = null
        private fun ensure(): Any? {
            device?.let { return it }
            return runCatching {
                val clazz = Class.forName(className)
                val created = clazz.getMethod("getInstance", Context::class.java).invoke(null, context)
                device = created; created
            }.getOrNull()
        }
        fun readNumber(methodName: String): Double? = runCatching {
            val d = ensure() ?: return null
            (d.javaClass.getMethod(methodName).invoke(d) as Number).toDouble()
        }.getOrNull()
        fun genericGet(featureId: Int, type: Class<*>): Pair<Any, String>? = runCatching {
            val d = ensure() ?: return null
            val method = d.javaClass.methods.firstOrNull { m ->
                m.name == "get" && m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == IntArray::class.java && m.parameterTypes[1] == Class::class.java
            } ?: return null
            val value = method.invoke(d, intArrayOf(featureId), type) ?: return null
            val scalar: Any = when (value) {
                is Number -> value
                is IntArray -> value.firstOrNull() ?: return null
                is LongArray -> value.firstOrNull() ?: return null
                is FloatArray -> value.firstOrNull() ?: return null
                is DoubleArray -> value.firstOrNull() ?: return null
                is ShortArray -> value.firstOrNull() ?: return null
                else -> return null
            }
            scalar to scalar.toString()
        }.getOrNull()
    }
}
