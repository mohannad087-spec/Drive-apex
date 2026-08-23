package com.driveapex.vehicle

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Read-only adapter for the documented BYD speed HAL.
 * The proprietary framework is resolved at runtime, so it is not bundled.
 * No vehicle-control method is called.
 */
class BydHalTelemetryBridge(context: Context) : VehicleTelemetryBridge {
    private val appContext = context.applicationContext
    private val validator = VehicleTelemetryValidator(500L)
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var running = false
    @Volatile private var latestFrame: TelemetryFrame? = null
    @Volatile private var lastError: String? = null

    private var speedDevice: Any? = null
    private var getSpeed: java.lang.reflect.Method? = null
    private var getThrottle: java.lang.reflect.Method? = null
    private var getBrake: java.lang.reflect.Method? = null

    fun isAvailable(): Boolean {
        ensureApi()
        return speedDevice != null && getSpeed != null && getThrottle != null && getBrake != null
    }

    fun error(): String? = lastError
    fun latest(): TelemetryFrame? = latestFrame

    override fun start(onFrame: (TelemetryFrame) -> Unit) {
        if (running || !isAvailable()) return
        running = true
        executor.scheduleAtFixedRate({
            val frame = readFrame() ?: return@scheduleAtFixedRate
            latestFrame = frame
            onFrame(frame)
        }, 0L, 50L, TimeUnit.MILLISECONDS)
    }

    override fun stop() {
        running = false
        latestFrame = null
    }

    private fun ensureApi() {
        if (speedDevice != null) return
        runCatching {
            val clazz = Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice")
            speedDevice = clazz.getMethod("getInstance", Context::class.java)
                .invoke(null, appContext)
            getSpeed = clazz.getMethod("getCurrentSpeed")
            getThrottle = clazz.getMethod("getAccelerateDeepness")
            getBrake = clazz.getMethod("getBrakeDeepness")
            lastError = null
        }.onFailure {
            lastError = it.javaClass.simpleName + ": " + (it.message ?: "BYD speed API unavailable")
            speedDevice = null
        }
    }

    private fun readFrame(): TelemetryFrame? = runCatching {
        val device = speedDevice ?: return null
        val speed = (getSpeed?.invoke(device) as Number).toDouble().toFloat()
        val throttlePct = (getThrottle?.invoke(device) as Number).toFloat()
        val brakePct = (getBrake?.invoke(device) as Number).toFloat()
        val now = System.currentTimeMillis()
        val frame = TelemetryFrame(
            timestampMs = now,
            rpm = deriveAcousticRpm(speed, throttlePct),
            speedKph = speed,
            throttle = (throttlePct / 100f).coerceIn(0f, 1f),
            brake = (brakePct / 100f).coerceIn(0f, 1f),
            regen = 0f,
            source = "BYD_HAL_SPEED"
        )
        if (validator.validate(frame, now) is VehicleTelemetryValidator.Result.Valid) frame else null
    }.onFailure {
        lastError = it.javaClass.simpleName + ": " + (it.message ?: "read failed")
    }.getOrNull()

    /** Audio-control parameter only; not claimed OEM motor RPM. */
    private fun deriveAcousticRpm(speedKph: Float, throttlePct: Float): Float {
        val speedComponent = speedKph.coerceIn(0f, 220f) / 220f
        val loadComponent = throttlePct.coerceIn(0f, 100f) / 100f
        return (700f + speedComponent * 4300f + loadComponent * 1900f).coerceIn(700f, 6900f)
    }
}
