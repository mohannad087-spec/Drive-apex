package com.driveapex

import android.app.Activity
import android.content.Intent
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.driveapex.audio.AudioOutputChannel
import com.driveapex.audio.DriveMode
import com.driveapex.audio.DriveSoundPipeline
import com.driveapex.audio.EngineCharacter
import com.driveapex.audio.EngineCharacters
import com.driveapex.audio.EngineSampleBank
import com.driveapex.audio.EngineSampleBankLoader
import com.driveapex.audio.LayeredSoundEngine
import com.driveapex.audio.SonicGenomeSession
import com.driveapex.audio.TuningStore
import com.driveapex.audio.tunedWith
import com.driveapex.diag.DriveApexLog
import com.driveapex.ui.ApexButtons
import com.driveapex.ui.GaugeView
import com.driveapex.ui.IconView
import com.driveapex.ui.WaveformView
import com.driveapex.update.BydAdbSetup
import com.driveapex.update.UpdateManager
import com.driveapex.vehicle.LiveTelemetry
import com.driveapex.vehicle.SimulatorVehicleDataProvider
import com.driveapex.vehicle.TelemetrySource
import com.driveapex.vehicle.UdpTelemetryReceiver
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The cockpit.
 *
 * Built to the driver's own design: a rail of sections down the left, the dial
 * and the car's live numbers in the middle, and a tab bar along the bottom. The
 * screen is one Activity with five sections swapped into a frame rather than
 * five Activities, because the telemetry poller, the engine and the genome
 * session are shared by all of them and handing those between Activities is how
 * the app used to lose its data on every trip through the background.
 *
 * Everything drawn here is real. Where the design asks for a value this app does
 * not read from the car -- battery charge, coolant temperature -- the tile shows
 * what it does read instead, rather than a number that looks right.
 */
class MainActivity : Activity() {

    private enum class Tab(val title: String, val glyph: IconView.Glyph) {
        HOME("Home", IconView.Glyph.HOME),
        SOUNDS("Sounds", IconView.Glyph.SOUND),
        MODES("Modes", IconView.Glyph.MODES),
        EXTERNAL("External", IconView.Glyph.EXTERNAL),
        SETTINGS("Settings", IconView.Glyph.SETTINGS)
    }

    // Borrowed, not owned: the engine belongs to the process so it can keep
    // playing while this screen is not in front.
    private val engine get() = DriveSoundPipeline.engine
    private val controller get() = DriveSoundPipeline.controller

    private val tuningStore by lazy { TuningStore(this) }
    private var activeCharacter: EngineCharacter = EngineCharacters.default
    private val vehicle = SimulatorVehicleDataProvider()
    private lateinit var genomeSession: SonicGenomeSession
    private lateinit var updateManager: UpdateManager
    private lateinit var telemetryReceiver: UdpTelemetryReceiver
    private val handler = Handler(Looper.getMainLooper())

    private var liveMode = false
    private var soundRunning = false
    private var tab = Tab.HOME

    private lateinit var content: FrameLayout
    private lateinit var gauge: GaugeView
    private lateinit var waveform: WaveformView
    private lateinit var rpmText: TextView
    private lateinit var gearText: TextView
    private lateinit var speedChip: TextView
    private lateinit var sourceChip: TextView
    private lateinit var linkText: TextView
    private lateinit var clockText: TextView
    private lateinit var channelStatus: TextView
    private lateinit var signatureText: TextView
    private lateinit var startButton: Button
    private lateinit var modeChip: Button
    private lateinit var throttleMeter: Meter
    private lateinit var brakeMeter: Meter
    private lateinit var regenMeter: Meter
    private lateinit var motorSpeedBar: SeekBar
    private lateinit var throttleBar: SeekBar
    private lateinit var speedBar: SeekBar
    private var testCard: View? = null
    private var soundsList: LinearLayout? = null
    private var modesList: LinearLayout? = null
    private val tabIcons = mutableMapOf<Tab, IconView>()
    private val tabLabels = mutableMapOf<Tab, TextView>()
    private val railIcons = mutableMapOf<Tab, IconView>()
    private val sections = mutableMapOf<Tab, View>()

