package com.driveapex.update

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.net.Socket

/** BYD/DiPlus-style ADB bootstrap. */
object BydAdbSetup {
    private const val HOST = "127.0.0.1"
    private const val PORT = 5555
    private const val PREFS = "driveapex_adb_setup"
    private const val KEY_SETTINGS_REQUESTED = "settings_requested"
    private const val BYD_DEV_PACKAGE = "com.byd.byddevelopmenttools"
    private const val BYD_ADB_SETTINGS = "com.byd.byddevelopmenttools.ADBSettingsActivity"
    private const val BYD_WIRELESS_PACKAGE = "com.byd.wirelesstools"

    private val mainHandler = Handler(Looper.getMainLooper())

    enum class Result {
        ALREADY_AVAILABLE,
        SETTINGS_OPENED,
        SETTINGS_UNAVAILABLE
    }

    fun prepare(activity: Activity, forceOpen: Boolean = false): Result {
        val appContext = activity.applicationContext
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val alreadyRequested = prefs.getBoolean(KEY_SETTINGS_REQUESTED, false)

        // The authoritative state is the real ADB authentication handshake.
        // A listening port or an OEM settings Activity is only a bootstrap hint.
        val connected = runCatching { VehicleAdbConnection(appContext).connect() }.getOrNull()
        if (connected != null && VehicleAdbConnection.state() == VehicleAdbConnection.State.AUTHORIZED) {
            prefs.edit().remove(KEY_SETTINGS_REQUESTED).apply()
            return Result.ALREADY_AVAILABLE
        }

        if (!forceOpen && alreadyRequested) return Result.SETTINGS_UNAVAILABLE

        if (isAdbPortOpen()) {
            // Port 5555 is already live but this app key is not authorized yet.
            // Do not report a fake "activity missing" error when no OEM activity is exported.
            val opened = openBydAdbSettings(activity)
            if (opened) {
                prefs.edit().putBoolean(KEY_SETTINGS_REQUESTED, true).apply()
                mainHandler.postDelayed({
                    VehicleAdbConnection.warmUp(appContext)
                }, 900L)
                return Result.SETTINGS_OPENED
            }
            VehicleAdbConnection.warmUp(appContext)
            return Result.ALREADY_AVAILABLE
        }

        val opened = openBydAdbSettings(activity)
        if (opened) {
            prefs.edit().putBoolean(KEY_SETTINGS_REQUESTED, true).apply()
            mainHandler.postDelayed({
                VehicleAdbConnection.warmUp(appContext)
            }, 1200L)
            return Result.SETTINGS_OPENED
        }

        return Result.SETTINGS_UNAVAILABLE
    }

    fun onResume(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_SETTINGS_REQUESTED, false)) return
        prefs.edit().remove(KEY_SETTINGS_REQUESTED).apply()
        mainHandler.postDelayed({
            VehicleAdbConnection.warmUp(activity.applicationContext)
        }, 350L)
    }

    fun openBydAdbSettings(activity: Activity): Boolean = runCatching {
        val pm = activity.packageManager

        val exact = Intent().apply {
            component = ComponentName(BYD_DEV_PACKAGE, BYD_ADB_SETTINGS)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (pm.resolveActivity(exact, PackageManager.MATCH_DEFAULT_ONLY) != null) {
            activity.startActivity(exact)
            return@runCatching true
        }

        val packages = listOf(BYD_DEV_PACKAGE, BYD_WIRELESS_PACKAGE)
        for (packageName in packages) {
            val packageInfo = runCatching {
                pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            }.getOrNull() ?: continue

            val ranked = packageInfo.activities.orEmpty()
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
