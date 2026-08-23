package com.driveapex.update

import android.content.Context
import com.driveapex.BuildConfig
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Direct vehicle installer for BYD/DiLink head units.
 *
 * The reference OverDrive implementation uses the device's local ADB daemon on
 * 127.0.0.1:5555 and a persisted ADB key. DriveApex uses the same protocol via
 * dadb 1.2.8, without bundling an adb executable.
 *
 * This path is deliberately limited to a BYD head unit. On ordinary phones the
 * normal Android package-installer fallback remains unchanged.
 */
class VehicleOtaInstaller(private val context: Context) {

    sealed interface Result {
        data object NotVehicle : Result
        data object AdbUnavailable : Result
        data object AuthPending : Result
        data class Installed(val versionCode: Int?) : Result
        data class Failed(val message: String) : Result
    }

    companion object {
        private const val ADB_HOST = "127.0.0.1"
        private const val ADB_PORT = 5555
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val SOCKET_TIMEOUT_MS = 45_000
        private const val RETRIES = 8
        private const val RETRY_DELAY_MS = 1_500L

        fun isVehicleRuntime(): Boolean {
            return runCatching {
                Class.forName("android.hardware.bydauto.engine.BYDAutoEngineDevice")
                true
            }.recoverCatching {
                Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice")
                true
            }.getOrDefault(false)
        }
    }

    fun install(apk: File, expectedVersionCode: Int): Result {
        if (!isVehicleRuntime()) return Result.NotVehicle
        if (!apk.isFile || apk.length() < 100_000L) {
            return Result.Failed("Staged APK is missing or unexpectedly small")
        }

        if (!adbPortOpen()) return Result.AdbUnavailable

        val keyPair = runCatching { getOrCreateKeyPair() }
            .getOrElse { return Result.Failed("ADB key initialization failed: ${it.message ?: it.javaClass.simpleName}") }

        var lastError: Throwable? = null
        repeat(RETRIES) { attempt ->
            var dadb: Dadb? = null
            try {
                // dadb 1.2.8 exposes the timeout-aware five-argument create;
                // keep-alive was added later, so it is intentionally not used here.
                dadb = Dadb.create(
                    ADB_HOST,
                    ADB_PORT,
                    keyPair,
                    CONNECT_TIMEOUT_MS,
                    SOCKET_TIMEOUT_MS
                )

                // dadb 1.2.x does not expose the InstallResult type used by newer
                // releases. Calling install and ignoring its return value keeps this
                // source compatible with 1.2.8; install failures are surfaced as
                // exceptions and handled by the retry/error path below.
                dadb.install(apk, "-r")

                val installedVersion = readInstalledVersion(dadb)
                if (installedVersion != null && installedVersion < expectedVersionCode) {
                    return Result.Failed(
                        "ADB install reported success but installed version is $installedVersion; expected >= $expectedVersionCode"
                    )
                }
                return Result.Installed(installedVersion)
            } catch (e: Exception) {
                lastError = e
            } finally {
                runCatching { dadb?.close() }
            }

            if (attempt < RETRIES - 1) {
                Thread.sleep(RETRY_DELAY_MS)
            }
        }

        return if (looksLikeAuthFailure(lastError?.message.orEmpty())) {
            Result.AuthPending
        } else {
            Result.Failed(lastError?.message ?: "ADB vehicle install failed")
        }
    }

    private fun readInstalledVersion(dadb: Dadb): Int? {
        val response = dadb.shell(
            "dumpsys package ${BuildConfig.APPLICATION_ID} | grep -m 1 -E 'versionCode'"
        )
        if (response.exitCode != 0) return null
        val match = Regex("versionCode=(\\d+)").find(response.output)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun getOrCreateKeyPair(): AdbKeyPair {
        val dir = File(context.filesDir, "vehicle_adb")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Cannot create ADB key directory")
        }
        val privateKey = File(dir, "adbkey")
        val publicKey = File(dir, "adbkey.pub")
        if (!privateKey.isFile || !publicKey.isFile) {
            privateKey.delete()
            publicKey.delete()
            AdbKeyPair.generate(privateKey, publicKey)
        }
        return AdbKeyPair.read(privateKey, publicKey)
    }

    private fun adbPortOpen(): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ADB_HOST, ADB_PORT), 1_000)
            }
            true
        }.getOrDefault(false)
    }

    private fun looksLikeAuthFailure(text: String): Boolean {
        val normalized = text.lowercase()
        return normalized.contains("auth") ||
            normalized.contains("unauthorized") ||
            normalized.contains("authentication") ||
            normalized.contains("public key") ||
            normalized.contains("permission denied")
    }
}
