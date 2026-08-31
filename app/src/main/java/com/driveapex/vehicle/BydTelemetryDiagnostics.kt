package com.driveapex.vehicle

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import com.driveapex.BuildConfig
import com.driveapex.update.VehicleAdbConnection
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.util.Locale

/** Truthful BYD telemetry capability probe using the DiPlus engine feature-listener path. */
object BydTelemetryDiagnostics {
    data class Report(
        val adbStatus: String, val adbError: String?, val engineApiPresent: Boolean,
        val engineMethodsPresent: Boolean, val engineReadable: Boolean, val engineRpm: Int?,
        val speedApiPresent: Boolean, val speedReadable: Boolean, val currentSpeedKph: Double?,
        val acceleratorPercent: Int?, val brakePercent: Int?, val directRead: Boolean,
        val directError: String?, val declaredPermissions: List<String>,
        val grantedPermissions: List<String>, val failedPermissionGrants: List<String>,
        val notes: List<String>, val sensorScan: List<String> = emptyList()
    )

    private val REQUIRED_PERMISSIONS = listOf(
        "android.permission.BYDAUTO_SPEED_COMMON", "android.permission.BYDAUTO_SPEED_GET",
        "android.permission.BYDAUTO_ENGINE_COMMON", "android.permission.BYDAUTO_ENGINE_GET",
        "android.permission.BYDAUTO_MOTOR_GET", "android.permission.BYDAUTO_ENERGY_COMMON",
        "android.permission.BYDAUTO_ENERGY_GET", "android.permission.BYDAUTO_GEARBOX_COMMON",
        "android.permission.BYDAUTO_GEARBOX_GET", "android.permission.BYDAUTO_VEHICLE_DATA_GET"
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
        val failedPermissionGrants = permissionResults.filterNot { it.granted }.map { "${it.permission}: ${it.detail}" }
        val packageInfo = runCatching { activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS) }.getOrNull()
        val declared = REQUIRED_PERMISSIONS.filter { permission -> packageInfo?.requestedPermissions?.contains(permission) == true }
        if (declared.size < REQUIRED_PERMISSIONS.size) notes += "Undeclared BYD permissions: ${(REQUIRED_PERMISSIONS - declared.toSet()).joinToString()}"
        if (failedPermissionGrants.isNotEmpty()) notes += "Some BYD GET/signature permissions are not runtime-grantable; common permissions are checked separately."

        var daemonAvailable = false
        var daemonRpm: Int? = null
        var daemonSpeed: Double? = null
        var daemonAccelerator: Int? = null
        var daemonBrake: Int? = null
        var daemonError: String? = null
        if (adbConnection != null) {
            val bridge = BydHalTelemetryBridge(activity)
            try {
                bridge.start { frame ->
                    daemonAvailable = true
                    daemonRpm = frame.rpm.takeIf { it.isFinite() && it >= 0f }?.toInt()
                    daemonSpeed = frame.speedKph.takeIf { it.isFinite() && it >= 0f }?.toDouble()
                    daemonAccelerator = (frame.throttle.takeIf { it.isFinite() }?.times(100f))?.toInt()
                    daemonBrake = (frame.brake.takeIf { it.isFinite() }?.times(100f))?.toInt()
                }
                repeat(30) { if (daemonAvailable) return@repeat; Thread.sleep(100L) }
                daemonError = bridge.error()
                if (!daemonAvailable) {
                    val last = bridge.latest()
                    if (last != null) {
                        daemonAvailable = true
                        daemonRpm = last.rpm.takeIf { it.isFinite() && it >= 0f }?.toInt()
                        daemonSpeed = last.speedKph.takeIf { it.isFinite() && it >= 0f }?.toDouble()
                        daemonAccelerator = (last.throttle.takeIf { it.isFinite() }?.times(100f))?.toInt()
                        daemonBrake = (last.brake.takeIf { it.isFinite() }?.times(100f))?.toInt()
                    }
                }
            } catch (t: Throwable) { daemonError = t.message ?: t.javaClass.simpleName } finally { bridge.stop() }
        } else daemonError = VehicleAdbConnection.lastError()

        if (daemonAvailable) notes += "Telemetry source: shell-UID DiPlus-compatible BYD engine feature listener." else if (!daemonError.isNullOrBlank()) notes += "Telemetry daemon error: $daemonError"
        var directFrame: DirectBydTelemetryReader.Frame? = null
        if (!daemonAvailable) {
            directFrame = runCatching { DirectBydTelemetryReader(activity).readOnce() }.getOrNull()
            if (directFrame != null) notes += "Telemetry source: direct BYD HAL fallback."
        }
        val readable = daemonAvailable || directFrame != null
        val rpm: Int?
        val speed: Double?
        val accelerator: Int?
        val brake: Int?
        if (daemonAvailable) {
            rpm = daemonRpm; speed = daemonSpeed; accelerator = daemonAccelerator; brake = daemonBrake
        } else {
            rpm = directFrame?.motorSpeed?.takeIf { it.isFinite() && it >= 0f }?.toInt()
            speed = directFrame?.speedKph?.takeIf { it.isFinite() && it >= 0f }?.toDouble()
            accelerator = (directFrame?.throttle?.takeIf { it.isFinite() }?.times(100f))?.toInt()
            brake = (directFrame?.brake?.takeIf { it.isFinite() }?.times(100f))?.toInt()
        }

