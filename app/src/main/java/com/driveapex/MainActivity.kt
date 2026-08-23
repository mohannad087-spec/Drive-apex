package com.driveapex

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.driveapex.audio.ApexSoundProfile
import com.driveapex.audio.ETronInspiredSoundProfile
import com.driveapex.audio.EngineSoundController
import com.driveapex.audio.LayeredSoundEngine
import com.driveapex.audio.SonicGenomeSession
import com.driveapex.update.UpdateManager
import com.driveapex.vehicle.LiveTelemetry
import com.driveapex.vehicle.SimulatorVehicleDataProvider
import com.driveapex.vehicle.TelemetrySource
import com.driveapex.vehicle.UdpTelemetryReceiver
import java.util.Locale

class MainActivity : Activity() {
    private val engine = LayeredSoundEngine(ETronInspiredSoundProfile.layers)
    private val vehicle = SimulatorVehicleDataProvider()
    private val controller = EngineSoundController(engine)
    private lateinit var genomeSession: SonicGenomeSession
    private lateinit var updateManager: UpdateManager
    private lateinit var udpReceiver: UdpTelemetryReceiver
    private val handler = Handler(Looper.getMainLooper())
    private var liveMode = false

    private lateinit var rpmValue: TextView
    private lateinit var telemetry: TextView
    private lateinit var sceneValue: TextView
    private lateinit var sourceValue: TextView
    private lateinit var liveDiagnosticsValue: TextView
    private lateinit var signatureValue: TextView
    private lateinit var genomeValue: TextView
    private lateinit var eventValue: TextView
    private lateinit var startButton: Button
    private lateinit var modeButton: Button
    private lateinit var rpmBar: SeekBar
    private lateinit var throttleBar: SeekBar
    private lateinit var speedBar: SeekBar

    private val livePoller = object : Runnable {
        override fun run() {
            if (liveMode) {
                updateLiveDiagnostics()
                udpReceiver.latest()?.let { applyTelemetry(it) } ?: showNoVehicleData()
                handler.postDelayed(this, 100L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        genomeSession = SonicGenomeSession(this)
        updateManager = UpdateManager(this)
        udpReceiver = UdpTelemetryReceiver(context = this)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 28)
            setBackgroundColor(Color.rgb(8, 10, 13))
        }
        scroll.addView(root)

        root.addView(label("DRIVE APEX", 28f, Color.WHITE))
        root.addView(label("PHONE / VEHICLE TEST LAB", 13f, Color.LTGRAY).apply { setPadding(0, 0, 0, 18) })
        sourceValue = label("SOURCE: SIMULATOR", 13f, Color.rgb(110, 210, 255))
        root.addView(sourceValue, marginParams(bottom = 6))
        liveDiagnosticsValue = label("LIVE: NOT CONNECTED", 12f, Color.rgb(255, 180, 70))
        root.addView(liveDiagnosticsValue, marginParams(bottom = 10))
        modeButton = Button(this).apply {
            text = "SWITCH TO LIVE"
            setOnClickListener { toggleTelemetryMode() }
        }
        root.addView(modeButton, marginParams(bottom = 14))

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(20, 22, 20, 22)
            setBackgroundColor(Color.rgb(18, 22, 28))
        }
        sceneValue = label("SCENE: IDLE", 14f, Color.rgb(255, 179, 0))
        statusCard.addView(sceneValue)
        rpmValue = label("700 RPM", 52f, Color.WHITE)
        statusCard.addView(rpmValue)
        telemetry = label("0 km/h  •  Throttle 0%  •  Regen 0%", 14f, Color.LTGRAY)
        statusCard.addView(telemetry)
        signatureValue = label("SONIC SIGNATURE: BALANCED", 13f, Color.rgb(180, 130, 255))
        signatureValue.setPadding(0, 10, 0, 0)
        statusCard.addView(signatureValue)
        genomeValue = label("GENOME: NEW  •  MATURITY 0%", 12f, Color.rgb(110, 210, 255))
        genomeValue.setPadding(0, 8, 0, 0)
        statusCard.addView(genomeValue)
        eventValue = label("EVENTS  L:0  A:0  O:0  R:0  B:0  S:0", 12f, Color.rgb(120, 220, 180))
        eventValue.setPadding(0, 8, 0, 0)
        statusCard.addView(eventValue)
        root.addView(statusCard, marginParams(bottom = 18))

        root.addView(label("RPM", 14f, Color.LTGRAY))
        rpmBar = seek(6300, 200)
        root.addView(rpmBar, marginParams(bottom = 14))
        root.addView(label("THROTTLE", 14f, Color.LTGRAY))
        throttleBar = seek(100, 10)
        root.addView(throttleBar, marginParams(bottom = 14))
        root.addView(label("SPEED  km/h", 14f, Color.LTGRAY))
        speedBar = seek(240, 0)
        root.addView(speedBar, marginParams(bottom = 16))

        root.addView(label("QUICK SCENES", 14f, Color.LTGRAY))
        val scenes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "IDLE" to { setControls(900, 5, 0, 0, 0) },
            "PULL" to { setControls(3000, 65, 45, 0, 0) },
            "BOOST" to { setControls(5200, 95, 120, 0, 0) },
            "COAST" to { setControls(2200, 0, 80, 0, 0) },
            "REGEN" to { setControls(1600, 0, 40, 15, 100) }
        ).forEach { (title, action) ->
            scenes.addView(Button(this).apply {
                text = title
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, 56).apply { weight = 1f; marginEnd = 8 })
        }
        root.addView(scenes, marginParams(bottom = 18))

