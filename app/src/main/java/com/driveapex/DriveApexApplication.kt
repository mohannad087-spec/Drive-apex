package com.driveapex

import android.app.Application
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
        VehicleAdbConnection.warmUp(this)
    }
}
