package com.driveapex.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Real-time layered EV sound renderer. The synthesis is a placeholder for the
 * final sample-bank engine, but its control model is deliberately sample-ready.
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

    fun setLayers(value: List<SoundLayer>) {
        layers = value
    }

    fun setRpm(value: Float) = run { rpm = value.coerceIn(700f, 7_000f) }
    fun setLoad(value: Float) = run { load = value.coerceIn(0f, 1.5f) }
    fun setSpeed(value: Float) = run { speedKph = value.coerceAtLeast(0f) }

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

        while (running) {
            val currentRpm = rpm
            val currentLoad = load
            val currentSpeed = speedKph
            val snapshot = layers
            val base = currentRpm / 60.0 * 2.0 * PI
            val speedNorm = (currentSpeed / 250f).coerceIn(0f, 1f)

            for (i in 0 until bufferSize) {
                var mono = 0.0
                var left = 0.0
                var right = 0.0

                snapshot.forEachIndexed { index, layer ->
                    val rpmFactor = smoothBand(currentRpm, layer.minRpm, layer.maxRpm)
                    val loadFactor = smoothBand(currentLoad, layer.minLoad, layer.maxLoad)
                    val activity = rpmFactor * loadFactor
                    if (activity <= 0.001f) return@forEachIndexed

                    val frequency = if (layer.baseFrequencyMultiplier > 0f) {
                        base * layer.baseFrequencyMultiplier
                    } else {
                        18.0 * PI * (0.35 + speedNorm)
                    }
                    val step = frequency / sampleRate
                    phases[index] += step
                    if (phases[index] >= 1.0) phases[index] -= 1.0

                    val waveform = when {
                        layer.harmonic <= 1 -> sin(phases[index] * 2.0 * PI)
                        else -> sin(phases[index] * 2.0 * PI * layer.harmonic)
                    }
                    val layerGain = layer.gain * activity
                    val stereoWidth = ((index % 5) / 5.0 - 0.4) * 0.55
                    left += waveform * layerGain * (1.0 - stereoWidth)
                    right += waveform * layerGain * (1.0 + stereoWidth)
                }

                lowPhase += base * 0.5 / sampleRate
                if (lowPhase >= 1.0) lowPhase -= 1.0
                val lowBody = sin(lowPhase * 2.0 * PI) * (0.10 + currentLoad * 0.10)
                mono = (left + right) * 0.5 + lowBody

                val master = (0.48 + currentLoad * 0.18).coerceIn(0.45, 0.70)
                val l = (left + lowBody * 0.9) * master
                val r = (right + lowBody * 1.1) * master
                pcm[i * 2] = (l * Short.MAX_VALUE).coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
                pcm[i * 2 + 1] = (r * Short.MAX_VALUE).coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
            }

            runCatching { track?.write(pcm, 0, pcm.size) }
        }
    }

    private fun smoothBand(value: Float, min: Float, max: Float): Float {
        if (value < min || value > max) return 0f
        if (max <= min) return 1f
        val normalized = ((value - min) / (max - min)).coerceIn(0f, 1f)
        return normalized * normalized * (3f - 2f * normalized)
    }
}
