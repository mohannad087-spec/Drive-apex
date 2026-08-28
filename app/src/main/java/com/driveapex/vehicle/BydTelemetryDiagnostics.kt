package com.driveapex.vehicle

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import com.driveapex.BuildConfig
import com.driveapex.update.VehicleAdbConnection
import java.util.Locale

/** Truthful BYD telemetry capability probe; shell-UID daemon is the primary live path. */
object BydTelemetryDiagnostics {
    data class Report(
        val adbStatus: String,
        val adbError: String?,
        val engineApiPresent: Boolean,
        val engineMethodsPresent: Boolean,
        val engineReadable: Boolean,
        val engineRpm: Int?,
        val speedApiPresent: Boolean,
        val speedReadable: Boolean,
        val currentSpeedKph: Double?,
        val acceleratorPercent: Int?,
        val brakePercent: Int?,
        val directRead: Boolean,
        val directError: String?,
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

        val packageInfo = runCatching {
            activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS)
        }.getOrNull()
        val declared = REQUIRED_PERMISSIONS.filter { permission ->
            packageInfo?.requestedPermissions?.contains(permission) == true
        }
        if (declared.size < REQUIRED_PERMISSIONS.size) {
            notes += "Undeclared BYD permissions: ${(REQUIRED_PERMISSIONS - declared.toSet()).joinToString()}"
        }
        if (failedPermissionGrants.isNotEmpty()) {
            notes += "Some BYD GET/signature permissions are not runtime-grantable; common permissions are checked separately."
        }

        var daemonFrame: TelemetryFrame? = null
        var daemonError: String? = null
        if (adbConnection != null) {
            val bridge = BydHalTelemetryBridge(activity)
            try {
                bridge.start { }
                repeat(30) {
                    if (daemonFrame == null) daemonFrame = bridge.latest()
                    if (daemonFrame != null) return@repeat
                    Thread.sleep(100L)
                }
                daemonFrame = bridge.latest()
                daemonError = bridge.error()
            } catch (t: Throwable) {
                daemonError = t.message ?: t.javaClass.simpleName
            } finally {
                bridge.stop()
            }
        } else {
            daemonError = VehicleAdbConnection.lastError()
        }

        if (daemonFrame != null) {
            notes += "Telemetry source: shell-UID BYD daemon."
        } else if (!daemonError.isNullOrBlank()) {
            notes += "Telemetry daemon error: $daemonError"
        }

        var directFrame: DirectBydTelemetryReader.Frame? = null
        if (daemonFrame == null) {
            directFrame = runCatching { DirectBydTelemetryReader(activity).readOnce() }.getOrNull()
            if (directFrame != null) notes += "Telemetry source: direct BYD HAL fallback."
        }

        val frame = daemonFrame ?: directFrame
        val readable = frame != null
        val rpm = frame?.rpm?.takeIf { it.isFinite() && it >= 0f }?.toInt()
        val speed = frame?.speedKph?.takeIf { it.isFinite() && it >= 0f }?.toDouble()
        val accelerator = frame?.throttle?.takeIf { it.isFinite() }?.times(100f)?.toInt()
        val brake = frame?.brake?.takeIf { it.isFinite() }?.times(100f)?.toInt()

        val engineApiPresent = runCatching {
            Class.forName("android.hardware.bydauto.engine.BYDAutoEngineDevice"); true
        }.getOrDefault(false) || runCatching {
            Class.forName("android.hardware.bydauto.motor.BYDAutoMotorDevice"); true
        }.getOrDefault(false)
        val speedApiPresent = runCatching {
            Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice"); true
        }.getOrDefault(false)

        notes += "Android API ${Build.VERSION.SDK_INT}; BYD HAL behavior depends on head-unit firmware."
        notes += "Live path: shell-UID daemon first, direct HAL second, UDP only for development."
        notes += "Daemon polls the BYD speed/engine devices continuously at 50 ms cadence."
        notes += "RPM invalid sentinels are rejected instead of being displayed as real RPM."

        return Report(
            adbStatus = adbStatus,
            adbError = VehicleAdbConnection.lastError(),
            engineApiPresent = engineApiPresent,
            engineMethodsPresent = readable,
            engineReadable = readable,
            engineRpm = rpm,
            speedApiPresent = speedApiPresent,
            speedReadable = readable,
            currentSpeedKph = speed,
            acceleratorPercent = accelerator,
            brakePercent = brake,
            directRead = directFrame != null,
            directError = if (readable) null else (daemonError ?: "No readable live drivetrain frame"),
            declaredPermissions = declared,
            grantedPermissions = grantedPermissions,
            failedPermissionGrants = failedPermissionGrants,
            notes = notes
        )
    }

    fun format(report: Report): String = buildString {
        appendLine("BYD TELEMETRY DIAGNOSTICS ${BuildConfig.VERSION_NAME}")
        appendLine()
        appendLine("ADB: ${report.adbStatus}")
        report.adbError?.let { appendLine("ADB error: $it") }
        appendLine()
        appendLine("BYD HAL API: ${if (report.engineApiPresent || report.speedApiPresent) "FOUND" else "NOT FOUND"}")
        appendLine("HAL read path: ${if (report.engineReadable) "LIVE DAEMON/HAL" else "NOT READY"}")
        appendLine("Live telemetry read: ${if (report.engineReadable || report.speedReadable) "OK" else "FAILED"}")
        report.engineRpm?.let { appendLine("MOTOR RPM: $it RPM") }
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
