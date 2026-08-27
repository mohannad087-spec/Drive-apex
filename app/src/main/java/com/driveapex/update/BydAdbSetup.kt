package com.driveapex.update

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.net.Socket

/** BYD/DiPlus-style ADB bootstrap. */
object BydAdbSetup {
    private const val HOST = "127.0.0.1"
    private const val PORT = 5555
    private const val PREFS = "driveapex_adb_setup"
    private const val KEY_SETTINGS_REQUESTED = "settings_requested"
    private val BYD_TOOL_PACKAGES = listOf(
        "com.byd.byddevelopmenttools",
        "com.byd.wirelesstools"
    )

    enum class Result {
        ALREADY_AVAILABLE,
        SETTINGS_OPENED,
        SETTINGS_UNAVAILABLE
    }

    fun prepare(activity: Activity, forceOpen: Boolean = false): Result {
        if (isAdbPortOpen()) {
            VehicleAdbConnection.warmUp(activity.applicationContext)
            return Result.ALREADY_AVAILABLE
        }

        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val alreadyRequested = prefs.getBoolean(KEY_SETTINGS_REQUESTED, false)
        if (!forceOpen && alreadyRequested) return Result.SETTINGS_UNAVAILABLE

        val opened = openBydAdbSettings(activity)
        if (opened) {
            prefs.edit().putBoolean(KEY_SETTINGS_REQUESTED, true).apply()
            VehicleAdbConnection.warmUp(activity.applicationContext)
            return Result.SETTINGS_OPENED
        }
        return Result.SETTINGS_UNAVAILABLE
    }

    /**
     * BYD firmware variants do not expose one stable ADB settings activity.
     * Resolve exported activities from the installed BYD system packages at
     * runtime and prefer activities whose actual component name identifies
     * development, wireless, or ADB tooling. No firmware-specific class name
     * is hard-coded.
     */
    fun openBydAdbSettings(activity: Activity): Boolean = runCatching {
        val pm = activity.packageManager
        for (packageName in BYD_TOOL_PACKAGES) {
            val packageInfo = runCatching {
                pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            }.getOrNull() ?: continue

            val activities = packageInfo.activities.orEmpty()
            val ranked = activities
                .filter { it.exported }
                .sortedByDescending { scoreActivity(it.name) }

            for (activityInfo in ranked) {
                if (scoreActivity(activityInfo.name) <= 0) continue
                val intent = Intent().apply {
                    component = ComponentName(packageName, activityInfo.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                    activity.startActivity(intent)
                    return@runCatching true
                }
            }

            // Some firmware builds expose only the package launcher activity.
            // Use it as a last deterministic fallback when it exists.
            pm.getLaunchIntentForPackage(packageName)?.let { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
                return@runCatching true
            }
        }
        false
    }.getOrDefault(false)

    private fun scoreActivity(name: String): Int {
        val n = name.lowercase()
        var score = 0
        if ("adb" in n) score += 100
        if ("wireless" in n) score += 80
        if ("development" in n || "develop" in n) score += 60
        if ("setting" in n) score += 40
        if ("tool" in n) score += 20
        return score
    }

    fun isAdbPortOpen(): Boolean = runCatching {
        Socket(HOST, PORT).use { true }
    }.getOrDefault(false)
}
