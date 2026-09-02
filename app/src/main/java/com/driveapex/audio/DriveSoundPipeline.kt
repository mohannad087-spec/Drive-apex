package com.driveapex.audio

import android.content.Context
import com.driveapex.diag.DriveApexLog
import com.driveapex.vehicle.SimulatorVehicleDataProvider
import com.driveapex.vehicle.UdpTelemetryReceiver
import com.driveapex.vehicle.VehicleData

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

    /**
     * The desk simulator, moved here beside the vehicle.
     *
     * It used to live on the screen, and the screen only pushed its values into
     * the engine when a slider moved. That is the bug behind "the sound plays
     * but nothing changes it": in TEST the engine was fed once and then never
     * again, and in LIVE it was fed only while the sound was running. One loop
     * now feeds the engine from whichever source is selected, always.
     */
    val simulator = SimulatorVehicleDataProvider()

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

    /** The last frame the loop actually gave the engine, for the screen to show. */
    @Volatile var lastApplied: VehicleData? = null
        private set

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

    /**
     * Stops the sound, and only the sound.
     *
     * The feed loop keeps running: it costs one wake every 50ms, it is what the
     * screen reads to show whether anything is reaching the engine, and stopping
     * it here is how the readout used to freeze the moment the driver pressed
     * STOP. It ends with the process, in shutdown().
     */
    fun stopSound() {
        soundRequested = false
        engine.stop()
    }

    /**
     * Feeds the engine from the vehicle, off the main thread.
     *
     * Its own thread rather than a Handler on the main looper: this has to keep
     * running while no Activity exists, and the main looper of a backgrounded
     * app is the first thing the head unit stops scheduling generously.
     */
    fun startFeeding(context: Context) {
        if (feeding) return
        val source = telemetry(context)
        source.start()
        feeding = true
        val thread = Thread({
            var sinceLog = 0
            while (feeding) {
                runCatching {
                    // Whichever source is selected, every 50ms, whether or not
                    // the sound is running and whether or not a screen exists.
                    val data = if (liveMode) source.latest()?.data else simulator.current()
                    if (data != null) {
                        scene = controller.apply(data)
                        lastApplied = data
                        // Once a second, so the app log can answer "was the
                        // engine being fed?" without another drive.
                        if (++sinceLog >= 20) {
                            sinceLog = 0
                            DriveApexLog.i("pipeline", String.format(
                                java.util.Locale.US,
                                "fed %s rpm %.0f throttle %.2f speed %.0f -> sounding %.0f gear %d",
                                if (liveMode) "live" else "test",
                                data.rpm, data.throttle, data.speedKph,
                                engine.soundingRpm(), engine.currentGear()
                            ))
                        }
                    }
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
