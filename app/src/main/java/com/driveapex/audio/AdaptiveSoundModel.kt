package com.driveapex.audio

import com.driveapex.vehicle.VehicleData
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min

/**
 * Produces smooth layer weights. The model intentionally avoids hard state
 * switches so sound remains continuous during rapid pedal and speed changes.
 */
class AdaptiveSoundModel(private val layers: List<SoundLayer>) {
    private val detector = AudioSceneDetector()

    fun evaluate(data: VehicleData): Map<String, Float> {
        val scene = detector.detect(data)
        val rpm = data.rpm
        val load = data.normalizedThrottle()

        return layers.associate { layer ->
            val rpmWeight = rangeWeight(rpm, layer.minRpm, layer.maxRpm)
            val loadWeight = rangeWeight(load, layer.minLoad, layer.maxLoad)
            val sceneWeight = layer.sceneBias[scene] ?: 1f
            val edge = 0.5f - 0.5f * cos((rpmWeight * Math.PI).toFloat())
            layer.id to (edge * loadWeight * sceneWeight * layer.gain).coerceIn(0f, 1f)
        }
    }

    private fun rangeWeight(value: Float, min: Float, max: Float): Float {
        if (max <= min) return 0f
        val margin = (max - min) * 0.12f
        if (value < min - margin || value > max + margin) return 0f
        return when {
            value < min -> (value - (min - margin)) / margin
            value > max -> 1f - (value - max) / margin
            else -> 1f
        }.coerceIn(0f, 1f)
    }
}
