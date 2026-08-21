package com.driveapex.audio

import com.driveapex.vehicle.VehicleData

/**
 * High-level audio decision pipeline. It converts raw telemetry into a scene,
 * adaptive layer weights, Sound DNA influence and stereo bus gains.
 */
class AdaptiveAudioPipeline(
    private val layers: List<SoundLayer>,
    private val dna: SoundDna = SoundDnaPresets.balanced
) {
    private val model = AdaptiveSoundModel(layers)
    private val spatial = SpatialMixModel()

    data class Frame(
        val scene: AudioScene,
        val layerWeights: Map<String, Float>,
        val stereo: StereoBusMix,
        val dna: SoundDna
    )

    fun evaluate(data: VehicleData): Frame {
        val weights = model.evaluate(data).mapValues { (_, value) ->
            shapeWeight(value, dna)
        }
        val scene = AudioSceneDetector().detect(data)
        val stereo = spatial.evaluate(
            speedKph = data.speedKph,
            throttle = data.normalizedThrottle(),
            cabinFocus = dna.cabinFocus,
            aggressive = dna.aggression
        )
        return Frame(scene, weights, stereo, dna.sanitized())
    }

    private fun shapeWeight(value: Float, dna: SoundDna): Float {
        val d = dna.sanitized()
        val personality = (0.74f + d.aggression * 0.26f) *
            (0.80f + d.futuristic * 0.20f)
        return (value * personality).coerceIn(0f, 1f)
    }
}
