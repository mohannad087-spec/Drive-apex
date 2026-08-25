package com.driveapex.vehicle

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.driveapex.update.VehicleAdbConnection
import java.lang.reflect.InvocationTargetException
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

    private const val ENGINE_DEVICE = "android.hardware.bydauto.engine.BYDAutoEngineDevice"
    private const val SPEED_DEVICE = "android.hardware.bydauto.speed.BYDAutoSpeedDevice"

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
        if (adbConnection == null && VehicleAdbConnection.isAuthPending()) {
            notes += "Accept the Android ADB RSA authorization prompt on the head unit, then run diagnostics again."
        }

        val permissionResults = VehicleAdbConnection.permissionResults()
        val grantedPermissions = permissionResults.filter { it.granted }.map { it.permission }
        val failedPermissionGrants = permissionResults.filterNot { it.granted }
            .map { "${it.permission}: ${it.detail}" }

        var engineApiPresent = false
        var engineMethodsPresent = false
        var engineReadable = false
        var engineRpm: Int? = null
        var engineCode: String? = null
        var engineType: Int? = null

        runCatching {
            val clazz = Class.forName(ENGINE_DEVICE)
            engineApiPresent = true
            val getInstance = clazz.getMethod("getInstance", Context::class.java)
            val getEngineSpeed = clazz.getMethod("getEngineSpeed")
            val getEngineCode = clazz.getMethod("getEngineCode")
            val getType = clazz.getMethod("getType")
            engineMethodsPresent = true
            val device = getInstance.invoke(null, activity.applicationContext)
            engineRpm = (getEngineSpeed.invoke(device) as Number).toInt()
            engineCode = (getEngineCode.invoke(device) as? String)?.ifBlank { null }
            engineType = (getType.invoke(device) as Number).toInt()
            engineReadable = true
        }.onFailure {
            val root = rootCause(it)
            notes += "Engine: ${root.javaClass.simpleName}: ${root.message ?: "unavailable"}"
        }

        var speedApiPresent = false
        var speedReadable = false
        var currentSpeed: Double? = null
        var accelerator: Int? = null
        var brake: Int? = null

        runCatching {
            val clazz = Class.forName(SPEED_DEVICE)
            speedApiPresent = true
            val getInstance = clazz.getMethod("getInstance", Context::class.java)
            val getCurrentSpeed = clazz.getMethod("getCurrentSpeed")
            val getAccelerateDeepness = clazz.getMethod("getAccelerateDeepness")
            val getBrakeDeepness = clazz.getMethod("getBrakeDeepness")
            val device = getInstance.invoke(null, activity.applicationContext)
            currentSpeed = (getCurrentSpeed.invoke(device) as Number).toDouble()
            accelerator = (getAccelerateDeepness.invoke(device) as Number).toInt()
            brake = (getBrakeDeepness.invoke(device) as Number).toInt()
            speedReadable = true
        }.onFailure {
            val root = rootCause(it)
            notes += "Speed: ${root.javaClass.simpleName}: ${root.message ?: "unavailable"}"
        }

        val declared = REQUIRED_PERMISSIONS.filter { permission ->
            runCatching {
                activity.packageManager.getPackageInfo(
                    activity.packageName,
                    PackageManager.GET_PERMISSIONS
                ).requestedPermissions?.contains(permission) == true
            }.getOrDefault(false)
        }

        val undeclared = REQUIRED_PERMISSIONS - declared.toSet()
        if (undeclared.isNotEmpty()) notes += "Undeclared BYD permissions: ${undeclared.joinToString() }"
        if (failedPermissionGrants.isNotEmpty()) notes += "ADB grant failures: ${failedPermissionGrants.joinToString(" ; ") }"
        notes += "Android API ${Build.VERSION.SDK_INT}; BYD HAL behavior depends on head-unit firmware."
        notes += "RPM source: BYDAutoEngineDevice.getEngineSpeed(); no derived RPM is used."
        notes += "ADB is bootstrap/authentication only; telemetry is read through BYD HAL."

        return Report(
            adbStatus,
            VehicleAdbConnection.lastError(),
            engineApiPresent,
            engineMethodsPresent,
            engineReadable,
            engineRpm,
            engineCode,
            engineType,
            speedApiPresent,
            speedReadable,
            currentSpeed,
            accelerator,
            brake,
            declared,
            grantedPermissions,
            failedPermissionGrants,
            notes
        )
    }

    fun format(report: Report): String = buildString {
        appendLine("BYD TELEMETRY DIAGNOSTICS 54")
        appendLine()
        appendLine("ADB: ${report.adbStatus}")
        report.adbError?.let { appendLine("ADB error: $it") }
        appendLine()
        appendLine("Engine API: ${if (report.engineApiPresent) "FOUND" else "NOT FOUND"}")
        appendLine("Engine methods: ${if (report.engineMethodsPresent) "FOUND" else "NOT FOUND"}")
        appendLine("Engine read: ${if (report.engineReadable) "OK" else "FAILED"}")
        report.engineRpm?.let { appendLine("ENGINE RPM: $it RPM") }
        report.engineCode?.let { appendLine("Engine code: $it") }
        report.engineType?.let { appendLine("Engine type: $it") }
        appendLine()
        appendLine("Speed API: ${if (report.speedApiPresent) "FOUND" else "NOT FOUND"}")
        appendLine("Speed read: ${if (report.speedReadable) "OK" else "FAILED"}")
        report.currentSpeedKph?.let { appendLine(String.format(Locale.US, "Speed: %.1f km/h", it)) }
        report.acceleratorPercent?.let { appendLine("Accelerator: $it%") }
        report.brakePercent?.let { appendLine("Brake: $it%") }
        appendLine()
        appendLine("BYD permissions declared: ${report.declaredPermissions.size}/${REQUIRED_PERMISSIONS.size}")
        appendLine("BYD permissions granted by ADB: ${report.grantedPermissions.size}/${REQUIRED_PERMISSIONS.size}")
        report.grantedPermissions.forEach { appendLine("✓ $it") }
        report.failedPermissionGrants.forEach { appendLine("✗ $it") }
        appendLine()
        report.notes.forEach { appendLine("• $it") }
    }

    private fun rootCause(error: Throwable): Throwable {
        var current = error
        val seen = HashSet<Throwable>()
        while (current is InvocationTargetException && current.targetException != null && seen.add(current)) {
            current = current.targetException
        }
        while (current.cause != null && current.cause !== current && seen.add(current)) {
            current = current.cause!!
        }
        return current
    }
}
