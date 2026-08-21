package com.driveapex.audio

import com.driveapex.vehicle.VehicleData
import kotlin.math.ln

/** Converts vehicle telemetry into audio-engine parameters. */
class EngineSoundController(private val engine: LayeredSoundEngine) {
    fun apply(data: VehicleData) {
        val rpm = data.rpm.coerceIn(700f, 7000f)
        val throttle = data.normalizedThrottle()
        val load = (0.18f + throttle * 0.82f) *
            (0.82f + ln(1f + data.normalizedSpeed()) / 7f)
        engine.setRpm(rpm)
        engine.setLoad(load.coerceIn(0.12f, 1.5f))
        engine.setSpeed(data.speedKph)
    }
}
