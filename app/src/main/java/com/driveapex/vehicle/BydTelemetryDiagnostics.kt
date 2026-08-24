package com.driveapex.vehicle

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import com.driveapex.update.VehicleAdbConnection
import java.lang.reflect.InvocationTargetException
import java.util.Locale

/** Read-only BYD telemetry capability probe. */
object BydTelemetryDiagnostics {
    data class Report(
        val adbStatus: String,
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
        val speedCommonPermissionDeclared: Boolean,
        val speedGetPermissionDeclared: Boolean,
        val engineCommonPermissionDeclared: Boolean,
        val engineGetPermissionDeclared: Boolean,
        val motorGetPermissionDeclared: Boolean,
        val notes: List<String>
    )

    private const val ENGINE_DEVICE = "android.hardware.bydauto.engine.BYDAutoEngineDevice"
    private const val SPEED_DEVICE = "android.hardware.bydauto.speed.BYDAutoSpeedDevice"
    private const val P_SPEED_COMMON = "android.permission.BYDAUTO_SPEED_COMMON"
    private const val P_SPEED_GET = "android.permission.BYDAUTO_SPEED_GET"
    private const val P_ENGINE_COMMON = "android.permission.BYDAUTO_ENGINE_COMMON"
    private const val P_ENGINE_GET = "android.permission.BYDAUTO_ENGINE_GET"
    private const val P_MOTOR_GET = "android.permission.BYDAUTO_MOTOR_GET"

    fun probe(activity: Activity): Report {
        val notes = mutableListOf<String>()

        // Trigger the same local ADB handshake used for the OTA path. When the
        // key is not authorized, the head unit can display its ADB authorization
        // prompt; the background poller remains active after this call returns.
        val adbConnection = runCatching { VehicleAdbConnection(activity).connect() }.getOrNull()
        val adbStatus = when {
            adbConnection != null -> "AUTHORIZED"
            VehicleAdbConnection.isAuthPending() -> "AUTH PENDING"
            else -> "UNAVAILABLE"
        }
        notes += "ADB bootstrap: $adbStatus"
        if (adbConnection != null) {
            notes += "ADB shell is available; read-only BYD HAL permissions were requested through pm grant."
        } else if (VehicleAdbConnection.isAuthPending()) {
            notes += "Accept the ADB authorization prompt on the head unit, then run diagnostics again."
        }

        var engineApiPresent = false
        var engineMethodsPresent = false
        var engineReadable = false
        var engineRpm: Int? = null
        var engineCode: String? = null
        var engineType: Int? = null

        // BYD SDK clients can enforce signature permissions on the supplied Context.
        // The wrapper mirrors the permission-bypass context used by OverDrive,
        // while ADB pm grant is used to set the actual OS permission state.
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
            val root = rootCause(it)
            notes += "Engine API probe: ${root.javaClass.simpleName}: ${root.message ?: "unavailable"}"
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
            val root = rootCause(it)
            notes += "Speed API probe: ${root.javaClass.simpleName}: ${root.message ?: "unavailable"}"
        }

        fun declared(permission: String): Boolean = runCatching {
            activity.packageManager.getPackageInfo(
                activity.packageName,
                PackageManager.GET_PERMISSIONS
            ).requestedPermissions?.contains(permission) == true
        }.getOrDefault(false)

        val speedCommonPermissionDeclared = declared(P_SPEED_COMMON)
        val speedGetPermissionDeclared = declared(P_SPEED_GET)
        val engineCommonPermissionDeclared = declared(P_ENGINE_COMMON)
        val engineGetPermissionDeclared = declared(P_ENGINE_GET)
        val motorGetPermissionDeclared = declared(P_MOTOR_GET)

        if (!speedCommonPermissionDeclared) notes += "$P_SPEED_COMMON is not declared."
        if (!speedGetPermissionDeclared) notes += "$P_SPEED_GET is not declared."
        if (!engineCommonPermissionDeclared) notes += "$P_ENGINE_COMMON is not declared."
        if (!engineGetPermissionDeclared) notes += "$P_ENGINE_GET is not declared."
        if (!motorGetPermissionDeclared) notes += "$P_MOTOR_GET is not declared."
        notes += if (Build.VERSION.SDK_INT >= 29) {
            "Head unit Android API ${Build.VERSION.SDK_INT}; BYD framework behavior is hardware/firmware dependent."
        } else "Head unit Android API ${Build.VERSION.SDK_INT}."
        notes += "RPM source: BYDAutoEngineDevice.getEngineSpeed(); no derived RPM is used."
        notes += "Telemetry reads are through BYD HAL; ADB is used for the first-launch authentication and shell-side permission bootstrap."

        return Report(
            adbStatus,
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
            speedCommonPermissionDeclared,
            speedGetPermissionDeclared,
            engineCommonPermissionDeclared,
            engineGetPermissionDeclared,
            motorGetPermissionDeclared,
            notes
        )
    }

    fun format(report: Report): String = buildString {
        appendLine("BYD TELEMETRY DIAGNOSTICS 53")
        appendLine()
        appendLine("ADB bootstrap: ${report.adbStatus}")
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
        appendLine("Speed COMMON permission declared: ${report.speedCommonPermissionDeclared}")
        appendLine("Speed GET permission declared: ${report.speedGetPermissionDeclared}")
        appendLine("Engine COMMON permission declared: ${report.engineCommonPermissionDeclared}")
        appendLine("Engine GET permission declared: ${report.engineGetPermissionDeclared}")
        appendLine("Motor GET permission declared: ${report.motorGetPermissionDeclared}")
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
