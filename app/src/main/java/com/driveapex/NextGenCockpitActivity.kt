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
import com.driveapex.audio.ETronInspiredSoundProfile
import com.driveapex.audio.ApexSoundProfile
import com.driveapex.audio.EngineSoundController
import com.driveapex.audio.LayeredSoundEngine
import com.driveapex.vehicle.LiveTelemetry
import com.driveapex.vehicle.UdpTelemetryReceiver
import com.driveapex.vehicle.VehicleData
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class NextGenCockpitActivity : Activity() {
    private val prefs: SharedPreferences by lazy { getSharedPreferences("driveapex_ui", Context.MODE_PRIVATE) }
    private val engine = LayeredSoundEngine(ETronInspiredSoundProfile.layers)
    private val controller = EngineSoundController(engine)
    private lateinit var receiver: UdpTelemetryReceiver
    private lateinit var root: LinearLayout
    private lateinit var speed: TextView
    private lateinit var rpm: TextView
    private lateinit var throttle: TextView
    private lateinit var battery: TextView
    private lateinit var dna: TextView
    private lateinit var liveLabel: TextView
    private lateinit var modeLabel: TextView
    private lateinit var car: CarView
    private var dark = prefs.getBoolean("dark", true)
    private var live = false
    private var running = false
    private var mode = "SPORT+"
    private var profile = "EV GT"
    private val handler = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            if (!live) return
            receiver.latest()?.let(::renderTelemetry)
            handler.postDelayed(this, 100L)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        receiver = UdpTelemetryReceiver(context = this)
        build()
    }

    private fun build() {
        window.statusBarColor = bg()
        window.navigationBarColor = bg()
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(22))
            setBackgroundColor(bg())
        }
        ScrollView(this).apply { setBackgroundColor(bg()); isFillViewport = true; addView(root); setContentView(this) }
        render()
    }

    private fun render() {
        root.removeAllViews()
        root.addView(header(), margin(10))
        root.addView(hero(), margin(10))
        root.addView(modes(), margin(10))
        root.addView(soundStudio(), margin(10))
        root.addView(headunit(), margin(10))
        root.addView(analytics(), margin(10))
        root.addView(theme(), margin(10))
        root.addView(experience(), margin(10))
        root.addView(startButton())
        syncVisuals()
    }

    private fun header(): LinearLayout {
        val row = hRow()
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(text("DriveApex", 25f, fg(), true))
        brand.addView(text("BYD SOUND EXPERIENCE", 10f, muted(), true))
        row.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val ready = if (isBydRuntime()) "HEADUNIT • CONNECTED" else "HEADUNIT • READY"
        row.addView(text(ready, 10f, if (isBydRuntime()) green() else muted(), true).apply {
            setPadding(dp(10), dp(7), dp(10), dp(7)); background = rounded(panel2(), dp(13), stroke())
        })
        return row
    }

    private fun hero(): LinearLayout {
        val box = card(14)
        val top = hRow()
        modeLabel = text(mode, 11f, accent(), true)
        top.addView(modeLabel, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(text("BYD • ${model()}", 10f, muted(), true))
        box.addView(top)
        car = CarView(this).apply { setDark(dark); setAccent(accent()) }
        box.addView(car, LinearLayout.LayoutParams(-1, dp(200)).apply { topMargin = dp(8); bottomMargin = dp(8) })
        val row = hRow()
        row.addView(metric("SPEED", "0", "km/h") { speed = it }, metric())
        row.addView(metric("MOTOR", "0", "RPM") { rpm = it }, metric())
        row.addView(metric("THROTTLE", "0", "%") { throttle = it }, metric())
        row.addView(metric("BATTERY", "--", "%") { battery = it }, metric())
        box.addView(row)
        val lower = hRow()
        dna = text("SOUND DNA • BALANCED • REAL-TIME", 10f, purple(), true)
        lower.addView(dna, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(8) })
        liveLabel = text(if (live) "LIVE" else "SIM", 10f, if (live) green() else muted(), true)
        lower.addView(liveLabel, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        box.addView(lower)
        return box
    }

    private fun modes(): LinearLayout {
        val box = card(12)
        box.addView(section("ADAPTIVE COCKPIT", "Visual identity follows the selected drive mode."), margin(6))
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = hRow()
        listOf("ECO" to green(), "SPORT" to blue(), "SPORT+" to red(), "CUSTOM" to purple()).forEach { (m, c) ->
            row.addView(action(m, if (mode == m) c else panel2(), if (mode == m) Color.WHITE else fg()).apply { setOnClickListener { mode = m; render() } }, LinearLayout.LayoutParams(dp(105), dp(50)).apply { marginEnd = dp(7) })
        }
        scroll.addView(row); box.addView(scroll); return box
    }

    private fun soundStudio(): LinearLayout {
        val box = card(12)
        box.addView(section("SOUND STUDIO", "Only installed profiles are selectable."), margin(6))
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = hRow()
        listOf("EV GT" to "Electric GT" to blue(), "APEX" to "Performance" to purple()).forEach { item ->
            val p = card(10)
            p.setOnClickListener {
                profile = item.first
                if (profile == "EV GT") engine.setLayers(ETronInspiredSoundProfile.layers) else engine.setLayers(ApexSoundProfile.layers)
                render()
            }
            p.addView(text(item.first, 18f, fg(), true))
            p.addView(text(item.second, 10f, muted(), false).apply { setPadding(0, dp(3), 0, 0) })
            p.addView(text(if (profile == item.first) "● ACTIVE" else "○ AVAILABLE", 9f, item.third, true).apply { setPadding(0, dp(8), 0, 0) })
            row.addView(p, LinearLayout.LayoutParams(dp(160), dp(90)).apply { marginEnd = dp(8) })
        }
        scroll.addView(row); box.addView(scroll)
        box.addView(WaveView(this).apply { setAccent(accent()); setDark(dark) }, LinearLayout.LayoutParams(-1, dp(80)).apply { topMargin = dp(10) })
        box.addView(text("DNA • ${profile.uppercase(Locale.US)} • ${if (mode == "ECO") "SMOOTH" else if (mode == "SPORT+") "AGGRESSIVE" else "BALANCED"}", 10f, accent(), true).apply { setPadding(0, dp(8), 0, 0) })
        return box
    }

    private fun headunit(): LinearLayout {
        val box = card(12)
        box.addView(section("HEADUNIT CONNECTION", "Existing vehicle stack supplies the runtime state."), margin(6))
        val row = hRow()
        listOf("VEHICLE" to "OK", "DRIVE" to mode, "BATTERY" to "--", "SYSTEM" to "OK").forEach { (k, v) -> row.addView(info(k, v), LinearLayout.LayoutParams(0, dp(68), 1f).apply { marginEnd = dp(6) }) }
        box.addView(row); return box
    }

    private fun analytics(): LinearLayout {
        val box = card(12)
        box.addView(section("REAL-TIME ANALYTICS", "Visual layer for the current drive session."), margin(6))
        box.addView(AnalyticsView(this).apply { setAccent(accent()); setDark(dark) }, LinearLayout.LayoutParams(-1, dp(110)))
        return box
    }

    private fun theme(): LinearLayout {
        val box = card(12)
        box.addView(section("THEME & APPEARANCE", "Dark / Light mode is stored locally."), margin(5))
        val row = hRow()
        row.addView(text(if (dark) "DARK MODE" else "LIGHT MODE", 11f, accent(), true), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Switch(this).apply { isChecked = dark; setOnCheckedChangeListener { _, checked -> dark = checked; prefs.edit().putBoolean("dark", checked).apply(); build() } })
        box.addView(row)
        return box
    }

    private fun experience(): LinearLayout {
        val box = card(12)
        box.addView(section("NEXT-GEN EXPERIENCE", "Premium visual concepts kept separate from vehicle control."), margin(6))
        listOf("3D CAR SHOWCASE" to "Embedded visual", "AMBIENT SYNC" to "Mode-aware glow", "SMART ADAPTATION" to "Adaptive cockpit", "PRIVACY FIRST" to "No vehicle control", "OTA READY" to "Existing update flow").forEach { (a, b) ->
            val row = hRow(); row.addView(text(a, 10f, fg(), true), LinearLayout.LayoutParams(0, -2, 1f)); row.addView(text(b, 9f, muted(), false)); box.addView(row, margin(6))
        }
        return box
    }

    private fun startButton(): Button = action(if (running) "STOP DRIVE SOUND" else "START DRIVE SOUND", accent(), Color.WHITE).apply {
        textSize = 14f; setOnClickListener { if (running) { engine.stop(); running = false } else { engine.start(); running = true; controller.apply(VehicleData(0f, 0f, 0f, false, 0f, 0f)) }; text = if (running) "STOP DRIVE SOUND" else "START DRIVE SOUND" }
    }

    private fun renderTelemetry(packet: LiveTelemetry) {
        val d = packet.data
        controller.apply(d)
        speed.text = String.format(Locale.US, "%.0f", d.speedKph)
        rpm.text = String.format(Locale.US, "%.0f", d.rpm)
        throttle.text = "${(d.throttle * 100f).roundToInt()}"
        battery.text = "--"
        dna.text = "SOUND DNA • ${profile.uppercase(Locale.US)} • REAL-TIME"
        liveLabel.text = "LIVE"; liveLabel.setTextColor(green())
    }

    private fun syncVisuals() { modeLabel.setTextColor(accent()); car.setAccent(accent()) }
    private fun isBydRuntime(): Boolean = runCatching { Class.forName("android.hardware.bydauto.engine.BYDAutoEngineDevice"); Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice"); true }.getOrDefault(false)
    private fun model(): String = android.os.Build.MODEL.trim().ifBlank { "BYD" }

    private fun hRow() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun card(p:Int) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(p),dp(p),dp(p),dp(p)); background = rounded(panel(),dp(16),stroke()) }
    private fun section(a:String,b:String) = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; addView(text(a,13f,fg(),true)); addView(text(b,10f,muted(),false).apply{setPadding(0,dp(3),0,0)}) }
    private fun metric(a:String,b:String,c:String,bind:(TextView)->Unit)=card(8).apply{gravity=Gravity.CENTER;addView(text(a,8f,muted(),true));val v=text(b,20f,fg(),true);addView(v);addView(text(c,8f,muted(),false));bind(v)}
    private fun info(a:String,b:String)=card(7).apply{addView(text(a,8f,muted(),true));addView(text(b,13f,fg(),true).apply{setPadding(0,dp(4),0,0)})}
    private fun action(a:String,fill:Int,color:Int)=Button(this).apply{text=a;textSize=11f;setTextColor(color);isAllCaps=false;background=rounded(fill,dp(12),stroke());stateListAnimator=null}
    private fun text(s:String,z:Float,c:Int,b:Boolean)=TextView(this).apply{text=s;textSize=z;setTextColor(c);typeface=android.graphics.Typeface.create("sans",if(b)android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)}
    private fun metric()=LinearLayout.LayoutParams(0,dp(78),1f).apply{marginEnd=dp(6)}
    private fun margin(b:Int)=LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(b)}
    private fun rounded(fill:Int,r:Int,strokeColor:Int)=android.graphics.drawable.GradientDrawable().apply{setColor(fill);cornerRadius=r.toFloat();setStroke(dp(1),strokeColor)}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).roundToInt()
    private fun bg()=if(dark)0xFF05080C.toInt()else 0xFFF4F7FA.toInt()
    private fun panel()=if(dark)0xFF0D141B.toInt()else Color.WHITE
    private fun panel2()=if(dark)0xFF091018.toInt()else 0xFFE9EEF4.toInt()
    private fun stroke()=if(dark)0xFF26333F.toInt()else 0xFFD6DEE8.toInt()
    private fun fg()=if(dark)Color.WHITE else 0xFF14202B.toInt()
    private fun muted()=if(dark)0xFF8B98A6.toInt()else 0xFF657381.toInt()
    private fun blue()=0xFF159BFF.toInt();private fun green()=0xFF34D27D.toInt();private fun purple()=0xFFA979FF.toInt();private fun red()=0xFFFF4A6E.toInt()
    private fun accent()=when(mode){"ECO"->green();"SPORT"->blue();"SPORT+"->red();else->purple()}

    override fun onStop(){handler.removeCallbacks(poll);receiver.stop();engine.stop();super.onStop()}

    private class CarView(c:Context):View(c){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var dark=true;private var accent=0xFF159BFF.toInt()
        fun setDark(v:Boolean){dark=v;invalidate()};fun setAccent(v:Int){accent=v;invalidate()}
        override fun onDraw(c:Canvas){val w=width.toFloat();val h=height.toFloat();val cx=w*.57f;val cy=h*.56f;p.shader=RadialGradient(cx,cy,min(w,h)*.56f,intArrayOf(accent,0x00000000),floatArrayOf(0f,1f),Shader.TileMode.CLAMP);c.drawCircle(cx,cy,min(w,h)*.46f,p);p.shader=null;p.style=Paint.Style.FILL;p.color=if(dark)0xFF15222E.toInt()else 0xFFE5EBF1.toInt();val car=Path();car.moveTo(w*.15f,h*.64f);car.lineTo(w*.25f,h*.49f);car.lineTo(w*.38f,h*.39f);car.lineTo(w*.64f,h*.39f);car.lineTo(w*.79f,h*.49f);car.lineTo(w*.89f,h*.64f);car.lineTo(w*.82f,h*.75f);car.lineTo(w*.22f,h*.75f);car.close();c.drawPath(car,p);p.color=0xFF020407.toInt();c.drawOval(w*.21f,h*.68f,w*.35f,h*.83f,p);c.drawOval(w*.69f,h*.68f,w*.83f,h*.83f,p);p.color=accent;c.drawRoundRect(w*.30f,h*.55f,w*.72f,h*.58f,8f,8f,p);p.color=Color.WHITE;c.drawRoundRect(w*.24f,h*.50f,w*.36f,h*.53f,5f,5f,p);c.drawRoundRect(w*.69f,h*.50f,w*.80f,h*.53f,5f,5f,p)}
    }

    private class WaveView(c:Context):View(c){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var accent=0xFF159BFF.toInt();private var dark=true
        fun setAccent(v:Int){accent=v;invalidate()};fun setDark(v:Boolean){dark=v;invalidate()}
        override fun onDraw(c:Canvas){val w=width.toFloat();val h=height.toFloat();val phase=(SystemClock.uptimeMillis()%100000L).toFloat()*.0012f;p.style=Paint.Style.STROKE;p.strokeWidth=resources.displayMetrics.density*2f;p.color=accent;val path=Path();for(x in 0 until width step 4){val t=x/w*14f+phase;val y=h/2f+sin(t*2f)*h*.18f+sin(t*5f)*h*.07f;if(x==0)path.moveTo(x.toFloat(),y)else path.lineTo(x.toFloat(),y)};c.drawPath(path,p);p.color=if(dark)0xFF344453.toInt()else 0xFFCCD5DE.toInt();p.strokeWidth=resources.displayMetrics.density;for(i in 1..5)c.drawLine(0f,h*i/6f,w,h*i/6f,p);postInvalidateDelayed(45L)}
    }

    private class AnalyticsView(c:Context):View(c){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var accent=0xFF159BFF.toInt();private var dark=true
        fun setAccent(v:Int){accent=v;invalidate()};fun setDark(v:Boolean){dark=v;invalidate()}
        override fun onDraw(c:Canvas){val w=width.toFloat();val h=height.toFloat();val phase=(SystemClock.uptimeMillis()%100000L).toFloat()*.0007f;p.color=if(dark)0xFF1B2630.toInt()else 0xFFDCE4EC.toInt();p.style=Paint.Style.STROKE;p.strokeWidth=resources.displayMetrics.density;for(i in 1..4)c.drawLine(0f,h*i/5f,w,h*i/5f,p);p.color=accent;p.strokeWidth=resources.displayMetrics.density*2f;val path=Path();for(x in 0 until width step 5){val y=h*(.62f-.22f*sin(x/w*8f+phase)-.08f*sin(x/w*19f+phase*1.7f));if(x==0)path.moveTo(x.toFloat(),y)else path.lineTo(x.toFloat(),y)};c.drawPath(path,p);postInvalidateDelayed(60L)}
    }
}
