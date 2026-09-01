package com.driveapex.update

import android.content.Context
import com.driveapex.BuildConfig
import com.driveapex.diag.DriveApexLog
import dadb.AdbKeyPair
import dadb.AdbShellResponse
import dadb.Dadb
import java.io.File
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** BYD/DiPlus-style local ADB transport. */
internal class VehicleAdbConnection(private val context: Context) {
    enum class State { OFF, CONNECTING, AUTH_REQUIRED, AUTHORIZED, ERROR }
    data class PermissionResult(val permission: String, val granted: Boolean, val detail: String)

    companion object {
        private const val HOST = "127.0.0.1"
        private const val PORT = 5555
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val SOCKET_TIMEOUT_MS = 45_000
        private const val QUICK_WAIT_MS = 7_000L
        private const val MAX_POLLS = 120
        /**
         * Every BYD permission the manifest declares, not just the _COMMON tier.
         * On the target vehicle the diagnostics probe reported 4/10 granted -- exactly
         * the four _COMMON permissions listed here before -- while the engine listener
         * registered successfully and then received zero events. Registration only
         * needs the _COMMON tier; delivering engine/motor values needs the matching
         * _GET permission, and those were never passed to `pm grant` at all.
         */
        private val BYD_RUNTIME_GRANT_PERMISSIONS = arrayOf(
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
        private const val TELEMETRY_DAEMON_CLASS = "com.driveapex.vehicle.BydDiPlusEngineTelemetryDaemonMain"
        private const val TELEMETRY_DAEMON_NAME = "driveapex-byd"
        private const val TELEMETRY_DAEMON_LOG = "/data/local/tmp/driveapex-byd.log"
        private const val TELEMETRY_DAEMON_APK = "/data/local/tmp/driveapex-byd.apk"
        private const val TELEMETRY_DAEMON_BUILD = "/data/local/tmp/driveapex-byd.build"
        private const val TELEMETRY_DAEMON_PORT = 18765
        private const val BYD_FRAMEWORK_JAR = "/system/framework/bmmcamera.jar"
        private const val BYD_NATIVE_LIB_PATH = "/system/lib64:/product/lib64"
        private const val PREFERRED_BUNDLE = "com.overdrive.app"

        @Volatile private var shared: Dadb? = null
        private val sharedLock = Object()
        @Volatile private var cachedKey: AdbKeyPair? = null
        private val keyLock = Object()
        private val authPending = AtomicBoolean(false)
        private val pollingStarted = AtomicBoolean(false)
        private val pollExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "driveapex-adb-auth-poll").apply { isDaemon = true }
        }
        @Volatile private var authCallback: (() -> Unit)? = null
        @Volatile private var state: State = State.OFF
        @Volatile private var lastError: String? = null
        @Volatile private var lastPermissionResults: List<PermissionResult> = emptyList()
        @Volatile private var permissionsGranted = false

        /**
         * One ADB command at a time.
         *
         * The shared Dadb is a single multiplexed connection and several threads
         * now reach for it -- the telemetry poller a few times a second, the
         * diagnostics probe, the permission grants inside connect(). Overlapping
         * shell calls on one connection interleave their responses, so every
         * caller goes through here.
         *
         * connect() takes sharedLock and then this one; nothing takes them in the
         * other order, so the pair cannot deadlock.
         */
        private val shellLock = Object()

        fun shell(dadb: Dadb, command: String): AdbShellResponse =
            synchronized(shellLock) { dadb.shell(command) }

        fun setAuthGrantedCallback(callback: (() -> Unit)?) { authCallback = callback }
        fun isAuthPending(): Boolean = authPending.get()
        fun state(): State = state
        fun lastError(): String? = lastError
        fun permissionResults(): List<PermissionResult> = lastPermissionResults

        fun warmUp(context: Context) {
            if (!isVehicleRuntime()) return
            val appContext = context.applicationContext
            Thread({ runCatching { VehicleAdbConnection(appContext).connect() } }, "driveapex-adb-bootstrap")
                .apply { isDaemon = true; start() }
        }

