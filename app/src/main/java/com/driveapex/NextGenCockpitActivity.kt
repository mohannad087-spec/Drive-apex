package com.driveapex

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
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
import android.widget.Switch
import android.widget.TextView
import com.driveapex.audio.ApexSoundProfile
import com.driveapex.audio.ETronInspiredSoundProfile
import com.driveapex.audio.EngineSoundController
import com.driveapex.audio.LayeredSoundEngine
import com.driveapex.vehicle.LiveTelemetry
import com.driveapex.vehicle.UdpTelemetryReceiver
import com.driveapex.vehicle.VehicleData
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Visual-first cockpit. It delegates all vehicle/audio behavior to the existing stack. */
class NextGenCockpitActivity : Activity() {
    private val prefs: SharedPreferences by lazy { getSharedPreferences("driveapex_ui", Context.MODE_PRIVATE) }
    private val engine = LayeredSoundEngine(ETronInspiredSoundProfile.layers)
    private val controller = EngineSoundController(engine)
    private lateinit var telemetryReceiver: UdpTelemetryReceiver
    private val handler = Handler(Looper.getMainLooper())

    private var darkMode = prefs.getBoolean("dark", true)
    private var selectedMode = "SPORT+"
    private var selectedProfile = "EV GT"
    private var liveMode = false
    private var running = false

    private lateinit var root: LinearLayout
    private lateinit var modeStatus: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var modelLabel: TextView
    private lateinit var speedValue: TextView
    private lateinit var rpmValue: TextView
    private lateinit var throttleValue: TextView
    private lateinit var batteryValue: TextView
    private lateinit var dnaValue: TextView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var carView: CarShowcaseView
    private lateinit var waveView: WaveformView
    private lateinit var themeSwitch: Switch

    private val poller = object : Runnable {
        override fun run() {
            if (!liveMode) return
            telemetryReceiver.latest()?.let(::applyTelemetry)
            handler.postDelayed(this, 100L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        telemetryReceiver = UdpTelemetryReceiver(context = this)
        buildUi()
    }

    private fun buildUi() {
        applySystemBars()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(22))
            setBackgroundColor(bg())
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(bg()); isFillViewport = true }
        scroll.addView(root)
        setContentView(scroll)
        render()
    }

    private fun render() {
        root.removeAllViews()
        root.addView(header(), margin(10))
        root.addView(hero(), margin(10))
        root.addView(adaptiveModes(), margin(10))
        root.addView(headunitCenter(), margin(10))
        root.addView(soundStudio(), margin(10))
        root.addView(analytics(), margin(10))
        root.addView(themeAppearance(), margin(10))
        root.addView(settingsAndAbout(), margin(10))
        root.addView(startStop(), margin(10))
        syncModeVisuals()
    }

    private fun header(): LinearLayout {
        val row = horizontal()
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(text("DriveApex", 25f, fg(), true))
        brand.addView(text("BYD SOUND EXPERIENCE", 10f, muted(), true))
        row.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val connected = isBydVehicleRuntime()
        val chip = text(if (connected) "HEADUNIT  •  CONNECTED" else "HEADUNIT  •  READY", 10f, if (connected) green() else muted(), true)
        chip.setPadding(dp(10), dp(7), dp(10), dp(7))
        chip.background = rounded(if (connected) 0xFF0E2A1E.toInt() else panel2(), dp(14), if (connected) 0xFF1F6444.toInt() else stroke())
        row.addView(chip)
        return row
    }

