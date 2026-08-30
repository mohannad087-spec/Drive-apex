package com.driveapex.vehicle

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
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
    private const val FALLBACK_FRONT_MOTOR_SPEED_ID = 1141899272
    private const val FALLBACK_REAR_MOTOR_SPEED_ID = 1141899274

    private data class ScanType(val clazz: Class<*>, val name: String)
    private data class ScanDevice(val name: String, val device: ReflectDevice)

    @JvmStatic
    fun main(args: Array<String>) {
        val context = createPackageContext()
        val speed = ReflectDevice("android.hardware.bydauto.speed.BYDAutoSpeedDevice", context)
        val motor = ReflectDevice("android.hardware.bydauto.motor.BYDAutoMotorDevice", context)
        val engine = ReflectDevice("android.hardware.bydauto.engine.BYDAutoEngineDevice", context)
        engine.registerFrontMotorSpeedListener()
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
                val eventRpm = engine.frontMotorSpeedEventRpm()
                val motorRpm = motor.readNumber("getMotorSpeed")
                val engineRpm = engine.readNumber("getEngineSpeed")
                val rpm = when {
                    eventRpm != null && eventRpm.isFinite() && eventRpm >= 0.0 -> eventRpm
                    motorRpm != null && motorRpm.isFinite() && motorRpm >= 0.0 -> motorRpm
                    engineRpm != null && engineRpm.isFinite() && engineRpm >= 0.0 -> engineRpm
                    else -> 0.0
                }
                writer.write("$timestamp,$rpm,${speedKph ?: 0.0},${throttlePct ?: 0.0},${brakePct ?: 0.0},BYD_ENGINE_FRONT_MOTOR_EVENT")
                writer.newLine(); writer.flush(); Thread.sleep(POLL_MS)
            }
        }
    }

    /** Read-only scanner. It now probes the exact BYD motor feature IDs used by DiPlus first. */
    private fun serveScan(socket: Socket, motor: ReflectDevice, engine: ReflectDevice) {
        socket.use {
            val writer = BufferedWriter(OutputStreamWriter(it.getOutputStream(), Charsets.UTF_8))
            writer.write("SCAN_READY\n"); writer.flush()
            val frontId = resolveFeatureId("ENGINE_FRONT_MOTOR_SPEED", FALLBACK_FRONT_MOTOR_SPEED_ID)
            val rearId = resolveFeatureId("ENGINE_REAR_MOTOR_SPEED", FALLBACK_REAR_MOTOR_SPEED_ID)
            writer.write("FEATURE,ENGINE_FRONT_MOTOR_SPEED,$frontId\n")
            writer.write("FEATURE,ENGINE_REAR_MOTOR_SPEED,$rearId\n")
            writer.write("EVENT_FRONT_MOTOR_SPEED,${engine.frontMotorSpeedEventRpm() ?: 0.0}\n")
            writer.flush()
            for ((name, device, id) in listOf(
                Triple("ENGINE_FRONT_MOTOR_SPEED", engine, frontId),
                Triple("ENGINE_REAR_MOTOR_SPEED", engine, rearId),
                Triple("MOTOR_GET_MOTOR_SPEED", motor, 0)
            )) {
                if (id != 0) {
                    val result = device.readFeatureVariants(id)
                    for ((typeName, value) in result) {
                        writer.write("HIT,$name,$id,$typeName,$value\n")
                        writer.flush()
                    }
                } else {
                    val value = motor.readNumber("getMotorSpeed")
                    if (value != null) writer.write("HIT,$name,GETTER,double,$value\n")
                }
            }
            writer.write("SCAN_DONE\n"); writer.flush()
        }
    }

    private fun resolveFeatureId(fieldName: String, fallback: Int): Int = runCatching {
        val clazz = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds")
        clazz.getField(fieldName).getInt(null)
    }.getOrDefault(fallback)

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
        @Volatile private var frontMotorEventRpm: Double? = null
        @Volatile private var frontMotorListenerRegistered = false

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

        fun registerFrontMotorSpeedListener(): Boolean = synchronized(this) {
            if (frontMotorListenerRegistered) return true
            val d = ensure() ?: return false
            runCatching {
                val listenerInterface = Class.forName("android.hardware.IBYDAutoListener")
                if (!listenerInterface.isInterface) return false
                val featureId = resolveFeatureId("ENGINE_FRONT_MOTOR_SPEED", FALLBACK_FRONT_MOTOR_SPEED_ID)
                val listener = Proxy.newProxyInstance(
                    listenerInterface.classLoader,
                    arrayOf(listenerInterface),
                    InvocationHandler { _, method, args ->
                        if (method.name == "onDataEventChanged" && args != null && args.size >= 2) {
                            val incomingId = (args[0] as? Number)?.toInt()
                            if (incomingId == featureId) {
                                val event = args[1]
                                frontMotorEventRpm = extractEventNumber(event)
                            }
                        }
                        when (method.returnType) {
                            java.lang.Boolean.TYPE -> false
                            java.lang.Integer.TYPE -> 0
                            java.lang.Long.TYPE -> 0L
                            java.lang.Float.TYPE -> 0f
                            java.lang.Double.TYPE -> 0.0
                            else -> null
                        }
                    }
                )
                val register = d.javaClass.methods.firstOrNull { m ->
                    m.name == "registerListener" &&
                        m.parameterTypes.size == 2 &&
                        m.parameterTypes[0] == listenerInterface &&
                        m.parameterTypes[1] == IntArray::class.java
                } ?: d.javaClass.methods.firstOrNull { m ->
                    m.name == "registerListener" &&
                        m.parameterTypes.size == 1 &&
                        m.parameterTypes[0] == listenerInterface
                } ?: return false
                register.isAccessible = true
                if (register.parameterTypes.size == 2) register.invoke(d, listener, intArrayOf(featureId))
                else register.invoke(d, listener)
                frontMotorListenerRegistered = true
                System.out.println("BYD front motor event listener registered feature=$featureId")
                true
            }.getOrElse {
                System.err.println("BYD front motor listener failed: ${it.message}")
                false
            }
        }

        fun frontMotorSpeedEventRpm(): Double? = frontMotorEventRpm

        fun readFeatureVariants(featureId: Int): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            for ((clazz, name) in listOf(
                Int::class.javaPrimitiveType!! to "int",
                Float::class.javaPrimitiveType!! to "float",
                Double::class.javaPrimitiveType!! to "double",
                Long::class.javaPrimitiveType!! to "long",
                Short::class.javaPrimitiveType!! to "short"
            )) {
                genericGet(featureId, clazz)?.let { out.add(name to it.toString()) }
            }
            return out
        }

        private fun genericGet(featureId: Int, type: Class<*>): Any? = runCatching {
            val d = ensure() ?: return null
            val method = d.javaClass.methods.firstOrNull { m ->
                m.name == "get" && m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == IntArray::class.java && m.parameterTypes[1] == Class::class.java
            } ?: return null
            val value = method.invoke(d, intArrayOf(featureId), type) ?: return null
            when (value) {
                is Number -> value
                is IntArray -> value.firstOrNull()
                is LongArray -> value.firstOrNull()
                is FloatArray -> value.firstOrNull()
                is DoubleArray -> value.firstOrNull()
                is ShortArray -> value.firstOrNull()
                else -> null
            }
        }.getOrNull()

        private fun extractEventNumber(event: Any?): Double? = runCatching {
            if (event == null) return null
            val clazz = event.javaClass
            val intValue = runCatching { clazz.getField("intValue").getInt(event).toDouble() }.getOrNull()
            if (intValue != null) return intValue
            val doubleValue = runCatching { clazz.getField("doubleValue").getDouble(event) }.getOrNull()
            if (doubleValue != null) return doubleValue
            val floatValue = runCatching { clazz.getField("floatValue").getFloat(event).toDouble() }.getOrNull()
            floatValue
        }.getOrNull()
    }
}
