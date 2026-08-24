package com.driveapex

import android.app.Application
import com.driveapex.update.VehicleAdbConnection

/**
 * Starts the BYD/DiLink ADB handshake at application startup.
 *
 * OverDrive requires first-launch ADB authorization before its shell-side
 * permission bootstrap can grant the BYD HAL permissions.
 */
class DriveApexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        VehicleAdbConnection.warmUp(this)
    }
}
