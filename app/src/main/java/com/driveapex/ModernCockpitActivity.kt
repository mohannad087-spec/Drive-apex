package com.driveapex

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.driveapex.audio.AudioScene
import com.driveapex.audio.ETronInspiredSoundProfile
import com.driveapex.audio.EngineSoundController
import com.driveapex.audio.LayeredSoundEngine
import com.driveapex.vehicle.LiveTelemetry
import com.driveapex.vehicle.UdpTelemetryReceiver
import java.util.Locale
import kotlin.math.roundToInt

class ModernCockpitActivity : Activity() {
    private val engine = LayeredSoundEngine(ETronInspiredSoundProfile.layers)
    private val controller = EngineSoundController(engine)
    private lateinit var receiver: UdpTelemetryReceiver
    private val handler = Handler(Looper.getMainLooper())
    private var live = false
    private var running = false
    private var page = PAGE_ENGINE
    private var lastScene = AudioScene.IDLE

    private lateinit var modelText: TextView
    private lateinit var speedText: TextView
    private lateinit var rpmText: TextView
    private lateinit var throttleText: TextView
    private lateinit var brakeText: TextView
    private lateinit var regenText: TextView
    private lateinit var sceneText: TextView
    private lateinit var profileText: TextView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var content: LinearLayout

    private val poller = object : Runnable {
        override fun run() {
            if (!live) return
            receiver.latest()?.let(::applyTelemetry)
            handler.postDelayed(this, 100L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiver = UdpTelemetryReceiver(this)
        buildShell()
        selectPage(PAGE_ENGINE)
    }

    private fun buildShell() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(18))
            setBackgroundColor(BG)
        }
        root.addView(header(), margin(10))
        root.addView(vehicleHero(), margin(10))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        root.addView(bottomNav(), margin(12))
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun header(): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        val brand = LinearLayout(this@ModernCockpitActivity).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(text("DriveApex", 23f, Color.WHITE, true))
        brand.addView(text("Engine Sound Simulator", 11f, MUTED, false))
        addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text("● LIVE", 11f, GREEN, true).apply { setPadding(dp(8), dp(5), dp(8), dp(5)); background = rounded(0xFF102E22.toInt(), 10, 0xFF1C573C.toInt()) })
    }

    private fun vehicleHero(): LinearLayout = card(14).apply {
        val title = LinearLayout(this@ModernCockpitActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        modelText = text(resolveBydModel(), 13f, Color.WHITE, true)
        title.addView(modelText, LinearLayout.LayoutParams(0, -2, 1f))
        title.addView(text("BYD", 10f, BLUE, true))
        addView(title)
        val art = text("BYD  •  CINEMATIC VEHICLE ART", 12f, MUTED, true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFF07131E.toInt(), 14, 0xFF12324A.toInt())
        }
        addView(art, LinearLayout.LayoutParams(-1, dp(130)).apply { topMargin = dp(10); bottomMargin = dp(8) })
        val metrics = row()
        metrics.addView(metric("SPEED", "--", "km/h") { speedText = it }, metricParams())
        metrics.addView(metric("RPM", "--", "rpm") { rpmText = it }, metricParams())
        metrics.addView(metric("THROTTLE", "--", "%") { throttleText = it }, metricParams())
        addView(metrics)
        val second = row()
        second.addView(metric("BRAKE", "--", "%") { brakeText = it }, metricParams())
        second.addView(metric("REGEN", "--", "%") { regenText = it }, metricParams())
        second.addView(metric("SCENE", "IDLE", "") { sceneText = it }, metricParams())
        addView(second, margin(6))
    }

    private fun enginePage() {
        content.addView(section("SOUND PROFILE", "Only profiles that exist in the current audio engine are shown."), margin(6))
        content.addView(profileCard("EV GT", "Electric GT", BLUE) { engine.setLayers(ETronInspiredSoundProfile.layers); profileText.text = "EV GT" }, margin(7))
        content.addView(profileCard("APEX", "Performance", PURPLE) { engine.setLayers(ApexSoundProfile.layers); profileText.text = "APEX" }, margin(7))
        profileText = text("EV GT", 11f, BLUE, true)
        content.addView(statusCard(), margin(7))
        startButton = action(if (running) "STOP SOUND" else "START DRIVE SOUND", BLUE, Color.WHITE)
        startButton.setOnClickListener { toggleSound() }
        content.addView(startButton, margin(9))
        val scenes = row()
        listOf("IDLE" to AudioScene.IDLE, "COAST" to AudioScene.COAST, "ACCEL" to AudioScene.ACCELERATION, "HARD" to AudioScene.HARD_ACCELERATION, "REGEN" to AudioScene.REGENERATION).forEach { (label, scene) ->
            scenes.addView(action(label, PANEL, SOFT).apply { setOnClickListener { engine.setScene(scene); lastScene = scene; sceneText.text = scene.name.replace('_', ' ') } }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        }
        content.addView(scenes)
    }

    private fun soundPage() {
        content.addView(section("ENGINE SOUNDS", "Profiles are wired to the existing LayeredSoundEngine."), margin(6))
        content.addView(profileCard("EV GT", "Electric GT", BLUE) { engine.setLayers(ETronInspiredSoundProfile.layers) }, margin(7))
        content.addView(profileCard("APEX", "Performance", PURPLE) { engine.setLayers(ApexSoundProfile.layers) }, margin(7))
        content.addView(card(12).apply {
            addView(text("Additional V6 / V8 / V10 profiles", 14f, Color.WHITE, true))
            addView(text("Not installed in the current repository audio assets — no fake selectable controls added.", 11f, MUTED, false).apply { setPadding(0, dp(6), 0, 0) })
        }, margin(7))
        content.addView(action("PREVIEW CURRENT SOUND", BLUE, Color.WHITE).apply { setOnClickListener { toggleSound() } })
    }

    private fun externalPage() {
        content.addView(section("EXTERNAL SOUND", "Output routing is informational until a verified BYD external-speaker API is present."), margin(6))
        content.addView(card(14).apply {
            addView(text("OUTPUT PATH", 10f, MUTED, true))
            addView(text("Android MEDIA / AudioTrack", 18f, Color.WHITE, true).apply { setPadding(0, dp(8), 0, 0) })
            addView(text("The current renderer uses USAGE_MEDIA. No unverified speaker toggle is exposed.", 11f, SOFT, false).apply { setPadding(0, dp(7), 0, 0) })
        }, margin(8))
    }

    private fun settingsPage() {
        content.addView(section("SETTINGS", "Only controls with a verified implementation are exposed."), margin(6))
        content.addView(action(if (live) "LIVE VEHICLE  •  ON" else "LIVE VEHICLE  •  OFF", if (live) 0xFF103723.toInt() else PANEL, if (live) GREEN else SOFT).apply { setOnClickListener { toggleLive(); selectPage(PAGE_SETTINGS) } }, margin(8))
        val bar = SeekBar(this).apply { max = 100; progress = 70; progressTintList = android.content.res.ColorStateList.valueOf(BLUE); thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) }
        content.addView(card(12).apply {
            addView(text("UI ACCENT INTENSITY", 11f, MUTED, true))
            addView(bar, LinearLayout.LayoutParams(-1, dp(48)))
            addView(text("Visual-only control; it does not alter audio gain.", 10f, MUTED, false))
        }, margin(8))
        content.addView(card(12).apply {
            addView(text("MODEL DETECTION", 10f, MUTED, true))
            addView(text(resolveBydModel(), 18f, Color.WHITE, true).apply { setPadding(0, dp(7), 0, 0) })
            addView(text("Embedded BYD vehicle art is selected by model when assets are installed.", 10f, MUTED, false).apply { setPadding(0, dp(5), 0, 0) })
        })
    }

    private fun aboutPage() {
        content.addView(section("ABOUT DRIVEAPEX", "Current project build and verified components."), margin(6))
        content.addView(card(14).apply {
            addView(text("DriveApex", 23f, Color.WHITE, true))
            addView(text("BYD electric vehicle engine sound simulator", 12f, MUTED, false).apply { setPadding(0, dp(3), 0, dp(12)) })
            addView(text("Telemetry: existing BYD/UDP path", 11f, SOFT, false))
            addView(text("Audio: LayeredSoundEngine", 11f, SOFT, false).apply { setPadding(0, dp(5), 0, 0) })
        })
    }

    private fun selectPage(target: Int) {
        page = target
        content.removeAllViews()
        when (target) { PAGE_ENGINE -> enginePage(); PAGE_SOUNDS -> soundPage(); PAGE_EXTERNAL -> externalPage(); PAGE_SETTINGS -> settingsPage(); PAGE_ABOUT -> aboutPage() }
    }

    private fun bottomNav(): LinearLayout = row().apply {
        val items = listOf("المحرك" to PAGE_ENGINE, "الأصوات" to PAGE_SOUNDS, "الخارجي" to PAGE_EXTERNAL, "الإعدادات" to PAGE_SETTINGS, "حول التطبيق" to PAGE_ABOUT)
        items.forEach { (label, target) -> addView(action(label, if (page == target) BLUE else PANEL, if (page == target) Color.WHITE else SOFT).apply { setOnClickListener { selectPage(target) } }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) }) }
    }

    private fun statusCard(): LinearLayout = card(11).apply {
        addView(text("CURRENT PROFILE", 9f, MUTED, true))
        addView(text("EV GT", 18f, BLUE, true).also { profileText = it }.apply { setPadding(0, dp(5), 0, 0) })
        statusText = text(if (running) "AUDIO RUNNING" else "AUDIO READY", 10f, if (running) GREEN else MUTED, true).apply { setPadding(0, dp(5), 0, 0) }
        addView(statusText)
    }

    private fun toggleSound() { if (running) { engine.stop(); running = false } else { engine.start(); running = true }; startButton.text = if (running) "STOP SOUND" else "START DRIVE SOUND"; statusText.text = if (running) "AUDIO RUNNING" else "AUDIO READY"; statusText.setTextColor(if (running) GREEN else MUTED) }
    private fun toggleLive() { live = !live; if (live) { receiver.start(); handler.post(poller) } else { receiver.stop(); handler.removeCallbacks(poller) } }

    private fun applyTelemetry(packet: LiveTelemetry) {
        val data = packet.data
        controller.apply(data)
        speedText.text = String.format(Locale.US, "%.0f", data.speedKph)
        rpmText.text = String.format(Locale.US, "%.0f", data.rpm)
        throttleText.text = "${(data.throttle * 100).roundToInt()}"
        brakeText.text = "${(data.brake * 100).roundToInt()}"
        regenText.text = "${(data.regen * 100).roundToInt()}"
        sceneText.text = lastScene.name.replace('_', ' ')
    }

    private fun resolveBydModel(): String { val raw = android.os.Build.MODEL.orEmpty().trim(); return if (raw.isBlank()) "BYD" else "BYD  •  $raw" }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun section(title: String, subtitle: String) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(text(title, 13f, Color.WHITE, true)); addView(text(subtitle, 10f, MUTED, false).apply { setPadding(0, dp(3), 0, 0) }) }
    private fun profileCard(title: String, subtitle: String, accent: Int, click: () -> Unit) = card(12).apply { setOnClickListener { click() }; addView(text(title, 18f, Color.WHITE, true)); addView(text(subtitle, 11f, MUTED, false).apply { setPadding(0, dp(3), 0, 0) }); addView(text("● AVAILABLE", 10f, accent, true).apply { setPadding(0, dp(8), 0, 0) }) }
    private fun metric(title: String, value: String, unit: String, bind: (TextView) -> Unit) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = rounded(PANEL_2, 11, STROKE); addView(text(title, 9f, MUTED, true)); val v=text(value, 20f, Color.WHITE, true); v.setPadding(0, dp(4), 0, 0); addView(v); if (unit.isNotBlank()) addView(text(unit, 8f, MUTED, false)); bind(v) }
    private fun action(label: String, fill: Int, fg: Int) = Button(this).apply { text=label; textSize=11f; setTextColor(fg); isAllCaps=false; background=rounded(fill, 11, STROKE); stateListAnimator=null }
    private fun card(padding: Int) = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding)); background=rounded(PANEL, 15, STROKE) }
    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply { text=value; textSize=size; setTextColor(color); typeface=Typeface.create("sans", if (bold) Typeface.BOLD else Typeface.NORMAL) }
    private fun metricParams() = LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginEnd=dp(5) }
    private fun margin(bottom: Int) = LinearLayout.LayoutParams(-1, -2).apply { this.bottomMargin=dp(bottom) }
    private fun rounded(fill: Int, radius: Int, stroke: Int) = GradientDrawable().apply { setColor(fill); cornerRadius=dp(radius).toFloat(); setStroke(dp(1), stroke) }
    private fun dp(v:Int)= (v*resources.displayMetrics.density).roundToInt()

    override fun onStop() { handler.removeCallbacks(poller); receiver.stop(); engine.stop(); super.onStop() }

    companion object {
        private const val PAGE_ENGINE=0; private const val PAGE_SOUNDS=1; private const val PAGE_EXTERNAL=2; private const val PAGE_SETTINGS=3; private const val PAGE_ABOUT=4
        private const val BG=0xFF05070A.toInt(); private const val PANEL=0xFF0E141A.toInt(); private const val PANEL_2=0xFF091018.toInt(); private const val STROKE=0xFF23303B.toInt(); private const val BLUE=0xFF1599FF.toInt(); private const val GREEN=0xFF31D17B.toInt(); private const val PURPLE=0xFFA978FF.toInt(); private const val SOFT=0xFFE8EDF3.toInt(); private const val MUTED=0xFF8A96A4.toInt()
    }
}