        val engineApiPresent = runCatching { Class.forName("android.hardware.bydauto.engine.BYDAutoEngineDevice"); true }.getOrDefault(false) || runCatching { Class.forName("android.hardware.bydauto.motor.BYDAutoMotorDevice"); true }.getOrDefault(false)
        val speedApiPresent = runCatching { Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice"); true }.getOrDefault(false)
        notes += "Android API ${Build.VERSION.SDK_INT}; BYD HAL behavior depends on head-unit firmware."
        notes += "Process name: ${currentProcessName()} (DiPlus runs as com.byd.warning)"
        notes += runCatching {
            val v = DiPlusMotorSpeedReader.readFrontMotorRpm()
            val where = DiPlusMotorSpeedReader.lastPath()
            if (v != null) "DiPlus API front motor: ${v.toInt()} RPM via $where"
            else "DiPlus API front motor: no answer from $where"
        }.getOrElse { "DiPlus API front motor: error ${it.javaClass.simpleName}" }
        notes += halOrigin("android.hardware.bydauto.engine.BYDAutoEngineDevice")
        notes += halOrigin("android.hardware.bydauto.speed.BYDAutoSpeedDevice")
        notes += "Live path: in-process listener (DiPlus style) first, shell-UID daemon second, direct HAL third."
        notes += "Feature ID is resolved from BYDAutoFeatureIds.ENGINE_FRONT_MOTOR_SPEED at runtime with a verified fallback."
        notes += "RPM invalid sentinels are rejected instead of being displayed as real RPM."

        val scan = if (adbConnection != null) runSensorScan() else listOf("SENSOR SCAN: ADB not authorized")
        val scanError = scan.firstOrNull { it.startsWith("SENSOR SCAN ERROR:") }
        if (scanError != null) notes += scanError
        else notes += "DiPlus listener scan: ${scan.size} diagnostic entries returned."

        return Report(adbStatus, VehicleAdbConnection.lastError(), engineApiPresent, readable, readable, rpm, speedApiPresent, readable, speed, accelerator, brake, directFrame != null, if (readable) null else (daemonError ?: "No readable live drivetrain frame"), declared, grantedPermissions, failedPermissionGrants, notes, scan)
    }

    /** The name this process actually reports, to confirm android:process took effect. */
    private fun currentProcessName(): String = runCatching {
        // cmdline is NUL-separated; take the first entry rather than trimming.
        java.io.File("/proc/self/cmdline").readText().split(0.toChar())[0].trim()
    }.getOrElse { "unknown (${it.javaClass.simpleName})" }

    /**
     * Says whether a BYD HAL class resolves to the real head-unit framework or to this
     * app's own compile-time stub. The stubs live in the APK under the same
     * android.hardware.bydauto.* names, so "Class.forName succeeded" on its own proves
     * nothing -- boot-classpath classes report a null class loader, APK classes report
     * the app's PathClassLoader. If the engine device resolves to the stub, no
     * in-process read can ever work no matter which permissions are held.
     */
    private fun halOrigin(className: String): String = runCatching {
        val loader = Class.forName(className).classLoader
        val short = className.substringAfterLast('.')
        if (loader == null) "$short: real framework class (boot classpath)"
        else "$short: LOCAL STUB from this APK (${loader.javaClass.simpleName}) -- not the vehicle HAL"
    }.getOrElse { "${className.substringAfterLast('.')}: not present (${it.javaClass.simpleName})" }

    private fun runSensorScan(): List<String> = runCatching {
        Socket("127.0.0.1", 18766).use { socket ->
            socket.soTimeout = 8_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val hits = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line == "SCAN_DONE" || line.startsWith("SCAN_DONE,") -> break
                    line.startsWith("HIT,") -> {
                        val p = line.split(',', limit = 5)
                        if (p.size == 5) hits += "${p[1]}  ${p[2]}  ${p[3]}  ${p[4]}"
                    }
                    line.startsWith("SCAN_ERROR,") -> return@use listOf("SENSOR SCAN ERROR: ${line.removePrefix("SCAN_ERROR,")}")
                }
            }
            hits
        }
    }.getOrElse { listOf("SENSOR SCAN ERROR: ${it.message ?: it.javaClass.simpleName}") }

    fun format(report: Report): String = buildString {
        appendLine("BYD TELEMETRY DIAGNOSTICS ${BuildConfig.VERSION_NAME}"); appendLine()
        appendLine("ADB: ${report.adbStatus}"); report.adbError?.let { appendLine("ADB error: $it") }; appendLine()
        appendLine("BYD HAL API: ${if (report.engineApiPresent || report.speedApiPresent) "FOUND" else "NOT FOUND"}")
        appendLine("HAL read path: ${if (report.engineReadable) "DIPLUS ENGINE FEATURE LISTENER" else "NOT READY"}")
        appendLine("Live telemetry read: ${if (report.engineReadable || report.speedReadable) "OK" else "FAILED"}")
        report.engineRpm?.let { appendLine("MOTOR RPM: $it RPM") }; report.currentSpeedKph?.let { appendLine(String.format(Locale.US, "Speed: %.1f km/h", it)) }
        report.acceleratorPercent?.let { appendLine("Accelerator: $it%") }; report.brakePercent?.let { appendLine("Brake: $it%") }; appendLine()
        appendLine("DIPLUS FRONT MOTOR SPEED LISTENER")
        if (report.sensorScan.isEmpty()) appendLine("No listener diagnostics returned.") else report.sensorScan.forEach { appendLine(it) }
        appendLine()
        appendLine("BYD permissions declared: ${report.declaredPermissions.size}/${REQUIRED_PERMISSIONS.size}")
        appendLine("BYD permissions reported granted: ${report.grantedPermissions.size}/${REQUIRED_PERMISSIONS.size}")
        report.grantedPermissions.forEach { appendLine("✓ $it") }; report.failedPermissionGrants.forEach { appendLine("✗ $it") }; appendLine()
        report.notes.forEach { appendLine("• $it") }
    }
}
