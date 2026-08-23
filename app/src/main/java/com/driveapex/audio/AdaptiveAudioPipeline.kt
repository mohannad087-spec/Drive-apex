package com.driveapex.audio

import com.driveapex.vehicle.VehicleData

/**
 * High-level audio decision pipeline. It converts raw telemetry into a scene,
 * adaptive layer weights, persistent driver Sonic Genome influence and transient events.
 */
class AdaptiveAudioPipeline(
    private val layers: List<SoundLayer>,
    private val baseDna: SoundDna = SoundDnaPresets.balanced,
    initialGenome: SonicGenome = SonicGenome()
) {
    private val model = AdaptiveSoundModel(layers)
    private val spatial = SpatialMixModel()
    private val detector = AudioSceneDetector()
    private val driverSignature = DriverSonicSignature()
    private val eventComposer = AcousticEventComposer()
    private var genome = initialGenome

    data class Frame(
        val scene: AudioScene,
        val layerWeights: Map<String, Float>,
        val stereo: StereoBusMix,
        val dna: SoundDna,
        val driverSignature: DriverSonicSignature.Signature,
        val genome: SonicGenome,
        val events: AcousticEventComposer.Events
    )

    fun evaluate(data: VehicleData): Frame {
        val signature = driverSignature.update(data)
        genome = genome.blend(signature, if (genome.maturity < 0.1f) 0.06f else 0.02f)
        val persistentSignature = genome.toSignature()
        val events = eventComposer.evaluate(data)
        val adaptiveDna = persistentSignature.toDna(baseDna)
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
            driverSignature = persistentSignature,
            genome = genome,
            events = events
        )
    }

    fun genome(): SonicGenome = genome

    fun replaceGenome(value: SonicGenome) {
        genome = value
    }

    private fun shapeWeight(value: Float, dna: SoundDna): Float {
        val d = dna.sanitized()
        val personality = (0.74f + d.aggression * 0.26f) *
            (0.80f + d.futuristic * 0.20f)
        return (value * personality).coerceIn(0f, 1f)
    }
}
