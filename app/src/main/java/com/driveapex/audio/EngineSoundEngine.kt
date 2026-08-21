package com.driveapex.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Prototype real-time engine sound generator.
 * The first milestone intentionally uses a synthesized fallback tone so the
 * audio control pipeline can be tested before adding vehicle-specific samples.
 */
class EngineSoundEngine {
    private val sampleRate = 44_100
    private val bufferSize = 2_048
    private var track: AudioTrack? = null
    private var running = false
    private var rpm = 900f

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
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }

        running = true
        Thread(::renderLoop, "DriveApex-Audio").start()
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

    fun setRpm(value: Float) {
        rpm = value.coerceIn(700f, 7000f)
    }

    private fun renderLoop() {
        val pcm = ShortArray(bufferSize)
        var phase = 0.0
        while (running) {
            val frequency = (rpm / 60f) * 2.0f
            val phaseStep = 2.0 * PI * frequency / sampleRate
            for (i in pcm.indices) {
                val harmonic = sin(phase) * 0.62 +
                    sin(phase * 2.0) * 0.22 +
                    sin(phase * 3.0) * 0.11
                pcm[i] = (harmonic * Short.MAX_VALUE * 0.22).toInt().toShort()
                phase += phaseStep
                if (phase > 2.0 * PI) phase -= 2.0 * PI
            }
            runCatching { track?.write(pcm, 0, pcm.size) }
        }
    }
}
