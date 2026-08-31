package com.driveapex

import android.app.Application
import com.driveapex.diag.DriveApexLog
import com.driveapex.update.VehicleAdbConnection
import com.driveapex.vehicle.BydDiPlusEngineTelemetryDaemonMain

/**
 * Starts the BYD/DiLink ADB handshake at application startup.
 *
 * The explicit daemon class reference is intentional: it keeps the app_process
 * entry point in the primary DEX of the installed APK. It anchored
 * BydTelemetryDaemonMain, which nothing launches -- the class actually started
 * over ADB is the one named by VehicleAdbConnection.TELEMETRY_DAEMON_CLASS, so
 * the anchor was pinning a class that did not need pinning while leaving the
 * real entry point unpinned.
 */
class DriveApexApplication : Application() {
    @Suppress("unused")
    private val telemetryDaemonPrimaryDexAnchor = BydDiPlusEngineTelemetryDaemonMain::class.java

    override fun onCreate() {
        super.onCreate()
        // First, so the crash handler is installed before anything can throw.
        DriveApexLog.init(this)
        DriveApexLog.i("app", "DriveApex ${BuildConfig.VERSION_NAME} starting")
        VehicleAdbConnection.warmUp(this)
    }

    /**
     * The head unit asks for memory back before it takes the process, so these
     * lines are usually the only warning that precedes a silent disappearance --
     * the case that leaves no stack trace to find afterwards.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val name = when (level) {
            TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
            TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
            TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
            TRIM_MEMORY_MODERATE -> "MODERATE"
            TRIM_MEMORY_COMPLETE -> "COMPLETE (first in line to be killed)"
            else -> "level $level"
        }
        DriveApexLog.w("memory", "onTrimMemory: $name")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        DriveApexLog.w("memory", "onLowMemory: system is out of memory")
    }
}