    private fun hero(): LinearLayout {
        val box = card(14)
        val top = horizontal()
        modeStatus = text(selectedMode, 11f, accent(), true)
        top.addView(modeStatus, LinearLayout.LayoutParams(0, -2, 1f))
        modelLabel = text("BYD  •  ${resolveModelLabel()}", 11f, muted(), true)
        top.addView(modelLabel)
        box.addView(top)

        carView = CarShowcaseView(this).apply { setDark(darkMode); setAccent(accent()) }
        box.addView(carView, LinearLayout.LayoutParams(-1, dp(198)).apply { topMargin = dp(8); bottomMargin = dp(8) })

        val metrics = horizontal()
        metrics.addView(bigMetric("SPEED", "0", "km/h") { speedValue = it }, metricParams())
        metrics.addView(bigMetric("MOTOR", "0", "RPM") { rpmValue = it }, metricParams())
        metrics.addView(bigMetric("THROTTLE", "0", "%") { throttleValue = it }, metricParams())
        metrics.addView(bigMetric("BATTERY", "--", "%") { batteryValue = it }, metricParams())
        box.addView(metrics)

        val lower = horizontal()
        dnaValue = text("SOUND DNA  •  BALANCED  •  REAL-TIME", 10f, purple(), true)
        lower.addView(dnaValue, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(8) })
        connectionStatus = text(if (liveMode) "LIVE" else "SIM", 10f, if (liveMode) green() else muted(), true)
        lower.addView(connectionStatus.apply { topMargin = dp(8) })
        box.addView(lower)
        return box
    }

    private fun adaptiveModes(): LinearLayout {
        val box = card(12)
        box.addView(sectionTitle("ADAPTIVE COCKPIT", "Visual identity adapts to the selected driving mode."), margin(7))
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val modes = horizontal()
        listOf("ECO" to green(), "SPORT" to blue(), "SPORT+" to red(), "CUSTOM" to purple()).forEach { (mode, color) ->
            val b = action(mode, if (selectedMode == mode) color else panel2(), if (selectedMode == mode) Color.WHITE else fg())
            b.setOnClickListener { selectedMode = mode; render() }
            modes.addView(b, LinearLayout.LayoutParams(dp(108), dp(52)).apply { marginEnd = dp(8) })
        }
        scroll.addView(modes)
        box.addView(scroll, margin(7))
        val adaptive = horizontal()
        adaptive.addView(text("POWER", 10f, muted(), true), LinearLayout.LayoutParams(0, -2, 1f))
        adaptive.addView(text(when (selectedMode) { "ECO" -> "CALM"; "SPORT+" -> "MAX"; "SPORT" -> "DYNAMIC"; else -> "SIGNATURE" }, 10f, accent(), true))
        box.addView(adaptive)
        return box
    }

    private fun headunitCenter(): LinearLayout {
        val box = card(12)
        val top = horizontal()
        top.addView(sectionTitle("HEADUNIT CONNECTION CENTER", "Visual connection state; existing vehicle stack remains unchanged."), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(text(if (isBydVehicleRuntime()) "CONNECTED" else "READY", 10f, if (isBydVehicleRuntime()) green() else muted(), true))
        box.addView(top)
        val flow = horizontal()
        listOf("VEHICLE" to "OK", "DRIVE" to selectedMode, "BATTERY" to "--", "SYSTEM" to "OK").forEach { (label, value) ->
            flow.addView(infoTile(label, value), LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginEnd = dp(6) })
        }
        box.addView(flow, margin(8))
        box.addView(text("Live UI • vehicle data • mode • battery • system state", 10f, muted(), false))
        return box
    }

    private fun soundStudio(): LinearLayout {
        val box = card(12)
        box.addView(sectionTitle("SOUND STUDIO", "Only installed audio profiles are selectable."), margin(6))
        val profiles = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = horizontal()
        val items = listOf("EV GT" to Pair("Electric GT", blue()), "APEX" to Pair("Performance", purple()))
        items.forEach { (name, meta) ->
            val p = card(10)
            p.setOnClickListener {
                selectedProfile = name
                if (name == "EV GT") engine.setLayers(ETronInspiredSoundProfile.layers) else engine.setLayers(ApexSoundProfile.layers)
                render()
            }
            p.addView(text(name, 18f, fg(), true))
            p.addView(text(meta.first, 10f, muted(), false).apply { setPadding(0, dp(3), 0, 0) })
            p.addView(text(if (selectedProfile == name) "● ACTIVE" else "○ AVAILABLE", 9f, meta.second, true).apply { setPadding(0, dp(8), 0, 0) })
            row.addView(p, LinearLayout.LayoutParams(dp(165), dp(92)).apply { marginEnd = dp(8) })
        }
        profiles.addView(row)
        box.addView(profiles)
        waveView = WaveformView(this).apply { setAccent(accent()); setDark(darkMode) }
        box.addView(waveView, LinearLayout.LayoutParams(-1, dp(82)).apply { topMargin = dp(10) })
        val dna = horizontal()
        dna.addView(text("Sound DNA", 10f, muted(), true), LinearLayout.LayoutParams(0, -2, 1f))
        dna.addView(text(when (selectedMode) { "ECO" -> "SMOOTH"; "SPORT+" -> "AGGRESSIVE"; else -> "BALANCED" }, 10f, accent(), true))
        box.addView(dna, margin(7))
        val sliders = horizontal()
        sliders.addView(soundSlider("Power", when (selectedMode) { "ECO" -> 48; "SPORT+" -> 88; else -> 66 }), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(7) })
        sliders.addView(soundSlider("Response", when (selectedMode) { "ECO" -> 48; "SPORT+" -> 84; else -> 76 }), LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(sliders)
        return box
    }

    private fun analytics(): LinearLayout {
        val box = card(12)
        box.addView(sectionTitle("REAL-TIME ANALYTICS", "Visual monitoring layer for the current drive session."), margin(7))
        box.addView(AnalyticsView(this).apply { setAccent(accent()); setDark(darkMode) }, LinearLayout.LayoutParams(-1, dp(118)))
        val row = horizontal()
        listOf("SPEED" to "0", "RPM" to "0", "THROTTLE" to "0%", "TEMP" to "--").forEach { (k, v) ->
            row.addView(infoTile(k, v), LinearLayout.LayoutParams(0, dp(66), 1f).apply { marginEnd = dp(6) })
        }
        box.addView(row, margin(8))
        return box
    }

    private fun themeAppearance(): LinearLayout {
        val box = card(12)
        box.addView(sectionTitle("THEME & APPEARANCE", "Manual dark/light control with a clean headunit-friendly palette."), margin(5))
        val line = horizontal()
        line.addView(text(if (darkMode) "DARK MODE" else "LIGHT MODE", 11f, accent(), true), LinearLayout.LayoutParams(0, -2, 1f))
        themeSwitch = Switch(this).apply {
            isChecked = darkMode
            setOnCheckedChangeListener { _, checked ->
                darkMode = checked
                prefs.edit().putBoolean("dark", checked).apply()
                buildUi()
            }
        }
        line.addView(themeSwitch)
        box.addView(line)
        val ambient = horizontal()
        ambient.addView(text("AMBIENT INTENSITY", 10f, muted(), true), LinearLayout.LayoutParams(0, -2, 1f))
        ambient.addView(text(if (selectedMode == "SPORT+") "85%" else "70%", 10f, fg(), true))
        box.addView(ambient, margin(5))
        box.addView(SeekBar(this).apply {
            max = 100; progress = if (selectedMode == "SPORT+") 85 else 70
            progressTintList = android.content.res.ColorStateList.valueOf(accent()); thumbTintList = android.content.res.ColorStateList.valueOf(fg())
        })
        return box
    }

    private fun settingsAndAbout(): LinearLayout {
        val box = card(12)
        box.addView(sectionTitle("EXPERIENCE", "Premium details without exposing controls the current stack cannot guarantee."), margin(6))
        listOf(
            "3D CAR SHOWCASE" to "Embedded visual layer",
            "AMBIENT SYNC" to "Mode-aware visual intensity",
            "SMART ADAPTATION" to "Mode changes update the cockpit identity",
            "PRIVACY FIRST" to "No vehicle control commands",
            "OTA READY" to "Uses the existing release/update flow"
        ).forEach { (title, sub) ->
            val row = horizontal()
            row.addView(text(title, 10f, fg(), true), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text(sub, 9f, muted(), false))
            box.addView(row, margin(7))
        }
        return box
    }

    private fun startStop(): Button {
        startButton = action(if (running) "STOP DRIVE SOUND" else "START DRIVE SOUND", accent(), Color.WHITE)
        startButton.textSize = 14f
        startButton.setOnClickListener {
            if (running) { engine.stop(); running = false }
            else { engine.start(); running = true; controller.apply(VehicleData(0f, 0f, 0f, false, 0f, 0f)) }
            startButton.text = if (running) "STOP DRIVE SOUND" else "START DRIVE SOUND"
            statusText?.text = if (running) "AUDIO RUNNING" else "AUDIO READY"
        }
        statusText = text(if (running) "AUDIO RUNNING" else "AUDIO READY", 1f, Color.TRANSPARENT, true)
        statusText.visibility = View.GONE
        return startButton
    }

    private fun soundSlider(label: String, progress: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(label, 9f, muted(), true))
        addView(SeekBar(this@NextGenCockpitActivity).apply {
            max = 100; this.progress = progress
            progressTintList = android.content.res.ColorStateList.valueOf(accent()); thumbTintList = android.content.res.ColorStateList.valueOf(fg())
        })
    }

    private fun infoTile(title: String, value: String): LinearLayout = card(7).apply {
        addView(text(title, 8f, muted(), true))
        addView(text(value, 13f, fg(), true).apply { setPadding(0, dp(5), 0, 0) })
    }

    private fun bigMetric(title: String, value: String, unit: String, bind: (TextView) -> Unit): LinearLayout = card(8).apply {
        gravity = Gravity.CENTER
        addView(text(title, 8f, muted(), true))
        val v = text(value, 20f, fg(), true).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }
        addView(v); addView(text(unit, 8f, muted(), false)); bind(v)
    }

    private fun applyTelemetry(packet: LiveTelemetry) {
        val d = packet.data
        controller.apply(d)
        speedValue.text = String.format(Locale.US, "%.0f", d.speedKph)
        rpmValue.text = String.format(Locale.US, "%.0f", d.rpm)
        throttleValue.text = "${(d.throttle * 100f).roundToInt()}"
        batteryValue.text = "--"
        dnaValue.text = "SOUND DNA  •  ${selectedProfile.uppercase(Locale.US)}  •  REAL-TIME"
        connectionStatus.text = "LIVE"
        connectionStatus.setTextColor(green())
    }

    private fun syncModeVisuals() {
        modeStatus.setTextColor(accent())
        carView.setAccent(accent())
        waveView.setAccent(accent())
        if (!liveMode) { connectionStatus.text = "SIM"; connectionStatus.setTextColor(muted()) }
    }

    private fun isBydVehicleRuntime(): Boolean = runCatching {
        Class.forName("android.hardware.bydauto.engine.BYDAutoEngineDevice")
        Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice")
        true
    }.getOrDefault(false)

    private fun resolveModelLabel(): String = android.os.Build.MODEL.trim().ifBlank { "BYD" }

    override fun onStop() {
        handler.removeCallbacks(poller); telemetryReceiver.stop(); engine.stop(); super.onStop()
    }

    private fun applySystemBars() {
        window.statusBarColor = bg(); window.navigationBarColor = bg()
        window.decorView.systemUiVisibility = if (darkMode) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    private fun horizontal() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun card(padding: Int) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding)); background = rounded(panel(), dp(16), stroke()) }
    private fun action(label: String, fill: Int, fgColor: Int) = Button(this).apply { text = label; textSize = 11f; setTextColor(fgColor); isAllCaps = false; background = rounded(fill, dp(12), stroke()); stateListAnimator = null }
    private fun sectionTitle(title: String, subtitle: String) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(text(title, 13f, fg(), true)); addView(text(subtitle, 10f, muted(), false).apply { setPadding(0, dp(3), 0, 0) }) }
    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply { text = value; textSize = size; setTextColor(color); typeface = android.graphics.Typeface.create("sans", if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL) }
    private fun metricParams() = LinearLayout.LayoutParams(0, dp(78), 1f).apply { marginEnd = dp(6) }
    private fun margin(bottom: Int) = LinearLayout.LayoutParams(-1, -2).apply { this.bottomMargin = dp(bottom) }
    private fun rounded(fill: Int, radius: Int, outline: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(fill); cornerRadius = radius.toFloat(); setStroke(dp(1), outline) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun bg() = if (darkMode) 0xFF05080C.toInt() else 0xFFF4F7FA.toInt()
    private fun panel() = if (darkMode) 0xFF0D141B.toInt() else Color.WHITE
    private fun panel2() = if (darkMode) 0xFF091018.toInt() else 0xFFE9EEF4.toInt()
    private fun stroke() = if (darkMode) 0xFF26333F.toInt() else 0xFFD6DEE8.toInt()
    private fun fg() = if (darkMode) Color.WHITE else 0xFF14202B.toInt()
    private fun muted() = if (darkMode) 0xFF8B98A6.toInt() else 0xFF657381.toInt()
    private fun blue() = 0xFF159BFF.toInt(); private fun green() = 0xFF34D27D.toInt(); private fun purple() = 0xFFA979FF.toInt(); private fun red() = 0xFFFF4A6E.toInt()
    private fun accent() = when (selectedMode) { "ECO" -> green(); "SPORT" -> blue(); "SPORT+" -> red(); else -> purple() }

    private class CarShowcaseView(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG); private var dark = true; private var accent = 0xFF159BFF.toInt()
        fun setDark(v: Boolean) { dark = v; invalidate() }; fun setAccent(v: Int) { accent = v; invalidate() }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val cx = w * .56f; val cy = h * .56f
            p.shader = RadialGradient(cx, cy, min(w, h) * .55f, intArrayOf(accent, 0x00000000), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP); c.drawCircle(cx, cy, min(w, h) * .45f, p); p.shader = null
            p.style = Paint.Style.FILL; p.color = if (dark) 0xFF14202B.toInt() else 0xFFE6ECF2.toInt()
            val car = Path(); car.moveTo(w*.16f,h*.64f); car.lineTo(w*.25f,h*.49f); car.lineTo(w*.38f,h*.39f); car.lineTo(w*.64f,h*.39f); car.lineTo(w*.78f,h*.49f); car.lineTo(w*.89f,h*.64f); car.lineTo(w*.82f,h*.75f); car.lineTo(w*.22f,h*.75f); car.close(); c.drawPath(car,p)
            p.color=0xFF020407; c.drawOval(w*.22f,h*.67f,w*.35f,h*.82f,p); c.drawOval(w*.69f,h*.67f,w*.82f,h*.82f,p)
            p.color=accent; c.drawRoundRect(w*.30f,h*.55f,w*.72f,h*.58f,8f,8f,p)
            p.color=Color.WHITE; c.drawRoundRect(w*.24f,h*.50f,w*.36f,h*.53f,5f,5f,p); c.drawRoundRect(w*.69f,h*.50f,w*.80f,h*.53f,5f,5f,p)
        }
    }

    private class WaveformView(context: Context) : View(context) {
        private val p=Paint(Paint.ANTI_ALIAS_FLAG); private var accent=0xFF159BFF.toInt(); private var dark=true; private var phase=0f
        fun setAccent(v:Int){accent=v;invalidate()}; fun setDark(v:Boolean){dark=v;invalidate()}
        override fun onDraw(c:Canvas){ val w=width.toFloat(); val h=height.toFloat(); val mid=h/2f; p.style=Paint.Style.STROKE; p.strokeWidth=resources.displayMetrics.density*2f; p.color=accent; val path=Path(); for(x in 0..width step 4){val t=x/w*14f+phase;val a=h*.24f*(.55f+.45f*((cos(t*.7f)+1f)/2f));val y=mid+sin(t*1.8f)*a*.55f+sin(t*4.1f)*a*.23f;if(x==0)path.moveTo(x.toFloat(),y)else path.lineTo(x.toFloat(),y)};c.drawPath(path,p);p.color=if(dark)0xFF344453.toInt()else 0xFFCCD5DE.toInt();p.strokeWidth=resources.displayMetrics.density;for(i in 1..5)c.drawLine(0f,h*i/6f,w,h*i/6f,p);phase+=.08f;postInvalidateDelayed(45L)}
    }

    private class AnalyticsView(context: Context) : View(context) {
        private val p=Paint(Paint.ANTI_ALIAS_FLAG); private var accent=0xFF159BFF.toInt(); private var dark=true; private var phase=0f
        fun setAccent(v:Int){accent=v;invalidate()}; fun setDark(v:Boolean){dark=v;invalidate()}
        override fun onDraw(c:Canvas){val w=width.toFloat();val h=height.toFloat();p.color=if(dark)0xFF18222C.toInt()else 0xFFDCE4EC.toInt();p.style=Paint.Style.STROKE;p.strokeWidth=resources.displayMetrics.density;for(i in 1..4)c.drawLine(0f,h*i/5f,w,h*i/5f,p);p.color=accent;p.strokeWidth=resources.displayMetrics.density*2f;val path=Path();for(x in 0..width step 5){val y=h*(.62f-.22f*sin(x/w*8f+phase)-.08f*sin(x/w*19f+phase*1.7f));if(x==0)path.moveTo(x.toFloat(),y)else path.lineTo(x.toFloat(),y)};c.drawPath(path,p);phase+=.05f;postInvalidateDelayed(60L)}
    }
}
