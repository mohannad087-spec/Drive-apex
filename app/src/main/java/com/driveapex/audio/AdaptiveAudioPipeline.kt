package com.driveapex.audio

import com.driveapex.vehicle.VehicleData

/**
 * High-level audio decision pipeline. It converts raw telemetry into a scene,
 * adaptive layer weights, driver-specific Sound DNA and transient acoustic events.
 */
class AdaptiveAudioPipeline(
    private val layers: List<SoundLayer>,
    private val baseDna: SoundDna = SoundDnaPresets.balanced
) {
    private val model = AdaptiveSoundModel(layers)
    private val spatial = SpatialMixModel()
    private val detector = AudioSceneDetector()
    private val driverSignature = DriverSonicSignature()
    private val eventComposer = AcousticEventComposer()

    data class Frame(
        val scene: AudioScene,
        val layerWeights: Map<String, Float>,
        val stereo: StereoBusMix,
        val dna: SoundDna,
        val driverSignature: DriverSonicSignature.Signature,
        val events: AcousticEventComposer.Events
    )

    fun evaluate(data: VehicleData): Frame {
        val signature = driverSignature.update(data)
        val events = eventComposer.evaluate(data)
        val adaptiveDna = signature.toDna(baseDna)
        val weights = model.evaluate(data).mapValues { (_, value) ->
            shapeWeight(value, adaptiveDna)
        }
        val scene = detector.detect(data)
        val stereo = spatial.evaluate(
            speedKph = data.speedKph,
            throttle = data.normalizedThrottle(),
            cabinFocus = adaptiveDna.cabinFocus,
            aggressive = adaptiveDna.aggression
        )
        return Frame(
            scene = scene,
            layerWeights = weights,
            stereo = stereo,
            dna = adaptiveDna.sanitized(),
            driverSignature = signature,
            events = events
        )
    }

    private fun shapeWeight(value: Float, dna: SoundDna): Float {
        val d = dna.sanitized()
        val personality = (0.74f + d.aggression * 0.26f) *
            (0.80f + d.futuristic * 0.20f)
        return (value * personality).coerceIn(0f, 1f)
    }
}