    private val livePoller = object : Runnable {
        override fun run() {
            if (!liveMode) return
            updateLink()
            telemetryReceiver.latest()?.let { applyTelemetry(it) } ?: showNoVehicleData()
            handler.postDelayed(this, 50L)
        }
    }

    private val clockTick = object : Runnable {
        override fun run() {
            clockText.text = android.text.format.DateFormat.getTimeFormat(this@MainActivity)
                .format(java.util.Date())
            handler.postDelayed(this, 20_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        genomeSession = SonicGenomeSession(this)
        updateManager = UpdateManager(this)
        telemetryReceiver = DriveSoundPipeline.telemetry(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        root.addView(topBar(), LinearLayout.LayoutParams(MATCH, dp(56)))

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(rail(), LinearLayout.LayoutParams(dp(62), MATCH))
        content = FrameLayout(this)
        body.addView(content, LinearLayout.LayoutParams(0, MATCH, 1f))
        root.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))

        root.addView(tabBar(), LinearLayout.LayoutParams(MATCH, dp(64)))
        setContentView(root)

        engine.attach(this)
        engine.setOutputChannel(AudioOutputChannel.load(this))
        select(Tab.HOME)
        syncSimulator()
        handler.post(clockTick)

        // Never on the main thread: prepare() blocks for seconds on the ADB
        // handshake, which is well past the ANR window.
        handler.postDelayed({
            Thread({ runCatching { BydAdbSetup.prepare(this, false) } }, "DriveApex-AdbBootstrap")
                .apply { isDaemon = true }.start()
        }, 500L)
        handler.postDelayed({ updateManager.checkSilently() }, 1500L)
        handler.postDelayed({
            if (isBydVehicleRuntime() && !liveMode) toggleTelemetryMode()
        }, 2200L)
    }

    // ---------------------------------------------------------------- chrome

    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(16), 0)
            setBackgroundColor(BAR)
        }
        bar.addView(IconView(this, IconView.Glyph.POWER).apply { tint = BLUE },
            LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) })

        bar.addView(TextView(this).apply {
            text = "COCKPIT"
            textSize = 12f
            letterSpacing = 0.14f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans", Typeface.BOLD)
            background = rounded(PANEL_2, dp(14), STROKE)
            setPadding(dp(16), dp(7), dp(16), dp(7))
        })

        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        linkText = TextView(this).apply {
            text = "LINK  --"
            textSize = 11f
            setTextColor(MUTED)
            typeface = Typeface.create("sans", Typeface.BOLD)
        }
        bar.addView(linkText, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(12) })

        modeChip = chip("TEST", SLATE) { toggleTelemetryMode() }
        bar.addView(modeChip, LinearLayout.LayoutParams(dp(92), dp(38)).apply { marginEnd = dp(12) })

        clockText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans", Typeface.BOLD)
        }
        bar.addView(clockText)
        return bar
    }

    /** The section rail. Same five places as the tab bar, for a wide screen. */
    private fun rail(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(14), 0, dp(14))
            setBackgroundColor(BAR)
        }
        Tab.entries.forEach { entry ->
            val icon = IconView(this, entry.glyph)
            railIcons[entry] = icon
            val holder = FrameLayout(this).apply {
                background = rounded(Color.TRANSPARENT, dp(14), Color.TRANSPARENT)
                setOnClickListener { select(entry) }
                addView(icon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            }
            column.addView(holder, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                bottomMargin = dp(10)
            })
        }
        return column
    }

    private fun tabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BAR)
        }
        Tab.entries.forEach { entry ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { select(entry) }
            }
            val icon = IconView(this, entry.glyph)
            tabIcons[entry] = icon
            cell.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
            val label = TextView(this).apply {
                text = entry.title
                textSize = 10f
                setTextColor(MUTED)
                setPadding(0, dp(4), 0, 0)
            }
            tabLabels[entry] = label
            cell.addView(label)
            bar.addView(cell, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        return bar
    }

    private fun select(entry: Tab) {
        tab = entry
        val view = sections.getOrPut(entry) { buildSection(entry) }
        content.removeAllViews()
        content.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        Tab.entries.forEach { other ->
            val on = other == entry
            tabIcons[other]?.tint = if (on) BLUE else MUTED
            tabLabels[other]?.setTextColor(if (on) Color.WHITE else MUTED)
            railIcons[other]?.tint = if (on) BLUE else MUTED
        }
    }

    private fun buildSection(entry: Tab): View = when (entry) {
        Tab.HOME -> homeSection()
        Tab.SOUNDS -> soundsSection()
        Tab.MODES -> modesSection()
        Tab.EXTERNAL -> externalSection()
        Tab.SETTINGS -> settingsSection()
    }

    // ------------------------------------------------------------------ home

    private fun homeSection(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(18))
        }
        scroll.addView(column)

        // --- the dial, with the car's own readings either side of it ---------
        val dialCard = card()
        val dialRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        speedChip = statTile(left, "SPEED", "--", "km/h")
        gearText = statTile(left, "GEAR", "--", "virtual")
        sourceChip = statTile(left, "SOURCE", "TEST", "telemetry")
        dialRow.addView(left, LinearLayout.LayoutParams(dp(104), WRAP))

        val centre = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        gauge = GaugeView(this)
        gauge.setRange(11_000f, 8_000f)
        centre.addView(gauge, LinearLayout.LayoutParams(MATCH, dp(240)))
        rpmText = TextView(this).apply {
            text = "0 RPM"
            textSize = 15f
            setTextColor(SOFT)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans", Typeface.BOLD)
        }
        centre.addView(rpmText, LinearLayout.LayoutParams(MATCH, WRAP))
        dialRow.addView(centre, LinearLayout.LayoutParams(0, WRAP, 1f))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        right.addView(TextView(this).apply {
            text = "BYD"
            textSize = 22f
            letterSpacing = 0.3f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans", Typeface.BOLD)
        })
        right.addView(TextView(this).apply {
            text = "YUAN PLUS 2023"
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(MUTED)
        })
        signatureText = TextView(this).apply {
            text = "BALANCED"
            textSize = 11f
            setTextColor(PURPLE)
            setPadding(0, dp(12), 0, 0)
            typeface = Typeface.create("sans", Typeface.BOLD)
        }
        right.addView(signatureText)
        dialRow.addView(right, LinearLayout.LayoutParams(dp(140), WRAP))

        dialCard.addView(dialRow)
        column.addView(dialCard, stack(10))

        // --- the three meters ------------------------------------------------
        val meters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        throttleMeter = meter(meters, "THROTTLE", BLUE)
        brakeMeter = meter(meters, "BRAKE", AMBER)
        regenMeter = meter(meters, "REGEN", GREEN)
        column.addView(meters, stack(10))

        // --- sound intensity -------------------------------------------------
        val waveCard = card()
        waveCard.addView(caption("SOUND INTENSITY"))
        waveform = WaveformView(this)
        waveCard.addView(waveform, LinearLayout.LayoutParams(MATCH, dp(64)).apply {
            topMargin = dp(8)
        })
        column.addView(waveCard, stack(10))

        // --- start / stop ----------------------------------------------------
        startButton = keyButton("START DRIVE SOUND", BLUE, 15f, dp(58)) {
            soundRunning = true
            DriveSoundService.start(this)
            if (!liveMode) syncSimulator()
            startButton.text = "DRIVE SOUND RUNNING"
            reportChannel()
        }
        column.addView(startButton, stack(8))

        channelStatus = TextView(this).apply {
            textSize = 11f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        column.addView(channelStatus, stack(8))

        column.addView(keyButton("STOP / SAFE", RED, 13f, dp(52)) {
            soundRunning = false
            genomeSession.finishDrive()
            DriveSoundService.stop(this)
            // Directly as well: stopService is asynchronous and STOP means now.
            DriveSoundPipeline.stopSound()
            startButton.text = "START DRIVE SOUND"
        }, stack(12))

        // --- manual controls, for when there is no car -----------------------
        val test = card()
        test.addView(caption("TEST CONTROLS"))
        test.addView(TextView(this).apply {
            text = "Hidden automatically once the car is feeding live telemetry."
            textSize = 10f
            setTextColor(MUTED)
            setPadding(0, dp(3), 0, dp(6))
        })
        motorSpeedBar = seek(11_000, 0)
        throttleBar = seek(100, 10)
        speedBar = seek(240, 0)
        test.addView(sliderRow("MOTOR RPM", motorSpeedBar))
        test.addView(sliderRow("THROTTLE", throttleBar))
        test.addView(sliderRow("SPEED", speedBar))
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
        testCard = test
        test.visibility = if (liveMode) View.GONE else View.VISIBLE
        column.addView(test)

        return scroll
    }

    // ---------------------------------------------------------------- sounds

    private fun soundsSection(): View {
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(18))
        }
        scroll.addView(column)
        column.addView(heading("ENGINE SOUNDS", "The voice the car speaks with."), stack(10))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        soundsList = list
        list.addView(listRow("COMBUSTION", "Petrol Sport  ·  8 gears", AMBER,
            activeCharacter.id == EngineCharacters.iceSport.id) {
            selectCharacter(EngineCharacters.iceSport)
        }, stack(8))
        list.addView(listRow("MEASURED", "Real engine profile  ·  8 gears", GREEN,
            activeCharacter.id == EngineCharacters.measuredPetrol.id) {
            selectCharacter(EngineCharacters.measuredPetrol)
        }, stack(8))
        list.addView(listRow("CORVETTE", "Measured V8  ·  6 gears", RED,
            activeCharacter.id == EngineCharacters.corvetteV8.id) {
            selectCharacter(EngineCharacters.corvetteV8)
        }, stack(8))
        column.addView(list, stack(12))

        column.addView(keyButton("SOUND TUNING", PURPLE, 13f, dp(50)) { showSoundTuning() }, stack(8))
        column.addView(keyButton("RESET SONIC GENOME", TEAL, 13f, dp(50)) {
            genomeSession.reset()
            if (!liveMode) syncSimulator()
        }, stack(8))

        loadSampleBanks()
        return scroll
    }

    // ----------------------------------------------------------------- modes

    private fun modesSection(): View {
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(18))
        }
        scroll.addView(column)
        column.addView(heading("DRIVING MODES", "When the box shifts, and how hard."), stack(10))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        modesList = list
        val colours = mapOf(DriveMode.ECO to GREEN, DriveMode.NORMAL to BLUE, DriveMode.SPORT to AMBER)
        DriveMode.entries.forEach { mode ->
            list.addView(
                listRow(mode.label, shiftLabel(mode), colours[mode] ?: BLUE,
                    mode == engine.driveMode()) { selectDriveMode(mode) },
                stack(8)
            )
        }
        column.addView(list)
        return scroll
    }

    // -------------------------------------------------------------- external

    private fun externalSection(): View {
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(18))
        }
        scroll.addView(column)
        column.addView(
            heading("EXTERNAL SOUND", "Which of the car's channels the engine comes out of."),
            stack(6)
        )
        column.addView(TextView(this).apply {
            text = "DriveApex plays through the head unit only. It does not drive the car's own " +
                "pedestrian speaker, and never sends a command to the vehicle."
            textSize = 10f
            setTextColor(MUTED)
            setPadding(0, 0, 0, dp(10))
        })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chosen = AudioOutputChannel.load(this)
        val palette = listOf(BLUE, GREEN, AMBER, PURPLE, TEAL)
        AudioOutputChannel.entries.forEachIndexed { index, channel ->
            val accent = palette[index % palette.size]
            val (level, max) = channel.volume(this)
            val detail = if (max > 0) "${channel.detail}  ·  volume $level/$max" else channel.detail
            list.addView(
                listRow(channel.label, detail, accent, channel == chosen) {
                    AudioOutputChannel.save(this, channel)
                    engine.setOutputChannel(channel)
                    reportChannel()
                },
                stack(8)
            )
        }
        column.addView(list)
        return scroll
    }

    // -------------------------------------------------------------- settings

    private fun settingsSection(): View {
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(18))
        }
        scroll.addView(column)
        column.addView(heading("SETTINGS", "DriveApex ${BuildConfig.VERSION_NAME}"), stack(12))
        column.addView(keyButton("VEHICLE, DIAGNOSTICS & UPDATES", BLUE, 13f, dp(52)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, stack(8))
        column.addView(keyButton("SOUND TUNING", PURPLE, 13f, dp(52)) { showSoundTuning() }, stack(8))
        column.addView(keyButton("CHECK FOR UPDATE", GREEN, 13f, dp(52)) {
            updateManager.checkManually()
        }, stack(16))
        column.addView(TextView(this).apply {
            text = "Read-only by design: DriveApex holds no BYD _SET permission and sends no " +
                "command to the vehicle. It reads speed and front motor speed and makes a noise."
            textSize = 10f
            setTextColor(MUTED)
        })
        return scroll
    }

    // ------------------------------------------------------------- behaviour

    private fun isBydVehicleRuntime(): Boolean = runCatching {
        Class.forName("android.hardware.bydauto.engine.BYDAutoEngineDevice")
        Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice")
        true
    }.getOrDefault(false)

    /**
     * Adds a row for each bank of real recordings found in the APK. Decoding is
     * off the main thread because it is decoding.
     */
    private fun loadSampleBanks() {
        Thread({
            val banks = EngineSampleBankLoader.loadAll(this, LayeredSoundEngine.SAMPLE_RATE)
            if (banks.isEmpty()) return@Thread
            handler.post {
                if (isFinishing || isDestroyed) return@post
                val list = soundsList ?: return@post
                banks.forEach { bank ->
                    list.addView(
                        listRow(bank.name.uppercase(Locale.US), "Recorded  ·  6 gears", PURPLE, false) {
                            selectBank(bank)
                        },
                        stack(8)
                    )
                }
                DriveApexLog.i("samples", "added ${banks.size} recorded voices")
            }
        }, "DriveApex-SampleBanks").apply { isDaemon = true }.start()
    }

    private fun selectBank(bank: EngineSampleBank) {
        engine.setSampleBank(bank)
        refreshModeLabels()
    }

    private fun selectCharacter(character: EngineCharacter) {
        activeCharacter = character
        // A synthesised voice also leaves the recordings behind; either would
        // otherwise keep the output and the row would appear to do nothing.
        engine.setSampleBank(null)
        engine.setCharacter(character.tunedWith(tuningStore.load(character.id)))
        refreshModeLabels()
    }

    private fun selectDriveMode(mode: DriveMode) {
        engine.setDriveMode(mode)
        DriveApexLog.i("drivemode", "selected ${mode.name}")
    }

    /** Where this mode's first upshift lands, on the box that is actually playing. */
    private fun shiftLabel(mode: DriveMode): String {
        val firstRatio = engine.activeGearbox()?.ratios?.firstOrNull()
        return firstRatio?.takeIf { it > 0f }
            ?.let { "Shifts at ${(mode.upshiftRpm / it).roundToInt()} motor rpm" }
            ?: "No gearbox"
    }

    private fun refreshModeLabels() {
        val list = modesList ?: return
        DriveMode.entries.forEachIndexed { index, mode ->
            val row = list.getChildAt(index) as? LinearLayout ?: return@forEachIndexed
            val texts = row.getChildAt(1) as? LinearLayout ?: return@forEachIndexed
            (texts.getChildAt(1) as? TextView)?.text = shiftLabel(mode)
        }
    }

    private fun showSoundTuning() {
        SoundTuningDialog.show(this, activeCharacter, tuningStore) { tuning ->
            engine.setCharacter(activeCharacter.tunedWith(tuning))
        }
    }

    /**
     * Prints what the chosen output channel actually did. The engine settles
     * that after about a quarter of a second of writing, so this asks later.
     */
    private fun reportChannel() {
        handler.postDelayed({
            if (isFinishing || isDestroyed || !::channelStatus.isInitialized) return@postDelayed
            val report = engine.lastChannelReport() ?: return@postDelayed
            channelStatus.text = report.summary()
            channelStatus.setTextColor(
                if (report.playing && report.advanced && report.volume != 0) MUTED else AMBER
            )
            channelStatus.visibility = View.VISIBLE
        }, 700L)
    }

    private fun toggleTelemetryMode() {
        liveMode = !liveMode
        DriveSoundPipeline.liveMode = liveMode
        if (liveMode) {
            telemetryReceiver.start()
            modeChip.text = "LIVE"
            paintChip(modeChip, GREEN)
            enableTestControls(false)
            updateLink()
            handler.post(livePoller)
        } else {
            telemetryReceiver.stop()
            handler.removeCallbacks(livePoller)
            modeChip.text = "TEST"
            paintChip(modeChip, SLATE)
            linkText.text = "LINK  --"
            linkText.setTextColor(MUTED)
            enableTestControls(true)
            syncSimulator()
        }
    }

    private fun enableTestControls(enabled: Boolean) {
        testCard?.visibility = if (enabled) View.VISIBLE else View.GONE
        if (::motorSpeedBar.isInitialized) {
            motorSpeedBar.isEnabled = enabled
            throttleBar.isEnabled = enabled
            speedBar.isEnabled = enabled
        }
    }

    private fun updateLink() {
        val d = telemetryReceiver.diagnostics()
        val age = if (d.ageMs == Long.MAX_VALUE) "--" else "${d.ageMs}ms"
        val valid = d.packetCount > 0 && d.ageMs <= 1500L
        linkText.text = String.format(
            Locale.US, "%s  ·  PKT %d  ·  %s",
            if (valid) "CONNECTED" else "WAITING", d.packetCount, age
        )
        linkText.setTextColor(if (valid) GREEN else AMBER)
    }

    private fun showNoVehicleData() {
        if (!liveMode) return
        sourceChip.text = "WAIT"
    }

    private fun applyTelemetry(packet: LiveTelemetry) {
        val data = packet.data
        // In LIVE mode the feed loop already applied this frame; asking again
        // from here would put two threads on one set of smoothing filters.
        val scene = if (liveMode) DriveSoundPipeline.scene else controller.apply(data)
        val genome = genomeSession.update(data)
        val signature = genome.toSignature()

        if (liveMode && ::motorSpeedBar.isInitialized) {
            motorSpeedBar.progress = data.rpm.roundToInt().coerceIn(0, motorSpeedBar.max)
            throttleBar.progress = (data.throttle * 100f).roundToInt().coerceIn(0, throttleBar.max)
            speedBar.progress = data.speedKph.roundToInt().coerceIn(0, speedBar.max)
        }

        gauge.setValues(data.rpm, data.speedKph, scene.name.replace('_', ' '))
        rpmText.text = String.format(Locale.US, "%,.0f RPM", data.rpm)
        val gear = engine.currentGear()
        setTile(gearText, if (gear > 0) "G$gear" else "--")
        setTile(speedChip, String.format(Locale.US, "%.0f", data.speedKph))
        setTile(sourceChip, if (liveMode && packet.source != TelemetrySource.SIMULATOR) "LIVE" else "TEST")

        throttleMeter.set(data.throttle, "${(data.throttle * 100).roundToInt()}%")
        brakeMeter.set(data.brake, "${(data.brake * 100).roundToInt()}%")
        regenMeter.set(data.regen, "${(data.regen * 100).roundToInt()}%")
        // The bars follow how hard the engine is working, which is the pedal
        // plus a floor so an idling engine still shows a pulse.
        waveform.setLevel(0.18f + 0.82f * data.throttle.coerceIn(0f, 1f))

        signatureText.text = String.format(
            Locale.US, "%s  ·  AGG %d%%", signature.label(),
            (signature.aggression * 100).roundToInt()
        )
    }

    private fun syncSimulator() {
        if (!::motorSpeedBar.isInitialized) return
        vehicle.setRpm(motorSpeedBar.progress.toFloat())
        vehicle.setThrottle(throttleBar.progress / 100f)
        vehicle.setSpeed(speedBar.progress.toFloat())
        vehicle.setBrake(0f)
        vehicle.setRegen(0f)
        applyTelemetry(LiveTelemetry(vehicle.current(), TelemetrySource.SIMULATOR))
    }

    // --------------------------------------------------------------- widgets

    /** One horizontal bar with its own label and readout. */
    private inner class Meter(val fill: View, val rest: View, val value: TextView) {
        fun set(fraction: Float, text: String) {
            val f = fraction.coerceIn(0f, 1f)
            (fill.layoutParams as LinearLayout.LayoutParams).weight = f
            (rest.layoutParams as LinearLayout.LayoutParams).weight = 1f - f
            fill.requestLayout()
            rest.requestLayout()
            value.text = text
        }
    }

    private fun meter(parent: LinearLayout, title: String, accent: Int): Meter {
        val box = card(10)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(TextView(this).apply {
            text = title
            textSize = 10f
            letterSpacing = 0.1f
            setTextColor(MUTED)
            typeface = Typeface.create("sans", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        val value = TextView(this).apply {
            text = "0%"
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans", Typeface.BOLD)
        }
        head.addView(value)
        box.addView(head)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(0xFF141C25.toInt(), dp(4), Color.TRANSPARENT)
        }
        val fill = View(this).apply { background = rounded(accent, dp(4), Color.TRANSPARENT) }
        val rest = View(this)
        bar.addView(fill, LinearLayout.LayoutParams(0, dp(7), 0f))
        bar.addView(rest, LinearLayout.LayoutParams(0, dp(7), 1f))
        box.addView(bar, LinearLayout.LayoutParams(MATCH, dp(7)).apply { topMargin = dp(8) })

        parent.addView(box, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) })
        return Meter(fill, rest, value)
    }

    /** A tile in the column beside the dial: title, big value, unit. */
    private fun statTile(parent: LinearLayout, title: String, value: String, unit: String): TextView {
        val box = card(10)
        box.addView(TextView(this).apply {
            text = title
            textSize = 9f
            letterSpacing = 0.1f
            setTextColor(MUTED)
            typeface = Typeface.create("sans", Typeface.BOLD)
        })
        val big = TextView(this).apply {
            text = value
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans", Typeface.BOLD)
            setPadding(0, dp(2), 0, 0)
        }
        box.addView(big)
        box.addView(TextView(this).apply {
            text = unit
            textSize = 9f
            setTextColor(MUTED)
        })
        parent.addView(box, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        return big
    }

    private fun setTile(view: TextView, value: String) { view.text = value }

    /**
     * A row in one of the lists: accent block, name, subtitle, chevron.
     *
     * Selecting one clears the rest of its own list, whichever list that turns
     * out to be, so the same row works for sounds, modes and channels.
     */
    private fun listRow(
        name: String,
        subtitle: String,
        accent: Int,
        selected: Boolean,
        click: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ApexButtons.selectable(this@MainActivity, accent, PANEL, 14)
            ApexButtons.padForTravel(this, 14)
            isClickable = true
            isSelected = selected
        }
        row.addView(View(this).apply {
            background = rounded(accent, dp(3), Color.TRANSPARENT)
        }, LinearLayout.LayoutParams(dp(4), dp(34)).apply { marginEnd = dp(12) })

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = name
            textSize = 15f
            typeface = Typeface.create("sans", Typeface.BOLD)
            setTextColor(rowTitleColour())
        })
        texts.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(MUTED)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))

        row.addView(TextView(this).apply {
            text = "›"
            textSize = 20f
            setTextColor(MUTED)
        })

        row.setOnClickListener {
            val parent = row.parent as? LinearLayout
            if (parent != null) {
                for (i in 0 until parent.childCount) {
                    parent.getChildAt(i).isSelected = parent.getChildAt(i) === row
                }
            }
            click()
        }
        return row
    }

    /** White when the row is the chosen one, grey otherwise. */
    private fun rowTitleColour() = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
        intArrayOf(Color.WHITE, 0xFFAAB4BF.toInt())
    )

    private fun heading(title: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 15f
            letterSpacing = 0.08f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans", Typeface.BOLD)
        })
        addView(TextView(this@MainActivity).apply {
            text = subtitle
            textSize = 11f
            setTextColor(MUTED)
            setPadding(0, dp(3), 0, 0)
        })
    }

    private fun caption(text: String) = TextView(this).apply {
        this.text = text
        textSize = 10f
        letterSpacing = 0.12f
        setTextColor(MUTED)
        typeface = Typeface.create("sans", Typeface.BOLD)
    }

    private fun chip(text: String, accent: Int, click: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 11f
        isAllCaps = true
        stateListAnimator = null
        setOnClickListener { click() }
        paintChip(this, accent)
    }

    private fun paintChip(button: Button, accent: Int) {
        button.background = ApexButtons.raised(this, accent, 12)
        button.setTextColor(ApexButtons.textOn(accent))
        ApexButtons.padForTravel(button, 10)
    }

    private fun keyButton(text: String, accent: Int, size: Float, height: Int, click: () -> Unit) =
        Button(this).apply {
            this.text = text
            textSize = size
            isAllCaps = true
            minHeight = height
            stateListAnimator = null
            background = ApexButtons.raised(this@MainActivity, accent, 12)
            setTextColor(ApexButtons.textOn(accent))
            ApexButtons.padForTravel(this, 12)
            setOnClickListener { click() }
        }

    private fun sliderRow(title: String, bar: SeekBar): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 10f
            setTextColor(MUTED)
        }, LinearLayout.LayoutParams(dp(92), WRAP))
        addView(bar, LinearLayout.LayoutParams(0, dp(40), 1f))
    }

    private fun seek(max: Int, progress: Int) = SeekBar(this).apply {
        this.max = max
        this.progress = progress.coerceIn(0, max)
        progressTintList = ColorStateList.valueOf(BLUE)
        thumbTintList = ColorStateList.valueOf(Color.WHITE)
    }

    private fun card(padding: Int = 14) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        background = rounded(PANEL, dp(16), STROKE)
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius.toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun stack(bottom: Int) = LinearLayout.LayoutParams(MATCH, WRAP).apply {
        bottomMargin = dp(bottom)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    // ------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        DriveApexLog.i("lifecycle", "onResume")
        updateManager.onResume()
        engine.attach(this)
        engine.setOutputChannel(AudioOutputChannel.load(this))
        if (liveMode) {
            telemetryReceiver.start()
            handler.removeCallbacks(livePoller)
            handler.post(livePoller)
        }
        handler.removeCallbacks(clockTick)
        handler.post(clockTick)
        // The service kept the engine running while the screen was away, so this
        // only catches the button up with the truth.
        soundRunning = DriveSoundPipeline.soundRequested
        if (::startButton.isInitialized) {
            startButton.text = if (soundRunning) "DRIVE SOUND RUNNING" else "START DRIVE SOUND"
        }
    }

    /**
     * Backgrounding is not a shutdown, and no longer a silence either: only this
     * screen's poller stops. The engine is held up by the foreground service.
     */
    override fun onStop() {
        DriveApexLog.i("lifecycle", "onStop: screen poller paused, engine left running")
        handler.removeCallbacks(livePoller)
        handler.removeCallbacks(clockTick)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(livePoller)
        handler.removeCallbacks(clockTick)
        genomeSession.finishDrive()
        if (!DriveSoundPipeline.soundRequested) {
            DriveApexLog.i("lifecycle", "onDestroy: no sound requested, shutting the pipeline down")
            DriveSoundPipeline.shutdown()
        } else {
            DriveApexLog.i("lifecycle", "onDestroy: sound still requested, service keeps it running")
        }
        DriveApexLog.markCleanExit()
        super.onDestroy()
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        const val BG = 0xFF05080C.toInt()
        const val BAR = 0xFF080C12.toInt()
        const val PANEL = 0xFF0C1219.toInt()
        const val PANEL_2 = 0xFF101822.toInt()
        const val STROKE = 0xFF1B2733.toInt()
        const val BLUE = 0xFF1D9BF0.toInt()
        const val GREEN = 0xFF35D07F.toInt()
        const val PURPLE = 0xFF7C5CFF.toInt()
        const val AMBER = 0xFFFFB74D.toInt()
        const val RED = 0xFFE04B4B.toInt()
        const val TEAL = 0xFF22B8CF.toInt()
        const val SLATE = 0xFF4A5C6E.toInt()
        const val SOFT = 0xFFE5EAF0.toInt()
        const val MUTED = 0xFF8695A6.toInt()
    }
}
