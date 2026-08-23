package com.driveapex.vehicle

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale

/** Read-only BYD telemetry capability probe. */
object BydTelemetryDiagnostics {
    data class Report(
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
        val motorPermissionDeclared: Boolean,
        val energyPermissionDeclared: Boolean,
        val gearboxPermissionDeclared: Boolean,
        val notes: List<String>
    )

    private const val ENGINE_DEVICE = "android.hardware.bydauto.engine.BYDAutoEngineDevice"
    private const val SPEED_DEVICE = "android.hardware.bydauto.speed.BYDAutoSpeedDevice"
    private const val P_SPEED_GET = "android.permission.BYDAUTO_SPEED_GET"
    private const val P_MOTOR_GET = "android.permission.BYDAUTO_MOTOR_GET"
    private const val P_ENERGY_GET = "android.permission.BYDAUTO_ENERGY_GET"
    private const val P_GEARBOX_GET = "android.permission.BYDAUTO_GEARBOX_GET"

    fun probe(activity: Activity): Report {
        val notes = mutableListOf<String>()
        var engineApiPresent = false
        var engineMethodsPresent = false
        var engineReadable = false
        var engineRpm: Int? = null
        var engineCode: String? = null
        var engineType: Int? = null

        // BYD SDK clients can enforce signature permissions on the supplied Context.
        // This mirrors the read-only Context-wrapper strategy used by OverDrive.
        val bydContext = BydPermissionContext(activity.applicationContext)

        runCatching {
            val clazz = Class.forName(ENGINE_DEVICE)
            engineApiPresent = true
            val getInstance = clazz.getMethod("getInstance", Context::class.java)
            val getEngineSpeed = clazz.getMethod("getEngineSpeed")
            val getEngineCode = clazz.getMethod("getEngineCode")
            val getType = clazz.getMethod("getType")
            engineMethodsPresent = true
            val device = getInstance.invoke(null, bydContext)
            engineRpm = (getEngineSpeed.invoke(device) as Number).toInt()
            engineCode = (getEngineCode.invoke(device) as? String)?.ifBlank { null }
            engineType = (getType.invoke(device) as Number).toInt()
            engineReadable = true
        }.onFailure {
            notes += "Engine API probe: ${it.javaClass.simpleName}: ${it.message ?: "unavailable"}"
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
            val device = getInstance.invoke(null, bydContext)
            currentSpeed = (getCurrentSpeed.invoke(device) as Number).toDouble()
            accelerator = (getAccelerateDeepness.invoke(device) as Number).toInt()
            brake = (getBrakeDeepness.invoke(device) as Number).toInt()
            speedReadable = true
        }.onFailure {
            notes += "Speed API probe: ${it.javaClass.simpleName}: ${it.message ?: "unavailable"}"
        }

        fun declared(permission: String): Boolean = runCatching {
            activity.packageManager.getPackageInfo(
                activity.packageName,
                PackageManager.GET_PERMISSIONS
            ).requestedPermissions?.contains(permission) == true
        }.getOrDefault(false)

        val motorPermissionDeclared = declared(P_MOTOR_GET)
        val energyPermissionDeclared = declared(P_ENERGY_GET)
        val gearboxPermissionDeclared = declared(P_GEARBOX_GET)
        val speedPermissionDeclared = declared(P_SPEED_GET)

        if (!speedPermissionDeclared) notes += "$P_SPEED_GET is not declared."
        if (!motorPermissionDeclared) notes += "$P_MOTOR_GET is not declared; engine class is tested directly."
        if (!energyPermissionDeclared) notes += "$P_ENERGY_GET is not declared."
        if (!gearboxPermissionDeclared) notes += "$P_GEARBOX_GET is not declared."
        notes += if (Build.VERSION.SDK_INT >= 29) {
            "Head unit Android API ${Build.VERSION.SDK_INT}; BYD framework behavior is hardware/firmware dependent."
        } else "Head unit Android API ${Build.VERSION.SDK_INT}."
        notes += "RPM source: BYDAutoEngineDevice.getEngineSpeed(); no derived RPM is used."
        notes += "No ADB connection is required by this diagnostic path."

        return Report(
            engineApiPresent, engineMethodsPresent, engineReadable, engineRpm, engineCode, engineType,
            speedApiPresent, speedReadable, currentSpeed, accelerator, brake,
            motorPermissionDeclared, energyPermissionDeclared, gearboxPermissionDeclared, notes
        )
    }

    fun format(report: Report): String = buildString {
        appendLine("BYD TELEMETRY DIAGNOSTICS 43")
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
        appendLine("Motor GET permission declared: ${report.motorPermissionDeclared}")
        appendLine("Energy GET permission declared: ${report.energyPermissionDeclared}")
        appendLine("Gearbox GET permission declared: ${report.gearboxPermissionDeclared}")
        appendLine()
        report.notes.forEach { appendLine("• $it") }
    }

    private class BydPermissionContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        private fun isByd(permission: String?): Boolean = permission?.startsWith("android.permission.BYD") == true
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (isByd(permission)) PackageManager.PERMISSION_GRANTED else super.checkPermission(permission, pid, uid)
        override fun checkSelfPermission(permission: String): Int =
            if (isByd(permission)) PackageManager.PERMISSION_GRANTED else super.checkSelfPermission(permission)
        override fun checkCallingPermission(permission: String): Int =
            if (isByd(permission)) PackageManager.PERMISSION_GRANTED else super.checkCallingPermission(permission)
        override fun checkCallingOrSelfPermission(permission: String): Int =
            if (isByd(permission)) PackageManager.PERMISSION_GRANTED else super.checkCallingOrSelfPermission(permission)
        override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {
            if (!isByd(permission)) super.enforcePermission(permission, pid, uid, message)
        }
        override fun enforceCallingPermission(permission: String, message: String?) {
            if (!isByd(permission)) super.enforceCallingPermission(permission, message)
        }
        override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
            if (!isByd(permission)) super.enforceCallingOrSelfPermission(permission, message)
        }
    }
}
