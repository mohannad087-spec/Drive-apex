package com.driveapex

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.driveapex.diag.DriveApexLog
import com.driveapex.diag.LogViewer
import com.driveapex.update.BydAdbSetup
import com.driveapex.update.UpdateManager
import com.driveapex.vehicle.BydTelemetryDiagnostics
import com.driveapex.vehicle.DriveModeReader
import kotlin.math.roundToInt

/**
 * Everything about the app that is not about the sound.
 *
 * ADB, the telemetry probe, the log, updates and the drive-mode scan live here
 * rather than on the drive screen, which now has only what a driver looks at
 * while moving. Sound tuning deliberately stays where it is: it is adjusted by
 * ear against the engine, so it belongs beside the thing making the noise.
 */
class SettingsActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var updateManager: UpdateManager
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateManager = UpdateManager(this)

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

        root.addView(heading("SETTINGS"), margin(4))
        root.addView(sub("DriveApex ${BuildConfig.VERSION_NAME}"), margin(16))

        status = sub("Ready")
        root.addView(status, margin(16))

        root.addView(heading2("VEHICLE CONNECTION"), margin(8))
        root.addView(button("BYD ADB SETUP / AUTHORIZE") { runAdbSetup() }, margin(8))
        root.addView(button("BYD TELEMETRY DIAGNOSTICS") { showDiagnostics() }, margin(8))
        root.addView(button("SCAN FOR DRIVE MODE") { scanDriveMode() }, margin(20))

        root.addView(heading2("DIAGNOSTICS"), margin(8))
        root.addView(button("APP LOG / WHY IT CLOSED") { LogViewer.show(this) }, margin(8))
        root.addView(button("CHECK FOR UPDATE") { updateManager.checkManually() }, margin(20))

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        if (::updateManager.isInitialized) updateManager.onResume()
    }

    private fun runAdbSetup() {
        status.text = "ADB: working"
        // prepare() blocks on ADB for seconds; never on the main thread.
        Thread({
            val result = runCatching { BydAdbSetup.prepare(this, true) }
                .getOrDefault(BydAdbSetup.Result.SETTINGS_UNAVAILABLE)
            val message = when (result) {
                BydAdbSetup.Result.ALREADY_AVAILABLE -> "ADB port 5555 is available."
                BydAdbSetup.Result.SETTINGS_OPENED -> "BYD ADB settings opened. Enable wireless ADB."
                BydAdbSetup.Result.SETTINGS_UNAVAILABLE -> "BYD ADB settings activity was not found."
            }
            handler.post {
                if (isFinishing || isDestroyed) return@post
                status.text = "ADB: ${result.name}"
                dialog("BYD ADB setup", message)
            }
        }, "DriveApex-AdbSetup").apply { isDaemon = true }.start()
    }

    /**
     * The probe opens ADB, verifies the daemon and tries several hosts with
     * their own timeouts, so the dialog opens first and fills itself in.
     */
    private fun showDiagnostics() {
        val shown = dialog("BYD telemetry probe", "Probing the vehicle...\n\nThis takes a few seconds.")
        Thread({
            val message = runCatching {
                BydTelemetryDiagnostics.format(BydTelemetryDiagnostics.probe(this, "settings screen"))
            }.getOrElse { "Probe failed: ${it.message ?: it.javaClass.simpleName}" }
            handler.post {
                if (!isFinishing && !isDestroyed && shown.isShowing) shown.setMessage(message)
            }
        }, "DriveApex-Probe").apply { isDaemon = true }.start()
    }

    /**
     * Lists every mode-ish getter the vehicle exposes, with what it returns.
     *
     * Which of them is the drive mode, and what its numbers mean, is not
     * guessable from here -- so this prints them and the mapping gets made from
     * a reading taken with the car in a known mode. Read-only: only
     * zero-argument getters are called.
     */
    private fun scanDriveMode() {
        val shown = dialog("Drive mode scan", "Asking the vehicle...")
        Thread({
            val readings = runCatching { DriveModeReader(this).probe() }.getOrDefault(emptyList())
            val message = if (readings.isEmpty()) {
                "No mode-like getter answered.\n\n" +
                    "Either these devices are not present on this head unit, or the mode is " +
                    "not exposed as a getter. Nothing was written to the car."
            } else {
                "Put the car in a known mode, read the values, then switch mode and " +
                    "read again. Whichever value moves is the one.\n\n" +
                    readings.joinToString("\n") { "${it.device}.${it.method} = ${it.value}" }
            }
            DriveApexLog.i("drivemode", "scan returned ${readings.size} candidates")
            handler.post {
                if (!isFinishing && !isDestroyed && shown.isShowing) shown.setMessage(message)
            }
        }, "DriveApex-ModeScan").apply { isDaemon = true }.start()
    }

    private fun dialog(title: String, message: String): AlertDialog =
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("OK", null).show()

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 24f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun heading2(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(BLUE)
        textSize = 12f
        letterSpacing = 0.12f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun sub(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(MUTED)
        textSize = 12f
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = true
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        minHeight = dp(52)
        setBackgroundColor(PANEL)
        setOnClickListener { onClick() }
    }

    private fun margin(bottom: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(bottom) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val BG = 0xFF07090C.toInt()
        const val PANEL = 0xFF10151B.toInt()
        const val BLUE = 0xFF1D9BF0.toInt()
        const val MUTED = 0xFF7B8794.toInt()
    }
}
