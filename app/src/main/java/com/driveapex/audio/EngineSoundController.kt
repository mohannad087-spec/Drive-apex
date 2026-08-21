package com.driveapex.audio

import com.driveapex.vehicle.VehicleData
import kotlin.math.ln

/** Converts vehicle state into audio-engine parameters. */
class EngineSoundController(private val engine: EngineSoundEngine) {
    fun apply(data: VehicleData) {
        val rpm = data.rpm.coerceIn(700f, 7000f)
        val throttle = data.normalizedThrottle()
        val load = (0.22f + throttle * 0.78f) * (0.88f + ln(1f + data.normalizedSpeed()) / 8f)
        engine.setRpm(rpm)
        engine.setLoad(load.coerceIn(0.18f, 1.35f))
    }
}
