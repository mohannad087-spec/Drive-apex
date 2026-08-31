package com.driveapex.vehicle

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import com.driveapex.BuildConfig
import com.driveapex.update.VehicleAdbConnection
import dadb.Dadb
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
        val notes: List<String>, val sensorScan: List<String> = emptyList(),
        val diPlusRpm: Int? = null, val diPlusHost: String = "", val diPlusError: String? = null,
        val heldPermissions: List<String> = emptyList(), val missingPermissions: List<String> = emptyList(),
        val inProcessReport: String = "", val inProcessRpm: Int? = null,
        val diPlusInstall: List<String> = emptyList(),
        val diPlusAdbRpm: Int? = null, val diPlusAdbStatus: String = ""
    )

    /** Same endpoint DiPlusMotorSpeedReader uses, for the shell-side comparison. */
    private const val PATH_QUERY = "/api/getVal?name=%E5%89%8D%E7%94%B5%E6%9C%BA%E8%BD%AC%E9%80%9F&status=true"

    private val REQUIRED_PERMISSIONS = listOf(
        "android.permission.BYDAUTO_SPEED_COMMON", "android.permission.BYDAUTO_SPEED_GET",
        "android.permission.BYDAUTO_ENGINE_COMMON", "android.permission.BYDAUTO_ENGINE_GET",
        "android.permission.BYDAUTO_MOTOR_GET", "android.permission.BYDAUTO_ENERGY_COMMON",
        "android.permission.BYDAUTO_ENERGY_GET", "android.permission.BYDAUTO_GEARBOX_COMMON",
        "android.permission.BYDAUTO_GEARBOX_GET", "android.permission.BYDAUTO_VEHICLE_DATA_GET"
    )

    fun probe(activity: Activity): Report {
        val notes = mutableListOf<String>()

        // Read this first and report it at the top. It is the only path that has
        // actually produced a front motor value on this vehicle, and burying it
        // under the ADB/permission output made it invisible on the head unit.
        var diPlusRpm: Int? = null
        var diPlusError: String? = null
        runCatching { DiPlusMotorSpeedReader.readFrontMotorRpm() }
            .onSuccess { diPlusRpm = it?.toInt() }
            .onFailure { diPlusError = it.javaClass.simpleName + (it.message?.let { m -> ": $m" } ?: "") }
        val diPlusHost = DiPlusMotorSpeedReader.lastPath()

        // Ground truth for permissions. Everything reported before this came from
        // `pm grant` exit codes; the app never asked the OS what it actually holds,
        // and every checkSelfPermission in the telemetry readers is a local
        // override that returns GRANTED unconditionally. This call is the real one.
        val declaredBydPermissions = runCatching {
            activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.filter { it.startsWith("android.permission.BYDAUTO") }.orEmpty()
        }.getOrDefault(emptyList())
        val held = declaredBydPermissions.filter {
            runCatching { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        }
        val missing = declaredBydPermissions - held.toSet()

        // Run the in-process listener the way DiPlus runs its own and report what
        // it receives. Every previous "0 events" reading came from the shell-UID
        // daemon on port 18766, which holds no manifest permissions at all, so it
        // never said anything about this path.
        var inProcessReport = "not attempted"
        var inProcessRpm: Int? = null
        val probeReader = runCatching { FrontMotorSpeedReader(activity) }.getOrNull()
        if (probeReader == null) inProcessReport = "could not construct FrontMotorSpeedReader"
        else {
            runCatching { probeReader.start() }
            var waited = 0
            while (waited < 5_000 && probeReader.frontMotorRpm == null) {
                Thread.sleep(100L)
                waited += 100
            }
            inProcessRpm = runCatching { probeReader.frontMotorRpm }.getOrNull()
            inProcessReport = runCatching { probeReader.diagnostics() }.getOrElse { "diagnostics failed: ${it.javaClass.simpleName}" }
            runCatching { probeReader.stop() }
        }
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
        notes += "Measured on this vehicle: DiPlus holds only BYDAUTO_ENGINE_COMMON and BYDAUTO_SPEED_COMMON -- the same tier this app holds -- and is installed to /data/app with no SYSTEM or PRIVILEGED flag. So the missing _GET permissions cannot be what separates the two apps."
        notes += "Its API is served by a native process (aps_diplus) on tcp6 :::8988, not by its Java HAL listener, so the decompiled listener may not be where its value comes from."

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
        notes += halOrigin("android.hardware.bydauto.engine.BYDAutoEngineDevice")
        notes += halOrigin("android.hardware.bydauto.speed.BYDAutoSpeedDevice")
        notes += "Live path: in-process listener (DiPlus style) first, shell-UID daemon second, direct HAL third."
        notes += "Feature ID is resolved from BYDAutoFeatureIds.ENGINE_FRONT_MOTOR_SPEED at runtime with a verified fallback."
        notes += "RPM invalid sentinels are rejected instead of being displayed as real RPM."

        val diPlusInstall = if (adbConnection != null) inspectDiPlus(adbConnection)
        else listOf("ADB not authorized -- cannot inspect DiPlus")

        // The same value over the path measured to work: the shell UID.
        val adbBridge = runCatching { DiPlusAdbBridge(activity) }.getOrNull()
        val diPlusAdbRpm = runCatching { adbBridge?.readOnce() }.getOrNull()
        val diPlusAdbStatus = runCatching { adbBridge?.status() }.getOrNull() ?: "unavailable"

        val scan = if (adbConnection != null) runSensorScan() else listOf("SENSOR SCAN: ADB not authorized")
        val scanError = scan.firstOrNull { it.startsWith("SENSOR SCAN ERROR:") }
        if (scanError != null) notes += scanError
        else notes += "DiPlus listener scan: ${scan.size} diagnostic entries returned."

        val anyRead = readable || diPlusRpm != null || inProcessRpm != null || diPlusAdbRpm != null
        return Report(adbStatus, VehicleAdbConnection.lastError(), engineApiPresent, anyRead, anyRead, rpm ?: diPlusRpm ?: diPlusAdbRpm, speedApiPresent, anyRead, speed, accelerator, brake, directFrame != null, if (anyRead) null else (daemonError ?: "No readable live drivetrain frame"), declared, grantedPermissions, failedPermissionGrants, notes, scan, diPlusRpm, diPlusHost, diPlusError, held, missing, inProcessReport, inProcessRpm, diPlusInstall, diPlusAdbRpm, diPlusAdbStatus)
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
        // A boot-classpath class on Android reports a BootClassLoader instance, not
        // null. The previous null check reported the real vehicle HAL as this APK's
        // stub on every run.
        if (loader == null || loader == Any::class.java.classLoader) "$short: real vehicle HAL (boot classpath)"
        else "$short: LOCAL STUB from this APK (${loader.javaClass.simpleName}) -- not the vehicle HAL"
    }.getOrElse { "${className.substringAfterLast('.')}: not present (${it.javaClass.simpleName})" }

    /**
     * How DiPlus is installed and which BYD permissions the OS says it holds,
     * plus the protection level of the two permissions this app is missing.
     *
     * Every claim about why DiPlus can read the motor and this app cannot has so
     * far been an inference from its decompiled code and its signing cert. These
     * four shell commands replace all of that with what the package manager
     * actually reports. protectionLevel is the crux: `signature` means the
     * permission needs BYD's platform key and no third-party app can ever hold
     * it -- in which case DiPlus must not hold it either, and it is reaching the
     * data another way.
     */
    private fun inspectDiPlus(dadb: Dadb): List<String> {
        val out = mutableListOf<String>()
        fun run(label: String, command: String, maxLines: Int) {
            runCatching {
                val text = VehicleAdbConnection.shell(dadb, command).output.trim()
                if (text.isBlank()) out += "$label: (no output)"
                else {
                    out += "$label:"
                    text.lineSequence().take(maxLines).forEach { out += "  ${it.trim()}" }
                }
            }.onFailure { out += "$label: failed (${it.javaClass.simpleName})" }
        }
        run("DiPlus installed at", "pm path com.van.diplus", 3)
        run("DiPlus install flags", "dumpsys package com.van.diplus | grep -E 'codePath|flags=|privateFlags=' | head -5", 5)
        run("DiPlus BYD permissions granted", "dumpsys package com.van.diplus | grep -E 'BYDAUTO_(ENGINE|MOTOR|SPEED)[A-Z_]*: granted=' | head -12", 12)
        run("Protection level of what we lack", "pm list permissions -f | grep -A4 -E 'BYDAUTO_(ENGINE|MOTOR)_GET\\b' | grep -E 'permission:|protectionLevel' | head -8", 8)
        run("DiPlus API port 8988", "netstat -tlnp 2>/dev/null | grep 8988 || echo 'nothing listening on 8988'", 4)

        // The decisive test. Every attempt from inside this app -- IPv4 and IPv6
        // loopback, the LAN address, two request shapes -- returned "Connection
        // reset", while curl from a phone over the network to the same port
        // returned 200 OK. The one variable never isolated is who is asking, so
        // repeat the request from the shell UID on the vehicle itself:
        //   reset here too  -> the service refuses same-host callers, and no local
        //                      client of any kind can use this API
        //   200 OK here     -> the service is fine locally and something about this
        //                      app's process is being blocked
        run("Local HTTP clients available", "command -v curl wget nc toybox busybox 2>/dev/null || echo none", 6)
        run(
            "Same request from shell UID on the vehicle",
            "curl -sS -m 4 -D - 'http://127.0.0.1:8988" + PATH_QUERY + "' 2>&1 | head -12",
            12
        )
        run("All listening TCP ports", "netstat -tlnp 2>/dev/null | head -20", 20)
        run(
            "Network rules for this app",
            "dumpsys package com.driveapex | grep -m1 userId= ; dumpsys netpolicy | grep -i -m6 driveapex ; " +
                "cmd connectivity list 2>/dev/null | head -3 ; echo '--- vpn ---' ; " +
                "ip rule 2>/dev/null | head -8",
            18
        )
        return out
    }

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
        appendLine("=== FRONT MOTOR (DiPlus API) ===")
        when {
            report.diPlusRpm != null -> appendLine("OK: ${report.diPlusRpm} RPM  (via ${report.diPlusHost})")
            report.diPlusError != null -> appendLine("ERROR: ${report.diPlusError}")
            else -> appendLine("NO ANSWER from ${report.diPlusHost} -- is DiPlus running?")
        }
        appendLine()
        appendLine("=== FRONT MOTOR (DiPlus API via shell UID) ===")
        appendLine(if (report.diPlusAdbRpm != null) "OK: ${report.diPlusAdbRpm} RPM" else "no value")
        appendLine(report.diPlusAdbStatus)
        appendLine()
        appendLine("=== FRONT MOTOR (BYD HAL, in-process like DiPlus) ===")
        appendLine(if (report.inProcessRpm != null) "OK: ${report.inProcessRpm} RPM" else "no value")
        appendLine(report.inProcessReport)
        appendLine()
        appendLine("=== BYD PERMISSIONS THIS APP ACTUALLY HOLDS ===")
        appendLine("held ${report.heldPermissions.size} / ${report.heldPermissions.size + report.missingPermissions.size} (asked the OS, not pm grant)")
        report.missingPermissions.forEach { appendLine("MISSING ${it.removePrefix("android.permission.")}") }
        appendLine()
        appendLine("=== HOW DIPLUS DOES IT (asked the OS over ADB) ===")
        if (report.diPlusInstall.isEmpty()) appendLine("no data") else report.diPlusInstall.forEach { appendLine(it) }
        appendLine()
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
