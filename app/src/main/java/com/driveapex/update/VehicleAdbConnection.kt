package com.driveapex.update

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent local ADB transport modeled on OverDrive's connection layer.
 *
 * A short foreground connection attempt is paired with a separate, bounded
 * background authorization poller so the app never blocks on the ADB handshake.
 */
internal class VehicleAdbConnection(private val context: Context) {

    companion object {
        private const val HOST = "127.0.0.1"
        private const val PORT = 5555
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val SOCKET_TIMEOUT_MS = 45_000
        private const val QUICK_WAIT_MS = 2_000L
        private const val MAX_POLLS = 60

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

        fun setAuthGrantedCallback(callback: (() -> Unit)?) {
            authCallback = callback
        }

        fun isAuthPending(): Boolean = authPending.get()
    }

    fun connect(): Dadb? {
        synchronized(sharedLock) {
            shared?.let { existing ->
                try {
                    if (existing.shell("echo ok").exitCode == 0) return existing
                } catch (_: Exception) {
                }
                runCatching { existing.close() }
                shared = null
            }

            if (!adbPortOpen()) return null
            val key = runCatching { getOrCreateKey() }.getOrNull() ?: return null

            if (!authPending.get() && pollingStarted.compareAndSet(false, true)) {
                authPending.set(true)
                startAuthPolling(key)
            }

            val connected = runCatching {
                tryConnectWithTimeout(key, QUICK_WAIT_MS)
            }.getOrNull()
            if (connected != null) {
                shared = connected
                authPending.set(false)
                pollingStarted.set(false)
                authCallback?.invoke()
                return connected
            }

            // Timeout/handshake failure is intentionally left to the independent
            // poller. On BYD, accepting the ADB authorization can happen after the
            // first connection attempt has already timed out.
            return null
        }
    }

    fun close() {
        synchronized(sharedLock) {
            runCatching { shared?.close() }
            shared = null
        }
    }

    private fun startAuthPolling(key: AdbKeyPair) {
        pollExecutor.execute {
            var attempts = 0
            while (authPending.get() && attempts < MAX_POLLS) {
                attempts++
                try {
                    val backoffMs = if (attempts <= 3) 3_000L
                    else minOf(3_000L * (1L shl minOf(attempts - 3, 4)), 30_000L)
                    Thread.sleep(backoffMs)
                } catch (_: InterruptedException) {
                    return@execute
                }

                if (!authPending.get() || !adbPortOpen()) continue
                val candidate = runCatching {
                    tryConnectWithTimeout(key, QUICK_WAIT_MS)
                }.getOrNull() ?: continue

                synchronized(sharedLock) {
                    runCatching { shared?.close() }
                    shared = candidate
                }
                authPending.set(false)
                pollingStarted.set(false)
                authCallback?.invoke()
                return@execute
            }

            if (authPending.get()) {
                authPending.set(false)
                pollingStarted.set(false)
            }
        }
    }

    private fun tryConnectWithTimeout(key: AdbKeyPair, waitMs: Long): Dadb? {
        var result: Dadb? = null
        var error: Exception? = null
        val thread = Thread({
            try {
                val dadb = Dadb.create(
                    HOST,
                    PORT,
                    key,
                    CONNECT_TIMEOUT_MS,
                    SOCKET_TIMEOUT_MS
                )
                val probe = dadb.shell("echo overdrive-style-adb-ready")
                if (probe.exitCode == 0) {
                    result = dadb
                } else {
                    dadb.close()
                }
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

    private fun adbPortOpen(): Boolean {
        return try {
            Socket(HOST, PORT).use { true }
        } catch (_: Exception) {
            false
        }
    }

    private fun getOrCreateKey(): AdbKeyPair {
        cachedKey?.let { return it }
        synchronized(keyLock) {
            cachedKey?.let { return it }
            val dir = File(context.filesDir, "vehicle_adb")
            if (!dir.exists() && !dir.mkdirs()) {
                error("Cannot create ADB key directory")
            }
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
