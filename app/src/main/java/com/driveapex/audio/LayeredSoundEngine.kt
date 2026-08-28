package com.driveapex.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Real-time EV drivetrain sound renderer.
 * The track is explicitly tagged as navigation guidance so BYD head units route it through
 * the navigation channel rather than the media/music channel.
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
    fun setRpm(value: Float) { rpm = value.coerceIn(700f, 7_000f) }
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
        val phases = DoubleArray(3)
        var envelope = 0f
        var bodyPhase = 0.0
        var inverterPhase = 0.0
        var textureState = 0.0
        var previousLoad = 0f
        var previousRpm = 700f
        var noiseState = 0x4D595DF4L

        while (running) {
            val currentRpm = rpm
            val currentLoad = load
            val currentSpeed = speedKph
            val currentScene = scene
            val currentEvents = events
            val targetEnvelope = when (currentScene) {
                AudioScene.IDLE -> 0.68f
                AudioScene.COAST -> 0.52f
                AudioScene.ACCELERATION -> 0.90f
                AudioScene.HARD_ACCELERATION -> 1.00f
                AudioScene.REGENERATION -> 0.70f
                AudioScene.LAUNCH -> 1.08f
                AudioScene.HIGH_SPEED -> 0.88f
            }

            val rpmRate = abs(currentRpm - previousRpm) / 600f
            val loadRate = abs(currentLoad - previousLoad) * 2.0f
            previousRpm += (currentRpm - previousRpm) * 0.10f
            previousLoad += (currentLoad - previousLoad) * 0.08f

            for (i in 0 until bufferSize) {
                envelope += (targetEnvelope - envelope) * if (targetEnvelope > envelope) 0.0038f else 0.0025f

                val rpmHz = (currentRpm / 60.0).coerceAtLeast(11.666)
                val loadWarp = 1.0 + currentLoad * 0.018
                val fundamentalStep = (2.0 * PI * rpmHz / sampleRate) * loadWarp
                val harmonicStep = fundamentalStep * 2.0
                val rotorStep = fundamentalStep * 0.5
                phases[0] += fundamentalStep
                phases[1] += harmonicStep
                phases[2] += rotorStep
                for (p in phases.indices) {
                    if (phases[p] >= 2.0 * PI) phases[p] -= 2.0 * PI
                }

                val motorFundamental = sin(phases[0])
                val motorHarmonic = sin(phases[1] + 0.18) * (0.20 + currentLoad * 0.11)
                val rotorTexture = sin(phases[2] + 1.07) * 0.11

                val loadPulse = 1.0 + currentLoad * 0.35 + loadRate * 0.08
                val bodyPhaseStep = (rpmHz * 0.42 + currentLoad * 2.8) / sampleRate
                bodyPhase += bodyPhaseStep
                if (bodyPhase >= 1.0) bodyPhase -= 1.0
                val body = (
                    sin(bodyPhase * 2.0 * PI) * 0.31 +
                        sin(bodyPhase * 4.0 * PI + 0.31) * 0.10 +
                        sin(bodyPhase * PI + 0.08) * 0.07
                    ) * envelope * loadPulse

                noiseState = noiseStep(noiseState)
                val white = ((noiseState and 0xFFFFL) / 32767.5 - 1.0).coerceIn(-1.0, 1.0)
                textureState += (white - textureState) * 0.020
                val roadTexture = textureState * (0.004 + currentSpeed / 24000.0 + currentLoad * 0.006)

                val inverterHz = (currentRpm * 2.65 + 520.0 + currentLoad * 700.0).coerceIn(850.0, 16_000.0)
                inverterPhase += (2.0 * PI * inverterHz / sampleRate)
                if (inverterPhase >= 2.0 * PI) inverterPhase -= 2.0 * PI
                val inverter = sin(inverterPhase) * (0.012 + currentLoad * 0.030)
                    * (0.30 + currentSpeed / 220.0).coerceAtMost(1.0)

                val dynamicPulse = (
                    currentEvents.accelerationHit * sin(phases[0] * 1.5) * 0.030 +
                        currentEvents.liftOff * sin(phases[0] * 0.75) * 0.024 +
                        currentEvents.regenerationHit * sin(phases[0] * 1.15) * 0.040 +
                        currentEvents.brakeHit * sin(phases[0] * 1.85) * 0.028 +
                        currentEvents.launch * sin(phases[0] * 1.25) * 0.050
                    )

                val sceneGain = when (currentScene) {
                    AudioScene.IDLE -> 0.58
                    AudioScene.COAST -> 0.62
                    AudioScene.ACCELERATION -> 0.86
                    AudioScene.HARD_ACCELERATION -> 1.00
                    AudioScene.REGENERATION -> 0.72
                    AudioScene.LAUNCH -> 1.08
                    AudioScene.HIGH_SPEED -> 0.90
                }

                // Keep a physical low/body core and use nonlinear saturation only at the end.
                val core = (
                    motorFundamental * (0.42 + currentLoad * 0.28) +
                        motorHarmonic + rotorTexture + body + inverter + roadTexture + dynamicPulse
                    ) * sceneGain

                val pan = (currentSpeed / 180.0).coerceIn(0.0, 1.0)
                val left = core * (0.98 - pan * 0.06)
                val right = core * (1.02 + pan * 0.06)
                pcm[i * 2] = (tanh(left * 0.74) * Short.MAX_VALUE)
                    .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
                    .toInt().toShort()
                pcm[i * 2 + 1] = (tanh(right * 0.74) * Short.MAX_VALUE)
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
}
