package com.driveapex.update

import android.content.Context
import com.driveapex.BuildConfig
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * BYD/DiLink vehicle OTA installer.
 *
 * This follows the same important transport model used by OverDrive:
 * the app connects to the local ADB daemon at 127.0.0.1:5555, keeps a
 * persistent ADB key, stages the APK under /data/local/tmp, and performs
 * the actual package replacement through the ADB shell with `pm install -r`.
 *
 * The normal Android package-installer path remains the phone fallback.
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
        private const val CONNECT_ATTEMPTS = 20
        private const val CONNECT_DELAY_MS = 1_500L
        private const val REMOTE_APK = "/data/local/tmp/driveapex_update.apk"
        private const val REMOTE_SCRIPT = "/data/local/tmp/driveapex_install.sh"
        private const val IN_PROGRESS = "/data/local/tmp/driveapex_update_in_progress"
        private const val POST_UPDATE = "/data/local/tmp/driveapex_post_update"
        private const val VERSION_FILE = "/data/local/tmp/driveapex_version"

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
            return Result.Failed("Verified APK is missing or unexpectedly small")
        }

        if (!adbPortOpen()) return Result.AdbUnavailable

        val keyPair = runCatching { getOrCreateKeyPair() }
            .getOrElse {
                return Result.Failed(
                    "ADB key initialization failed: ${it.message ?: it.javaClass.simpleName}"
                )
            }

        var connected: Dadb? = null
        var lastError: Throwable? = null

        repeat(CONNECT_ATTEMPTS) { attempt ->
            try {
                connected?.close()
                connected = Dadb.create(
                    ADB_HOST,
                    ADB_PORT,
                    keyPair,
                    CONNECT_TIMEOUT_MS,
                    SOCKET_TIMEOUT_MS
                )
                connected!!.shell("echo overdrive-style-adb-ready")
                lastError = null
                return@repeat
            } catch (e: Exception) {
                lastError = e
                connected = null
                if (attempt < CONNECT_ATTEMPTS - 1) {
                    Thread.sleep(CONNECT_DELAY_MS)
                }
            }
        }

        val dadb = connected ?: return if (looksLikeAuthFailure(lastError?.message.orEmpty())) {
            Result.AuthPending
        } else {
            Result.Failed(lastError?.message ?: "ADB connection failed")
        }

        return try {
            // Stage the APK exactly where a shell process can consume it.
            dadb.push(apk, REMOTE_APK)

            val marker = "${BuildConfig.VERSION_NAME} (${expectedVersionCode})"
            val script = buildInstallerScript(marker)
            writeScript(dadb, script)

            // Execute the package replacement from the ADB shell. We do not use
            // dadb.install() here because the reference OverDrive path performs
            // installation as an explicit shell `pm install -r` operation.
            val run = dadb.shell("sh $REMOTE_SCRIPT")
            if (run.exitCode != 0) {
                return Result.Failed("Vehicle OTA shell install failed: ${run.allOutput}")
            }

            // pm install returns before/while the old app process is replaced;
            // verify package metadata from the same shell once it is available.
            var installedVersion: Int? = null
            repeat(8) {
                val response = dadb.shell(
                    "dumpsys package ${BuildConfig.APPLICATION_ID} | grep -m 1 -E 'versionCode'"
                )
                if (response.exitCode == 0) {
                    installedVersion = Regex("versionCode=(\\d+)")
                        .find(response.allOutput)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                }
                if (installedVersion != null) return@repeat
                Thread.sleep(750L)
            }

            if (installedVersion != null && installedVersion < expectedVersionCode) {
                return Result.Failed(
                    "Vehicle OTA completed but installed version is $installedVersion; expected >= $expectedVersionCode"
                )
            }

            Result.Installed(installedVersion)
        } catch (e: Exception) {
            if (looksLikeAuthFailure(e.message.orEmpty())) {
                Result.AuthPending
            } else {
                Result.Failed(e.message ?: e.javaClass.simpleName)
            }
        } finally {
            runCatching { dadb.shell("rm -f $REMOTE_APK $REMOTE_SCRIPT 2>/dev/null") }
            runCatching { dadb.close() }
        }
    }

    private fun buildInstallerScript(marker: String): String {
        val safeMarker = marker.replace("'", "_")
        return """
            #!/system/bin/sh
            echo '$safeMarker' > $IN_PROGRESS
            echo '$safeMarker' > $POST_UPDATE
            echo '$safeMarker' > $VERSION_FILE
            chmod 666 $IN_PROGRESS $POST_UPDATE $VERSION_FILE 2>/dev/null
            pm install -r $REMOTE_APK
            rc=\$?
            if [ \"\$rc\" -eq 0 ]; then
              rm -f $IN_PROGRESS 2>/dev/null
              am force-stop ${BuildConfig.APPLICATION_ID} 2>/dev/null
              am start -n ${BuildConfig.APPLICATION_ID}/.MainActivity --ez post_update true >/dev/null 2>&1
            fi
            exit \$rc
        """.trimIndent()
    }

    private fun writeScript(dadb: Dadb, script: String) {
        val nonce = "${System.nanoTime()}"
        val eof = "__DRIVE_APEX_EOF_$nonce__"
        val writeCommand = "cat > $REMOTE_SCRIPT <<'$eof'\n$script\n$eof"
        val write = dadb.shell(writeCommand)
        if (write.exitCode != 0) {
            throw IllegalStateException("Vehicle OTA script staging failed: ${write.allOutput}")
        }
        dadb.shell("chmod 700 $REMOTE_SCRIPT 2>/dev/null")
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
            normalized.contains("permission denied") ||
            normalized.contains("adb auth pending")
    }
}
