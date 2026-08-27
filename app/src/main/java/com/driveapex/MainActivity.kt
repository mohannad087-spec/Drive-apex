package com.driveapex

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
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
import kotlin.math.roundToInt

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
            if (!liveMode) return
            updateLiveDiagnostics()
            udpReceiver.latest()?.let { applyTelemetry(it) } ?: showNoVehicleData()
            handler.postDelayed(this, 100L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        genomeSession = SonicGenomeSession(this)
        updateManager = UpdateManager(this)
        udpReceiver = UdpTelemetryReceiver(context = this)

        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            setBackgroundColor(BG)
        }
        scroll.addView(root)

        root.addView(header(), margin(18))
        root.addView(heroCard(), margin(12))
        root.addView(liveCard(), margin(18))

        root.addView(section("DRIVE CONTROLS", "Tune the sound response in test mode."), margin(8))
        rpmBar = seek(6300, 200)
        root.addView(controlCard("RPM", rpmBar), margin(9))
        throttleBar = seek(100, 10)
        root.addView(controlCard("THROTTLE", throttleBar), margin(9))
        speedBar = seek(240, 0)
        root.addView(controlCard("SPEED  /  km/h", speedBar), margin(18))

        root.addView(section("QUICK SCENES", "Instant driving states for acoustic tuning."), margin(8))
        root.addView(chips(
            "IDLE" to { setControls(900, 5, 0, 0, 0) },
            "PULL" to { setControls(3000, 65, 45, 0, 0) },
            "BOOST" to { setControls(5200, 95, 120, 0, 0) },
            "COAST" to { setControls(2200, 0, 80, 0, 0) },
            "REGEN" to { setControls(1600, 0, 40, 15, 100) }
        ), margin(18))

        root.addView(section("SOUND DNA", "Change character without changing vehicle telemetry."), margin(8))
        root.addView(profiles(), margin(18))

        startButton = primary("START DRIVE SOUND")
        startButton.setOnClickListener {
            engine.start()
            if (!liveMode) syncSimulator()
            startButton.text = "DRIVE SOUND RUNNING"
        }
        root.addView(startButton, margin(10))

        val stop = secondary("STOP / SAFE")
        stop.setOnClickListener {
            genomeSession.finishDrive()
            engine.stop()
            startButton.text = "START DRIVE SOUND"
            sceneValue.text = "SAFE / STOPPED"
            eventValue.text = "EVENTS  L:0  A:0  O:0  R:0  B:0  S:0"
        }
        root.addView(stop, margin(18))

        root.addView(section("SERVICE", "Diagnostics and maintenance."), margin(8))
        val service = card(12)
        service.addView(serviceButton("BYD ADB SETUP / AUTHORIZE") { runAdbSetup(true) }, margin(7))
        service.addView(serviceButton("BYD TELEMETRY DIAGNOSTICS") { showBydDiagnostics() }, margin(7))
        service.addView(serviceButton("RESET SONIC GENOME") {
            genomeSession.reset()
            if (!liveMode) syncSimulator()
        }, margin(7))
        service.addView(serviceButton("CHECK FOR UPDATE") { updateManager.checkManually() })
        root.addView(service)

        eventValue = label("EVENTS", 1f, Color.TRANSPARENT, false)

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
        handler.postDelayed({ BydAdbSetup.prepare(this, false) }, 500L)
        handler.postDelayed({ updateManager.checkSilently() }, 1500L)
    }

    private fun header(): LinearLayout {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(label("DRIVE APEX", 25f, Color.WHITE, true))
        brand.addView(label("BYD Yuan Plus 2023  •  Sonic Control", 12f, MUTED, false).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        modeButton = action("TEST", 0xFF102E26.toInt(), GREEN)
        modeButton.setOnClickListener { toggleTelemetryMode() }
        row.addView(modeButton, LinearLayout.LayoutParams(dp(86), dp(44)))
        return row
    }

    private fun heroCard(): LinearLayout {
        val box = card(18)
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        sceneValue = label("IDLE", 13f, BLUE, true)
        top.addView(sceneValue, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sourceValue = label("SIMULATOR", 11f, MUTED, true)
        top.addView(sourceValue)
        box.addView(top)
        box.addView(label("DRIVE SOUND", 11f, MUTED, true).apply { setPadding(0, dp(16), 0, 0) })
        rpmValue = label("700 RPM", 58f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setIncludeFontPadding(false)
        }
        box.addView(rpmValue, LinearLayout.LayoutParams.MATCH_PARENT, dp(78))
        telemetry = label("0 km/h   •   Throttle 0%   •   Regen 0%", 13f, SOFT, false).apply { gravity = Gravity.CENTER }
        box.addView(telemetry)
        signatureValue = label("BALANCED   •   AGG 0%   •   SMOOTH 0%", 11f, PURPLE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, 0)
        }
        box.addView(signatureValue)
        return box
    }

    private fun liveCard(): LinearLayout {
        val box = card(13)
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(label("●", 13f, GREEN, true), LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT))
        liveDiagnosticsValue = label("LIVE READY  •  VEHICLE DATA STANDBY", 11f, SOFT, true)
        row.addView(liveDiagnosticsValue, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)
        return box
    }

    private fun section(title: String, subtitle: String): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(label(title, 13f, Color.WHITE, true))
        box.addView(label(subtitle, 11f, MUTED, false).apply { setPadding(0, dp(3), 0, 0) })
        return box
    }

    private fun controlCard(title: String, bar: SeekBar): LinearLayout {
        val box = card(11)
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(title, 11f, MUTED, true), LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(bar, LinearLayout.LayoutParams(0, dp(44), 1f))
        box.addView(row)
        return box
    }

    private fun chips(vararg items: Pair<String, () -> Unit>): HorizontalScrollView {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { (text, action) ->
            row.addView(action(text, PANEL, SOFT).apply { setOnClickListener { action() } }, LinearLayout.LayoutParams(dp(94), dp(52)).apply { marginEnd = dp(8) })
        }
        scroll.addView(row)
        return scroll
    }

    private fun profiles(): HorizontalScrollView {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(profile("EV GT", "Electric GT", BLUE) { engine.setLayers(ETronInspiredSoundProfile.layers) }, LinearLayout.LayoutParams(dp(156), dp(92)).apply { marginEnd = dp(10) })
        row.addView(profile("APEX", "Performance", PURPLE) { engine.setLayers(ApexSoundProfile.layers) }, LinearLayout.LayoutParams(dp(156), dp(92)))
        scroll.addView(row)
        return scroll
    }

    private fun profile(name: String, subtitle: String, accent: Int, action: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(PANEL, dp(14), STROKE)
            setOnClickListener { action() }
            addView(label(name, 18f, Color.WHITE, true))
            addView(label(subtitle, 11f, MUTED, false).apply { setPadding(0, dp(3), 0, 0) })
            addView(label("●  READY", 10f, accent, true).apply { setPadding(0, dp(8), 0, 0) })
        }
    }

    private fun runAdbSetup(forceOpen: Boolean) {
        val result = BydAdbSetup.prepare(this, forceOpen)
        val message = when (result) {
            BydAdbSetup.Result.ALREADY_AVAILABLE -> "ADB port 5555 is available."
            BydAdbSetup.Result.SETTINGS_OPENED -> "BYD ADB settings opened. Enable wireless ADB."
            BydAdbSetup.Result.SETTINGS_UNAVAILABLE -> "BYD ADB settings activity was not found."
        }
        liveDiagnosticsValue.text = "ADB: ${result.name}"
        AlertDialog.Builder(this).setTitle("BYD ADB setup").setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun showBydDiagnostics() {
        Thread {
            val report = BydTelemetryDiagnostics.probe(this)
            val message = BydTelemetryDiagnostics.format(report)
            handler.post {
                if (!isFinishing && !isDestroyed) AlertDialog.Builder(this).setTitle("BYD telemetry probe").setMessage(message).setPositiveButton("OK", null).show()
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
            rpmBar.isEnabled = true
            throttleBar.isEnabled = true
            speedBar.isEnabled = true
            syncSimulator()
        }
    }

    private fun updateLiveDiagnostics() {
        val d = udpReceiver.diagnostics()
        val age = if (d.ageMs == Long.MAX_VALUE) "--" else "${d.ageMs}ms"
        val valid = d.packetCount > 0 && d.ageMs <= 1500L
        liveDiagnosticsValue.text = String.format(Locale.US, "LIVE %s  •  PKT %d  •  INVALID %d  •  AGE %s", if (valid) "CONNECTED" else "WAITING", d.packetCount, d.invalidPacketCount, age)
        liveDiagnosticsValue.setTextColor(if (valid) GREEN else AMBER)
    }

    private fun showNoVehicleData() {
        if (rpmValue.text.toString() == "—") {
            telemetry.text = "Waiting for verified vehicle telemetry"
            sceneValue.text = "LIVE WAIT"
        }
    }

    private fun applyTelemetry(packet: LiveTelemetry) {
        val data = packet.data
        val scene = controller.apply(data)
        val genome = genomeSession.update(data)
        val signature = genome.toSignature()
        val events = controller.events()
        if (liveMode) {
            rpmBar.progress = (data.rpm - 700f).roundToInt().coerceIn(0, rpmBar.max)
            throttleBar.progress = (data.throttle * 100f).roundToInt().coerceIn(0, throttleBar.max)
            speedBar.progress = data.speedKph.roundToInt().coerceIn(0, speedBar.max)
        }
        rpmValue.text = formatRpm(data.rpm)
        telemetry.text = String.format(Locale.US, "%.0f km/h   •   Throttle %d%%   •   Regen %d%%", data.speedKph, (data.throttle * 100).toInt(), (data.regen * 100).toInt())
        sceneValue.text = scene.name.replace('_', ' ')
        signatureValue.text = String.format(Locale.US, "%s   •   AGG %d%%   •   SMOOTH %d%%", signature.label(), (signature.aggression * 100).toInt(), (signature.smoothness * 100).toInt())
        genomeValue.text = String.format(Locale.US, "GENOME: %s • MATURITY %d%% • OBS %d", signature.label(), (genome.maturity * 100).toInt(), genome.observations.coerceAtMost(999_999L))
        eventValue.text = String.format(Locale.US, "EVENTS L:%d A:%d O:%d R:%d B:%d S:%d", (events.launch * 100).toInt(), (events.accelerationHit * 100).toInt(), (events.liftOff * 100).toInt(), (events.regenerationHit * 100).toInt(), (events.brakeHit * 100).toInt(), (events.speedRush * 100).toInt())
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

    private fun label(text: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("sans", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun action(text: String, fill: Int, color: Int) = Button(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(color)
        isAllCaps = false
        background = rounded(fill, dp(12), STROKE)
        stateListAnimator = null
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun primary(text: String) = action(text, BLUE, Color.WHITE).apply { textSize = 14f; minHeight = dp(56) }
    private fun secondary(text: String) = action(text, PANEL, SOFT).apply { textSize = 12f; minHeight = dp(50) }
    private fun serviceButton(text: String, click: () -> Unit) = action(text, PANEL_2, MUTED).apply { setOnClickListener { click() }; minHeight = dp(46) }

    private fun card(padding: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        background = rounded(PANEL, dp(16), STROKE)
    }

    private fun seek(max: Int, progress: Int) = SeekBar(this).apply {
        this.max = max
        this.progress = progress.coerceIn(0, max)
        minHeight = dp(42)
        progressTintList = ColorStateList.valueOf(BLUE)
        thumbTintList = ColorStateList.valueOf(Color.WHITE)
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius.toFloat()
        setStroke(dp(1), stroke)
    }

    private fun margin(bottom: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(bottom) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun formatRpm(rpm: Float) = String.format(Locale.US, "%,.0f RPM", rpm)

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
