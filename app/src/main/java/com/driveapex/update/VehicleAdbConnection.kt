package com.driveapex.update

import android.content.Context
import com.driveapex.BuildConfig
import dadb.AdbKeyPair
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

    fun connect(): Dadb? {
        synchronized(sharedLock) {
            shared?.let { existing ->
                try {
                    if (existing.shell("echo driveapex-adb-ready").exitCode == 0) {
                        state = State.AUTHORIZED
                        lastPermissionResults = grantBydReadPermissions(existing)
                        return existing
                    }
                } catch (_: Exception) {}
                runCatching { existing.close() }
                shared = null
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
            val listening = dadb.shell(
                "(nc -z $HOST $TELEMETRY_DAEMON_PORT || toybox nc -z $HOST $TELEMETRY_DAEMON_PORT) 2>/dev/null; echo \$?"
            ).output.trim() == "0"
            if (listening) return@runCatching true

            val apkPath = dadb.shell(
                "pm path ${BuildConfig.APPLICATION_ID} | sed -n 's/^package://p' | head -n 1"
            ).output.trim()
            if (!apkPath.startsWith("/")) {
                lastError = "Cannot resolve APK path for telemetry daemon"
                return@runCatching false
            }

            val preferredPath = runCatching {
                dadb.shell(
                    "pm path $PREFERRED_BUNDLE | sed -n 's/^package://p' | head -n 1"
                ).output.trim()
            }.getOrDefault("")
            val targetPackage = if (preferredPath.startsWith("/")) PREFERRED_BUNDLE else BuildConfig.APPLICATION_ID
            val sourceDir = if (preferredPath.startsWith("/")) preferredPath else apkPath
            val userId = currentUserId(dadb)
            val sessionId = System.currentTimeMillis().toString()

            val launch =
                "rm -f $TELEMETRY_DAEMON_LOG; " +
                    "rm -f $TELEMETRY_DAEMON_APK; " +
                    "cp \"$apkPath\" $TELEMETRY_DAEMON_APK; " +
                    "chmod 644 $TELEMETRY_DAEMON_APK; " +
                    "killall $TELEMETRY_DAEMON_NAME 2>/dev/null || true; " +
                    "nohup app_process -Djava.class.path=$BYD_FRAMEWORK_JAR:$TELEMETRY_DAEMON_APK " +
                    "-Djava.library.path=$BYD_NATIVE_LIB_PATH /system/bin --nice-name=$TELEMETRY_DAEMON_NAME " +
                    "$TELEMETRY_DAEMON_CLASS --package=$targetPackage --source-dir=\"$sourceDir\" " +
                    "--session-id=$sessionId --requested-user-id=$userId --external-root=/sdcard --cid=0 --caller-app-uid=2000 " +
                    "< /dev/null > $TELEMETRY_DAEMON_LOG 2>&1 &"

            val launchResult = dadb.shell(launch)
            if (launchResult.exitCode != 0) {
                lastError = listOf(launchResult.output, launchResult.errorOutput)
                    .filter { it.isNotBlank() }.joinToString(" | ")
                    .ifBlank { "Telemetry daemon launch failed: exit=${launchResult.exitCode}" }
                return@runCatching false
            }

            repeat(40) {
                Thread.sleep(100L)
                if (runCatching { Socket(HOST, TELEMETRY_DAEMON_PORT).use { true } }.getOrDefault(false)) {
                    lastError = null
                    return@runCatching true
                }
                val process = runCatching {
                    dadb.shell("ps -A | grep -w $TELEMETRY_DAEMON_NAME | grep -v grep").output.trim()
                }.getOrDefault("")
                if (process.isBlank()) {
                    val log = runCatching {
                        dadb.shell("tail -n 80 $TELEMETRY_DAEMON_LOG 2>/dev/null").output.trim()
                    }.getOrDefault("")
                    if (log.isNotBlank()) lastError = log
                }
            }
            lastError = runCatching {
                dadb.shell("tail -n 80 $TELEMETRY_DAEMON_LOG 2>/dev/null").output.trim()
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
            runCatching { shared?.shell("killall $TELEMETRY_DAEMON_NAME 2>/dev/null || true") }
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
                val result = dadb.shell("pm grant --user $userId ${BuildConfig.APPLICATION_ID} $permission")
                val detail = listOf(result.output, result.errorOutput).filter { it.isNotBlank() }.joinToString(" | ")
                val granted = result.exitCode == 0 || permissionCheck(dadb, userId, permission)
                PermissionResult(permission, granted, if (detail.isBlank()) "exit=${result.exitCode}" else detail)
            }.getOrElse { PermissionResult(permission, false, it.message ?: it.javaClass.simpleName) }
        }
    }

    private fun permissionCheck(dadb: Dadb, userId: Int, permission: String): Boolean = runCatching {
        dadb.shell("pm check-permission --user $userId ${BuildConfig.APPLICATION_ID} $permission")
            .output.contains("granted", ignoreCase = true)
    }.getOrDefault(false)

    private fun currentUserId(dadb: Dadb): Int = runCatching {
        dadb.shell("cmd activity get-current-user").output.trim().toInt()
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