        root.addView(label("SOUND CHARACTER", 14f, Color.LTGRAY))
        val profiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        profiles.addView(Button(this).apply {
            text = "EV GT"
            setOnClickListener { engine.setLayers(ETronInspiredSoundProfile.layers) }
        }, LinearLayout.LayoutParams(0, 60).apply { weight = 1f; marginEnd = 8 })
        profiles.addView(Button(this).apply {
            text = "APEX"
            setOnClickListener { engine.setLayers(ApexSoundProfile.layers) }
        }, LinearLayout.LayoutParams(0, 60).apply { weight = 1f })
        root.addView(profiles, marginParams(bottom = 16))

        startButton = Button(this).apply {
            text = "START DRIVE SOUND"
            setOnClickListener {
                engine.start()
                syncSimulator()
                text = "DRIVE SOUND RUNNING"
            }
        }
        root.addView(startButton, marginParams(bottom = 8))

        root.addView(Button(this).apply {
            text = "RESET SONIC GENOME"
            setOnClickListener { genomeSession.reset(); syncSimulator() }
        }, marginParams(bottom = 8))

        root.addView(Button(this).apply {
            text = "CHECK FOR UPDATE"
            setOnClickListener { updateManager.checkManually() }
        }, marginParams(bottom = 8))

        root.addView(Button(this).apply {
            text = "STOP / SAFE"
            setOnClickListener {
                genomeSession.finishDrive()
                engine.stop()
                startButton.text = "START DRIVE SOUND"
                sceneValue.text = "SCENE: SAFE / STOPPED"
                eventValue.text = "EVENTS  L:0  A:0  O:0  R:0  B:0  S:0"
            }
        })

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !liveMode) syncSimulator()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        rpmBar.setOnSeekBarChangeListener(listener)
        throttleBar.setOnSeekBarChangeListener(listener)
        speedBar.setOnSeekBarChangeListener(listener)

        syncSimulator()
        setContentView(scroll)
        handler.postDelayed({ updateManager.checkSilently() }, 1500L)
    }

    override fun onResume() {
        super.onResume()
        if (::updateManager.isInitialized) updateManager.onResume()
    }

    private fun toggleTelemetryMode() {
        liveMode = !liveMode
        if (liveMode) {
            udpReceiver.start()
            modeButton.text = "SWITCH TO SIMULATOR"
            sourceValue.text = "SOURCE: LIVE VEHICLE / UDP FALLBACK"
            sourceValue.setTextColor(Color.rgb(90, 230, 150))
            rpmBar.isEnabled = false
            throttleBar.isEnabled = false
            speedBar.isEnabled = false
            updateLiveDiagnostics()
            handler.post(livePoller)
        } else {
            udpReceiver.stop()
            handler.removeCallbacks(livePoller)
            modeButton.text = "SWITCH TO LIVE"
            sourceValue.text = "SOURCE: SIMULATOR"
            sourceValue.setTextColor(Color.rgb(110, 210, 255))
            liveDiagnosticsValue.text = "LIVE: NOT CONNECTED"
            liveDiagnosticsValue.setTextColor(Color.rgb(255, 180, 70))
            rpmBar.isEnabled = true
            throttleBar.isEnabled = true
            speedBar.isEnabled = true
            syncSimulator()
        }
    }

    private fun updateLiveDiagnostics() {
        val d = udpReceiver.diagnostics()
        val ageText = if (d.ageMs == Long.MAX_VALUE) "--" else "${d.ageMs}ms"
        liveDiagnosticsValue.text = String.format(
            Locale.US,
            "LIVE: %s  •  PKT %d  •  INVALID %d  •  AGE %s  •  SRC %s",
            if (d.packetCount > 0 && d.ageMs <= 250L) "VALID" else "NO VEHICLE DATA",
            d.packetCount,
            d.invalidPacketCount,
            ageText,
            d.source
        )
        liveDiagnosticsValue.setTextColor(if (d.packetCount > 0 && d.ageMs <= 250L) Color.rgb(90, 230, 150) else Color.rgb(255, 100, 100))
    }

    private fun showNoVehicleData() {
        rpmValue.text = "NO VEHICLE DATA"
        telemetry.text = "Waiting for verified live telemetry…"
        sceneValue.text = "SCENE: LIVE WAIT"
    }

    private fun applyTelemetry(packet: LiveTelemetry) {
        val data = packet.data
        val scene = controller.apply(data)
        val genome = genomeSession.update(data)
        val signature = genome.toSignature()
        val events = controller.events()
        rpmValue.text = formatRpm(data.rpm)
        telemetry.text = String.format(Locale.US, "%.0f km/h  •  Throttle %d%%  •  Regen %d%%", data.speedKph, (data.throttle * 100).toInt(), (data.regen * 100).toInt())
        sceneValue.text = "SCENE: ${scene.name.replace('_', ' ')}"
        signatureValue.text = String.format(Locale.US, "SONIC SIGNATURE: %s   •   AGG %d%%   •   SMOOTH %d%%", signature.label(), (signature.aggression * 100).toInt(), (signature.smoothness * 100).toInt())
        genomeValue.text = String.format(Locale.US, "GENOME: %s   •   MATURITY %d%%   •   OBS %d", signature.label(), (genome.maturity * 100).toInt(), genome.observations.coerceAtMost(999_999L))
        eventValue.text = String.format(Locale.US, "EVENTS  L:%d  A:%d  O:%d  R:%d  B:%d  S:%d", (events.launch * 100).toInt(), (events.accelerationHit * 100).toInt(), (events.liftOff * 100).toInt(), (events.regenerationHit * 100).toInt(), (events.brakeHit * 100).toInt(), (events.speedRush * 100).toInt())
    }

    private fun syncSimulator() {
        vehicle.setRpm((700 + rpmBar.progress).toFloat())
        vehicle.setThrottle(throttleBar.progress / 100f)
        vehicle.setSpeed(speedBar.progress.toFloat())
        vehicle.setBrake(0f)
        vehicle.setRegen(0f)
        applyTelemetry(LiveTelemetry(vehicle.current(), TelemetrySource.SIMULATOR))
    }

    private fun setControls(rpm: Int, throttle: Int, speed: Int, brake: Int, regen: Int) {
        rpmBar.progress = rpm - 700
        throttleBar.progress = throttle
        speedBar.progress = speed
        vehicle.setBrake(brake / 100f)
        vehicle.setRegen(regen / 100f)
        if (!liveMode) applyTelemetry(LiveTelemetry(vehicle.current(), TelemetrySource.SIMULATOR))
    }

    private fun formatRpm(rpm: Float): String = String.format(Locale.US, "%,.0f RPM", rpm)
    private fun label(text: String, size: Float, color: Int): TextView = TextView(this).apply { this.text = text; textSize = size; setTextColor(color) }
    private fun seek(max: Int, progress: Int): SeekBar = SeekBar(this).apply { this.max = max; this.progress = progress; minHeight = 52 }
    private fun marginParams(bottom: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { this.bottomMargin = bottom }

    override fun onStop() {
        handler.removeCallbacks(livePoller)
        genomeSession.finishDrive()
        udpReceiver.stop()
        engine.stop()
        super.onStop()
    }
}
