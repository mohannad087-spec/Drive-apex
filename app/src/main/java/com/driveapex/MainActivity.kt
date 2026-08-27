package com.driveapex

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.driveapex.audio.ApexSoundProfile
import com.driveapex.audio.ETronInspiredSoundProfile
import com.driveapex.audio.EngineSoundController
import com.driveapex.audio.LayeredSoundEngine
import com.driveapex.audio.SonicGenomeSession
import com.driveapex.update.BydAdbSetup
import com.driveapex.update.UpdateManager
import com.driveapex.vehicle.BydTelemetryDiagnostics
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

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
            setBackgroundColor(BG)
        }
        scroll.addView(root)

        // Header
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(label("DRIVE APEX", 25f, Color.WHITE, true))
        brand.addView(label("Yuan Plus 2023  •  Sonic Control", 12f, MUTED, false))
        header.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        modeButton = actionButton("LIVE", 0xFF12352A.toInt(), GREEN)
        modeButton.setOnClickListener { toggleTelemetryMode() }
        header.addView(modeButton, LinearLayout.LayoutParams(dp(82), dp(44)))
        root.addView(header, margin(bottom = 18))

        // Main hero card
        val hero = card()
        val heroTop = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        sceneValue = label("IDLE", 13f, BLUE, true)
        heroTop.addView(sceneValue, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sourceValue = label("SIMULATOR", 11f, MUTED, true)
        heroTop.addView(sourceValue)
        hero.addView(heroTop)

        hero.addView(label("DRIVE SOUND", 11f, MUTED, true).apply { setPadding(0, dp(14), 0, 0) })
        rpmValue = label("700", 62f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setIncludeFontPadding(false)
        }
        hero.addView(rpmValue, LinearLayout.LayoutParams.MATCH_PARENT, dp(72))
        hero.addView(label("RPM", 11f, MUTED, true).apply { gravity = Gravity.CENTER_HORIZONTAL })

        telemetry = label("0 km/h     •     Throttle 0%     •     Regen 0%", 13f, SOFT, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        hero.addView(telemetry)

        signatureValue = label("BALANCED  •  AGG 0%  •  SMOOTH 0%", 11f, PURPLE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        hero.addView(signatureValue)
        root.addView(hero, margin(bottom = 14))

        // Live status strip
        val liveCard = card(padding = 14)
        val liveRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val liveDot = label("●", 13f, GREEN, true)
        liveRow.addView(liveDot, LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.WRAP_CONTENT))
        liveDiagnosticsValue = label("LIVE READY  •  VEHICLE DATA STANDBY", 11f, SOFT, true)
        liveRow.addView(liveDiagnosticsValue, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        liveCard.addView(liveRow)
        root.addView(liveCard, margin(bottom = 18))

        // Drive controls
        root.addView(sectionTitle("DRIVE CONTROLS", "Live values remain linked to the existing vehicle/audio pipeline."))
        root.addView(controlCard("RPM", rpmBar = seek(6300, 200)), margin(bottom = 10))
        root.addView(controlCard("THROTTLE", throttleBar = seek(100, 10)), margin(bottom = 10))
        root.addView(controlCard("SPEED  /  km/h", speedBar = seek(240, 0)), margin(bottom = 18))

        // Quick scenes
        root.addView(sectionTitle("QUICK SCENES", "One-tap driving situations for tuning."))
        val scenes = horizontalRow()
        listOf(
            "IDLE" to { setControls(900, 5, 0, 0, 0) },
            "PULL" to { setControls(3000, 65, 45, 0, 0) },
            "BOOST" to { setControls(5200, 95, 120, 0, 0) },
            "COAST" to { setControls(2200, 0, 80, 0, 0) },
            "REGEN" to { setControls(1600, 0, 40, 15, 100) }
        ).forEach { (title, action) ->
            scenes.addView(chipButton(title, action), LinearLayout.LayoutParams(dp(94), dp(52)).apply { marginEnd = dp(8) })
        }
        root.addView(scenes, margin(bottom = 18))

        // Sound profiles
        root.addView(sectionTitle("SOUND DNA", "Choose the acoustic character without changing telemetry."))
        val profiles = horizontalRow()
        profiles.addView(profileCard("EV GT", "Electric GT", BLUE) {
            engine.setLayers(ETronInspiredSoundProfile.layers)
        }, LinearLayout.LayoutParams(dp(156), dp(92)).apply { marginEnd = dp(10) })
        profiles.addView(profileCard("APEX", "Performance", PURPLE) {
            engine.setLayers(ApexSoundProfile.layers)
        }, LinearLayout.LayoutParams(dp(156), dp(92)))
        root.addView(profiles, margin(bottom = 18))

        // Primary actions
        startButton = primaryButton("START DRIVE SOUND")
        startButton.setOnClickListener {
            engine.start()
            if (!liveMode) syncSimulator()
            startButton.text = "DRIVE SOUND RUNNING"
        }
        root.addView(startButton, margin(bottom = 10))

        val stop = secondaryButton("STOP / SAFE")
        stop.setOnClickListener {
            genomeSession.finishDrive()
            engine.stop()
            startButton.text = "START DRIVE SOUND"
            sceneValue.text = "SAFE / STOPPED"
            eventValue.text = "EVENTS  L:0  A:0  O:0  R:0  B:0  S:0"
        }
        root.addView(stop, margin(bottom = 18))

        // Advanced / service tools kept out of the main visual hierarchy.
        root.addView(sectionTitle("SERVICE", "Diagnostics and maintenance tools."))
        val service = card(padding = 12)
        service.addView(serviceButton("BYD ADB SETUP / AUTHORIZE") {
            runAdbSetup(forceOpen = true)
        }, margin(bottom = 8))
        service.addView(serviceButton("BYD TELEMETRY DIAGNOSTICS") {
            showBydDiagnostics()
        }, margin(bottom = 8))
        service.addView(serviceButton("RESET SONIC GENOME") {
            genomeSession.reset()
            if (!liveMode) syncSimulator()
        }, margin(bottom = 8))
        service.addView(serviceButton("CHECK FOR UPDATE") {
            updateManager.checkManually()
        })
        root.addView(service, margin(bottom = 12))

        eventValue = label("EVENTS  L:0  A:0  O:0  R:0  B:0  S:0", 1f, Color.TRANSPARENT, false)
        eventValue.visibility = View.GONE

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
        handler.postDelayed({ BydAdbSetup.prepare(this, forceOpen = false) }, 500L)
        handler.postDelayed({ updateManager.checkSilently() }, 1500L)
    }

    private fun runAdbSetup(forceOpen: Boolean) {
        val result = BydAdbSetup.prepare(this, forceOpen)
        val message = when (result) {
            BydAdbSetup.Result.ALREADY_AVAILABLE -> "ADB port 5555 is available. The app is attempting the persistent ADB key connection."
            BydAdbSetup.Result.SETTINGS_OPENED -> "BYD ADB settings opened. Enable ADB over Wi-Fi. Android will show the RSA authorization dialog for this app's key the first time the connection is accepted."
            BydAdbSetup.Result.SETTINGS_UNAVAILABLE -> "BYD Development Tools ADB settings activity was not found. Enable wireless ADB manually, then retry."
        }
        liveDiagnosticsValue.text = "ADB: ${result.name}"
        AlertDialog.Builder(this)
            .setTitle("BYD ADB setup")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showBydDiagnostics() {
        Thread {
            val report = BydTelemetryDiagnostics.probe(this)
            val message = BydTelemetryDiagnostics.format(report)
            handler.post {
                if (isFinishing || isDestroyed) return@post
                AlertDialog.Builder(this)
                    .setTitle("BYD telemetry probe")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }.start()
    }

    private fun toggleTelemetryMode() {
        liveMode = !liveMode
        if (liveMode) {
            udpReceiver.start()
            modeButton.text = "LIVE"
            sourceValue.text = "LIVE VEHICLE"
            sourceValue.setTextColor(GREEN)
            rpmBar.isEnabled = false
            throttleBar.isEnabled = false
            speedBar.isEnabled = false
            updateLiveDiagnostics()
            handler.post(livePoller)
        } else {
            udpReceiver.stop()
            handler.removeCallbacks(livePoller)
            modeButton.text = "TEST"
            sourceValue.text = "SIMULATOR"
            sourceValue.setTextColor(MUTED)
            liveDiagnosticsValue.text = "LIVE READY  •  VEHICLE DATA STANDBY"
            liveDiagnosticsValue.setTextColor(SOFT)
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
            "LIVE %s  •  PKT %d  •  INVALID %d  •  AGE %s",
            if (d.packetCount > 0 && d.ageMs <= 250L) "CONNECTED" else "WAITING",
            d.packetCount,
            d.invalidPacketCount,
            ageText
        )
        liveDiagnosticsValue.setTextColor(if (d.packetCount > 0 && d.ageMs <= 250L) GREEN else AMBER)
    }

    private fun showNoVehicleData() {
        rpmValue.text = "—"
        telemetry.text = "Waiting for verified vehicle telemetry"
        sceneValue.text = "LIVE WAIT"
    }

    private fun applyTelemetry(packet: LiveTelemetry) {
        val data = packet.data
        val scene = controller.apply(data)
        val genome = genomeSession.update(data)
        val signature = genome.toSignature()
        val events = controller.events()
        rpmValue.text = formatRpm(data.rpm)
        telemetry.text = String.format(
            Locale.US,
            "%.0f km/h     •     Throttle %d%%     •     Regen %d%%",
            data.speedKph,
            (data.throttle * 100).toInt(),
            (data.regen * 100).toInt()
        )
        sceneValue.text = scene.name.replace('_', ' ')
        signatureValue.text = String.format(
            Locale.US,
            "%s  •  AGG %d%%  •  SMOOTH %d%%",
            signature.label(),
            (signature.aggression * 100).toInt(),
            (signature.smoothness * 100).toInt()
        )
        eventValue.text = String.format(
            Locale.US,
            "EVENTS  L:%d  A:%d  O:%d  R:%d  B:%d  S:%d",
            (events.launch * 100).toInt(),
            (events.accelerationHit * 100).toInt(),
            (events.liftOff * 100).toInt(),
            (events.regenerationHit * 100).toInt(),
            (events.brakeHit * 100).toInt(),
            (events.speedRush * 100).toInt()
        )
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
        rpmBar.progress = (rpm - 700).coerceIn(0, rpmBar.max)
        throttleBar.progress = throttle.coerceIn(0, throttleBar.max)
        speedBar.progress = speed.coerceIn(0, speedBar.max)
        vehicle.setBrake((brake / 100f).coerceIn(0f, 1f))
        vehicle.setRegen((regen / 100f).coerceIn(0f, 1f))
        if (!liveMode) applyTelemetry(LiveTelemetry(vehicle.current(), TelemetrySource.SIMULATOR))
    }

    private fun sectionTitle(title: String, subtitle: String): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(label(title, 13f, Color.WHITE, true))
        box.addView(label(subtitle, 11f, MUTED, false).apply { setPadding(0, dp(3), 0, dp(8)) })
        return box
    }

    private fun controlCard(title: String, rpmBar: SeekBar? = null, throttleBar: SeekBar? = null, speedBar: SeekBar? = null): LinearLayout {
        val bar = rpmBar ?: throttleBar ?: speedBar ?: seek(100, 0)
        val box = card(padding = 12)
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(title, 11f, MUTED, true), LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(bar, LinearLayout.LayoutParams(0, dp(42), 1f))
        box.addView(row)
        return box
    }

    private fun profileCard(name: String, subtitle: String, accent: Int, action: () -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(PANEL, dp(14), STROKE)
            setOnClickListener { action() }
        }
        box.addView(label(name, 18f, Color.WHITE, true))
        box.addView(label(subtitle, 11f, MUTED, false).apply { setPadding(0, dp(3), 0, 0) })
        box.addView(label("●  READY", 10f, accent, true).apply { setPadding(0, dp(8), 0, 0) })
        return box
    }

    private fun chipButton(text: String, action: () -> Unit): Button = actionButton(text, PANEL, SOFT).apply {
        setOnClickListener { action() }
    }

    private fun primaryButton(text: String): Button = actionButton(text, BLUE, Color.WHITE).apply {
        textSize = 14f
        minHeight = dp(56)
    }

    private fun secondaryButton(text: String): Button = actionButton(text, PANEL, SOFT).apply {
        textSize = 12f
        minHeight = dp(50)
    }

    private fun serviceButton(text: String, action: () -> Unit): Button = actionButton(text, PANEL_2, MUTED).apply {
        textSize = 11f
        minHeight = dp(46)
        setOnClickListener { action() }
    }

    private fun actionButton(text: String, backgroundColor: Int, textColor: Int): Button = Button(this).apply {
        this.text = text
        this.textSize = 11f
        this.setTextColor(textColor)
        this.isAllCaps = false
        this.background = rounded(backgroundColor, dp(12), STROKE)
        this.stateListAnimator = null
        this.setPadding(dp(8), 0, dp(8), 0)
    }

    private fun card(padding: Int = 18): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        background = rounded(PANEL, dp(16), STROKE)
    }

    private fun horizontalRow(): HorizontalScrollView = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL })
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = if (bold) android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD) else android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL)
    }

    private fun seek(max: Int, progress: Int): SeekBar = SeekBar(this).apply {
        this.max = max
        this.progress = progress.coerceIn(0, max)
        minHeight = dp(42)
        progressTintList = ColorStateList.valueOf(BLUE)
        thumbTintList = ColorStateList.valueOf(Color.WHITE)
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius.toFloat()
        setStroke(dp(1), stroke)
    }

    private fun margin(bottom: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(bottom) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun formatRpm(rpm: Float): String = String.format(Locale.US, "%,.0f", rpm)

    override fun onResume() {
        super.onResume()
        if (::updateManager.isInitialized) updateManager.onResume()
    }

    override fun onStop() {
        handler.removeCallbacks(livePoller)
        genomeSession.finishDrive()
        udpReceiver.stop()
        engine.stop()
        super.onStop()
    }

    companion object {
        private const val BG = 0xFF07090C.toInt()
        private const val PANEL = 0xFF10151B.toInt()
        private const val PANEL_2 = 0xFF0C1116.toInt()
        private const val STROKE = 0xFF26303A.toInt()
        private const val BLUE = 0xFF1D9BF0.toInt()
        private const val GREEN = 0xFF35D07F.toInt()
        private const val PURPLE = 0xFFA778FF.toInt()
        private const val AMBER = 0xFFFFB74D.toInt()
        private const val SOFT = 0xFFE5EAF0.toInt()
        private const val MUTED = 0xFF8995A3.toInt()
    }
}
