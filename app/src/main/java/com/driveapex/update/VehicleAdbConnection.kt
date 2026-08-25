package com.driveapex.update

import android.content.Context
import com.driveapex.BuildConfig
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BYD/DiPlus-style local ADB transport.
 *
 * ADB is used only for bootstrap/authorization and shell-side permission
 * provisioning. Vehicle telemetry itself is read through the BYD HAL layer.
 */
internal class VehicleAdbConnection(private val context: Context) {

    enum class State { OFF, CONNECTING, AUTH_REQUIRED, AUTHORIZED, ERROR }

    data class PermissionResult(
        val permission: String,
        val granted: Boolean,
        val detail: String
    )

    companion object {
        private const val HOST = "127.0.0.1"
        private const val PORT = 5555
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val SOCKET_TIMEOUT_MS = 45_000
        private const val QUICK_WAIT_MS = 2_000L
        private const val MAX_POLLS = 60

        // Read-only BYD permissions observed in the DiPlus APK for vehicle data.
        private val BYD_READ_PERMISSIONS = arrayOf(
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

        fun setAuthGrantedCallback(callback: (() -> Unit)?) {
            authCallback = callback
        }

        fun isAuthPending(): Boolean = authPending.get()
        fun state(): State = state
        fun lastError(): String? = lastError
        fun permissionResults(): List<PermissionResult> = lastPermissionResults

        /** Starts the first-launch ADB handshake without blocking the UI. */
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
                } catch (_: Exception) {
                }
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

            val connected = runCatching { tryConnectWithTimeout(key, QUICK_WAIT_MS) }.getOrNull()
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
            lastError = "ADB authorization is not complete"
            return null
        }
    }

    fun close() {
        synchronized(sharedLock) {
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
                try {
                    val backoffMs = when {
                        attempts <= 3 -> 3_000L
                        else -> minOf(3_000L * (1L shl minOf(attempts - 3, 4)), 30_000L)
                    }
                    Thread.sleep(backoffMs)
                } catch (_: InterruptedException) {
                    return@execute
                }

                if (!authPending.get() || !adbPortOpen()) continue
                state = State.CONNECTING
                val candidate = runCatching { tryConnectWithTimeout(key, QUICK_WAIT_MS) }.getOrNull() ?: continue

                synchronized(sharedLock) {
                    runCatching { shared?.close() }
                    shared = candidate
                }
                authPending.set(false)
                pollingStarted.set(false)
                state = State.AUTHORIZED
                lastPermissionResults = grantBydReadPermissions(candidate)
                lastError = null
                authCallback?.invoke()
                return@execute
            }

            if (authPending.get()) {
                authPending.set(false)
                pollingStarted.set(false)
                if (!adbPortOpen()) state = State.OFF else state = State.AUTH_REQUIRED
            }
        }
    }

    /**
     * Mirrors DiPlus's `pm grant --user ...` strategy. Failures are retained and
     * exposed to diagnostics instead of being swallowed with `|| true`.
     */
    private fun grantBydReadPermissions(dadb: Dadb): List<PermissionResult> {
        val userId = currentUserId(dadb)
        return BYD_READ_PERMISSIONS.map { permission ->
            val command = "pm grant --user $userId ${BuildConfig.APPLICATION_ID} $permission"
            runCatching {
                val result = dadb.shell(command)
                val detail = listOf(result.stdout, result.stderr)
                    .filter { it.isNotBlank() }
                    .joinToString(" | ")
                val granted = result.exitCode == 0 || permissionCheck(dadb, userId, permission)
                PermissionResult(permission, granted, if (detail.isBlank()) "exit=${result.exitCode}" else detail)
            }.getOrElse {
                PermissionResult(permission, false, it.message ?: it.javaClass.simpleName)
            }
        }
    }

    private fun permissionCheck(dadb: Dadb, userId: Int, permission: String): Boolean = runCatching {
        val result = dadb.shell("pm check-permission --user $userId ${BuildConfig.APPLICATION_ID} $permission")
        result.stdout.contains("granted", ignoreCase = true)
    }.getOrDefault(false)

    private fun currentUserId(dadb: Dadb): Int = runCatching {
        val result = dadb.shell("cmd activity get-current-user")
        result.stdout.trim().toInt()
    }.getOrElse {
        runCatching {
            val result = dadb.shell("pm list users")
            Regex("UserInfo\\{(\\d+):").find(result.stdout)?.groupValues?.get(1)?.toInt() ?: 0
        }.getOrDefault(0)
    }

    private fun tryConnectWithTimeout(key: AdbKeyPair, waitMs: Long): Dadb? {
        var result: Dadb? = null
        var error: Exception? = null
        val thread = Thread({
            try {
                val dadb = Dadb.create(HOST, PORT, key, CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS)
                val probe = dadb.shell("echo driveapex-adb-ready")
                if (probe.exitCode == 0) result = dadb else dadb.close()
            } catch (e: Exception) {
                error = e
            }
        }, "driveapex-adb-connect").apply { isDaemon = true }

        thread.start()
        thread.join(waitMs)
        if (thread.isAlive) {
            thread.interrupt()
            return null
        }
        error?.let { throw it }
        return result
    }

    private fun adbPortOpen(): Boolean = try {
        Socket(HOST, PORT).use { true }
    } catch (_: Exception) {
        false
    }

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
                    privateKey.delete()
                    publicKey.delete()
                    AdbKeyPair.generate(privateKey, publicKey)
                    AdbKeyPair.read(privateKey, publicKey)
                }
            } else {
                AdbKeyPair.generate(privateKey, publicKey)
                AdbKeyPair.read(privateKey, publicKey)
            }
            cachedKey = key
            return key
        }
    }
}
