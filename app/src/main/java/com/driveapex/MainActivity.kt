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
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.driveapex.audio.CharacterTuning
import com.driveapex.diag.DriveApexLog
import com.driveapex.diag.LogViewer
import com.driveapex.audio.EngineCharacter
import com.driveapex.audio.EngineCharacters
import com.driveapex.audio.TuningStore
import com.driveapex.audio.tunedWith
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
    private val engine = LayeredSoundEngine(EngineCharacters.default)
    private val tuningStore by lazy { TuningStore(this) }
    private var activeCharacter: EngineCharacter = EngineCharacters.default
    private val vehicle = SimulatorVehicleDataProvider()
    private val controller = EngineSoundController(engine)
    private lateinit var genomeSession: SonicGenomeSession
    private lateinit var updateManager: UpdateManager
    private lateinit var telemetryReceiver: UdpTelemetryReceiver
    private val handler = Handler(Looper.getMainLooper())
    private var liveMode = false

    private lateinit var rpmValue: TextView
    private lateinit var telemetry: TextView
    private lateinit var sceneValue: TextView
    private lateinit var sourceValue: TextView
    private lateinit var liveStatus: TextView
    private lateinit var speedValue: TextView
    private lateinit var throttleValue: TextView
    private lateinit var brakeValue: TextView
    private lateinit var regenValue: TextView
    private lateinit var signatureValue: TextView
    private lateinit var genomeValue: TextView
    private lateinit var eventValue: TextView
    private lateinit var startButton: Button
    private lateinit var modeButton: Button
    private lateinit var motorSpeedBar: SeekBar
    private lateinit var throttleBar: SeekBar
    private lateinit var speedBar: SeekBar

    private val livePoller = object : Runnable {
        override fun run() {
            if (!liveMode) return
            updateLiveStatus()
            telemetryReceiver.latest()?.let { live ->
      applyTelemetry(live)
      controller.apply(live.data)
      val motorSpeed = live.data.rpm.coerceIn(0f, 25000f).roundToInt()
      val gear = engine.currentGear()
      rpmValue.text = if (gear > 0) "$motorSpeed  ·  G$gear" else "${motorSpeed} MOTOR SPEED"
      motorSpeedBar.progress = motorSpeed
  } ?: showNoVehicleData()
            handler.postDelayed(this, 50L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        genomeSession = SonicGenomeSession(this)
        updateManager = UpdateManager(this)
        telemetryReceiver = UdpTelemetryReceiver(context = this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            setBackgroundColor(BG)
        }
        scroll.addView(root)

        root.addView(header(), margin(16))
        root.addView(heroCard(), margin(10))
        root.addView(liveDashboardCard(), margin(18))
        root.addView(section("DRIVE CONTROLS", "Manual controls are disabled automatically in LIVE mode."), margin(8))

        motorSpeedBar = seek(25000, 0)
        throttleBar = seek(100, 10)
        speedBar = seek(240, 0)
        root.addView(controlCard("MOTOR SPEED", motorSpeedBar), margin(8))
        root.addView(controlCard("THROTTLE", throttleBar), margin(8))
        root.addView(controlCard("SPEED / km/h", speedBar), margin(16))

        root.addView(section("QUICK SCENES", "Instant driving states for acoustic tuning."), margin(8))
        root.addView(chips(
            "IDLE" to { setControls(900, 5, 0, 0, 0) },
            "PULL" to { setControls(3000, 65, 45, 0, 0) },
            "BOOST" to { setControls(5200, 95, 120, 0, 0) },
            "COAST" to { setControls(2200, 0, 80, 0, 0) },
            "REGEN" to { setControls(1600, 0, 40, 15, 100) }
        ), margin(16))

        root.addView(section("SOUND DNA", "Choose the acoustic character."), margin(8))
        root.addView(profiles(), margin(16))

        startButton = primary("START DRIVE SOUND")
        startButton.setOnClickListener {
            engine.start()
            if (!liveMode) syncSimulator()
            startButton.text = "DRIVE SOUND RUNNING"
        }
        root.addView(startButton, margin(8))

        val stop = secondary("STOP / SAFE")
        stop.setOnClickListener {
            genomeSession.finishDrive()
            engine.stop()
            startButton.text = "START DRIVE SOUND"
            sceneValue.text = "SAFE / STOPPED"
            eventValue.text = "EVENTS L:0 A:0 O:0 R:0 B:0 S:0"
        }
        root.addView(stop, margin(16))

        root.addView(section("SERVICE", "Diagnostics and maintenance."), margin(8))
        val service = card(12)
        service.addView(serviceButton("BYD ADB SETUP / AUTHORIZE") { runAdbSetup(true) }, margin(6))
        service.addView(serviceButton("BYD TELEMETRY DIAGNOSTICS") { showBydDiagnostics() }, margin(6))
        service.addView(serviceButton("RESET SONIC GENOME") {
            genomeSession.reset()
            if (!liveMode) syncSimulator()
        }, margin(6))
        service.addView(serviceButton("SOUND TUNING") { showSoundTuning() }, margin(6))
        service.addView(serviceButton("APP LOG / WHY IT CLOSED") { LogViewer.show(this) }, margin(6))
        service.addView(serviceButton("CHECK FOR UPDATE") { updateManager.checkManually() })
        root.addView(service)

        eventValue = label("EVENTS", 1f, Color.TRANSPARENT, false)
        eventValue.visibility = View.GONE

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !liveMode) syncSimulator()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        motorSpeedBar.setOnSeekBarChangeListener(listener)
        throttleBar.setOnSeekBarChangeListener(listener)
        speedBar.setOnSeekBarChangeListener(listener)

        setContentView(scroll)
        syncSimulator()
        // Never on the main thread: prepare() -> VehicleAdbConnection.connect() blocks
        // for up to 7s on the ADB handshake and then runs one shell round-trip per BYD
        // permission. That is well past the ANR window, and the app freezes on launch.
        handler.postDelayed({
            Thread({ runCatching { BydAdbSetup.prepare(this, false) } }, "DriveApex-AdbBootstrap")
                .apply { isDaemon = true }.start()
        }, 500L)
        handler.postDelayed({ updateManager.checkSilently() }, 1500L)
        handler.postDelayed({
            if (isBydVehicleRuntime() && !liveMode) toggleTelemetryMode()
        }, 2200L)
    }

    private fun isBydVehicleRuntime(): Boolean = runCatching {
        Class.forName("android.hardware.bydauto.engine.BYDAutoEngineDevice")
        Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice")
        true
    }.getOrDefault(false)

    private fun header(): LinearLayout {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(label("DRIVE APEX", 25f, Color.WHITE, true))
        brand.addView(label("BYD Yuan Plus 2023  •  Sonic Control", 12f, MUTED, false).apply {
            setPadding(0, dp(2), 0, 0)
        })
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
        rpmValue = label("0 MOTOR SPEED", 58f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setIncludeFontPadding(false)
        }
        box.addView(rpmValue, LinearLayout.LayoutParams.MATCH_PARENT, dp(78))
        box.addView(label("FRONT MOTOR SPEED • RPM", 11f, MUTED, true).apply { gravity = Gravity.CENTER_HORIZONTAL })
        telemetry = label("0 km/h   •   Throttle 0%   •   Brake 0%   •   Regen 0%", 13f, SOFT, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        box.addView(telemetry)
        signatureValue = label("BALANCED   •   AGG 0%   •   SMOOTH 0%", 11f, PURPLE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, 0)
        }
        box.addView(signatureValue)
        genomeValue = label("GENOME: NEW  •   OBS 0", 10f, MUTED, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, 0)
        }
        box.addView(genomeValue)
        return box
    }

    /** Always-visible vehicle dashboard. Diagnostics is not required to see these values. */
    private fun liveDashboardCard(): LinearLayout {
        val box = card(13)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("●", 15f, GREEN, true), LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT))
        liveStatus = label("LIVE WAITING  •  VEHICLE TELEMETRY", 12f, SOFT, true)
        header.addView(liveStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(header)

        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        metrics.addView(liveMetric("SPEED", "--", "km/h") { speedValue = it }, metricParams())
        metrics.addView(liveMetric("THROTTLE", "--", "%") { throttleValue = it }, metricParams())
        metrics.addView(liveMetric("BRAKE", "--", "%") { brakeValue = it }, metricParams())
        metrics.addView(liveMetric("REGEN", "--", "%") { regenValue = it }, metricParams())
        box.addView(metrics)
        box.addView(label("Live values update automatically from the verified BYD telemetry path.", 10f, MUTED, false).apply {
            setPadding(0, dp(10), 0, 0)
        })
        return box
    }

    private fun liveMetric(title: String, initial: String, unit: String, bind: (TextView) -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(5), dp(7), dp(5), dp(7))
        background = rounded(PANEL_2, dp(11), STROKE)
        addView(label(title, 9f, MUTED, true))
        val value = label(initial, 21f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        }
        addView(value)
        addView(label(unit, 9f, MUTED, false).apply { gravity = Gravity.CENTER })
        bind(value)
    }

    private fun metricParams() = LinearLayout.LayoutParams(0, dp(78), 1f).apply { marginEnd = dp(7) }

    private fun section(title: String, subtitle: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title, 13f, Color.WHITE, true))
        addView(label(subtitle, 11f, MUTED, false).apply { setPadding(0, dp(3), 0, 0) })
    }

    private fun controlCard(title: String, bar: SeekBar): LinearLayout = card(11).apply {
        val row = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(title, 11f, MUTED, true), LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(bar, LinearLayout.LayoutParams(0, dp(44), 1f))
        addView(row)
    }

    private fun chips(vararg items: Pair<String, () -> Unit>): HorizontalScrollView {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { (text, click) ->
            row.addView(action(text, PANEL, SOFT).apply { setOnClickListener { click() } },
                LinearLayout.LayoutParams(dp(94), dp(52)).apply { marginEnd = dp(8) })
        }
        scroll.addView(row)
        return scroll
    }

    private fun profiles(): HorizontalScrollView {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(profile("EV REAL", "Authentic EV", BLUE) {
            selectCharacter(EngineCharacters.evRealistic)
        }, LinearLayout.LayoutParams(dp(156), dp(92)).apply { marginEnd = dp(10) })
        row.addView(profile("EV SPORT", "Electric GT", PURPLE) {
            selectCharacter(EngineCharacters.evSport)
        }, LinearLayout.LayoutParams(dp(156), dp(92)).apply { marginEnd = dp(10) })
        row.addView(profile("COMBUSTION", "Petrol Sport", AMBER) {
            selectCharacter(EngineCharacters.iceSport)
        }, LinearLayout.LayoutParams(dp(156), dp(92)).apply { marginEnd = dp(10) })
        row.addView(profile("MERCEDES", "AMG V12 · 7-speed", GREEN) {
            selectCharacter(EngineCharacters.mercedesV12)
        }, LinearLayout.LayoutParams(dp(156), dp(92)))
        scroll.addView(row)
        return scroll
    }

    private fun profile(name: String, subtitle: String, accent: Int, click: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = rounded(PANEL, dp(14), STROKE)
        setOnClickListener { click() }
        addView(label(name, 18f, Color.WHITE, true))
        addView(label(subtitle, 11f, MUTED, false).apply { setPadding(0, dp(3), 0, 0) })
        addView(label("●  READY", 10f, accent, true).apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun runAdbSetup(forceOpen: Boolean) {
        // Same reason as the launch bootstrap: prepare() blocks on ADB for seconds.
        liveStatus.text = "ADB: WORKING"
        Thread({
            val result = runCatching { BydAdbSetup.prepare(this, forceOpen) }
                .getOrDefault(BydAdbSetup.Result.SETTINGS_UNAVAILABLE)
            val message = when (result) {
                BydAdbSetup.Result.ALREADY_AVAILABLE -> "ADB port 5555 is available."
                BydAdbSetup.Result.SETTINGS_OPENED -> "BYD ADB settings opened. Enable wireless ADB."
                BydAdbSetup.Result.SETTINGS_UNAVAILABLE -> "BYD ADB settings activity was not found."
            }
            handler.post {
                if (isFinishing) return@post
                liveStatus.text = "ADB: ${result.name}"
                AlertDialog.Builder(this).setTitle("BYD ADB setup").setMessage(message)
                    .setPositiveButton("OK", null).show()
            }
        }, "DriveApex-AdbSetup").apply { isDaemon = true }.start()
    }

    /** Applies a character together with whatever tuning was saved for it. */
    private fun selectCharacter(character: EngineCharacter) {
        activeCharacter = character
        engine.setCharacter(character.tunedWith(tuningStore.load(character.id)))
    }

    private fun showSoundTuning() {
        SoundTuningDialog.show(this, activeCharacter, tuningStore) { tuning ->
            engine.setCharacter(activeCharacter.tunedWith(tuning))
        }
    }

    private fun showBydDiagnostics() {
        Thread {
            val report = BydTelemetryDiagnostics.probe(this)
            val message = BydTelemetryDiagnostics.format(report)
            handler.post {
                if (!isFinishing && !isDestroyed) {
                    AlertDialog.Builder(this).setTitle("BYD telemetry probe").setMessage(message).setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    private fun toggleTelemetryMode() {
        liveMode = !liveMode
        if (liveMode) {
            telemetryReceiver.start()
            modeButton.text = "LIVE"
            sourceValue.text = "LIVE VEHICLE"
            sourceValue.setTextColor(GREEN)
            motorSpeedBar.isEnabled = false
            throttleBar.isEnabled = false
            speedBar.isEnabled = false
            updateLiveStatus()
            handler.post(livePoller)
        } else {
            telemetryReceiver.stop()
            handler.removeCallbacks(livePoller)
            modeButton.text = "TEST"
            sourceValue.text = "SIMULATOR"
            sourceValue.setTextColor(MUTED)
            liveStatus.text = "LIVE READY  •  VEHICLE DATA STANDBY"
            liveStatus.setTextColor(SOFT)
            speedValue.text = "--"
            throttleValue.text = "--"
            brakeValue.text = "--"
            regenValue.text = "--"
            motorSpeedBar.isEnabled = true
            throttleBar.isEnabled = true
            speedBar.isEnabled = true
            syncSimulator()
        }
    }

    private fun updateLiveStatus() {
        val d = telemetryReceiver.diagnostics()
        val age = if (d.ageMs == Long.MAX_VALUE) "--" else "${d.ageMs}ms"
        val valid = d.packetCount > 0 && d.ageMs <= 1500L
        liveStatus.text = String.format(Locale.US, "LIVE %s  •  PKT %d  •  AGE %s",
            if (valid) "CONNECTED" else "WAITING", d.packetCount, age)
        liveStatus.setTextColor(if (valid) GREEN else AMBER)
    }

    private fun showNoVehicleData() {
        if (!liveMode) return
        telemetry.text = "Waiting for verified vehicle telemetry"
        sceneValue.text = "LIVE WAIT"
        speedValue.text = "--"
        throttleValue.text = "--"
        brakeValue.text = "--"
        regenValue.text = "--"
    }

    private fun applyTelemetry(packet: LiveTelemetry) {
        val data = packet.data
        val scene = controller.apply(data)
        val genome = genomeSession.update(data)
        val signature = genome.toSignature()
        val events = controller.events()

        if (liveMode) {
            motorSpeedBar.progress = data.rpm.roundToInt().coerceIn(0, motorSpeedBar.max)
            throttleBar.progress = (data.throttle * 100f).roundToInt().coerceIn(0, throttleBar.max)
            speedBar.progress = data.speedKph.roundToInt().coerceIn(0, speedBar.max)
        }

        rpmValue.text = formatMotorSpeed(data.rpm)
        telemetry.text = String.format(Locale.US,
            "%.0f km/h   •   Throttle %d%%   •   Brake %d%%   •   Regen %d%%",
            data.speedKph, (data.throttle * 100).roundToInt(), (data.brake * 100).roundToInt(), (data.regen * 100).roundToInt())
        speedValue.text = String.format(Locale.US, "%.0f", data.speedKph)
        throttleValue.text = String.format(Locale.US, "%d", (data.throttle * 100).roundToInt())
        brakeValue.text = String.format(Locale.US, "%d", (data.brake * 100).roundToInt())
        regenValue.text = String.format(Locale.US, "%d", (data.regen * 100).roundToInt())
        sceneValue.text = scene.name.replace('_', ' ')
        sourceValue.text = if (liveMode && packet.source != TelemetrySource.SIMULATOR) "LIVE VEHICLE" else "SIMULATOR"
        sourceValue.setTextColor(if (liveMode) GREEN else MUTED)
        signatureValue.text = String.format(Locale.US, "%s   •   AGG %d%%   •   SMOOTH %d%%",
            signature.label(), (signature.aggression * 100).roundToInt(), (signature.smoothness * 100).roundToInt())
        genomeValue.text = String.format(Locale.US, "GENOME: %s  •   OBS %d",
            signature.label(), genome.observations.coerceAtMost(999_999L))
        eventValue.text = String.format(Locale.US, "EVENTS L:%d A:%d O:%d R:%d B:%d S:%d",
            (events.launch * 100).roundToInt(), (events.accelerationHit * 100).roundToInt(),
            (events.liftOff * 100).roundToInt(), (events.regenerationHit * 100).roundToInt(),
            (events.brakeHit * 100).roundToInt(), (events.speedRush * 100).roundToInt())
    }

    private fun syncSimulator() {
        vehicle.setRpm(motorSpeedBar.progress.toFloat())
        vehicle.setThrottle(throttleBar.progress / 100f)
        vehicle.setSpeed(speedBar.progress.toFloat())
        vehicle.setBrake(0f)
        vehicle.setRegen(0f)
        applyTelemetry(LiveTelemetry(vehicle.current(), TelemetrySource.SIMULATOR))
    }

    private fun setControls(rpm: Int, throttle: Int, speed: Int, brake: Int, regen: Int) {
        motorSpeedBar.progress = rpm.coerceIn(0, motorSpeedBar.max)
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
    private fun serviceButton(text: String, click: () -> Unit) = action(text, PANEL_2, MUTED).apply {
        setOnClickListener { click() }
        minHeight = dp(46)
    }

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

    private fun margin(bottom: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(bottom) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun formatMotorSpeed(motorSpeed: Float) = String.format(Locale.US, "%,.0f MOTOR SPEED", motorSpeed)

    override fun onResume() {
        super.onResume()
        DriveApexLog.i("lifecycle", "onResume")
        if (::updateManager.isInitialized) updateManager.onResume()
    }

    override fun onStop() {
        DriveApexLog.i("lifecycle", "onStop: shutting telemetry and audio down")
        handler.removeCallbacks(livePoller)
        genomeSession.finishDrive()
        telemetryReceiver.stop()
        engine.stop()
        super.onStop()
    }

    /**
     * The orderly-shutdown marker. Its absence on the next launch is what proves
     * the previous run ended some other way, so it is written last and only
     * here -- onStop also runs when the screen merely goes to the background.
     */
    override fun onDestroy() {
        DriveApexLog.markCleanExit()
        super.onDestroy()
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
