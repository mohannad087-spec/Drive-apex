package com.driveapex

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
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
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Next-generation DriveApex visual shell. Existing vehicle/audio stack is reused unchanged. */
class NextGenCockpitActivity : Activity() {
    private val prefs: SharedPreferences by lazy { getSharedPreferences("driveapex_ui", Context.MODE_PRIVATE) }
    private val engine = LayeredSoundEngine(ETronInspiredSoundProfile.layers)
    private val controller = EngineSoundController(engine)
    private lateinit var telemetryReceiver: UdpTelemetryReceiver
    private lateinit var root: LinearLayout
    private lateinit var speedValue: TextView
    private lateinit var rpmValue: TextView
    private lateinit var throttleValue: TextView
    private lateinit var batteryValue: TextView
    private lateinit var dnaValue: TextView
    private lateinit var connectionValue: TextView
    private lateinit var modeValue: TextView
    private lateinit var carView: CarShowcaseView
    private var darkMode = prefs.getBoolean("dark", true)
    private var liveMode = false
    private var running = false
    private var selectedMode = "SPORT+"
    private var selectedProfile = "EV GT"
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

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
        ScrollView(this).apply {
            setBackgroundColor(bg())
            isFillViewport = true
            addView(root)
            setContentView(this)
        }
        render()
    }

    private fun render() {
        root.removeAllViews()
        root.addView(header(), margin(10))
        root.addView(cockpitHero(), margin(10))
        root.addView(adaptiveModes(), margin(10))
        root.addView(soundStudio(), margin(10))
        root.addView(headunitCenter(), margin(10))
        root.addView(analytics(), margin(10))
        root.addView(themeCard(), margin(10))
        root.addView(experienceCard(), margin(10))
        root.addView(startStopButton())
        syncVisuals()
    }

    private fun header(): LinearLayout {
        val row = hRow()
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(text("DriveApex", 25f, fg(), true))
        brand.addView(text("BYD SOUND EXPERIENCE", 10f, muted(), true))
        row.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val connected = isBydRuntime()
        row.addView(text(if (connected) "HEADUNIT • CONNECTED" else "HEADUNIT • READY", 10f, if (connected) green() else muted(), true).apply {
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(if (connected) 0xFF0F2D20.toInt() else panel2(), dp(13), stroke())
        })
        return row
    }

    private fun cockpitHero(): LinearLayout {
        val box = card(14)
        val top = hRow()
        modeValue = text(selectedMode, 11f, accent(), true)
        top.addView(modeValue, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(text("BYD • ${resolveModelLabel()}", 10f, muted(), true))
        box.addView(top)
        carView = CarShowcaseView(this).apply { setDark(darkMode); setAccent(accent()) }
        box.addView(carView, LinearLayout.LayoutParams(-1, dp(200)).apply { topMargin = dp(8); bottomMargin = dp(8) })
        val metrics = hRow()
        metrics.addView(metric("SPEED", "0", "km/h") { speedValue = it }, metricParams())
        metrics.addView(metric("MOTOR", "0", "RPM") { rpmValue = it }, metricParams())
        metrics.addView(metric("THROTTLE", "0", "%") { throttleValue = it }, metricParams())
        metrics.addView(metric("BATTERY", "--", "%") { batteryValue = it }, metricParams())
        box.addView(metrics)
        val lower = hRow()
        dnaValue = text("SOUND DNA • BALANCED • REAL-TIME", 10f, purple(), true)
        lower.addView(dnaValue, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(8) })
        connectionValue = text(if (liveMode) "LIVE" else "SIM", 10f, if (liveMode) green() else muted(), true)
        lower.addView(connectionValue, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        box.addView(lower)
        return box
    }

    private fun adaptiveModes(): LinearLayout {
        val box = card(12)
        box.addView(section("ADAPTIVE COCKPIT", "Mode changes the visual personality of the cockpit."), margin(5))
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val modes = hRow()
        listOf("ECO" to green(), "SPORT" to blue(), "SPORT+" to red(), "CUSTOM" to purple()).forEach { (mode, color) ->
            val b = action(mode, if (selectedMode == mode) color else panel2(), if (selectedMode == mode) Color.WHITE else fg())
            b.setOnClickListener { selectedMode = mode; render() }
            modes.addView(b, LinearLayout.LayoutParams(dp(105), dp(50)).apply { marginEnd = dp(7) })
        }
        scroll.addView(modes)
        box.addView(scroll, margin(4))
        return box
    }

    private fun soundStudio(): LinearLayout {
        val box = card(12)
        box.addView(section("SOUND STUDIO", "Real profiles only; no placeholder V6/V8/V10 entries."), margin(6))
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = hRow()
        listOf("EV GT" to Pair("Electric GT", blue()), "APEX" to Pair("Performance", purple())).forEach { (name, meta) ->
            val p = card(10)
            p.setOnClickListener {
                selectedProfile = name
                if (name == "EV GT") engine.setLayers(ETronInspiredSoundProfile.layers) else engine.setLayers(ApexSoundProfile.layers)
                render()
            }
            p.addView(text(name, 18f, fg(), true))
            p.addView(text(meta.first, 10f, muted(), false).apply { setPadding(0, dp(3), 0, 0) })
            p.addView(text(if (selectedProfile == name) "● ACTIVE" else "○ AVAILABLE", 9f, meta.second, true).apply { setPadding(0, dp(8), 0, 0) })
            row.addView(p, LinearLayout.LayoutParams(dp(160), dp(90)).apply { marginEnd = dp(8) })
        }
        scroll.addView(row)
        box.addView(scroll)
        box.addView(WaveformView(this).apply { setAccent(accent()); setDark(darkMode) }, LinearLayout.LayoutParams(-1, dp(80)).apply { topMargin = dp(10) })
        box.addView(text("DNA • ${selectedProfile.uppercase(Locale.US)} • ${when (selectedMode) { "ECO" -> "SMOOTH"; "SPORT+" -> "AGGRESSIVE"; else -> "BALANCED" }}", 10f, accent(), true).apply { setPadding(0, dp(8), 0, 0) })
        return box
    }

    private fun headunitCenter(): LinearLayout {
        val box = card(12)
        val top = hRow()
        top.addView(section("HEADUNIT CONNECTION", "Visual state only; vehicle logic remains in the existing telemetry path."), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(text(if (isBydRuntime()) "CONNECTED" else "READY", 10f, if (isBydRuntime()) green() else muted(), true))
        box.addView(top, margin(6))
        val row = hRow()
        listOf("VEHICLE" to "OK", "DRIVE" to selectedMode, "BATTERY" to "--", "SYSTEM" to "OK").forEach { (a, b) ->
            row.addView(info(a, b), LinearLayout.LayoutParams(0, dp(68), 1f).apply { marginEnd = dp(6) })
        }
        box.addView(row)
        return box
    }

    private fun analytics(): LinearLayout {
        val box = card(12)
        box.addView(section("REAL-TIME ANALYTICS", "Visual telemetry history for the current session."), margin(6))
        box.addView(AnalyticsView(this).apply { setAccent(accent()); setDark(darkMode) }, LinearLayout.LayoutParams(-1, dp(110)))
        return box
    }

    private fun themeCard(): LinearLayout {
        val box = card(12)
        box.addView(section("THEME & APPEARANCE", "Dark and Light are stored locally for the headunit."), margin(5))
        val row = hRow()
        row.addView(text(if (darkMode) "DARK MODE" else "LIGHT MODE", 11f, accent(), true), LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this).apply {
            isChecked = darkMode
            setOnCheckedChangeListener { _, checked ->
                darkMode = checked
                prefs.edit().putBoolean("dark", checked).apply()
                buildUi()
            }
        }
        row.addView(sw)
        box.addView(row)
        return box
    }

    private fun experienceCard(): LinearLayout {
        val box = card(12)
        box.addView(section("NEXT-GEN EXPERIENCE", "Design features prepared for the DriveApex visual system."), margin(6))
        listOf(
            "3D CAR SHOWCASE" to "Embedded visual layer",
            "AMBIENT SYNC" to "Mode-aware glow",
            "SMART ADAPTATION" to "Adaptive cockpit identity",
            "PRIVACY FIRST" to "No vehicle control commands",
            "OTA READY" to "Existing update channel"
        ).forEach { (title, subtitle) ->
            val row = hRow()
            row.addView(text(title, 10f, fg(), true), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text(subtitle, 9f, muted(), false))
            box.addView(row, margin(7))
        }
        return box
    }

    private fun startStopButton(): Button = action(if (running) "STOP DRIVE SOUND" else "START DRIVE SOUND", accent(), Color.WHITE).apply {
        textSize = 14f
        setOnClickListener {
            if (running) { engine.stop(); running = false } else { engine.start(); running = true; controller.apply(VehicleData(0f, 0f, 0f, false, 0f, 0f)) }
            text = if (running) "STOP DRIVE SOUND" else "START DRIVE SOUND"
        }
    }

    private fun applyTelemetry(packet: LiveTelemetry) {
        val d = packet.data
        controller.apply(d)
        speedValue.text = String.format(Locale.US, "%.0f", d.speedKph)
        rpmValue.text = String.format(Locale.US, "%.0f", d.rpm)
        throttleValue.text = "${(d.throttle * 100f).roundToInt()}"
        batteryValue.text = "--"
        dnaValue.text = "SOUND DNA • ${selectedProfile.uppercase(Locale.US)} • REAL-TIME"
        connectionValue.text = "LIVE"
        connectionValue.setTextColor(green())
    }

    private fun syncVisuals() {
        modeValue.setTextColor(accent())
        carView.setAccent(accent())
        if (!liveMode) { connectionValue.text = "SIM"; connectionValue.setTextColor(muted()) }
    }

    private fun isBydRuntime(): Boolean = runCatching {
        Class.forName("android.hardware.bydauto.engine.BYDAutoEngineDevice")
        Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice")
        true
    }.getOrDefault(false)

    private fun resolveModelLabel(): String = android.os.Build.MODEL.trim().ifBlank { "BYD" }

    private fun applySystemBars() {
        window.statusBarColor = bg(); window.navigationBarColor = bg()
        window.decorView.systemUiVisibility = if (darkMode) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    private fun hRow() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun section(title: String, subtitle: String) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(text(title, 13f, fg(), true)); addView(text(subtitle, 10f, muted(), false).apply { setPadding(0, dp(3), 0, 0) }) }
    private fun metric(title: String, value: String, unit: String, bind: (TextView) -> Unit) = card(8).apply { gravity = Gravity.CENTER; addView(text(title, 8f, muted(), true)); val v=text(value,20f,fg(),true); v.gravity=Gravity.CENTER; v.setPadding(0,dp(4),0,0); addView(v); addView(text(unit,8f,muted(),false)); bind(v) }
    private fun info(title: String, value: String) = card(7).apply { addView(text(title,8f,muted(),true)); addView(text(value,13f,fg(),true).apply { setPadding(0,dp(4),0,0) }) }
    private fun action(label: String, fill: Int, fgColor: Int) = Button(this).apply { text=label; textSize=11f; setTextColor(fgColor); isAllCaps=false; background=rounded(fill,dp(12),stroke()); stateListAnimator=null }
    private fun card(padding: Int) = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(padding),dp(padding),dp(padding),dp(padding)); background=rounded(panel(),dp(16),stroke()) }
    private fun text(value:String,size:Float,color:Int,bold:Boolean)=TextView(this).apply{ text=value; textSize=size; setTextColor(color); typeface=android.graphics.Typeface.create("sans",if(bold)android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)}
    private fun metricParams()=LinearLayout.LayoutParams(0,dp(78),1f).apply{marginEnd=dp(6)}
    private fun margin(bottom:Int)=LinearLayout.LayoutParams(-1,-2).apply{this.bottomMargin=dp(bottom)}
    private fun rounded(fill:Int,radius:Int,outline:Int)=android.graphics.drawable.GradientDrawable().apply{setColor(fill);cornerRadius=radius.toFloat();setStroke(dp(1),outline)}
    private fun dp(v:Int)= (v*resources.displayMetrics.density).roundToInt()
    private fun bg()=if(darkMode)0xFF05080C.toInt()else 0xFFF4F7FA.toInt()
    private fun panel()=if(darkMode)0xFF0D141B.toInt()else Color.WHITE
    private fun panel2()=if(darkMode)0xFF091018.toInt()else 0xFFE9EEF4.toInt()
    private fun stroke()=if(darkMode)0xFF26333F.toInt()else 0xFFD6DEE8.toInt()
    private fun fg()=if(darkMode)Color.WHITE else 0xFF14202B.toInt()
    private fun muted()=if(darkMode)0xFF8B98A6.toInt()else 0xFF657381.toInt()
    private fun blue()=0xFF159BFF.toInt(); private fun green()=0xFF34D27D.toInt(); private fun purple()=0xFFA979FF.toInt(); private fun red()=0xFFFF4A6E.toInt()
    private fun accent()=when(selectedMode){"ECO"->green();"SPORT"->blue();"SPORT+"->red();else->purple()}

    override fun onStop(){ handler.removeCallbacks(poller); telemetryReceiver.stop(); engine.stop(); super.onStop() }

    private class CarShowcaseView(context: Context):View(context){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG); private var dark=true; private var accent=0xFF159BFF.toInt()
        fun setDark(v:Boolean){dark=v;invalidate()}; fun setAccent(v:Int){accent=v;invalidate()}
        override fun onDraw(c:Canvas){
            val w=width.toFloat(); val h=height.toFloat(); val cx=w*.57f; val cy=h*.56f
            p.shader=RadialGradient(cx,cy,min(w,h)*.56f,intArrayOf(accent,0x00000000),floatArrayOf(0f,1f),Shader.TileMode.CLAMP);c.drawCircle(cx,cy,min(w,h)*.46f,p);p.shader=null
            p.style=Paint.Style.FILL;p.color=if(dark)0xFF15222E.toInt()else 0xFFE5EBF1.toInt()
            val car=Path();car.moveTo(w*.15f,h*.64f);car.lineTo(w*.25f,h*.49f);car.lineTo(w*.38f,h*.39f);car.lineTo(w*.64f,h*.39f);car.lineTo(w*.79f,h*.49f);car.lineTo(w*.89f,h*.64f);car.lineTo(w*.82f,h*.75f);car.lineTo(w*.22f,h*.75f);car.close();c.drawPath(car,p)
            p.color=0xFF020407;c.drawOval(w*.21f,h*.68f,w*.35f,h*.83f,p);c.drawOval(w*.69f,h*.68f,w*.83f,h*.83f,p)
            p.color=accent;c.drawRoundRect(w*.30f,h*.55f,w*.72f,h*.58f,dpSafe(8f),dpSafe(8f),p)
            p.color=Color.WHITE;c.drawRoundRect(w*.24f,h*.50f,w*.36f,h*.53f,dpSafe(5f),dpSafe(5f),p);c.drawRoundRect(w*.69f,h*.50f,w*.80f,h*.53f,dpSafe(5f),dpSafe(5f),p)
        }
        private fun dpSafe(v:Float)=v*resources.displayMetrics.density
    }

    private class WaveformView(context:Context):View(context){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var accent=0xFF159BFF.toInt();private var dark=true
        fun setAccent(v:Int){accent=v;invalidate()};fun setDark(v:Boolean){dark=v;invalidate()}
        override fun onDraw(c:Canvas){
            val w=width.toFloat();val h=height.toFloat();val mid=h/2f;val phase=(SystemClock.uptimeMillis()%100000L).toFloat()*.0012f
            p.style=Paint.Style.STROKE;p.strokeWidth=resources.displayMetrics.density*2f;p.color=accent
            val path=Path();for(x in 0 until width step 4){val t=x/w*14f+phase;val y=mid+sin(t*2.0f)*h*.18f+sin(t*5.0f)*h*.07f;if(x==0)path.moveTo(x.toFloat(),y)else path.lineTo(x.toFloat(),y)};c.drawPath(path,p)
            p.color=if(dark)0xFF344453.toInt()else 0xFFCCD5DE.toInt();p.strokeWidth=resources.displayMetrics.density;for(i in 1..5)c.drawLine(0f,h*i/6f,w,h*i/6f,p);postInvalidateDelayed(45L)
        }
    }

    private class AnalyticsView(context:Context):View(context){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var accent=0xFF159BFF.toInt();private var dark=true
        fun setAccent(v:Int){accent=v;invalidate()};fun setDark(v:Boolean){dark=v;invalidate()}
        override fun onDraw(c:Canvas){
            val w=width.toFloat();val h=height.toFloat();val phase=(SystemClock.uptimeMillis()%100000L).toFloat()*.0007f
            p.color=if(dark)0xFF1B2630.toInt()else 0xFFDCE4EC.toInt();p.style=Paint.Style.STROKE;p.strokeWidth=resources.displayMetrics.density;for(i in 1..4)c.drawLine(0f,h*i/5f,w,h*i/5f,p)
            p.color=accent;p.strokeWidth=resources.displayMetrics.density*2f;val path=Path();for(x in 0 until width step 5){val y=h*(.62f-.22f*sin(x/w*8f+phase)-.08f*sin(x/w*19f+phase*1.7f));if(x==0)path.moveTo(x.toFloat(),y)else path.lineTo(x.toFloat(),y)};c.drawPath(path,p);postInvalidateDelayed(60L)
        }
    }
}
