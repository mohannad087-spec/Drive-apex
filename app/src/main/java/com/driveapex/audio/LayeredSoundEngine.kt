package com.driveapex.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.driveapex.diag.DriveApexLog

/**
 * Real-time layered EV sound renderer.
 * The output is bound to the BYD OEM navigation stream (STREAM_NAVI, 14 on the
 * verified Overdrive/DiPlus route), not STREAM_MUSIC.
 */
class LayeredSoundEngine(character: EngineCharacter = EngineCharacters.default) {
    companion object {
        private const val TAG = "DriveApexAudio"
        private const val DEFAULT_NAV_STREAM = 14
        /** Public so a bank can be decoded to the rate the engine will play it at. */
        const val SAMPLE_RATE = 44_100
    }

    private val sampleRate = SAMPLE_RATE

    // Halved from 1536. Render latency is one chunk, so this is 17ms instead of
    // 35ms, while the AudioTrack queue below stays two chunks deep -- the same
    // number of frames as before, so the margin against underrun is unchanged
    // and only the writes get more frequent.
    private val bufferSize = 768

    // Declared after the sample rate on purpose: Kotlin initialises properties in
    // source order, so constructing this above would hand the renderer a zero.
    private val renderer = CharacterRenderer(SAMPLE_RATE).apply { setCharacter(character) }

    // The sample voice and the rpm mapping it needs. Silent and inert until a
    // bank of recordings is loaded; synthesis stays the voice until then, so an
    // APK with no samples in it behaves exactly as before.
    private val sampleVoice = SampleVoiceRenderer(SAMPLE_RATE)
    private val sampleRpm = VoiceRpmMapper().apply { retune(character.gearbox) }
    private var sampleClock = 0L

    private var track: AudioTrack? = null

    @Volatile private var running = false
    @Volatile private var rpm = 700f
    @Volatile private var load = 0.10f

    // Kept apart from load on purpose: load mixes throttle with brake, regen and
    // road speed, which is right for how hard the engine is working but wrong for
    // a free rev at a standstill, where the pedal is the only thing that should
    // move the note.
    @Volatile private var throttle = 0f
    @Volatile private var speedKph = 0f
    @Volatile private var scene = AudioScene.IDLE
    @Volatile private var events = AcousticEventComposer.Events(0f, 0f, 0f, 0f, 0f, 0f)

    fun setCharacter(value: EngineCharacter) {
        renderer.setCharacter(value)
        sampleRpm.retune(value.gearbox)
    }

    /**
     * Plays real recordings instead of synthesising, when a playable bank is
     * given. Passing null returns the engine to synthesis.
     */
    fun setSampleBank(bank: EngineSampleBank?) = sampleVoice.setBank(bank)

    fun sampleBank(): EngineSampleBank? = sampleVoice.currentBank()

    fun character(): EngineCharacter = renderer.currentCharacter()

    /** Virtual gear, 1-based; 0 when the current character has no gearbox. */
    fun currentGear(): Int =
        if (sampleVoice.isReady()) sampleRpm.currentGear() else renderer.currentGear()
    fun setRpm(value: Float) { rpm = value.coerceIn(0f, 25_000f) }
    fun setLoad(value: Float) { load = value.coerceIn(0f, 1.5f) }
    fun setThrottle(value: Float) { throttle = value.coerceIn(0f, 1f) }
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

        // Two chunks in bytes: four bytes per stereo frame, times two chunks.
        val targetBuffer = maxOf(bufferSize * 4 * 2, minBuffer)
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
            DriveApexLog.e("audio", "AudioTrack create failed on stream $navStream", it)
        }.getOrNull() ?: return

        track = created
        runCatching { created.setVolume(1f) }
        runCatching { created.play() }.onFailure {
            Log.e(TAG, "Unable to start navigation AudioTrack stream=$navStream", it)
            DriveApexLog.e("audio", "AudioTrack play failed on stream $navStream", it)
            runCatching { created.release() }
            track = null
            return
        }

        Log.i(TAG, "AudioTrack started on OEM navigation stream=$navStream")
        DriveApexLog.i("audio", "AudioTrack started on stream $navStream, buffer $targetBuffer bytes")
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

    /**
     * A fault here must cost the sound, not the app.
     *
     * This thread died on an IndexOutOfBounds and took the whole process with
     * it, because an uncaught exception on any thread ends the app -- from the
     * driver's seat that reads as the app closing on its own. The cause is
     * fixed, but a renderer defect should never again be fatal: catch it, record
     * it so the log names the file and line, and stop cleanly.
     */
    /**
     * One buffer from the recordings, or false when there is no bank to play.
     *
     * The shift cut is applied here rather than inside the sample voice: a real
     * recording of a steady rpm has no torque interruption in it, so without
     * this an upshift would be a bare pitch jump.
     */
    private fun renderFromSamples(pcm: ShortArray): Boolean {
        if (!sampleVoice.isReady()) return false
        sampleClock += (bufferSize * 1000L) / sampleRate
        val mapped = sampleRpm.map(rpm, throttle, speedKph, bufferSize, sampleRate, sampleClock)
        if (!sampleVoice.render(pcm, bufferSize, mapped.rpm, throttle)) return false
        if (mapped.cutGain < 1f) {
            val gain = mapped.cutGain
            for (i in pcm.indices) pcm[i] = (pcm[i] * gain).toInt().toShort()
        }
        return true
    }

    private fun renderLoop() {
        // Ask the scheduler for audio priority. This thread has to refill the
        // track every 17ms; at default priority a head unit busy with navigation
        // or its own UI can preempt it for longer than that, and a buffer that
        // arrives late is a gap in the output -- heard as crackle, not silence.
        runCatching {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        }
        val pcm = ShortArray(bufferSize * 2)
        try {
            while (running) {
                if (!renderFromSamples(pcm)) {
                    renderer.render(pcm, bufferSize, rpm, load, speedKph, throttle, scene, events)
                }
                runCatching { track?.write(pcm, 0, pcm.size) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "render loop failed", t)
            DriveApexLog.e("audio", "render loop failed; sound stopped, app kept running", t)
            running = false
            runCatching { track?.let { it.pause(); it.flush(); it.stop(); it.release() } }
            track = null
        }
    }
}
