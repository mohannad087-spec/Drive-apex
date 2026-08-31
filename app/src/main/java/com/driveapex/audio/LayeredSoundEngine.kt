package com.driveapex.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Real-time layered EV sound renderer.
 * The output is bound to the BYD OEM navigation stream (STREAM_NAVI, 14 on the
 * verified Overdrive/DiPlus route), not STREAM_MUSIC.
 */
class LayeredSoundEngine(character: EngineCharacter = EngineCharacters.default) {
    companion object {
        private const val TAG = "DriveApexAudio"
        private const val DEFAULT_NAV_STREAM = 14
        private const val SAMPLE_RATE = 44_100
    }

    private val sampleRate = SAMPLE_RATE
    private val bufferSize = 1_536

    // Declared after the sample rate on purpose: Kotlin initialises properties in
    // source order, so constructing this above would hand the renderer a zero.
    private val renderer = CharacterRenderer(SAMPLE_RATE).apply { setCharacter(character) }
    private var track: AudioTrack? = null

    @Volatile private var running = false
    @Volatile private var rpm = 700f
    @Volatile private var load = 0.10f
    @Volatile private var speedKph = 0f
    @Volatile private var scene = AudioScene.IDLE
    @Volatile private var events = AcousticEventComposer.Events(0f, 0f, 0f, 0f, 0f, 0f)

    fun setCharacter(value: EngineCharacter) { renderer.setCharacter(value) }

    fun character(): EngineCharacter = renderer.currentCharacter()
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

        val navStream = runCatching {
            AudioManager::class.java.getField("STREAM_NAVI").getInt(null)
        }.getOrDefault(DEFAULT_NAV_STREAM)

        val targetBuffer = maxOf(bufferSize * 4, minBuffer)
        val created = runCatching {
            @Suppress("DEPRECATION")
            AudioTrack(
                navStream,
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                targetBuffer,
                AudioTrack.MODE_STREAM
            )
        }.onFailure {
            Log.e(TAG, "Unable to create navigation AudioTrack stream=$navStream", it)
        }.getOrNull() ?: return

        track = created
        runCatching { created.setVolume(1f) }
        runCatching { created.play() }.onFailure {
            Log.e(TAG, "Unable to start navigation AudioTrack stream=$navStream", it)
            runCatching { created.release() }
            track = null
            return
        }

        Log.i(TAG, "AudioTrack started on OEM navigation stream=$navStream")
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
        while (running) {
            renderer.render(pcm, bufferSize, rpm, load, speedKph, scene, events)
            runCatching { track?.write(pcm, 0, pcm.size) }
        }
    }
}
