package com.driveapex.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/** Real-time procedural fallback. Sample layers can replace individual layers later. */
class EngineSoundEngine {
    private val sampleRate = 44_100
    private val bufferSize = 2_048
    private var track: AudioTrack? = null
    @Volatile private var running = false
    @Volatile private var rpm = 900f
    @Volatile private var load = 0.5f
    @Volatile private var profile = SoundProfiles.apexSport

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

    fun setRpm(value: Float) { rpm = value.coerceIn(700f, profile.maxRpm) }
    fun setLoad(value: Float) { load = value.coerceIn(0.1f, 1.5f) }
    fun setProfile(value: SoundProfile) {
        profile = value
        rpm = rpm.coerceIn(700f, value.maxRpm)
    }

    private fun renderLoop() {
        val pcm = ShortArray(bufferSize)
        var phase = 0.0
        var phase2 = 0.0
        while (running) {
            val currentRpm = rpm
            val currentLoad = load
            val currentProfile = profile
            val rpmRatio = ((currentRpm - 700f) / (currentProfile.maxRpm - 700f)).coerceIn(0f, 1f)
            val baseFrequency = (currentRpm / 60f) * 2.0f
            val step = 2.0 * PI * baseFrequency / sampleRate
            val step2 = 2.0 * PI * baseFrequency * 0.5 / sampleRate
            val effectiveLoad = (currentLoad * currentProfile.loadResponse).coerceIn(0.1f, 1.5f)
            val drive = 0.10 + effectiveLoad * (currentProfile.lowBodyGain + 0.08 * rpmRatio)
            val output = (0.22 + effectiveLoad * 0.10 + currentProfile.aggression * 0.05).coerceIn(0.18, 0.40)

            for (i in pcm.indices) {
                val fundamental = sin(phase) * (currentProfile.fundamentalGain + effectiveLoad * 0.16)
                val harmonic2 = sin(phase * 2.0) * (currentProfile.harmonic2Gain + effectiveLoad * 0.05)
                val harmonic3 = sin(phase * 3.0) * (currentProfile.harmonic3Gain + currentProfile.aggression * 0.05 * rpmRatio)
                val lowBody = sin(phase2) * drive
                val sample = (fundamental + harmonic2 + harmonic3 + lowBody) * Short.MAX_VALUE * output
                pcm[i] = sample.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
                phase += step
                phase2 += step2
                if (phase > 2.0 * PI) phase -= 2.0 * PI
                if (phase2 > 2.0 * PI) phase2 -= 2.0 * PI
            }
            runCatching { track?.write(pcm, 0, pcm.size) }
        }
    }
}
