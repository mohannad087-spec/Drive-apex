package com.driveapex.diag

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.driveapex.BuildConfig

/**
 * Shows why the app stopped last time, and the events leading up to it.
 *
 * The crash report comes first because it is the answer when there is one. When
 * there is not, the verdict line still distinguishes an orderly exit from the
 * head unit having taken the process -- and the memory lines above the end of
 * the log say which.
 */
object LogViewer {

    fun show(activity: Activity) {
        val crash = DriveApexLog.crashReport()
        val body = buildString {
            appendLine("DriveApex ${BuildConfig.VERSION_NAME}")
            appendLine("Previous session ended: ${DriveApexLog.previousExit}")
            appendLine()
            if (crash != null) {
                appendLine("=== LAST CRASH ===")
                appendLine(crash.trim())
                appendLine()
            } else {
                appendLine("No crash recorded. If the app disappeared, look for")
                appendLine("onTrimMemory lines near the end of the log below.")
                appendLine()
            }
            appendLine("=== RECENT LOG ===")
            val lines = DriveApexLog.recent(300)
            if (lines.isEmpty()) appendLine("(empty)") else lines.forEach { appendLine(it) }
        }

        val view = TextView(activity).apply {
            text = body
            textSize = 9f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            val pad = (12 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
        }

        AlertDialog.Builder(activity)
            .setTitle("APP LOG")
            .setView(ScrollView(activity).apply { addView(view) })
            .setPositiveButton("CLOSE", null)
            .setNeutralButton("SHARE") { _, _ -> share(activity, body) }
            .setNegativeButton("CLEAR CRASH") { _, _ -> DriveApexLog.clearCrashReport() }
            .show()
    }

    /**
     * Sends the log out of the car. Attaching the file is preferred, but a head
     * unit may have no app that accepts one, so the text goes along as well and
     * anything that can share plain text will do.
     */
    private fun share(activity: Activity, body: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "DriveApex log ${BuildConfig.VERSION_NAME}")
            putExtra(Intent.EXTRA_TEXT, body.take(120_000))
        }
        runCatching {
            DriveApexLog.logFile()?.let { file ->
                val uri = FileProvider.getUriForFile(
                    activity, "${BuildConfig.APPLICATION_ID}.fileprovider", file
                )
                intent.putExtra(Intent.EXTRA_STREAM, uri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        runCatching { activity.startActivity(Intent.createChooser(intent, "Share log")) }
            .onFailure { DriveApexLog.e("log", "no app could share the log", it) }
    }
}
