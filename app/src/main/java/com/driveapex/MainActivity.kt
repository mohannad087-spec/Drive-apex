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
import com.driveapex.audio.GrainSource
import com.driveapex.audio.GrainSourceLoader
import com.driveapex.audio.LayeredSoundEngine
import com.driveapex.audio.SonicGenomeSession
import com.driveapex.audio.TuningStore
import com.driveapex.audio.tunedWith
import com.driveapex.diag.DriveApexLog
import com.driveapex.ui.ApexButtons
import com.driveapex.ui.CarView
import com.driveapex.ui.GaugeView
import com.driveapex.ui.IconView
import com.driveapex.ui.WaveformView
import com.driveapex.update.BydAdbSetup
import com.driveapex.update.UpdateManager
import com.driveapex.vehicle.LiveTelemetry
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

    private val tuningStore by lazy { TuningStore(this) }
    private var activeCharacter: EngineCharacter = EngineCharacters.default
    // The simulator lives in the pipeline now, next to the vehicle, because one
    // loop feeds the engine from whichever of them is selected.
    private val vehicle get() = DriveSoundPipeline.simulator
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
    private lateinit var motorText: TextView
    private lateinit var gearText: TextView
    private lateinit var sourceChip: TextView
    private lateinit var modeLabel: TextView
    private lateinit var linkText: TextView
    private lateinit var clockText: TextView
    private lateinit var channelStatus: TextView
    private lateinit var engineText: TextView
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
    private val tabCells = mutableMapOf<Tab, LinearLayout>()
    private val railHolders = mutableMapOf<Tab, FrameLayout>()
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

    /**
     * The engine readout, five times a second, in both modes.
     *
     * Deliberately not part of the live poller: the poller only runs in LIVE,
     * and the question this answers -- is anything reaching the engine? -- is
     * exactly the one worth asking when it is not.
     */
    private val engineTick = object : Runnable {
        override fun run() {
            if (::engineText.isInitialized) {
                val fed = DriveSoundPipeline.lastApplied
                engineText.text = if (fed == null) {
                    "ENGINE  waiting for telemetry"
                } else {
                    String.format(
                        Locale.US,
                        "ENGINE  in %,.0f rpm · thr %d%% · %.0f km/h  →  out %,.0f rpm · G%d",
                        fed.rpm, (fed.throttle * 100).roundToInt(), fed.speedKph,
                        engine.soundingRpm(), engine.currentGear()
                    )
                }
            }
            handler.postDelayed(this, 200L)
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
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        // One card holds the whole cockpit -- bar, rail, screen and tabs -- the
        // way the design draws it. The rail and the tab bar are inside it, not
        // chrome around it.
        val cockpit = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(PANEL, dp(20), STROKE)
            clipToPadding = false
        }
        cockpit.addView(topBar(), LinearLayout.LayoutParams(MATCH, dp(54)))

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(rail(), LinearLayout.LayoutParams(dp(58), MATCH))
        content = FrameLayout(this)
        body.addView(content, LinearLayout.LayoutParams(0, MATCH, 1f))
        cockpit.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))

        cockpit.addView(tabBar(), LinearLayout.LayoutParams(MATCH, dp(62)))
        root.addView(cockpit, LinearLayout.LayoutParams(MATCH, MATCH))

        setContentView(root)

        engine.attach(this)
        engine.setOutputChannel(AudioOutputChannel.load(this))
        select(Tab.HOME)
        syncSimulator()
        // The loop that feeds the engine runs whenever the app is open, not
        // only while the sound is playing.
        DriveSoundPipeline.startFeeding(this)
        handler.post(clockTick)
        handler.post(engineTick)

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
            setPadding(dp(14), dp(10), dp(16), dp(4))
        }
        bar.addView(IconView(this, IconView.Glyph.POWER).apply { tint = BLUE },
            LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })

        bar.addView(TextView(this).apply {
            text = "COCKPIT"
            textSize = 11f
            letterSpacing = 0.16f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans", Typeface.BOLD)
            background = rounded(PANEL_2, dp(13), STROKE)
            setPadding(dp(16), dp(6), dp(16), dp(6))
        })

        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        linkText = TextView(this).apply {
            text = "LINK  --"
            textSize = 10f
            letterSpacing = 0.06f
            setTextColor(MUTED)
            typeface = Typeface.create("sans", Typeface.BOLD)
        }
        bar.addView(linkText, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(12) })

        modeChip = chip("TEST", SLATE) { toggleTelemetryMode() }
        bar.addView(modeChip, LinearLayout.LayoutParams(dp(86), dp(34)).apply { marginEnd = dp(14) })

        clockText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans", Typeface.BOLD)
        }
        bar.addView(clockText)
        return bar
    }

    /**
     * The rail down the left of the cockpit.
     *
     * Same five places as the tab bar. The design lights the current one as a
     * filled square rather than only tinting its icon, so that is what the
     * holder's background does.
     */
    private fun rail(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
        }
        Tab.entries.forEach { entry ->
            val icon = IconView(this, entry.glyph)
            railIcons[entry] = icon
            val holder = FrameLayout(this).apply {
                setOnClickListener { select(entry) }
                addView(icon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            }
            railHolders[entry] = holder
            column.addView(holder, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                bottomMargin = dp(8)
            })
        }
        return column
    }

    private fun tabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(10))
        }
        Tab.entries.forEach { entry ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { select(entry) }
            }
            val icon = IconView(this, entry.glyph)
            tabIcons[entry] = icon
            cell.addView(icon, LinearLayout.LayoutParams(dp(19), dp(19)).apply {
                marginEnd = dp(8)
            })
            val label = TextView(this).apply {
                text = entry.title
                textSize = 12f
                setTextColor(MUTED)
                typeface = Typeface.create("sans", Typeface.BOLD)
            }
            tabLabels[entry] = label
            cell.addView(label)
            tabCells[entry] = cell
            bar.addView(cell, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginEnd = dp(6)
            })
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
            tabIcons[other]?.tint = if (on) Color.WHITE else MUTED
            tabLabels[other]?.setTextColor(if (on) Color.WHITE else MUTED)
            tabCells[other]?.background =
                if (on) rounded(BLUE, dp(12), Color.TRANSPARENT) else null
            railIcons[other]?.tint = if (on) Color.WHITE else MUTED
            railHolders[other]?.background =
                if (on) rounded(BLUE, dp(12), Color.TRANSPARENT) else null
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
        val home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(2), dp(14), dp(6))
        }

        val mainRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // --- left: the small readings, then start and stop -------------------
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        motorText = statTile(left, "--", "Motor rpm", BLUE)
        gearText = statTile(left, "--", "Virtual gear", PURPLE)
        sourceChip = statTile(left, "TEST", "Telemetry", GREEN)

        startButton = keyButton("START", BLUE, 12f, dp(44)) {
            soundRunning = true
            DriveSoundService.start(this)
            if (!liveMode) syncSimulator()
            startButton.text = "RUNNING"
            reportChannel()
        }
        left.addView(startButton, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(4); bottomMargin = dp(6)
        })
        left.addView(keyButton("STOP", RED, 12f, dp(40)) {
            soundRunning = false
            genomeSession.finishDrive()
            DriveSoundService.stop(this)
            // Directly as well: stopService is asynchronous and STOP means now.
            DriveSoundPipeline.stopSound()
            startButton.text = "START"
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        mainRow.addView(left, LinearLayout.LayoutParams(dp(120), MATCH))

        // --- middle: the drive mode over the dial ----------------------------
        val centre = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        modeLabel = TextView(this).apply {
            text = DriveMode.NORMAL.label
            textSize = 15f
            letterSpacing = 0.18f
            setTextColor(BLUE)
            typeface = Typeface.create("sans", Typeface.BOLD)
        }
        centre.addView(modeLabel, LinearLayout.LayoutParams(WRAP, WRAP))
        gauge = GaugeView(this)
        // The dial reads the engine the driver hears, after the gearbox, so it
        // fits the 0-8 face in the design instead of the motor's own 0-11.
        gauge.setRange(8_000f, 7_200f)
        centre.addView(gauge, LinearLayout.LayoutParams(MATCH, 0, 1f))
        mainRow.addView(centre, LinearLayout.LayoutParams(0, MATCH, 1.35f))

        // --- right: the car, and the sound it is making ----------------------
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        right.addView(TextView(this).apply {
            text = "BYD"
            textSize = 20f
            letterSpacing = 0.34f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans", Typeface.BOLD)
        })
        right.addView(TextView(this).apply {
            text = "YUAN PLUS 2023"
            textSize = 10f
            letterSpacing = 0.14f
            setTextColor(MUTED)
        })
        right.addView(CarView(this), LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
            topMargin = dp(4)
        })

        val waveCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(PANEL_2, dp(12), STROKE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        waveform = WaveformView(this)
        waveCard.addView(waveform, LinearLayout.LayoutParams(MATCH, dp(34)))
        waveCard.addView(TextView(this).apply {
            text = "SOUND INTENSITY"
            textSize = 8f
            letterSpacing = 0.14f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        right.addView(waveCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        mainRow.addView(right, LinearLayout.LayoutParams(dp(210), MATCH))

        home.addView(mainRow, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // --- the meter row across the bottom ---------------------------------
        val meters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        throttleMeter = meter(meters, "THROTTLE", BLUE)
        brakeMeter = meter(meters, "BRAKE", AMBER)
        regenMeter = meter(meters, "REGEN", GREEN)
        home.addView(meters, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        // What the audio engine is actually holding, read from the engine
        // rather than from this screen's own copy of the telemetry. When the
        // sound does not follow the car, this line says which half is at fault.
        engineText = TextView(this).apply {
            text = "ENGINE  --"
            textSize = 10f
            letterSpacing = 0.04f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }
        home.addView(engineText, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(4)
        })

        channelStatus = TextView(this).apply {
            textSize = 10f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        home.addView(channelStatus, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(4)
        })

        // --- manual controls, only while there is no car ---------------------
        val test = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(PANEL_2, dp(12), STROKE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        motorSpeedBar = seek(11_000, 0)
        throttleBar = seek(100, 10)
        speedBar = seek(240, 0)
        test.addView(sliderCell("RPM", motorSpeedBar))
        test.addView(sliderCell("THR", throttleBar))
        test.addView(sliderCell("KPH", speedBar))
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
        home.addView(test, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        return home
    }

    private fun sliderCell(title: String, bar: SeekBar): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 9f
            setTextColor(MUTED)
        }, LinearLayout.LayoutParams(dp(30), WRAP))
        addView(bar, LinearLayout.LayoutParams(0, dp(32), 1f))
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
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

        loadRecordedVoices()
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
     * Adds a row for every voice built from a real recording.
     *
     * Two kinds, both decoded off the main thread because decoding is what they
     * are: grain sources, which are a rev read granularly, and sample banks,
     * which are looped layers. An APK carrying neither finds none and the list
     * is left exactly as it was.
     */
    private fun loadRecordedVoices() {
        Thread({
            val grains = GrainSourceLoader.loadAll(this, LayeredSoundEngine.SAMPLE_RATE)
            val banks = EngineSampleBankLoader.loadAll(this, LayeredSoundEngine.SAMPLE_RATE)
            if (grains.isEmpty() && banks.isEmpty()) return@Thread
            handler.post {
                if (isFinishing || isDestroyed) return@post
                val list = soundsList ?: return@post
                grains.forEach { source ->
                    val coverage = String.format(Locale.US, "%.1fx", source.coverage())
                    list.addView(
                        listRow(
                            source.name.uppercase(Locale.US),
                            "Real recording, granular  ·  $coverage range  ·  6 gears",
                            TEAL, false
                        ) { selectGrain(source) },
                        stack(8)
                    )
                }
                banks.forEach { bank ->
                    list.addView(
                        listRow(bank.name.uppercase(Locale.US), "Recorded loops  ·  6 gears", PURPLE, false) {
                            selectBank(bank)
                        },
                        stack(8)
                    )
                }
                DriveApexLog.i("samples",
                    "added ${grains.size} granular and ${banks.size} looped voices")
            }
        }, "DriveApex-RecordedVoices").apply { isDaemon = true }.start()
    }

    /** The granular voice: a real rev, read at whatever rpm is asked for. */
    private fun selectGrain(source: GrainSource) {
        engine.setGrainSource(source)
        refreshModeLabels()
    }

    private fun selectBank(bank: EngineSampleBank) {
        engine.setSampleBank(bank)
        refreshModeLabels()
    }

    private fun selectCharacter(character: EngineCharacter) {
        activeCharacter = character
        // A synthesised voice also leaves the recordings behind; any of them
        // would otherwise keep the output and the row would appear to do
        // nothing.
        engine.setGrainSource(null)
        engine.setSampleBank(null)
        engine.setCharacter(character.tunedWith(tuningStore.load(character.id)))
        refreshModeLabels()
    }

    private fun selectDriveMode(mode: DriveMode) {
        engine.setDriveMode(mode)
        if (::modeLabel.isInitialized) modeLabel.text = mode.label
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
        // This screen draws; it never drives. The feed loop is the only caller
        // of the controller, in both modes -- the screen used to apply the
        // simulator itself, which meant the engine was fed once when a slider
        // moved and then not again until the next one did.
        val scene = DriveSoundPipeline.scene
        genomeSession.update(data)

        if (liveMode && ::motorSpeedBar.isInitialized) {
            motorSpeedBar.progress = data.rpm.roundToInt().coerceIn(0, motorSpeedBar.max)
            throttleBar.progress = (data.throttle * 100f).roundToInt().coerceIn(0, throttleBar.max)
            speedBar.progress = data.speedKph.roundToInt().coerceIn(0, speedBar.max)
        }

        // The dial reads the engine being heard, not the motor: that is what a
        // rev counter shows in a car with a gearbox, and it is why the face fits
        // 0 to 8 the way the design draws it.
        val sounding = engine.soundingRpm()
        gauge.setValues(
            sounding, data.speedKph,
            String.format(Locale.US, "%,.0f RPM", sounding),
            scene.name.replace('_', ' ')
        )

        val gear = engine.currentGear()
        motorText.text = String.format(Locale.US, "%,.0f", data.rpm)
        gearText.text = if (gear > 0) "G$gear" else "--"
        sourceChip.text =
            if (liveMode && packet.source != TelemetrySource.SIMULATOR) "LIVE" else "TEST"
        modeLabel.text = engine.driveMode().label

        throttleMeter.set(data.throttle, "${(data.throttle * 100).roundToInt()}%")
        brakeMeter.set(data.brake, "${(data.brake * 100).roundToInt()}%")
        regenMeter.set(data.regen, "${(data.regen * 100).roundToInt()}%")
        // The bars follow how hard the engine is working, with a floor so an
        // idling engine still shows a pulse.
        waveform.setLevel(0.18f + 0.82f * data.throttle.coerceIn(0f, 1f))
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

    /**
     * A reading beside the dial: the value, what it is, and an accent edge.
     *
     * The design puts these on the panel itself rather than in boxes, so the
     * only framing is the coloured bar on the left.
     */
    private fun statTile(parent: LinearLayout, value: String, caption: String, accent: Int): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(View(this).apply {
            background = rounded(accent, dp(2), Color.TRANSPARENT)
        }, LinearLayout.LayoutParams(dp(3), dp(28)).apply { marginEnd = dp(9) })

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val big = TextView(this).apply {
            text = value
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans", Typeface.BOLD)
        }
        texts.addView(big)
        texts.addView(TextView(this).apply {
            text = caption
            textSize = 9f
            setTextColor(MUTED)
        })
        row.addView(texts)
        parent.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })
        return big
    }

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
        handler.removeCallbacks(engineTick)
        handler.post(engineTick)
        DriveSoundPipeline.startFeeding(this)
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
        handler.removeCallbacks(engineTick)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(livePoller)
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(engineTick)
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
