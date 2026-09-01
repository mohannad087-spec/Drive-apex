package com.driveapex.audio

import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.driveapex.diag.DriveApexLog

/**
 * Real-time layered EV sound renderer.
 *
 * The output goes to whichever of the head unit's channels the driver chose in
 * Settings; it defaults to the BYD OEM navigation stream (STREAM_NAVI, 14 on the
 * verified Overdrive/DiPlus route), which is the one known to play over the
 * radio on this car.
 */
class LayeredSoundEngine(character: EngineCharacter = EngineCharacters.default) {
    companion object {
        private const val TAG = "DriveApexAudio"
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

    // Shared by both non-additive voices: gears and the standstill rev are the
    // same behaviour whichever of them is producing the note.
    private val voiceRpm = VoiceRpmMapper().apply { retune(character.gearbox) }
    private var voiceClock = 0L

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

    @Volatile private var driveMode = DriveMode.NORMAL
    @Volatile private var baseCharacter: EngineCharacter = character
    @Volatile private var outputChannel = AudioOutputChannel.DEFAULT

    /**
     * Which run of the render loop is the current one.
     *
     * Changing the output channel stops the track and starts a new one, and
     * without this the old loop would see `running` go true again and keep
     * writing alongside the new one -- two threads filling one track, which is
     * heard as the engine playing twice at slightly different times.
     */
    @Volatile private var generation = 0

    fun setCharacter(value: EngineCharacter) {
        baseCharacter = value
        applyVoice()
    }

    /**
     * Eco, Normal or Sport. Changes when the box shifts and how hard, not what
     * the engine is: the character keeps its own timbre in every mode.
     */
    fun setDriveMode(mode: DriveMode) {
        if (mode == driveMode) return
        driveMode = mode
        applyVoice()
    }

    fun driveMode(): DriveMode = driveMode

    private fun applyVoice() {
        val mode = driveMode
        val base = baseCharacter
        val shaped = base.copy(
            level = base.level * mode.levelScale,
            gearbox = base.gearbox?.let { mode.applyTo(it) }
        )
        renderer.setCharacter(shaped)
        // A bank of recordings brings its own gear count -- six, as asked for on
        // the uploaded voices -- rather than inheriting the eight belonging to
        // whichever synthesised character happened to be selected before it.
        val box = if (sampleVoice.isReady()) EngineCharacter.Gearbox.sixSpeed() else base.gearbox
        voiceRpm.retune(box?.let { mode.applyTo(it) })
    }

    /**
     * Moves the sound to another of the head unit's channels.
     *
     * The channel is fixed when the AudioTrack is created, so a change while
     * the engine is running means building a new track: stop, then start again
     * on the new one. Nothing else about the voice is touched.
     */
    fun setOutputChannel(channel: AudioOutputChannel) {
        if (channel == outputChannel) return
        outputChannel = channel
        DriveApexLog.i("audio", "output channel -> ${channel.name} (stream ${channel.streamType()})")
        if (running) {
            stop()
            start()
        }
    }

    fun outputChannel(): AudioOutputChannel = outputChannel

    /**
     * The gear set the voice is actually using.
     *
     * Not always the selected character's: a bank of recordings brings its own
     * six. The screen prints where each drive mode shifts, and it has to print
     * the truth about whatever is playing.
     */
    fun activeGearbox(): EngineCharacter.Gearbox? =
        if (sampleVoice.isReady()) EngineCharacter.Gearbox.sixSpeed() else baseCharacter.gearbox

    /**
     * Plays real recordings instead of synthesising, when a playable bank is
     * given. Passing null returns the engine to synthesis.
     */
    fun setSampleBank(bank: EngineSampleBank?) {
        sampleVoice.setBank(bank)
        applyVoice()
    }

    fun sampleBank(): EngineSampleBank? = sampleVoice.currentBank()

    fun character(): EngineCharacter = renderer.currentCharacter()

    /** Virtual gear, 1-based; 0 when the current character has no gearbox. */
    fun currentGear(): Int =
        if (sampleVoice.isReady()) voiceRpm.currentGear() else renderer.currentGear()
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

        val stream = outputChannel.streamType()

        // Two chunks in bytes: four bytes per stereo frame, times two chunks.
        val targetBuffer = maxOf(bufferSize * 4 * 2, minBuffer)
        val created = runCatching {
            @Suppress("DEPRECATION")
            AudioTrack(
                stream,
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                targetBuffer,
                AudioTrack.MODE_STREAM
            )
        }.onFailure {
            Log.e(TAG, "Unable to create AudioTrack on ${outputChannel.name} stream=$stream", it)
            DriveApexLog.e("audio", "AudioTrack create failed on ${outputChannel.name} stream $stream", it)
        }.getOrNull() ?: return

        track = created
        runCatching { created.setVolume(1f) }
        runCatching { created.play() }.onFailure {
            Log.e(TAG, "Unable to start AudioTrack on ${outputChannel.name} stream=$stream", it)
            DriveApexLog.e("audio", "AudioTrack play failed on ${outputChannel.name} stream $stream", it)
            runCatching { created.release() }
            track = null
            return
        }

        Log.i(TAG, "AudioTrack started on ${outputChannel.name} stream=$stream")
        DriveApexLog.i("audio",
            "AudioTrack started on ${outputChannel.name} stream $stream, buffer $targetBuffer bytes")
        running = true
        val mine = ++generation
        Thread({ renderLoop(mine) }, "DriveApex-LayeredAudio").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        // Retires the current loop even if start() is called again immediately.
        generation++
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
        voiceClock += (bufferSize * 1000L) / sampleRate
        val mapped = voiceRpm.map(rpm, throttle, speedKph, bufferSize, sampleRate, voiceClock)
        if (!sampleVoice.render(pcm, bufferSize, mapped.rpm, throttle)) return false
        if (mapped.cutGain < 1f) {
            val gain = mapped.cutGain
            for (i in pcm.indices) pcm[i] = (pcm[i] * gain).toInt().toShort()
        }
        return true
    }

    private fun renderLoop(mine: Int) {
        // Ask the scheduler for audio priority. This thread has to refill the
        // track every 17ms; at default priority a head unit busy with navigation
        // or its own UI can preempt it for longer than that, and a buffer that
        // arrives late is a gap in the output -- heard as crackle, not silence.
        runCatching {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        }
        val pcm = ShortArray(bufferSize * 2)
        try {
            while (running && generation == mine) {
                if (!renderFromSamples(pcm)) {
                    renderer.render(pcm, bufferSize, rpm, load, speedKph, throttle, scene, events)
                }
                runCatching { track?.write(pcm, 0, pcm.size) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "render loop failed", t)
            DriveApexLog.e("audio", "render loop failed; sound stopped, app kept running", t)
            if (generation != mine) return
            running = false
            runCatching { track?.let { it.pause(); it.flush(); it.stop(); it.release() } }
            track = null
        }
    }
}
