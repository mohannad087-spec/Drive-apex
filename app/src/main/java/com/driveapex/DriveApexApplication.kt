package com.driveapex

import android.app.Application
import com.driveapex.update.VehicleAdbConnection
import com.driveapex.vehicle.BydTelemetryDaemonMain

/**
 * Starts the BYD/DiLink ADB handshake at application startup.
 *
 * The explicit daemon class reference is intentional: it keeps the
 * app_process entry point in the primary DEX of the installed APK.
 */
class DriveApexApplication : Application() {
    @Suppress("unused")
    private val telemetryDaemonPrimaryDexAnchor = BydTelemetryDaemonMain::class.java

    override fun onCreate() {
        super.onCreate()
        VehicleAdbConnection.warmUp(this)
    }
}
