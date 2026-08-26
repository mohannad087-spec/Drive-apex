package com.driveapex.vehicle

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import com.driveapex.update.VehicleAdbConnection
import java.util.Locale

/** Read-only BYD telemetry capability and authorization probe. */
object BydTelemetryDiagnostics {
    data class Report(
        val adbStatus: String,
        val adbError: String?,
        val engineApiPresent: Boolean,
        val engineMethodsPresent: Boolean,
        val engineReadable: Boolean,
        val engineRpm: Int?,
        val engineCode: String?,
        val engineType: Int?,
        val speedApiPresent: Boolean,
        val speedReadable: Boolean,
        val currentSpeedKph: Double?,
        val acceleratorPercent: Int?,
        val brakePercent: Int?,
        val declaredPermissions: List<String>,
        val grantedPermissions: List<String>,
        val failedPermissionGrants: List<String>,
        val notes: List<String>
    )

    private val REQUIRED_PERMISSIONS = listOf(
        "android.permission.BYDAUTO_SPEED_COMMON",
        "android.permission.BYDAUTO_SPEED_GET",
        "android.permission.BYDAUTO_ENGINE_COMMON",
        "android.permission.BYDAUTO_ENGINE_GET",
        "android.permission.BYDAUTO_MOTOR_GET",
        "android.permission.BYDAUTO_ENERGY_COMMON",
        "android.permission.BYDAUTO_ENERGY_GET",
        "android.permission.BYDAUTO_GEARBOX_COMMON",
        "android.permission.BYDAUTO_GEARBOX_GET",
        "android.permission.BYDAUTO_VEHICLE_DATA_GET"
    )

    fun probe(activity: Activity): Report {
        val notes = mutableListOf<String>()
        val adbConnection = VehicleAdbConnection(activity).connect()
        val adbStatus = when {
            adbConnection != null -> "AUTHORIZED"
            VehicleAdbConnection.isAuthPending() -> "AUTH PENDING"
            VehicleAdbConnection.state() == VehicleAdbConnection.State.OFF -> "OFF"
            else -> VehicleAdbConnection.state().name
        }
        notes += "ADB bootstrap: $adbStatus"
        VehicleAdbConnection.lastError()?.let { notes += "ADB: $it" }

        val permissionResults = VehicleAdbConnection.permissionResults()
        val grantedPermissions = permissionResults.filter { it.granted }.map { it.permission }
        val failedPermissionGrants = permissionResults.filterNot { it.granted }
            .map { "${it.permission}: ${it.detail}" }

        val declared = REQUIRED_PERMISSIONS.filter { permission ->
            runCatching {
                activity.packageManager.getPackageInfo(
                    activity.packageName,
                    PackageManager.GET_PERMISSIONS
                ).requestedPermissions?.contains(permission) == true
            }.getOrDefault(false)
        }

        val undeclared = REQUIRED_PERMISSIONS - declared.toSet()
        if (undeclared.isNotEmpty()) notes += "Undeclared BYD permissions: ${undeclared.joinToString()}"
        if (failedPermissionGrants.isNotEmpty()) {
            notes += "Some pm grants are expected to be non-changeable signature/install permissions."
        }

        val bridge = BydHalTelemetryBridge(activity)
        val daemonReady = runCatching { bridge.isAvailable() }.getOrDefault(false)
        if (!daemonReady) {
            notes += "Telemetry daemon is not reachable: ${VehicleAdbConnection.lastError() ?: "unknown error"}"
        }

        if (daemonReady) bridge.start { }
        repeat(20) {
            if (bridge.latest() != null) return@repeat
            Thread.sleep(100L)
        }
        val frame = bridge.latest()
        bridge.stop()

        val engineApiPresent = runCatching {
            Class.forName("android.hardware.bydauto.engine.BYDAutoEngineDevice")
            true
        }.getOrDefault(false)
        val motorApiPresent = runCatching {
            Class.forName("android.hardware.bydauto.motor.BYDAutoMotorDevice")
            true
        }.getOrDefault(false)

        val readable = frame != null
        val speedReadable = frame != null
        val engineRpm = frame?.rpm?.takeIf { it.isFinite() }?.toInt()
        val speedKph = frame?.speedKph?.toDouble()
        val accelerator = frame?.throttle?.times(100f)?.toInt()
        val brake = frame?.brake?.times(100f)?.toInt()

        notes += "Android API ${Build.VERSION.SDK_INT}; BYD HAL behavior depends on head-unit firmware."
        notes += "RPM source: BYDAutoMotorDevice.getMotorSpeed(); BYDAutoEngineDevice.getEngineSpeed() is fallback only."
        notes += "The app process does not call BYD HAL directly; a shell-UID app_process daemon owns the device handles."
        notes += "Telemetry transport: localhost TCP from the daemon, 20 Hz."

        return Report(
            adbStatus = adbStatus,
            adbError = VehicleAdbConnection.lastError(),
            engineApiPresent = engineApiPresent || motorApiPresent,
            engineMethodsPresent = daemonReady,
            engineReadable = readable,
            engineRpm = engineRpm,
            engineCode = null,
            engineType = null,
            speedApiPresent = runCatching {
                Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice")
                true
            }.getOrDefault(false),
            speedReadable = speedReadable,
            currentSpeedKph = speedKph,
            acceleratorPercent = accelerator,
            brakePercent = brake,
            declaredPermissions = declared,
            grantedPermissions = grantedPermissions,
            failedPermissionGrants = failedPermissionGrants,
            notes = notes
        )
    }

    fun format(report: Report): String = buildString {
        appendLine("BYD TELEMETRY DIAGNOSTICS 55")
        appendLine()
        appendLine("ADB: ${report.adbStatus}")
        report.adbError?.let { appendLine("ADB error: $it") }
        appendLine()
        appendLine("BYD HAL API: ${if (report.engineApiPresent || report.speedApiPresent) "FOUND" else "NOT FOUND"}")
        appendLine("Daemon methods: ${if (report.engineMethodsPresent) "FOUND" else "NOT READY"}")
        appendLine("Live telemetry read: ${if (report.engineReadable || report.speedReadable) "OK" else "FAILED"}")
        report.engineRpm?.let { appendLine("MOTOR RPM: $it RPM") }
        appendLine()
        report.currentSpeedKph?.let { appendLine(String.format(Locale.US, "Speed: %.1f km/h", it)) }
        report.acceleratorPercent?.let { appendLine("Accelerator: $it%") }
        report.brakePercent?.let { appendLine("Brake: $it%") }
        appendLine()
        appendLine("BYD permissions declared: ${report.declaredPermissions.size}/${REQUIRED_PERMISSIONS.size}")
        appendLine("BYD permissions reported granted: ${report.grantedPermissions.size}/${REQUIRED_PERMISSIONS.size}")
        report.grantedPermissions.forEach { appendLine("✓ $it") }
        report.failedPermissionGrants.forEach { appendLine("✗ $it") }
        appendLine()
        report.notes.forEach { appendLine("• $it") }
    }
}
