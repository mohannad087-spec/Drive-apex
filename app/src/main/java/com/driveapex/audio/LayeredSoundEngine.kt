package com.driveapex.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Real-time layered EV sound renderer.
 * Procedural synthesis remains the fallback while the sample-bank renderer is being integrated.
 *
 * The procedural fallback prioritizes a smooth, premium EV-GT body over an obviously synthetic
 * high-frequency whistle. High-frequency inverter content is kept as a subtle garnish.
 */
class LayeredSoundEngine(
    private var layers: List<SoundLayer> = ETronInspiredSoundProfile.layers
) {
    private val sampleRate = 44_100
    private val bufferSize = 1_536
    private var track: AudioTrack? = null

    @Volatile private var running = false
    @Volatile private var rpm = 900f
    @Volatile private var load = 0.25f
    @Volatile private var speedKph = 0f
    @Volatile private var scene = AudioScene.IDLE
    @Volatile private var events = AcousticEventComposer.Events(0f, 0f, 0f, 0f, 0f, 0f)

    fun setLayers(value: List<SoundLayer>) { layers = value }
    fun setRpm(value: Float) { rpm = value.coerceIn(700f, 7_000f) }
    fun setLoad(value: Float) { load = value.coerceIn(0f, 1.5f) }
    fun setSpeed(value: Float) { speedKph = value.coerceAtLeast(0f) }
    fun setScene(value: AudioScene) { scene = value }
    fun setEvents(value: AcousticEventComposer.Events) { events = value }

    fun start() {
        if (running) return
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
        running = true
        Thread(::renderLoop, "DriveApex-LayeredAudio").start()
    }

    fun stop() {
        running = false
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.stop() }
            it.release()
        }
        track = null
    }

    private fun renderLoop() {
        val pcm = ShortArray(bufferSize * 2)
        val phases = DoubleArray(layers.size)
        var bodyPhase = 0.0
        var eventPhase = 0.0
        var textureState = 0.0
        var noiseState = 0x4D595DF4L
        var bodyEnvelope = 0f

        while (running) {
            val currentRpm = rpm
            val currentLoad = load
            val currentSpeed = speedKph
            val currentScene = scene
            val currentEvents = events
            val snapshot = layers
            val baseCyclesPerSecond = currentRpm / 60.0
            val baseAngular = baseCyclesPerSecond * 2.0 * PI
            val targetBody = bodyTarget(currentScene)
            val bodyGain = (0.18 + currentLoad * 0.22).coerceIn(0.16, 0.48)
            val textureAmount = (0.0025 + currentLoad * 0.007 + currentSpeed / 110000.0).coerceAtMost(0.016)

            for (i in 0 until bufferSize) {
                bodyEnvelope += (targetBody - bodyEnvelope) * 0.0028f
                var left = 0.0
                var right = 0.0
                var tonalEnergy = 0.0
                var highFrequencyEnergy = 0.0

                snapshot.forEachIndexed { index, layer ->
                    val rpmFactor = smoothBand(currentRpm, layer.minRpm, layer.maxRpm)
                    val loadFactor = smoothBand(currentLoad, layer.minLoad, layer.maxLoad)
                    val speedFactor = smoothBand(currentSpeed, layer.minSpeedKph, layer.maxSpeedKph)
                    val sceneFactor = layer.sceneBias[currentScene] ?: 1f
                    val activity = rpmFactor * loadFactor * speedFactor * sceneFactor
                    if (activity <= 0.001f) return@forEachIndexed

                    val loadWarp = 1.0 + currentLoad * 0.010
                    val frequency = if (layer.baseFrequencyMultiplier > 0f) {
                        baseAngular * layer.baseFrequencyMultiplier * loadWarp
                    } else {
                        2.0 * PI * (24.0 + currentSpeed * 0.95)
                    }
                    val step = frequency / sampleRate
                    phases[index] += step
                    if (phases[index] >= 1.0) phases[index] -= 1.0

                    val waveform = when {
                        layer.harmonic <= 1 -> sin(phases[index] * 2.0 * PI)
                        else -> sin(phases[index] * 2.0 * PI * layer.harmonic)
                    }

                    val layerGain = layer.gain * activity
                    val stereo = layer.stereoPosition.toDouble().coerceIn(-1.0, 1.0)
                    val leftGain = 0.5 * (1.0 - stereo)
                    val rightGain = 0.5 * (1.0 + stereo)
                    left += waveform * layerGain * (0.52 + leftGain)
                    right += waveform * layerGain * (0.52 + rightGain)

                    val absWave = abs(waveform)
                    tonalEnergy += absWave * layerGain
                    if (layer.baseFrequencyMultiplier >= 4f) highFrequencyEnergy += absWave * layerGain
                }

                // Broad EV motor body: slightly asymmetric left/right to avoid a synthetic mono feel.
                bodyPhase += (baseCyclesPerSecond * 0.50 + currentLoad * 2.0) / sampleRate
                if (bodyPhase >= 1.0) bodyPhase -= 1.0
                val fundamental = sin(bodyPhase * 2.0 * PI)
                val bodyHarmonic = sin(bodyPhase * 4.0 * PI + 0.22) * 0.34
                val bodySub = sin(bodyPhase * PI + 0.08) * 0.20
                val mechanicalBody = (fundamental + bodyHarmonic + bodySub) * bodyGain * bodyEnvelope

                // Very small broadband texture. It should be felt as material/noise, not heard as hiss.
                noiseState = noiseStep(noiseState)
                val white = ((noiseState and 0xFFFFL) / 32767.5 - 1.0).coerceIn(-1.0, 1.0)
                textureState += (white - textureState) * 0.018
                val materialTexture = textureState * textureAmount * (0.30 + currentSpeed / 400.0)

                // Keep inverter content subordinate to the body. It rises only during high load.
                val inverterSheen = highFrequencyEnergy * 0.025 * (0.35 + currentLoad * 0.55)
                val blendedSheen = inverterSheen * sin(eventPhase * 2.0 * PI * 0.65)

                eventPhase += (0.85 + currentRpm / 2200.0) / sampleRate
                if (eventPhase >= 1.0) eventPhase -= 1.0
                val accentEnvelope = 0.58 + 0.42 * sin(eventPhase * 2.0 * PI)
                val eventMix = (
                    currentEvents.launch * 0.16 * sin(eventPhase * 2.0 * PI * 1.45) +
                        currentEvents.accelerationHit * 0.08 * sin(eventPhase * 2.0 * PI * 2.05) +
                        currentEvents.liftOff * 0.06 * sin(eventPhase * 2.0 * PI * 2.7) +
                        currentEvents.regenerationHit * 0.08 * sin(eventPhase * 2.0 * PI * 1.85) +
                        currentEvents.brakeHit * 0.06 * sin(eventPhase * 2.0 * PI * 3.4)
                    ) * accentEnvelope

                val sceneBody = when (currentScene) {
                    AudioScene.IDLE -> 0.88
                    AudioScene.COAST -> 0.92
                    AudioScene.ACCELERATION -> 1.00
                    AudioScene.HARD_ACCELERATION -> 1.08
                    AudioScene.REGENERATION -> 0.98
                    AudioScene.LAUNCH -> 1.16
                    AudioScene.HIGH_SPEED -> 1.06
                }

                val master = (0.44 + currentLoad * 0.16).coerceIn(0.44, 0.64)
                val l = (left * 0.90 + mechanicalBody * 0.82 + materialTexture * 0.18 + blendedSheen * 0.22 + eventMix * 0.62) * master * sceneBody
                val r = (right * 0.90 + mechanicalBody * 1.02 + materialTexture * 0.22 + blendedSheen * 0.28 + eventMix * 0.78) * master * sceneBody

                pcm[i * 2] = (tanh(l) * Short.MAX_VALUE)
                    .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
                    .toInt().toShort()
                pcm[i * 2 + 1] = (tanh(r) * Short.MAX_VALUE)
                    .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
                    .toInt().toShort()
            }

            runCatching { track?.write(pcm, 0, pcm.size) }
        }
    }

    private fun noiseStep(state: Long): Long {
        var x = state and 0xFFFFFFFFL
        x = x xor ((x shl 13) and 0xFFFFFFFFL)
        x = x xor (x shr 17)
        x = x xor ((x shl 5) and 0xFFFFFFFFL)
        return x and 0xFFFFFFFFL
    }

    private fun bodyTarget(value: AudioScene): Float = when (value) {
        AudioScene.IDLE -> 0.72f
        AudioScene.COAST -> 0.68f
        AudioScene.ACCELERATION -> 0.88f
        AudioScene.HARD_ACCELERATION -> 1.02f
        AudioScene.REGENERATION -> 0.78f
        AudioScene.LAUNCH -> 1.08f
        AudioScene.HIGH_SPEED -> 0.98f
    }

    private fun smoothBand(value: Float, min: Float, max: Float): Float {
        if (max <= min) return if (value >= min) 1f else 0f
        if (value < min || value > max) return 0f
        val normalized = ((value - min) / (max - min)).coerceIn(0f, 1f)
        return normalized * normalized * (3f - 2f * normalized)
    }
}
