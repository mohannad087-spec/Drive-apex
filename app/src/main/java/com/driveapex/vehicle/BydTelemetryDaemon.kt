package com.driveapex.vehicle

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * BYD telemetry daemon launched through ADB/app_process (UID 2000).
 *
 * The normal app process is intentionally not used to access BYD HAL. The daemon
 * runs with shell UID, creates the app package context from the system context,
 * wraps it with the same permission-bypass pattern used by OverDrive, and keeps
 * the HAL device handles alive in one polling loop.
 *
 * Protocol: one CSV line per sample
 *   timestampMs,rpm,speedKph,throttlePercent,brakePercent,source
 */
object BydTelemetryDaemon {
    private const val PACKAGE_NAME = "com.driveapex"
    private const val HOST = "127.0.0.1"
    private const val PORT = 18765
    private const val POLL_MS = 50L

    @JvmStatic
    fun main(args: Array<String>) {
        val context = createPackageContext()
        val speed = ReflectDevice("android.hardware.bydauto.speed.BYDAutoSpeedDevice", context)
        val motor = ReflectDevice("android.hardware.bydauto.motor.BYDAutoMotorDevice", context)
        val engine = ReflectDevice("android.hardware.bydauto.engine.BYDAutoEngineDevice", context)

        val server = ServerSocket(PORT, 4, InetAddress.getByName(HOST))
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { server.close() } })

        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: continue
            Thread { serveClient(client, speed, motor, engine) }.apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun serveClient(
        socket: Socket,
        speed: ReflectDevice,
        motor: ReflectDevice,
        engine: ReflectDevice,
    ) {
        socket.use {
            val writer = BufferedWriter(OutputStreamWriter(it.getOutputStream(), Charsets.UTF_8))
            while (!it.isClosed) {
                val timestamp = System.currentTimeMillis()
                val speedKph = speed.readNumber("getCurrentSpeed")
                val throttlePct = speed.readNumber("getAccelerateDeepness")
                val brakePct = speed.readNumber("getBrakeDeepness")

                // Front/motor speed is the primary RPM source when available.
                val motorRpm = motor.readNumber("getMotorSpeed")
                val engineRpm = engine.readNumber("getEngineSpeed")
                val rpm = when {
                    motorRpm != null && motorRpm.isFinite() && motorRpm >= 0.0 -> motorRpm
                    engineRpm != null && engineRpm.isFinite() && engineRpm >= 0.0 -> engineRpm
                    else -> 0.0
                }

                writer.write(
                    "$timestamp,$rpm,${speedKph ?: 0.0},${throttlePct ?: 0.0},${brakePct ?: 0.0},BYD_DAEMON"
                )
                writer.newLine()
                writer.flush()
                Thread.sleep(POLL_MS)
            }
        }
    }

    private fun createPackageContext(): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentMethod = activityThreadClass.getDeclaredMethod("currentActivityThread")
        currentMethod.isAccessible = true
        var thread = runCatching { currentMethod.invoke(null) }.getOrNull()
        if (thread == null) {
            val systemMain = activityThreadClass.getDeclaredMethod("systemMain")
            systemMain.isAccessible = true
            thread = systemMain.invoke(null)
        }
        val systemContextMethod = activityThreadClass.getDeclaredMethod("getSystemContext")
        systemContextMethod.isAccessible = true
        val systemContext = systemContextMethod.invoke(thread) as Context
        val packageContext = systemContext.createPackageContext(
            PACKAGE_NAME,
            Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
        )
        return BydPermissionContext(packageContext)
    }

    private class BydPermissionContext(base: Context) : ContextWrapper(base) {
        override fun checkCallingOrSelfPermission(permission: String): Int = PackageManager.PERMISSION_GRANTED
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int = PackageManager.PERMISSION_GRANTED
        override fun checkSelfPermission(permission: String): Int = PackageManager.PERMISSION_GRANTED
    }

    private class ReflectDevice(
        private val className: String,
        private val context: Context,
    ) {
        @Volatile private var device: Any? = null

        private fun ensure(): Any? {
            device?.let { return it }
            return runCatching {
                val clazz = Class.forName(className)
                val getInstance = clazz.getMethod("getInstance", Context::class.java)
                val created = getInstance.invoke(null, context)
                device = created
                created
            }.getOrNull()
        }

        fun readNumber(methodName: String): Double? = runCatching {
            val d = ensure() ?: return null
            val method = d.javaClass.getMethod(methodName)
            (method.invoke(d) as Number).toDouble()
        }.getOrNull()
    }
}
