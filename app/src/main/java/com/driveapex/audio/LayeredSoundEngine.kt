package com.driveapex.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Real-time layered EV sound renderer. Procedural synthesis remains the fallback
 * while the sample-bank renderer is being integrated.
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

    fun setLayers(value: List<SoundLayer>) {
        layers = value
    }

    fun setRpm(value: Float) { rpm = value.coerceIn(700f, 7_000f) }
    fun setLoad(value: Float) { load = value.coerceIn(0f, 1.5f) }
    fun setSpeed(value: Float) { speedKph = value.coerceAtLeast(0f) }
    fun setScene(value: AudioScene) { scene = value }

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

        while (running) {
            val currentRpm = rpm
            val currentLoad = load
            val currentSpeed = speedKph
            val currentScene = scene
            val snapshot = layers
            val base = currentRpm / 60.0 * 2.0 * PI
            val targetSceneEnvelope = sceneEnvelopeTarget(currentScene)

            for (i in 0 until bufferSize) {
                sceneEnvelope += (targetSceneEnvelope - sceneEnvelope) * 0.0022f
                var left = 0.0
                var right = 0.0

                snapshot.forEachIndexed { index, layer ->
                    val rpmFactor = smoothBand(currentRpm, layer.minRpm, layer.maxRpm)
                    val loadFactor = smoothBand(currentLoad, layer.minLoad, layer.maxLoad)
                    val speedFactor = smoothBand(currentSpeed, layer.minSpeedKph, layer.maxSpeedKph)
                    val sceneFactor = layer.sceneBias[currentScene] ?: 1f
                    val activity = rpmFactor * loadFactor * speedFactor * sceneFactor
                    if (activity <= 0.001f) return@forEachIndexed

                    val frequency = if (layer.baseFrequencyMultiplier > 0f) {
                        base * layer.baseFrequencyMultiplier
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
                }

                lowPhase += base * 0.5 / sampleRate
                if (lowPhase >= 1.0) lowPhase -= 1.0
                val lowBody = sin(lowPhase * 2.0 * PI) * (0.10 + currentLoad * 0.10) * sceneEnvelope
                val master = (0.48 + currentLoad * 0.18).coerceIn(0.45, 0.70)
                val l = (left + lowBody * 0.9) * master
                val r = (right + lowBody * 1.1) * master
                pcm[i * 2] = (l * Short.MAX_VALUE)
                    .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
                    .toInt().toShort()
                pcm[i * 2 + 1] = (r * Short.MAX_VALUE)
                    .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
                    .toInt().toShort()
            }

            runCatching { track?.write(pcm, 0, pcm.size) }
        }
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
