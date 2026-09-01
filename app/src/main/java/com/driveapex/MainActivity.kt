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
import com.driveapex.diag.DriveApexLog
import com.driveapex.audio.EngineCharacter
import com.driveapex.audio.DriveMode
import com.driveapex.audio.EngineCharacters
import com.driveapex.audio.EngineSampleBank
import com.driveapex.audio.EngineSampleBankLoader
import com.driveapex.audio.TuningStore
import com.driveapex.audio.tunedWith
import com.driveapex.audio.EngineSoundController
import com.driveapex.audio.LayeredSoundEngine
import com.driveapex.audio.SonicGenomeSession
import com.driveapex.audio.AudioOutputChannel
import com.driveapex.ui.ApexButtons
import com.driveapex.update.BydAdbSetup
import com.driveapex.update.UpdateManager
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

    /**
     * Whether the driver asked for sound. Backgrounding releases the audio
     * track, so this is what says to take it back on the way in.
     */
    private var soundRunning = false

    /** The SOUND DNA row, kept so recorded voices can be appended once decoded. */
    private var profileRow: LinearLayout? = null

    /** The DRIVE MODE row, kept so its subtitles can follow the chosen voice. */
    private var driveModeRow: LinearLayout? = null

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
            Triple("IDLE", SLATE, { setControls(900, 5, 0, 0, 0) }),
            Triple("PULL", BLUE, { setControls(3000, 65, 45, 0, 0) }),
            Triple("BOOST", AMBER, { setControls(5200, 95, 120, 0, 0) }),
            Triple("COAST", TEAL, { setControls(2200, 0, 80, 0, 0) }),
            Triple("REGEN", GREEN, { setControls(1600, 0, 40, 15, 100) })
        ), margin(16))

        root.addView(section("SOUND DNA", "Choose the acoustic character."), margin(8))
        root.addView(profiles(), margin(12))
        root.addView(section("DRIVE MODE", "When the box shifts, and how hard."), margin(8))
        root.addView(driveModes(), margin(16))

        startButton = primary("START DRIVE SOUND")
        startButton.setOnClickListener {
            soundRunning = true
            engine.start()
            if (!liveMode) syncSimulator()
            startButton.text = "DRIVE SOUND RUNNING"
        }
        root.addView(startButton, margin(8))

        val stop = secondary("STOP / SAFE")
        stop.setOnClickListener {
            soundRunning = false
            genomeSession.finishDrive()
            engine.stop()
            startButton.text = "START DRIVE SOUND"
            sceneValue.text = "SAFE / STOPPED"
            eventValue.text = "EVENTS L:0 A:0 O:0 R:0 B:0 S:0"
        }
        root.addView(stop, margin(16))

        // Sound tuning stays here: it is adjusted by ear against the engine, so
        // it belongs beside the thing making the noise. Everything else about
        // the app moved to Settings.
        root.addView(section("SERVICE", "Sound tuning here; everything else in Settings."), margin(8))
        val service = card(12)
        service.addView(serviceButton("SOUND TUNING", PURPLE) { showSoundTuning() }, margin(8))
        service.addView(serviceButton("RESET SONIC GENOME", TEAL) {
            genomeSession.reset()
            if (!liveMode) syncSimulator()
        }, margin(8))
        service.addView(serviceButton("SETTINGS", SLATE) {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        })
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
        // The channel the driver chose in Settings, applied before anything can
        // ask for sound.
        engine.setOutputChannel(AudioOutputChannel.load(this))
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
        modeButton = action("TEST", SLATE, ApexButtons.textOn(SLATE))
        modeButton.setOnClickListener { toggleTelemetryMode() }
        row.addView(modeButton, LinearLayout.LayoutParams(dp(92), dp(52)))
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

    private fun chips(vararg items: Triple<String, Int, () -> Unit>): HorizontalScrollView {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { (text, accent, click) ->
            row.addView(
                action(text, accent, ApexButtons.textOn(accent)).apply {
                    textSize = 12f
                    setOnClickListener { click() }
                },
                LinearLayout.LayoutParams(dp(98), dp(58)).apply { marginEnd = dp(8) }
            )
        }
        scroll.addView(row)
        return scroll
    }

    private fun profiles(): HorizontalScrollView {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        profileRow = row
        val combustion = profile("COMBUSTION", "Petrol Sport · 8 gears", AMBER) {
            selectCharacter(EngineCharacters.iceSport)
        }
        row.addView(combustion, profileParams())
        row.addView(profile("MEASURED", "Real engine profile · 8 gears", GREEN) {
            selectCharacter(EngineCharacters.measuredPetrol)
        }, profileParams())
        row.addView(profile("CORVETTE", "Measured V8 · 6 gears", RED) {
            selectCharacter(EngineCharacters.corvetteV8)
        }, profileParams())
        scroll.addView(row)
        // The voice the app starts on, lit from the beginning rather than after
        // the driver first touches something.
        combustion.isSelected = true
        loadSampleBanks()
        return scroll
    }

    private fun profileParams() =
        LinearLayout.LayoutParams(dp(186), dp(96)).apply { marginEnd = dp(10) }

    /**
     * Adds a button for each bank of real recordings found in the APK.
     *
     * Decoding happens off the main thread because it is decoding: several
     * seconds of audio per layer through the platform codec. An APK carrying no
     * banks finds none and the row is left exactly as it was, which is the
     * current state -- the machinery is here, the recordings are not yet.
     */
    private fun loadSampleBanks() {
        Thread({
            val banks = EngineSampleBankLoader.loadAll(this, LayeredSoundEngine.SAMPLE_RATE)
            if (banks.isEmpty()) return@Thread
            handler.post {
                if (isFinishing || isDestroyed) return@post
                val row = profileRow ?: return@post
                banks.forEach { bank ->
                    row.addView(
                        profile(bank.name.uppercase(Locale.US), "Recorded · 6 gears", PURPLE) { selectBank(bank) },
                        profileParams()
                    )
                }
                DriveApexLog.i("samples", "added ${banks.size} recorded voices to the profile row")
            }
        }, "DriveApex-SampleBanks").apply { isDaemon = true }.start()
    }

    /**
     * Switches to real recordings.
     *
     * A bank plays through its own six-speed box rather than inheriting the
     * eight belonging to whichever synthesised character was selected before
     * it -- six gears on the uploaded voices is what the driver asked for. The
     * standstill rev is still shared, because that is behaviour rather than
     * gearing.
     */
    private fun selectBank(bank: EngineSampleBank) {
        engine.setSampleBank(bank)
        sceneValue.text = bank.name.uppercase(Locale.US)
        refreshDriveModeLabels()
    }

    /**
     * One choosable card -- a voice or a drive mode -- as a raised key.
     *
     * It carries its selected state rather than only its pressed one, because
     * these are not actions: the driver needs to see at a glance which voice and
     * which mode the car is currently in. Selecting one clears the rest of its
     * own row, whichever row that turns out to be, so the same card works for
     * both without either knowing about the other.
     *
     * The label colours are state lists for the same reason the background is:
     * the face turns bright accent when selected, and fixed white text vanishes
     * on amber.
     */
    private fun profile(name: String, subtitle: String, accent: Int, click: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        background = ApexButtons.selectable(this@MainActivity, accent, PANEL, 16)
        ApexButtons.padForTravel(this, 14)
        isClickable = true
        setOnClickListener {
            selectOnly(parent as? LinearLayout, this)
            click()
        }
        addView(label(name, 18f, Color.WHITE, true).apply {
            setTextColor(onAccent(accent, Color.WHITE))
        })
        addView(label(subtitle, 11f, MUTED, false).apply {
            setTextColor(onAccent(accent, MUTED))
            setPadding(0, dp(3), 0, 0)
        })
    }

    /** Text that stays readable when the card lights up in its accent colour. */
    private fun onAccent(accent: Int, resting: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
        intArrayOf(ApexButtons.textOn(accent), resting)
    )

    /** Exactly one card in a row is selected; this is the one. */
    private fun selectOnly(row: LinearLayout?, chosen: View) {
        val r = row ?: return
        for (i in 0 until r.childCount) r.getChildAt(i).isSelected = r.getChildAt(i) === chosen
    }


    /** Applies a character together with whatever tuning was saved for it. */
    /**
     * Eco, Normal, Sport. The ratios are the same in every mode -- a car does
     * not grow different gears -- so what changes is when the box uses them:
     * Eco upshifts at motor 808, Normal at 1000, Sport at 1250, and so on up
     * the set.
     */
    private fun driveModes(): HorizontalScrollView {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val colours = mapOf(
            DriveMode.ECO to GREEN, DriveMode.NORMAL to BLUE, DriveMode.SPORT to AMBER
        )
        driveModeRow = row
        DriveMode.entries.forEachIndexed { index, mode ->
            row.addView(
                profile(mode.label, shiftLabel(mode), colours[mode] ?: BLUE) { selectDriveMode(mode) }
                    .apply { isSelected = mode == engine.driveMode() },
                LinearLayout.LayoutParams(dp(132), dp(88)).apply {
                    if (index < DriveMode.entries.lastIndex) marginEnd = dp(10)
                }
            )
        }
        scroll.addView(row)
        return scroll
    }

    /**
     * Where this mode's first upshift lands, in motor rpm.
     *
     * Derived from the box that is actually playing rather than written down: a
     * six-speed voice shifts in a different place from an eight-speed one at the
     * same mode, and a label that says otherwise is worse than no label.
     */
    private fun shiftLabel(mode: DriveMode): String {
        val firstRatio = engine.activeGearbox()?.ratios?.firstOrNull()
        return firstRatio?.takeIf { it > 0f }
            ?.let { "shifts at ${(mode.upshiftRpm / it).roundToInt()} motor rpm" }
            ?: "no gearbox"
    }

    /** Re-prints the shift points after a change of voice. */
    private fun refreshDriveModeLabels() {
        val row = driveModeRow ?: return
        DriveMode.entries.forEachIndexed { index, mode ->
            val card = row.getChildAt(index) as? LinearLayout ?: return@forEachIndexed
            // Child 1 is the subtitle; profile() builds name then subtitle.
            (card.getChildAt(1) as? TextView)?.text = shiftLabel(mode)
        }
    }

    private fun selectDriveMode(mode: DriveMode) {
        engine.setDriveMode(mode)
        sceneValue.text = "${mode.label}  •  ${activeCharacter.name.uppercase(Locale.US)}"
        DriveApexLog.i("drivemode", "selected ${mode.name}")
    }

    private fun selectCharacter(character: EngineCharacter) {
        activeCharacter = character
        // Choosing a synthesised voice also leaves the recordings and the model:
        // either would otherwise keep the output and the button would appear to
        // do nothing.
        engine.setSampleBank(null)
        engine.setCharacter(character.tunedWith(tuningStore.load(character.id)))
        refreshDriveModeLabels()
    }

    private fun showSoundTuning() {
        SoundTuningDialog.show(this, activeCharacter, tuningStore) { tuning ->
            engine.setCharacter(activeCharacter.tunedWith(tuning))
        }
    }


    private fun toggleTelemetryMode() {
        liveMode = !liveMode
        if (liveMode) {
            telemetryReceiver.start()
            // Green while it is reading the car, slate while it is not: the one
            // button whose colour is the answer to a question the driver asks
            // constantly.
            paintModeButton(GREEN)
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
            paintModeButton(SLATE)
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

    private fun paintModeButton(accent: Int) {
        modeButton.background = ApexButtons.raised(this, accent, 12)
        modeButton.setTextColor(ApexButtons.textOn(accent))
        ApexButtons.padForTravel(modeButton, 10)
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

    /**
     * Every button on this screen: coloured, raised, and it moves when pressed.
     *
     * stateListAnimator is cleared because the platform's own elevation
     * animation fights the drawable that does the moving here, and its shadow is
     * clipped away by the scrolling parents anyway.
     */
    private fun action(text: String, fill: Int, color: Int) = Button(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(color)
        isAllCaps = false
        background = ApexButtons.raised(this@MainActivity, fill, 12)
        stateListAnimator = null
        ApexButtons.padForTravel(this, 10)
    }

    private fun primary(text: String) =
        action(text, BLUE, ApexButtons.textOn(BLUE)).apply { textSize = 15f; minHeight = dp(60) }

    /** Red, because stopping is the one button that must never be hunted for. */
    private fun secondary(text: String) =
        action(text, RED, ApexButtons.textOn(RED)).apply { textSize = 13f; minHeight = dp(54) }

    private fun serviceButton(text: String, accent: Int, click: () -> Unit) =
        action(text, accent, ApexButtons.textOn(accent)).apply {
            setOnClickListener { click() }
            textSize = 12f
            minHeight = dp(50)
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
        resumeLivePipeline()
    }

    /**
     * Takes the screen poller and the audio track back after a spell in the
     * background. Both start() calls return immediately when the thing is
     * already running, so this is safe on every resume, including the first one
     * after onCreate.
     */
    private fun resumeLivePipeline() {
        // Coming back from Settings is how the output channel changes, and the
        // engine rebuilds its track only when the choice actually differs.
        engine.setOutputChannel(AudioOutputChannel.load(this))
        if (!::telemetryReceiver.isInitialized) return
        if (liveMode) {
            telemetryReceiver.start()
            handler.removeCallbacks(livePoller)
            handler.post(livePoller)
        }
        if (soundRunning) engine.start()
    }

    /**
     * Backgrounding is not a shutdown. This head unit sends the app to the
     * background constantly -- a navigation prompt, the OEM launcher, the screen
     * blanking -- and onStop used to tear down telemetry and audio with nothing
     * anywhere to bring them back: the app came forward alive but deaf and blind,
     * which is the "works for a while then goes crazy" the driver kept hitting.
     *
     * Only the poller and the audio track stop here. The telemetry receiver stays
     * up, because restarting it re-runs the whole ADB and daemon bootstrap, and
     * doing that on every trip through the background is its own failure.
     */
    override fun onStop() {
        DriveApexLog.i("lifecycle", "onStop: pausing screen poller and audio, telemetry kept alive")
        handler.removeCallbacks(livePoller)
        engine.stop()
        super.onStop()
    }

    /**
     * The real shutdown, and the orderly-shutdown marker with it. Its absence on
     * the next launch is what proves the previous run ended some other way.
     */
    override fun onDestroy() {
        DriveApexLog.i("lifecycle", "onDestroy: shutting telemetry and audio down")
        handler.removeCallbacks(livePoller)
        if (::genomeSession.isInitialized) genomeSession.finishDrive()
        if (::telemetryReceiver.isInitialized) telemetryReceiver.stop()
        engine.stop()
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
        private const val RED = 0xFFE04B4B.toInt()
        private const val TEAL = 0xFF22B8CF.toInt()
        private const val SLATE = 0xFF4A5C6E.toInt()
        private const val SOFT = 0xFFE5EAF0.toInt()
        private const val MUTED = 0xFF8995A3.toInt()
    }
}
