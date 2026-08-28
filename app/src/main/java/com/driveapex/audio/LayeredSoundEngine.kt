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
 * The output track is tagged as navigation guidance so the head unit can route it through
 * the navigation channel. Existing sound profiles remain active.
 */
class LayeredSoundEngine(
    private var layers: List<SoundLayer> = ETronInspiredSoundProfile.layers
) {
    private val sampleRate = 44_100
    private val bufferSize = 1_536
    private var track: AudioTrack? = null

    @Volatile private var running = false
    @Volatile private var rpm = 700f
    @Volatile private var load = 0.10f
    @Volatile private var speedKph = 0f
    @Volatile private var scene = AudioScene.IDLE
    @Volatile private var events = AcousticEventComposer.Events(0f, 0f, 0f, 0f, 0f, 0f)

    fun setLayers(value: List<SoundLayer>) { layers = value }
    fun setRpm(value: Float) { rpm = value.coerceIn(0f, 25_000f) }
    fun setLoad(value: Float) { load = value.coerceIn(0f, 1.5f) }
    fun setSpeed(value: Float) { speedKph = value.coerceAtLeast(0f) }
    fun setScene(value: AudioScene) { scene = value }
    fun setEvents(value: AcousticEventComposer.Events) { events = value }

    fun start() {
        if (running) return
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).let { if (it > 0) it else bufferSize * 4 }

        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(bufferSize * 4, minBuffer))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                it.setVolume(1f)
                it.play()
            }

        running = true
        Thread(::renderLoop, "DriveApex-LayeredAudio").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        track = null
    }

    private fun renderLoop() {
        val pcm = ShortArray(bufferSize * 2)
        var phases = DoubleArray(layers.size)
        var bodyPhase = 0.0
        var textureState = 0.0
        var noiseState = 0x4D595DF4L
        var eventPhase = 0.0
        var bodyEnvelope = 0f
        var previousRpm = 700f
        var previousLoad = 0f

        while (running) {
            val currentRpm = rpm
            val currentLoad = load
            val currentSpeed = speedKph
            val currentScene = scene
            val currentEvents = events
            val snapshot = layers

            if (phases.size != snapshot.size) phases = DoubleArray(snapshot.size)

            val baseCyclesPerSecond = (currentRpm / 60.0).coerceAtLeast(1.0)
            val baseAngular = baseCyclesPerSecond * 2.0 * PI
            val rpmRate = abs(currentRpm - previousRpm) / 600f
            val loadRate = abs(currentLoad - previousLoad) * 2f
            previousRpm += (currentRpm - previousRpm) * 0.10f
            previousLoad += (currentLoad - previousLoad) * 0.08f

            val targetBody = when (currentScene) {
                AudioScene.IDLE -> 0.68f
                AudioScene.COAST -> 0.52f
                AudioScene.ACCELERATION -> 0.90f
                AudioScene.HARD_ACCELERATION -> 1.00f
                AudioScene.REGENERATION -> 0.70f
                AudioScene.LAUNCH -> 1.08f
                AudioScene.HIGH_SPEED -> 0.88f
            }

            for (i in 0 until bufferSize) {
                bodyEnvelope += (targetBody - bodyEnvelope) * 0.003f
                var left = 0.0
                var right = 0.0
                var highFrequencyEnergy = 0.0

                snapshot.forEachIndexed { index, layer ->
                    val rpmFactor = smoothBand(currentRpm, layer.minRpm, layer.maxRpm)
                    val loadFactor = smoothBand(currentLoad, layer.minLoad, layer.maxLoad)
                    val speedFactor = smoothBand(currentSpeed, layer.minSpeedKph, layer.maxSpeedKph)
                    val sceneFactor = layer.sceneBias[currentScene] ?: 1f
                    val activity = rpmFactor * loadFactor * speedFactor * sceneFactor
                    if (activity <= 0.001f) return@forEachIndexed

                    val frequency = if (layer.baseFrequencyMultiplier > 0f) {
                        baseAngular * layer.baseFrequencyMultiplier * (1.0 + currentLoad * 0.012)
                    } else {
                        2.0 * PI * (24.0 + currentSpeed * 0.95)
                    }
                    phases[index] += frequency / sampleRate
                    if (phases[index] >= 2.0 * PI) phases[index] -= 2.0 * PI

                    val waveform = if (layer.harmonic <= 1) {
                        sin(phases[index])
                    } else {
                        sin(phases[index] * layer.harmonic)
                    }
                    val layerGain = layer.gain * activity
                    val stereo = layer.stereoPosition.toDouble().coerceIn(-1.0, 1.0)
                    left += waveform * layerGain * (1.0 - stereo * 0.5)
                    right += waveform * layerGain * (1.0 + stereo * 0.5)
                    if (layer.baseFrequencyMultiplier >= 4f) {
                        highFrequencyEnergy += abs(waveform) * layerGain
                    }
                }

                bodyPhase += (baseCyclesPerSecond * 0.46 + currentLoad * 2.0) / sampleRate
                if (bodyPhase >= 1.0) bodyPhase -= 1.0
                val body = (
                    sin(bodyPhase * 2.0 * PI) * 0.30 +
                        sin(bodyPhase * 4.0 * PI + 0.25) * 0.11 +
                        sin(bodyPhase * PI + 0.08) * 0.08
                    ) * bodyEnvelope * (0.78 + currentLoad * 0.42)

                noiseState = noiseStep(noiseState)
                val white = ((noiseState and 0xFFFFL) / 32767.5 - 1.0).coerceIn(-1.0, 1.0)
                textureState += (white - textureState) * 0.018
                val texture = textureState * (0.003 + currentSpeed / 32000.0 + currentLoad * 0.004)

                val inverterFrequency = (currentRpm * 2.65 + 520.0 + currentLoad * 700.0)
                    .coerceIn(850.0, 16_000.0)
                eventPhase += (0.75 + currentRpm / 2600.0) / sampleRate
                if (eventPhase >= 1.0) eventPhase -= 1.0
                val inverter = sin(eventPhase * 2.0 * PI * inverterFrequency / 1000.0) *
                    (0.004 + highFrequencyEnergy * 0.012 + currentLoad * 0.010)

                val eventPulse = (
                    currentEvents.launch * sin(eventPhase * 2.0 * PI * 1.4) * 0.020 +
                        currentEvents.accelerationHit * sin(eventPhase * 2.0 * PI * 1.9) * 0.012 +
                        currentEvents.liftOff * sin(eventPhase * 2.0 * PI * 2.5) * 0.010 +
                        currentEvents.regenerationHit * sin(eventPhase * 2.0 * PI * 1.7) * 0.018 +
                        currentEvents.brakeHit * sin(eventPhase * 2.0 * PI * 2.8) * 0.012
                    ) * (0.65 + currentLoad * 0.35)

                val sceneGain = when (currentScene) {
                    AudioScene.IDLE -> 0.72
                    AudioScene.COAST -> 0.78
                    AudioScene.ACCELERATION -> 0.96
                    AudioScene.HARD_ACCELERATION -> 1.08
                    AudioScene.REGENERATION -> 0.84
                    AudioScene.LAUNCH -> 1.12
                    AudioScene.HIGH_SPEED -> 1.02
                }

                val transientGain = 1.0 + rpmRate * 0.10 + loadRate * 0.06
                val core = (left * 0.92 + body * 0.92 + texture * 0.25 + inverter + eventPulse) * sceneGain * transientGain
                val coreRight = (right * 0.92 + body * 1.02 + texture * 0.30 + inverter * 0.92 + eventPulse * 1.08) * sceneGain * transientGain

                pcm[i * 2] = (tanh(core * 0.64) * Short.MAX_VALUE)
                    .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
                    .toInt().toShort()
                pcm[i * 2 + 1] = (tanh(coreRight * 0.64) * Short.MAX_VALUE)
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

    private fun smoothBand(value: Float, min: Float, max: Float): Float {
        if (max <= min) return if (value >= min) 1f else 0f
        if (value < min || value > max) return 0f
        val normalized = ((value - min) / (max - min)).coerceIn(0f, 1f)
        return normalized * normalized * (3f - 2f * normalized)
    }
}
