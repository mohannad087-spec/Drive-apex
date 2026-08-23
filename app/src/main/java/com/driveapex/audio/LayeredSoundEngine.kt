package com.driveapex.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Real-time layered EV sound renderer.
 * Procedural synthesis remains the fallback while the sample-bank renderer is being integrated.
 *
 * The renderer deliberately combines tonal motor/inverter components with a deterministic
 * broadband texture and soft saturation. This makes the procedural fallback feel less like
 * stacked sine waves while preserving low CPU cost for phone and head-unit testing.
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

    fun setLayers(value: List<SoundLayer>) {
        layers = value
    }

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
        var lowPhase = 0.0
        var sceneEnvelope = 0f
        var eventPhase = 0.0
        var noiseState = 0x4D595DF4L
        var textureState = 0f

        while (running) {
            val currentRpm = rpm
            val currentLoad = load
            val currentSpeed = speedKph
            val currentScene = scene
            val currentEvents = events
            val snapshot = layers
            val base = currentRpm / 60.0 * 2.0 * PI
            val targetSceneEnvelope = sceneEnvelopeTarget(currentScene)
            val textureAmount = (0.008 + currentLoad * 0.018 + currentSpeed / 50000.0).coerceAtMost(0.04)

            for (i in 0 until bufferSize) {
                sceneEnvelope += (targetSceneEnvelope - sceneEnvelope) * 0.0022f
                var left = 0.0
                var right = 0.0
                var tonalEnergy = 0.0

                snapshot.forEachIndexed { index, layer ->
                    val rpmFactor = smoothBand(currentRpm, layer.minRpm, layer.maxRpm)
                    val loadFactor = smoothBand(currentLoad, layer.minLoad, layer.maxLoad)
                    val speedFactor = smoothBand(currentSpeed, layer.minSpeedKph, layer.maxSpeedKph)
                    val sceneFactor = layer.sceneBias[currentScene] ?: 1f
                    val activity = rpmFactor * loadFactor * speedFactor * sceneFactor
                    if (activity <= 0.001f) return@forEachIndexed

                    val frequency = if (layer.baseFrequencyMultiplier > 0f) {
                        base * layer.baseFrequencyMultiplier * (1.0 + currentLoad * 0.015)
                    } else {
                        2.0 * PI * (28.0 + currentSpeed * 1.15)
                    }
                    val step = frequency / sampleRate
                    phases[index] += step
                    if (phases[index] >= 1.0) phases[index] -= 1.0

                    val waveform = when {
                        layer.harmonic <= 1 -> sin(phases[index] * 2.0 * PI)
                        else -> sin(phases[index] * 2.0 * PI * layer.harmonic)
                    }
                    val layerGain = layer.gain * activity * sceneEnvelope
                    val stereo = layer.stereoPosition.toDouble().coerceIn(-1.0, 1.0)
                    val leftGain = 0.5 * (1.0 - stereo)
                    val rightGain = 0.5 * (1.0 + stereo)
                    left += waveform * layerGain * (0.55 + leftGain)
                    right += waveform * layerGain * (0.55 + rightGain)
                    tonalEnergy += kotlin.math.abs(waveform) * layerGain
                }

                lowPhase += base * 0.5 / sampleRate
                if (lowPhase >= 1.0) lowPhase -= 1.0
                val lowBody = sin(lowPhase * 2.0 * PI) * (0.10 + currentLoad * 0.10) * sceneEnvelope

                // A deterministic, very small broadband texture is layered under the tonal core.
                // It is intentionally bounded so the sound remains clean on phone/head-unit speakers.
                noiseState = noiseStep(noiseState)
                val white = ((noiseState and 0xFFFFL) / 32767.5 - 1.0).coerceIn(-1.0, 1.0)
                textureState += (white - textureState) * 0.035f
                val sheen = textureState * textureAmount * (0.45 + currentSpeed / 260.0)
                val mechanicalTexture = sheen * (0.35 + tonalEnergy * 1.8).coerceAtMost(1.0)

                // Event accents: deliberately short-lived and layered over the continuous bed.
                eventPhase += (1.0 + currentRpm / 1800.0) / sampleRate
                if (eventPhase >= 1.0) eventPhase -= 1.0
                val accentEnvelope = 0.65 + 0.35 * sin(eventPhase * 2.0 * PI)
                val launchAccent = currentEvents.launch * 0.20 * sin(eventPhase * 2.0 * PI * 1.7)
                val accelAccent = currentEvents.accelerationHit * 0.12 * sin(eventPhase * 2.0 * PI * 2.4)
                val liftAccent = currentEvents.liftOff * 0.10 * sin(eventPhase * 2.0 * PI * 3.1)
                val regenAccent = currentEvents.regenerationHit * 0.14 * sin(eventPhase * 2.0 * PI * 2.1)
                val brakeAccent = currentEvents.brakeHit * 0.11 * sin(eventPhase * 2.0 * PI * 4.0)
                val eventMix = (launchAccent + accelAccent + liftAccent + regenAccent + brakeAccent) * accentEnvelope

                val master = (0.46 + currentLoad * 0.18).coerceIn(0.45, 0.68)
                val l = left + lowBody * 0.9 + eventMix * 0.85 + mechanicalTexture * 0.32
                val r = right + lowBody * 1.1 + eventMix * 1.10 + mechanicalTexture * 0.40

                // Soft clip preserves transient punch without harsh 16-bit clipping.
                pcm[i * 2] = (tanh(l * master) * Short.MAX_VALUE)
                    .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
                    .toInt().toShort()
                pcm[i * 2 + 1] = (tanh(r * master) * Short.MAX_VALUE)
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

    private fun sceneEnvelopeTarget(value: AudioScene): Float = when (value) {
        AudioScene.IDLE -> 0.58f
        AudioScene.COAST -> 0.64f
        AudioScene.ACCELERATION -> 0.90f
        AudioScene.HARD_ACCELERATION -> 1.06f
        AudioScene.REGENERATION -> 0.80f
        AudioScene.LAUNCH -> 1.14f
        AudioScene.HIGH_SPEED -> 1.00f
    }

    private fun smoothBand(value: Float, min: Float, max: Float): Float {
        if (max <= min) return if (value >= min) 1f else 0f
        if (value < min || value > max) return 0f
        val normalized = ((value - min) / (max - min)).coerceIn(0f, 1f)
        return normalized * normalized * (3f - 2f * normalized)
    }
}
