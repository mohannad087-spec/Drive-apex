package com.driveapex.vehicle

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale

/**
 * Read-only capability probe for the BYD/DiLink telemetry path.
 *
 * This class intentionally probes only APIs that are verified by public BYD SDK
 * documentation/source. It never invokes a vehicle-control method and never
 * invents a motor-RPM method when one has not been verified for this build.
 */
object BydTelemetryDiagnostics {

    data class Report(
        val speedApiPresent: Boolean,
        val speedMethodsPresent: Boolean,
        val speedReadable: Boolean,
        val currentSpeedKph: Double?,
        val acceleratorPercent: Int?,
        val brakePercent: Int?,
        val motorPermissionDeclared: Boolean,
        val energyPermissionDeclared: Boolean,
        val gearboxPermissionDeclared: Boolean,
        val notes: List<String>
    )

    private const val SPEED_DEVICE = "android.hardware.bydauto.speed.BYDAutoSpeedDevice"
    private const val P_SPEED_GET = "android.permission.BYDAUTO_SPEED_GET"
    private const val P_MOTOR_GET = "android.permission.BYDAUTO_MOTOR_GET"
    private const val P_ENERGY_GET = "android.permission.BYDAUTO_ENERGY_GET"
    private const val P_GEARBOX_GET = "android.permission.BYDAUTO_GEARBOX_GET"

    fun probe(activity: Activity): Report {
        val notes = mutableListOf<String>()
        var speedApiPresent = false
        var speedMethodsPresent = false
        var speedReadable = false
        var currentSpeed: Double? = null
        var accelerator: Int? = null
        var brake: Int? = null

        runCatching {
            val clazz = Class.forName(SPEED_DEVICE)
            speedApiPresent = true
            val getInstance = clazz.getMethod("getInstance", android.content.Context::class.java)
            val getCurrentSpeed = clazz.getMethod("getCurrentSpeed")
            val getAccelerateDeepness = clazz.getMethod("getAccelerateDeepness")
            val getBrakeDeepness = clazz.getMethod("getBrakeDeepness")
            speedMethodsPresent = true
            val device = getInstance.invoke(null, activity.applicationContext)
            currentSpeed = (getCurrentSpeed.invoke(device) as Number).toDouble()
            accelerator = (getAccelerateDeepness.invoke(device) as Number).toInt()
            brake = (getBrakeDeepness.invoke(device) as Number).toInt()
            speedReadable = true
        }.onFailure {
            notes += "Speed API probe: ${it.javaClass.simpleName}: ${it.message ?: "unavailable"}"
        }

        fun declared(permission: String): Boolean {
            return runCatching {
                activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS)
                    .requestedPermissions
                    ?.contains(permission) == true
            }.getOrDefault(false)
        }

        val motorPermissionDeclared = declared(P_MOTOR_GET)
        val energyPermissionDeclared = declared(P_ENERGY_GET)
        val gearboxPermissionDeclared = declared(P_GEARBOX_GET)
        val speedPermissionDeclared = declared(P_SPEED_GET)

        if (!speedPermissionDeclared) {
            notes += "${P_SPEED_GET} is not declared by DriveApex; speed access may be provided by the installed BYD framework instead."
        }
        if (!motorPermissionDeclared) {
            notes += "${P_MOTOR_GET} is not declared yet. No motor-RPM read is attempted by DriveApex."
        }
        if (!energyPermissionDeclared) {
            notes += "${P_ENERGY_GET} is not declared yet. Energy telemetry is not attempted by DriveApex."
        }
        if (!gearboxPermissionDeclared) {
            notes += "${P_GEARBOX_GET} is not declared yet. Gear telemetry is not attempted by DriveApex."
        }

        notes += if (Build.VERSION.SDK_INT >= 29) {
            "Head unit Android API ${Build.VERSION.SDK_INT}; capability results are hardware/firmware dependent."
        } else {
            "Head unit Android API ${Build.VERSION.SDK_INT}."
        }
        notes += "Motor RPM is kept unimplemented until an exact BYD class/method is verified for this head unit."

        return Report(
            speedApiPresent = speedApiPresent,
            speedMethodsPresent = speedMethodsPresent,
            speedReadable = speedReadable,
            currentSpeedKph = currentSpeed,
            acceleratorPercent = accelerator,
            brakePercent = brake,
            motorPermissionDeclared = motorPermissionDeclared,
            energyPermissionDeclared = energyPermissionDeclared,
            gearboxPermissionDeclared = gearboxPermissionDeclared,
            notes = notes
        )
    }

    fun format(report: Report): String = buildString {
        appendLine("BYD TELEMETRY DIAGNOSTICS")
        appendLine()
        appendLine("Speed API: ${if (report.speedApiPresent) "FOUND" else "NOT FOUND"}")
        appendLine("Speed methods: ${if (report.speedMethodsPresent) "FOUND" else "NOT FOUND"}")
        appendLine("Speed read: ${if (report.speedReadable) "OK" else "FAILED"}")
        report.currentSpeedKph?.let { appendLine(String.format(Locale.US, "Speed: %.1f km/h", it)) }
        report.acceleratorPercent?.let { appendLine("Accelerator: $it%") }
        report.brakePercent?.let { appendLine("Brake: $it%") }
        appendLine()
        appendLine("Motor GET permission declared: ${report.motorPermissionDeclared}")
        appendLine("Energy GET permission declared: ${report.energyPermissionDeclared}")
        appendLine("Gearbox GET permission declared: ${report.gearboxPermissionDeclared}")
        appendLine()
        report.notes.forEach { appendLine("• $it") }
    }
}