        private fun isVehicleRuntime(): Boolean = runCatching {
            Class.forName("android.hardware.bydauto.engine.BYDAutoEngineDevice")
            true
        }.getOrDefault(false)
    }

    /**
     * The live connection if one is already established, with no side effects.
     *
     * connect() is not usable on a hot path: even when it reuses the cached
     * connection it first round-trips an `echo` and then re-runs
     * grantBydReadPermissions, which is one `pm grant` per declared permission.
     * A caller polling a few times a second would issue a dozen ADB commands
     * per poll. Pollers should take this and fall back to connect() only when
     * it returns null.
     *
     * Reads the volatile field directly rather than taking sharedLock: a
     * connect() elsewhere holds that lock across many ADB round trips, and a
     * poller blocking on it stalls the only working read path for seconds.
     */
    fun existing(): Dadb? = shared

    fun connect(): Dadb? {
        synchronized(sharedLock) {
            shared?.let { existing ->
                try {
                    if (shell(existing, "echo driveapex-adb-ready").exitCode == 0) {
                        state = State.AUTHORIZED
                        // Grant once per connection. Re-running it on every call
                        // meant one `pm grant` round trip per declared permission
                        // every time anything asked for the connection.
                        if (!permissionsGranted) {
                            lastPermissionResults = grantBydReadPermissions(existing)
                            allowBackgroundExecution(existing)
                            permissionsGranted = true
                        }
                        return existing
                    }
                } catch (_: Exception) {}
                runCatching { existing.close() }
                shared = null
                permissionsGranted = false
            }
            if (!adbPortOpen()) {
                state = State.OFF
                lastError = "ADB port 127.0.0.1:5555 is not available"
                return null
            }
            state = State.CONNECTING
            val key = runCatching { getOrCreateKey() }.getOrElse {
                state = State.ERROR
                lastError = "ADB key: ${it.message ?: it.javaClass.simpleName}"
                return null
            }
            if (!authPending.get() && pollingStarted.compareAndSet(false, true)) {
                authPending.set(true)
                startAuthPolling(key)
            }
            val connected = runCatching { tryConnectWithTimeout(key, QUICK_WAIT_MS) }
                .onFailure { lastError = "ADB connect: ${it.message ?: it.javaClass.simpleName}" }
                .getOrNull()
            if (connected != null) {
                shared = connected
                authPending.set(false)
                pollingStarted.set(false)
                state = State.AUTHORIZED
                lastPermissionResults = grantBydReadPermissions(connected)
                allowBackgroundExecution(connected)
                permissionsGranted = true
                lastError = null
                authCallback?.invoke()
                return connected
            }
            state = State.AUTH_REQUIRED
            lastError = lastError ?: "ADB authorization is not complete"
            return null
        }
    }

    /** Launch the DiPlus-compatible listener-driven telemetry daemon. */
    fun ensureTelemetryDaemon(): Boolean {
        val dadb = connect() ?: return false
        return runCatching {
            // The daemon is a separate shell-UID process: it outlives the app,
            // and an app update does not touch it. Reusing whatever holds the
            // port meant every new version talked to the daemon built from an
            // older APK -- which is how a fix that moved the motor read into the
            // daemon shipped twice and never once ran on the vehicle. So the
            // running daemon is reused only when it was launched from this exact
            // build; anything else is torn down and replaced below.
            val stamp = BuildConfig.VERSION_CODE.toString()
            val listening = shell(dadb, 
                "(nc -z $HOST $TELEMETRY_DAEMON_PORT || toybox nc -z $HOST $TELEMETRY_DAEMON_PORT) 2>/dev/null; echo \$?"
            ).output.trim() == "0"
            val runningBuild = runCatching {
                shell(dadb, "cat $TELEMETRY_DAEMON_BUILD 2>/dev/null").output.trim()
            }.getOrDefault("")
            if (listening && runningBuild == stamp) {
                DriveApexLog.i("daemon", "reusing daemon build $stamp on port $TELEMETRY_DAEMON_PORT")
                return@runCatching true
            }
            DriveApexLog.i(
                "daemon",
                "relaunching daemon: listening=$listening running=" +
                    (runningBuild.ifBlank { "none" }) + " expected=" + stamp
            )
            // A daemon that carried a stamp and is no longer listening did not
            // fail to start -- it died while serving, and its own output is the
            // only account of why. Read it before the launch below rotates it.
            if (!listening && runningBuild.isNotBlank()) {
                val dying = runCatching {
                    shell(dadb, "tail -n 6 $TELEMETRY_DAEMON_LOG 2>/dev/null").output.trim()
                }.getOrDefault("")
                if (dying.isNotBlank()) {
                    DriveApexLog.i("daemon", "previous daemon ended with: " +
                        dying.replace('\n', ' ').take(400))
                }
            }

            val apkPath = shell(dadb, 
                "pm path ${BuildConfig.APPLICATION_ID} | sed -n 's/^package://p' | head -n 1"
            ).output.trim()
            if (!apkPath.startsWith("/")) {
                lastError = "Cannot resolve APK path for telemetry daemon"
                return@runCatching false
            }

            val preferredPath = runCatching {
                shell(dadb, 
                    "pm path $PREFERRED_BUNDLE | sed -n 's/^package://p' | head -n 1"
                ).output.trim()
            }.getOrDefault("")
            val targetPackage = if (preferredPath.startsWith("/")) PREFERRED_BUNDLE else BuildConfig.APPLICATION_ID
            val sourceDir = if (preferredPath.startsWith("/")) preferredPath else apkPath
            val userId = currentUserId(dadb)
            val sessionId = System.currentTimeMillis().toString()

            val launch =
                "mv -f $TELEMETRY_DAEMON_LOG ${TELEMETRY_DAEMON_LOG}.prev 2>/dev/null; " +
                    "rm -f $TELEMETRY_DAEMON_BUILD; " +
                    "rm -f $TELEMETRY_DAEMON_APK; " +
                    "cp \"$apkPath\" $TELEMETRY_DAEMON_APK; " +
                    "chmod 644 $TELEMETRY_DAEMON_APK; " +
                    "killall $TELEMETRY_DAEMON_NAME 2>/dev/null || true; " +
                    "sleep 1; " +
                    "nohup app_process -Djava.class.path=$BYD_FRAMEWORK_JAR:$TELEMETRY_DAEMON_APK " +
                    "-Djava.library.path=$BYD_NATIVE_LIB_PATH /system/bin --nice-name=$TELEMETRY_DAEMON_NAME " +
                    "$TELEMETRY_DAEMON_CLASS --package=$targetPackage --source-dir=\"$sourceDir\" " +
                    "--session-id=$sessionId --requested-user-id=$userId --external-root=/sdcard --cid=0 --caller-app-uid=2000 " +
                    "< /dev/null > $TELEMETRY_DAEMON_LOG 2>&1 &"

            val launchResult = shell(dadb, launch)
            if (launchResult.exitCode != 0) {
                lastError = listOf(launchResult.output, launchResult.errorOutput)
                    .filter { it.isNotBlank() }.joinToString(" | ")
                    .ifBlank { "Telemetry daemon launch failed: exit=${launchResult.exitCode}" }
                return@runCatching false
            }

            repeat(40) {
                Thread.sleep(100L)
                if (runCatching { Socket(HOST, TELEMETRY_DAEMON_PORT).use { true } }.getOrDefault(false)) {
                    // Written only now, so a launch that never came up leaves no
                    // stamp and the next attempt relaunches instead of trusting it.
                    runCatching { shell(dadb, "echo $stamp > $TELEMETRY_DAEMON_BUILD") }
                    DriveApexLog.i("daemon", "daemon build $stamp is serving")
                    lastError = null
                    return@runCatching true
                }
                val process = runCatching {
                    shell(dadb, "ps -A | grep -w $TELEMETRY_DAEMON_NAME | grep -v grep").output.trim()
                }.getOrDefault("")
                if (process.isBlank()) {
                    val log = runCatching {
                        shell(dadb, "tail -n 80 $TELEMETRY_DAEMON_LOG 2>/dev/null").output.trim()
                    }.getOrDefault("")
                    if (log.isNotBlank()) lastError = log
                }
            }
            lastError = runCatching {
                shell(dadb, "tail -n 80 $TELEMETRY_DAEMON_LOG 2>/dev/null").output.trim()
            }.getOrDefault("").ifBlank {
                "Telemetry daemon did not open localhost:$TELEMETRY_DAEMON_PORT"
            }
            false
        }.getOrElse {
            lastError = "Telemetry daemon: ${it.message ?: it.javaClass.simpleName}"
            false
        }
    }

    fun close() {
        synchronized(sharedLock) {
            runCatching { shared?.let { shell(it, "killall $TELEMETRY_DAEMON_NAME 2>/dev/null || true") } }
            runCatching { shared?.close() }
            shared = null
            state = State.OFF
        }
    }

    private fun startAuthPolling(key: AdbKeyPair) {
        pollExecutor.execute {
            var attempts = 0
            while (authPending.get() && attempts < MAX_POLLS) {
                attempts++
                try { Thread.sleep(if (attempts <= 4) 2_000L else if (attempts <= 12) 4_000L else 10_000L) }
                catch (_: InterruptedException) { return@execute }
                if (!authPending.get() || !adbPortOpen()) continue
                state = State.CONNECTING
                val candidate = runCatching { tryConnectWithTimeout(key, QUICK_WAIT_MS) }.getOrNull() ?: continue
                synchronized(sharedLock) { runCatching { shared?.close() }; shared = candidate }
                authPending.set(false); pollingStarted.set(false); state = State.AUTHORIZED
                lastPermissionResults = grantBydReadPermissions(candidate); lastError = null; authCallback?.invoke()
                return@execute
            }
            if (authPending.get()) {
                authPending.set(false); pollingStarted.set(false)
                state = if (!adbPortOpen()) State.OFF else State.AUTH_REQUIRED
            }
        }
    }

    private fun grantBydReadPermissions(dadb: Dadb): List<PermissionResult> {
        val userId = currentUserId(dadb)
        return BYD_RUNTIME_GRANT_PERMISSIONS.map { permission ->
            runCatching {
                val result = shell(dadb, "pm grant --user $userId ${BuildConfig.APPLICATION_ID} $permission")
                val detail = listOf(result.output, result.errorOutput).filter { it.isNotBlank() }.joinToString(" | ")
                val granted = result.exitCode == 0 || permissionCheck(dadb, userId, permission)
                PermissionResult(permission, granted, if (detail.isBlank()) "exit=${result.exitCode}" else detail)
            }.getOrElse { PermissionResult(permission, false, it.message ?: it.javaClass.simpleName) }
        }
    }

    /**
     * Uses the ADB channel we already hold to let this app keep running.
     *
     * The engine has to keep playing with the app behind a navigation prompt or
     * the OEM launcher, and a foreground service alone is not always enough on a
     * head unit: OEM builds park background apps in restricted app-standby
     * buckets and revoke their background execution, which is a process that
     * simply stops making sound with nothing in the log to say why.
     *
     * All of these are scoped to this package and reversible, and none of them
     * touches the vehicle: they are the same kind of thing as the `pm grant`
     * above. Nothing here is a _SET permission or a command to the car.
     *
     * Failures are logged rather than reported: several of these commands do not
     * exist on every Android version, and an app that works without them should
     * not act as though something is broken.
     */
    private fun allowBackgroundExecution(dadb: Dadb) {
        val pkg = BuildConfig.APPLICATION_ID
        val userId = currentUserId(dadb)
        val commands = listOf(
            // Background execution, in the two spellings Android has used.
            "cmd appops set $pkg RUN_IN_BACKGROUND allow",
            "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            // Permission to be a foreground service at all.
            "cmd appops set $pkg START_FOREGROUND allow",
            // Out of doze's reach, so a parked car does not silence the engine.
            "dumpsys deviceidle whitelist +$pkg",
            // Out of the restricted standby bucket the OEM launcher puts
            // backgrounded apps in.
            "cmd package set-standby-bucket $pkg active",
            // Only affects whether the service's notification is visible; the
            // service runs either way. Not grantable before API 33, which fails
            // harmlessly here.
            "pm grant --user $userId $pkg android.permission.POST_NOTIFICATIONS"
        )
        commands.forEach { command ->
            val outcome = runCatching { shell(dadb, command).exitCode }.getOrElse { -1 }
            DriveApexLog.i("adb", "background allowance: $command -> exit $outcome")
        }
    }

    private fun permissionCheck(dadb: Dadb, userId: Int, permission: String): Boolean = runCatching {
        shell(dadb, "pm check-permission --user $userId ${BuildConfig.APPLICATION_ID} $permission")
            .output.contains("granted", ignoreCase = true)
    }.getOrDefault(false)

    private fun currentUserId(dadb: Dadb): Int = runCatching {
        shell(dadb, "cmd activity get-current-user").output.trim().toInt()
    }.getOrElse { 0 }

    private fun tryConnectWithTimeout(key: AdbKeyPair, waitMs: Long): Dadb? {
        var result: Dadb? = null
        var error: Exception? = null
        val thread = Thread({
            try {
                val dadb = Dadb.create(HOST, PORT, key, CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS)
                if (dadb.shell("echo driveapex-adb-ready").exitCode == 0) result = dadb else dadb.close()
            } catch (e: Exception) { error = e }
        }, "driveapex-adb-connect").apply { isDaemon = true }
        thread.start(); thread.join(waitMs)
        if (thread.isAlive) { thread.interrupt(); return null }
        error?.let { throw it }; return result
    }

    private fun adbPortOpen(): Boolean = try { Socket(HOST, PORT).use { true } } catch (_: Exception) { false }

    private fun getOrCreateKey(): AdbKeyPair {
        cachedKey?.let { return it }
        synchronized(keyLock) {
            cachedKey?.let { return it }
            val dir = File(context.filesDir, "vehicle_adb")
            if (!dir.exists() && !dir.mkdirs()) error("Cannot create ADB key directory")
            val privateKey = File(dir, "adbkey")
            val publicKey = File(dir, "adbkey.pub")
            val key = if (privateKey.isFile && publicKey.isFile) {
                runCatching { AdbKeyPair.read(privateKey, publicKey) }.getOrElse {
                    privateKey.delete(); publicKey.delete(); AdbKeyPair.generate(privateKey, publicKey); AdbKeyPair.read(privateKey, publicKey)
                }
            } else {
                AdbKeyPair.generate(privateKey, publicKey); AdbKeyPair.read(privateKey, publicKey)
            }
            cachedKey = key; return key
        }
    }
}
