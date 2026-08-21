package com.driveapex.audio

import com.driveapex.vehicle.VehicleData
import kotlin.math.ln

/** Converts vehicle telemetry into audio-engine parameters and scene state. */
class EngineSoundController(private val engine: LayeredSoundEngine) {
    private val sceneDetector = AudioSceneDetector()

    fun apply(data: VehicleData): AudioScene {
        val rpm = data.rpm.coerceIn(700f, 7000f)
        val throttle = data.normalizedThrottle()
        val speedFactor = 0.82f + ln(1f + data.normalizedSpeed()) / 7f
        val load = (0.16f + throttle * 0.82f +
            data.normalizedBrake() * 0.35f +
            data.normalizedRegen() * 0.55f) * speedFactor
        val scene = sceneDetector.detect(data)

        engine.setRpm(rpm)
        engine.setLoad(load.coerceIn(0.10f, 1.5f))
        engine.setSpeed(data.speedKph)
        engine.setScene(scene)
        return scene
    }
}
