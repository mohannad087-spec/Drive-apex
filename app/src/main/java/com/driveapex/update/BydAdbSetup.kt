package com.driveapex.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import java.net.Socket

/** BYD/DiPlus-style ADB bootstrap. */
object BydAdbSetup {
    private const val HOST = "127.0.0.1"
    private const val PORT = 5555
    private const val PREFS = "driveapex_adb_setup"
    private const val KEY_SETTINGS_REQUESTED = "settings_requested"
    private const val BYD_DEV_PACKAGE = "com.byd.byddevelopmenttools"

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
     * Firmware variants do not expose one stable ADBSettingsActivity name.
     * The development-tools package itself is stable in the reference dump,
     * so resolve and launch its exported main activity instead of hard-coding
     * an activity class that may not exist on the head unit.
     */
    fun openBydAdbSettings(activity: Activity): Boolean = runCatching {
        val intent = activity.packageManager.getLaunchIntentForPackage(BYD_DEV_PACKAGE)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
        true
    }.getOrDefault(false)

    fun isAdbPortOpen(): Boolean = runCatching {
        Socket(HOST, PORT).use { true }
    }.getOrDefault(false)
}
