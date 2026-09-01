package com.driveapex.audio

import android.content.Context
import com.driveapex.diag.DriveApexLog
import com.driveapex.vehicle.UdpTelemetryReceiver

/**
 * The parts of DriveApex that have to outlive the screen.
 *
 * The engine, the telemetry receiver and the controller between them used to
 * belong to the drive Activity, which meant the sound belonged to the Activity
 * too: the head unit sends the app to the background constantly -- a navigation
 * prompt, the OEM launcher, the screen blanking -- and every one of those took
 * the engine with it.
 *
 * They live here instead, owned by the process rather than by a screen. The
 * Activity draws them; [com.driveapex.DriveSoundService] keeps them running and
 * keeps the process alive while they do. Neither owns them.
 *
 * A singleton rather than an injected graph because there is exactly one engine
 * in one car, and the alternative was passing the same instance through a
 * binder for no gain.
 */
object DriveSoundPipeline {

    val engine = LayeredSoundEngine(EngineCharacters.default)
    val controller = EngineSoundController(engine)

    @Volatile private var receiver: UdpTelemetryReceiver? = null
    @Volatile private var feeder: Thread? = null
    @Volatile private var feeding = false

    /**
     * Whether the vehicle, rather than the simulator, is driving the sound.
     *
     * The feed loop only applies live telemetry; in TEST mode the screen's own
     * sliders are the source and applying both would have them fighting over
     * every value.
     */
    @Volatile var liveMode = false

    /** True while the driver has asked for sound, whatever screen is in front. */
    @Volatile var soundRequested = false
        private set

    /**
     * The scene the feed loop last put the engine in.
     *
     * The screen needs it to print, but must not compute it: the controller
     * carries smoothing state, and two threads calling apply() on it -- the feed
     * loop and the screen's poller, on the same frame -- is two writers on one
     * filter. So the loop applies and the screen reads what it decided.
     */
    @Volatile var scene: AudioScene = AudioScene.IDLE
        private set

    fun telemetry(context: Context): UdpTelemetryReceiver {
        receiver?.let { return it }
        synchronized(this) {
            receiver?.let { return it }
            val created = UdpTelemetryReceiver(context = context.applicationContext)
            receiver = created
            return created
        }
    }

    /** Starts the sound, and the loop that keeps feeding it the car's numbers. */
    fun startSound(context: Context) {
        soundRequested = true
        engine.attach(context)
        engine.setOutputChannel(AudioOutputChannel.load(context))
        engine.start()
        startFeeding(context)
    }

    fun stopSound() {
        soundRequested = false
        stopFeeding()
        engine.stop()
    }

    /**
     * Feeds the engine from the vehicle, off the main thread.
     *
     * Its own thread rather than a Handler on the main looper: this has to keep
     * running while no Activity exists, and the main looper of a backgrounded
     * app is the first thing the head unit stops scheduling generously.
     */
    private fun startFeeding(context: Context) {
        if (feeding) return
        val source = telemetry(context)
        source.start()
        feeding = true
        val thread = Thread({
            while (feeding) {
                runCatching {
                    if (liveMode) source.latest()?.let { scene = controller.apply(it.data) }
                }
                runCatching { Thread.sleep(50L) }
            }
        }, "DriveApex-Feed").apply { isDaemon = true }
        feeder = thread
        thread.start()
        DriveApexLog.i("pipeline", "feed loop started")
    }

    private fun stopFeeding() {
        feeding = false
        feeder = null
        DriveApexLog.i("pipeline", "feed loop stopped")
    }

    /** The full shutdown, for when the service itself is going away. */
    fun shutdown() {
        stopSound()
        receiver?.stop()
    }
}
