package com.driveapex

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * Visual-only shell for the main DriveApex screen.
 *
 * This class owns layout/styling and exposes view references. Vehicle, telemetry,
 * audio and update behavior remain in MainActivity and are only reached through callbacks.
 */
class DriveApexUiShell(
    private val activity: MainActivity,
    private val onLiveToggle: () -> Unit,
    private val onQuickScene: (String) -> Unit,
    private val onProfile: (String) -> Unit,
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
    private val onAdbSetup: () -> Unit,
    private val onDiagnostics: () -> Unit,
    private val onResetGenome: () -> Unit,
    private val onCheckUpdate: () -> Unit,
) {
    data class Views(
        val root: ScrollView,
        val rpmValue: TextView,
        val telemetry: TextView,
        val sceneValue: TextView,
        val sourceValue: TextView,
        val liveStatus: TextView,
        val speedValue: TextView,
        val throttleValue: TextView,
        val brakeValue: TextView,
        val regenValue: TextView,
        val signatureValue: TextView,
        val genomeValue: TextView,
        val eventValue: TextView,
        val startButton: Button,
        val modeButton: Button,
        val motorSpeedBar: SeekBar,
        val throttleBar: SeekBar,
        val speedBar: SeekBar,
    )

    fun build(): Views {
        val scroll = ScrollView(activity).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            clipToPadding = false
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
            setBackgroundColor(BG)
        }
        scroll.addView(root)

        val modeButton = action("TEST", BLUE, Color.WHITE).apply {
            minWidth = dp(82)
            minHeight = dp(42)
            setOnClickListener { onLiveToggle() }
        }
        root.addView(header(modeButton), margin(14))

        val sceneValue = text("IDLE", 11f, BLUE, true)
        val sourceValue = text("SIMULATOR", 10f, MUTED, true)
        val rpmValue = text("0", 58f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setIncludeFontPadding(false)
        }
        val telemetry = text("0 km/h   •   Throttle 0%   •   Brake 0%   •   Regen 0%", 12f, SOFT, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        val signatureValue = text("BALANCED   •   AGG 0%   •   SMOOTH 0%", 10f, PURPLE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        val genomeValue = text("GENOME: NEW  •   OBS 0", 9f, MUTED, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(hero(sceneValue, sourceValue, rpmValue, telemetry, signatureValue, genomeValue), margin(10))

        val liveStatus = text("LIVE READY  •  VEHICLE DATA STANDBY", 10f, SOFT, true)
        val speedValue = text("--", 22f, Color.WHITE, true)
        val throttleValue = text("--", 22f, Color.WHITE, true)
        val brakeValue = text("--", 22f, Color.WHITE, true)
        val regenValue = text("--", 22f, Color.WHITE, true)
        root.addView(livePanel(liveStatus, speedValue, throttleValue, brakeValue, regenValue), margin(16))

        root.addView(section("DRIVE CONTROLS", "Manual controls are disabled automatically in LIVE mode."), margin(7))
        val motorSpeedBar = seek(25000, 0)
        val throttleBar = seek(100, 10)
        val speedBar = seek(240, 0)
        root.addView(control("MOTOR SPEED", "RPM", motorSpeedBar), margin(7))
        root.addView(control("THROTTLE", "%", throttleBar), margin(7))
        root.addView(control("SPEED", "km/h", speedBar), margin(16))

        root.addView(section("QUICK SCENES", "One-tap driving states for sound tuning."), margin(7))
        root.addView(chips(listOf("IDLE", "PULL", "BOOST", "COAST", "REGEN"), onQuickScene), margin(16))

        root.addView(section("SOUND DNA", "Select the installed acoustic character."), margin(7))
        root.addView(profiles(onProfile), margin(16))

        val startButton = primary("START DRIVE SOUND").apply { setOnClickListener { onStart() } }
        root.addView(startButton, margin(8))
        root.addView(secondary("STOP / SAFE").apply { setOnClickListener { onStop() } }, margin(16))

        root.addView(section("SERVICE", "Diagnostics and maintenance."), margin(7))
        val service = card(12)
        service.addView(serviceButton("BYD ADB SETUP / AUTHORIZE", onAdbSetup), margin(5))
        service.addView(serviceButton("BYD TELEMETRY DIAGNOSTICS", onDiagnostics), margin(5))
        service.addView(serviceButton("RESET SONIC GENOME", onResetGenome), margin(5))
        service.addView(serviceButton("CHECK FOR UPDATE", onCheckUpdate))
        root.addView(service)

        val eventValue = text("EVENTS L:0 A:0 O:0 R:0 B:0 S:0", 1f, Color.TRANSPARENT, false)
        eventValue.visibility = View.GONE
        root.addView(eventValue)

        activity.setContentView(scroll)
        return Views(scroll, rpmValue, telemetry, sceneValue, sourceValue, liveStatus,
            speedValue, throttleValue, brakeValue, regenValue, signatureValue, genomeValue,
            eventValue, startButton, modeButton, motorSpeedBar, throttleBar, speedBar)
    }

    private fun header(modeButton: Button) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val brand = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(text("DriveApex", 24f, Color.WHITE, true))
        brand.addView(text("BYD SONIC CONTROL", 10f, MUTED, true).apply { setPadding(0, dp(2), 0, 0) })
        addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        addView(modeButton, LinearLayout.LayoutParams(dp(84), dp(42)))
    }

    private fun hero(
        scene: TextView,
        source: TextView,
        rpm: TextView,
        telemetry: TextView,
        signature: TextView,
        genome: TextView,
    ) = card(18).apply {
        val top = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(scene, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(source)
        addView(top)
        addView(text("FRONT MOTOR", 9f, MUTED, true).apply { setPadding(0, dp(20), 0, 0) })
        addView(rpm, LinearLayout.LayoutParams(-1, dp(76)))
        addView(text("MOTOR SPEED  •  RPM", 10f, MUTED, true).apply { gravity = Gravity.CENTER })
        addView(telemetry)
        addView(signature)
        addView(genome)
    }

    private fun livePanel(status: TextView, speed: TextView, throttle: TextView, brake: TextView, regen: TextView) = card(12).apply {
        val head = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("●", 14f, GREEN, true), LinearLayout.LayoutParams(dp(20), -2))
        head.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        addView(head)
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        row.addView(metric("SPEED", speed, "km/h"), metricParams())
        row.addView(metric("THROTTLE", throttle, "%"), metricParams())
        row.addView(metric("BRAKE", brake, "%"), metricParams())
        row.addView(metric("REGEN", regen, "%"), LinearLayout.LayoutParams(0, dp(76), 1f))
        addView(row)
        addView(text("Verified vehicle telemetry is displayed here when LIVE is enabled.", 9f, MUTED, false).apply { setPadding(0, dp(9), 0, 0) })
    }

    private fun control(title: String, unit: String, bar: SeekBar) = card(11).apply {
        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val labelWrap = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        labelWrap.addView(text(title, 10f, SOFT, true))
        labelWrap.addView(text(unit, 9f, MUTED, false).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(labelWrap, LinearLayout.LayoutParams(dp(100), -2))
        row.addView(bar, LinearLayout.LayoutParams(0, dp(44), 1f))
        addView(row)
    }

    private fun chips(labels: List<String>, callback: (String) -> Unit) = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            labels.forEach { label ->
                addView(action(label, PANEL_2, SOFT).apply {
                    minHeight = dp(48)
                    setOnClickListener { callback(label) }
                }, LinearLayout.LayoutParams(dp(94), dp(48)).apply { marginEnd = dp(7) })
            }
        })
    }

    private fun profiles(callback: (String) -> Unit) = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(profileCard("EV GT", "Electric GT", BLUE, callback), LinearLayout.LayoutParams(dp(160), dp(92)).apply { marginEnd = dp(9) })
            addView(profileCard("APEX", "Performance", PURPLE, callback), LinearLayout.LayoutParams(dp(160), dp(92)))
        })
    }

    private fun profileCard(title: String, subtitle: String, accent: Int, callback: (String) -> Unit) = card(13).apply {
        setOnClickListener { callback(title) }
        addView(text(title, 18f, Color.WHITE, true))
        addView(text(subtitle, 10f, MUTED, false).apply { setPadding(0, dp(3), 0, 0) })
        addView(text("●  INSTALLED", 9f, accent, true).apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun metric(title: String, value: TextView, unit: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(7), dp(4), dp(5))
        background = rounded(PANEL_2, dp(11), STROKE)
        addView(text(title, 8f, MUTED, true))
        addView(value.apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
        addView(text(unit, 8f, MUTED, false))
    }

    private fun section(title: String, subtitle: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, 12f, Color.WHITE, true))
        addView(text(subtitle, 10f, MUTED, false).apply { setPadding(0, dp(3), 0, 0) })
    }

    private fun primary(label: String) = action(label, BLUE, Color.WHITE).apply {
        textSize = 14f
        minHeight = dp(56)
    }

    private fun secondary(label: String) = action(label, PANEL, SOFT).apply {
        textSize = 12f
        minHeight = dp(50)
    }

    private fun serviceButton(label: String, callback: () -> Unit) = action(label, PANEL_2, MUTED).apply {
        minHeight = dp(46)
        setOnClickListener { callback() }
    }

    private fun action(label: String, fill: Int, fg: Int) = Button(activity).apply {
        text = label
        textSize = 10f
        setTextColor(fg)
        isAllCaps = false
        background = rounded(fill, dp(12), STROKE)
        stateListAnimator = null
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun card(padding: Int) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        background = rounded(PANEL, dp(16), STROKE)
    }

    private fun seek(max: Int, progress: Int) = SeekBar(activity).apply {
        this.max = max
        this.progress = progress.coerceIn(0, max)
        minHeight = dp(42)
        progressTintList = ColorStateList.valueOf(BLUE)
        thumbTintList = ColorStateList.valueOf(Color.WHITE)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("sans", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun metricParams() = LinearLayout.LayoutParams(0, dp(76), 1f).apply { marginEnd = dp(6) }
    private fun margin(bottom: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) }
    private fun rounded(fill: Int, radius: Int, stroke: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = radius.toFloat(); setStroke(dp(1), stroke) }
    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt().coerceAtLeast(v)

    companion object {
        private const val BG = 0xFF070A0E.toInt()
        private const val PANEL = 0xFF0F151C.toInt()
        private const val PANEL_2 = 0xFF0A1016.toInt()
        private const val STROKE = 0xFF24313C.toInt()
        private const val BLUE = 0xFF1D9BF0.toInt()
        private const val GREEN = 0xFF35D07F.toInt()
        private const val PURPLE = 0xFFA778FF.toInt()
        private const val SOFT = 0xFFE8EDF3.toInt()
        private const val MUTED = 0xFF8995A3.toInt()
    }
}
