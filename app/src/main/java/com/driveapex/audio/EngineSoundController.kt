package com.driveapex.audio

import com.driveapex.vehicle.VehicleData
import kotlin.math.ln

/** Converts conditioned vehicle telemetry into the complete audio-control frame. */
class EngineSoundController(private val engine: LayeredSoundEngine) {
    private val sceneDetector = AudioSceneDetector()
    private val smoother = TelemetrySmoother()
    private val eventComposer = AcousticEventComposer()
    @Volatile private var lastEvents = AcousticEventComposer.Events(0f, 0f, 0f, 0f, 0f, 0f)

    fun apply(data: VehicleData): AudioScene {
        val conditioned = smoother.filter(data)
        val rpm = conditioned.rpm.coerceIn(0f, 25000f)
        val throttle = conditioned.normalizedThrottle()
        val speedFactor = 0.82f + ln(1f + conditioned.normalizedSpeed()) / 7f
        val load = (0.16f + throttle * 0.82f +
            conditioned.normalizedBrake() * 0.35f +
            conditioned.normalizedRegen() * 0.55f) * speedFactor
        val scene = sceneDetector.detect(conditioned)
        lastEvents = eventComposer.evaluate(conditioned)

        engine.setRpm(rpm)
        engine.setLoad(load.coerceIn(0.10f, 1.5f))
        engine.setThrottle(throttle)
        engine.setSpeed(conditioned.speedKph)
        engine.setScene(scene)
        engine.setEvents(lastEvents)
        return scene
    }

    fun events(): AcousticEventComposer.Events = lastEvents

    fun resetSmoothing() {
        smoother.reset()
        lastEvents = AcousticEventComposer.Events(0f, 0f, 0f, 0f, 0f, 0f)
    }
}
