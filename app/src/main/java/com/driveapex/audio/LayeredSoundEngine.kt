package com.driveapex.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
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

        /**
         * Output trim for the granular voice.
         *
         * The recordings are peak normalised to 0.97 and two half-overlapped
         * Hann windows sum to exactly one, so the grain stream peaks at very
         * nearly the recording's own peak. This is the headroom under it.
         */
        private const val GRAIN_LEVEL = 0.75f
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

    // The granular voice: a real rev recording read at whatever rpm is asked
    // for, which is how racing games sound like cars. Inert until a source is
    // loaded, so an APK without one behaves exactly as before.
    private val grainVoice = GranularVoiceRenderer(SAMPLE_RATE)

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

    /** The sample path's own mapped rpm, for the dial to read. */
    @Volatile private var lastSampleRpm = 0f

    /**
     * The app context, for audio focus and for reading a channel's volume.
     *
     * Optional on purpose: the engine still plays without it, on the legacy
     * path, which is what every earlier version did.
     */
    @Volatile private var appContext: Context? = null
    private var focusRequest: AudioFocusRequest? = null

    /** What happened the last time a track was built, and whether it was heard. */
    @Volatile private var report: ChannelReport? = null

    /**
     * The outcome of putting sound on a channel.
     *
     * `advanced` is the honest part: a track can be created, accepted and left
     * in PLAYING state while its playback head never moves, which is what a
     * channel that routes nowhere looks like from inside the app. Anything else
     * here is a guess about whether the driver can hear it; this is a
     * measurement.
     */
    data class ChannelReport(
        val channel: AudioOutputChannel,
        val stream: Int,
        val builtFromAttributes: Boolean,
        val focusGranted: Boolean?,
        val volume: Int,
        val maxVolume: Int,
        val playing: Boolean,
        val advanced: Boolean
    ) {
        /** One line a driver can act on. */
        fun summary(): String = when {
            !playing -> "${channel.label}: the car refused the track."
            volume == 0 -> "${channel.label}: playing, but this channel's volume is 0 on the car."
            !advanced -> "${channel.label}: accepted but not playing out. This car does not route it."
            else -> "${channel.label}: playing" + if (maxVolume > 0) " (volume $volume/$maxVolume)." else "."
        }
    }

    /** Lets the engine ask the car for focus and read channel volumes. */
    fun attach(context: Context) { appContext = context.applicationContext }

    fun lastChannelReport(): ChannelReport? = report

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
        // A recording brings its own gear count -- six, as asked for on the
        // uploaded voices -- rather than inheriting the eight belonging to
        // whichever synthesised character happened to be selected before it.
        val box = if (playingRecording()) EngineCharacter.Gearbox.sixSpeed() else base.gearbox
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
        if (playingRecording()) EngineCharacter.Gearbox.sixSpeed() else baseCharacter.gearbox

    /** True while either recording voice is the one making the sound. */
    private fun playingRecording(): Boolean = grainVoice.isReady() || sampleVoice.isReady()

    /**
     * Plays real recordings instead of synthesising, when a playable bank is
     * given. Passing null returns the engine to synthesis.
     */
    fun setSampleBank(bank: EngineSampleBank?) {
        sampleVoice.setBank(bank)
        if (bank != null) grainVoice.setSource(null)
        applyVoice()
    }

    /**
     * Plays a rev recording granularly instead of synthesising.
     *
     * Passing null returns the engine to whatever else is loaded. Setting one
     * clears the sample bank: they are two ways of playing recordings and
     * running both would be two engines at once.
     */
    fun setGrainSource(source: GrainSource?) {
        grainVoice.setSource(source)
        if (source != null) sampleVoice.setBank(null)
        applyVoice()
    }

    fun grainSource(): GrainSource? = grainVoice.currentSource()

    fun sampleBank(): EngineSampleBank? = sampleVoice.currentBank()

    fun character(): EngineCharacter = renderer.currentCharacter()

    /**
     * The rpm the voice is sounding, after the gearbox.
     *
     * The dial shows this rather than the motor's own speed: it is the engine
     * the driver hears, and it is what a rev counter reads in a car that has a
     * gearbox between the two.
     */
    fun soundingRpm(): Float =
        if (playingRecording()) lastSampleRpm else renderer.currentVoiceRpm()

    /** Virtual gear, 1-based; 0 when the current character has no gearbox. */
    fun currentGear(): Int =
        if (playingRecording()) voiceRpm.currentGear() else renderer.currentGear()
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

        val channel = outputChannel
        val stream = channel.streamType()

        // Ask before building: on a channel the radio owns, a track built first
        // and given focus afterwards spends its opening second mixed underneath.
        val focusGranted = requestFocus(channel)

        // Two chunks in bytes: four bytes per stereo frame, times two chunks.
        val targetBuffer = maxOf(bufferSize * 4 * 2, minBuffer)
        val created = runCatching { buildTrack(channel, stream, targetBuffer) }
            .onFailure {
                Log.e(TAG, "Unable to create AudioTrack on ${channel.name} stream=$stream", it)
                DriveApexLog.e("audio", "AudioTrack create failed on ${channel.name} stream $stream", it)
            }.getOrNull() ?: run { abandonFocus(); return }

        track = created
        runCatching { created.setVolume(1f) }
        runCatching { created.play() }.onFailure {
            Log.e(TAG, "Unable to start AudioTrack on ${channel.name} stream=$stream", it)
            DriveApexLog.e("audio", "AudioTrack play failed on ${channel.name} stream $stream", it)
            runCatching { created.release() }
            track = null
            abandonFocus()
            return
        }

        val (volume, maxVolume) = appContext?.let { channel.volume(it) } ?: (-1 to -1)
        DriveApexLog.i("audio",
            "AudioTrack on ${channel.name}: stream $stream, " +
                (if (channel.usage != null) "attributes usage ${channel.usage}" else "legacy stream") +
                ", focus ${focusGranted ?: "not asked"}, volume $volume/$maxVolume, buffer $targetBuffer bytes")
        Log.i(TAG, "AudioTrack started on ${channel.name} stream=$stream")

        report = ChannelReport(
            channel = channel, stream = stream,
            builtFromAttributes = channel.usage != null,
            focusGranted = focusGranted,
            volume = volume, maxVolume = maxVolume,
            // Both are settled by the render loop once it has written enough to
            // tell; until then the honest answer is the optimistic one.
            playing = true, advanced = true
        )

        running = true
        val mine = ++generation
        Thread({ renderLoop(mine) }, "DriveApex-LayeredAudio").apply { isDaemon = true }.start()
    }

    /**
     * A track on this channel, built the way the channel needs.
     *
     * Navigation keeps the legacy stream constructor because 14 is BYD's own
     * number and no AudioAttributes usage maps onto it; every other channel is
     * described by what it is for, which is what an automotive HAL routes by.
     */
    private fun buildTrack(channel: AudioOutputChannel, stream: Int, bytes: Int): AudioTrack {
        val usage = channel.usage ?: return legacyTrack(stream, bytes)
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(attributesFor(usage))
            .setAudioFormat(format)
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun legacyTrack(stream: Int, bytes: Int) = AudioTrack(
        stream, sampleRate,
        AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        bytes, AudioTrack.MODE_STREAM
    )

    private fun attributesFor(usage: Int): AudioAttributes = AudioAttributes.Builder()
        .setUsage(usage)
        // Sonification rather than music: this is a sound the car is making, and
        // on a HAL that distinguishes them it is the honest label.
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * Takes audio focus for channels that need it.
     *
     * Returns null when the channel asks for none, true or false otherwise. A
     * refusal is not fatal -- the track still plays, usually underneath
     * whatever holds focus -- so it is recorded rather than acted on.
     */
    private fun requestFocus(channel: AudioOutputChannel): Boolean? {
        val gain = channel.focusGain ?: return null
        val usage = channel.usage ?: return null
        val context = appContext ?: return null
        val manager = runCatching {
            context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }.getOrNull() ?: return null
        abandonFocus()
        return runCatching {
            val request = AudioFocusRequest.Builder(gain)
                .setAudioAttributes(attributesFor(usage))
                // The engine does not stop for anything else; it is the sound of
                // the car moving. Ducking it is the car's business, not ours.
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener { }
                .build()
            focusRequest = request
            manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.getOrDefault(false)
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        val context = appContext ?: return
        runCatching {
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .abandonAudioFocusRequest(request)
        }
    }

    fun stop() {
        running = false
        // Retires the current loop even if start() is called again immediately.
        generation++
        abandonFocus()
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
        if (!grainVoice.isReady() && !sampleVoice.isReady()) return false
        voiceClock += (bufferSize * 1000L) / sampleRate
        val mapped = voiceRpm.map(rpm, throttle, speedKph, bufferSize, sampleRate, voiceClock)
        lastSampleRpm = mapped.rpm

        val played = if (grainVoice.isReady()) {
            // The recording's own rev range is mapped onto the voice's, so the
            // bottom of the recording sounds at idle and the top at the
            // limiter. Those come from the box actually in use rather than from
            // the character, which may be a synthesised one left selected.
            val box = activeGearbox()
            grainVoice.render(
                pcm, bufferSize, mapped.rpm, throttle, load,
                box?.idleRpm ?: 800f,
                box?.limiterRpm ?: 7200f,
                GRAIN_LEVEL * driveMode.levelScale
            )
        } else {
            sampleVoice.render(pcm, bufferSize, mapped.rpm, throttle)
        }
        if (!played) return false

        if (mapped.cutGain < 1f) {
            val gain = mapped.cutGain
            for (i in pcm.indices) pcm[i] = (pcm[i] * gain).toInt().toShort()
        }
        return true
    }

    /**
     * Records whether the channel actually took the audio.
     *
     * PLAYSTATE_PLAYING with a playback head that never moved is exactly what a
     * correctly created track on an output the car does not route looks like.
     */
    private fun settleReport() {
        val current = track ?: return
        val previous = report ?: return
        val playing = runCatching { current.playState == AudioTrack.PLAYSTATE_PLAYING }
            .getOrDefault(false)
        val advanced = runCatching { current.playbackHeadPosition > 0 }.getOrDefault(false)
        val settled = previous.copy(playing = playing, advanced = advanced)
        report = settled
        DriveApexLog.i("audio", settled.summary())
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
        var buffers = 0
        try {
            while (running && generation == mine) {
                if (!renderFromSamples(pcm)) {
                    renderer.render(pcm, bufferSize, rpm, load, speedKph, throttle, scene, events)
                }
                runCatching { track?.write(pcm, 0, pcm.size) }
                // After a quarter of a second the car has either taken these
                // buffers or it has not, and the playback head is the only thing
                // that says which. Checked once, off the first buffers, so a
                // channel that routes nowhere is reported rather than left as
                // silence the driver has to guess about.
                if (++buffers == 16) settleReport()
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
