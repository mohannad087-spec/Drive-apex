package com.driveapex.update

import android.content.Context
import com.driveapex.BuildConfig
import dadb.Dadb
import java.io.File

/**
 * BYD/DiLink vehicle OTA installer.
 *
 * The transport deliberately goes through the persistent OverDrive-style ADB
 * connection rather than creating a separate DADB session for every update.
 * The APK is staged through ADB push and the replacement is performed by the
 * shell with `pm install -r -d`; the normal Android package-installer UI is not
 * used for vehicle updates.
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

        val transport = VehicleAdbConnection(context)
        val dadb = transport.connect()
            ?: return if (VehicleAdbConnection.isAuthPending()) {
                Result.AuthPending
            } else {
                Result.AdbUnavailable
            }

        var installSucceeded = false
        return try {
            // Match the reference flow: stage the already SHA-256-verified APK
            // under /data/local/tmp, then let the shell perform the install.
            dadb.push(apk, REMOTE_APK)

            val marker = "${BuildConfig.VERSION_NAME} (${expectedVersionCode})"
            writeScript(dadb, buildInstallerScript(marker))

            val run = dadb.shell("sh $REMOTE_SCRIPT")
            if (run.exitCode != 0) {
                return Result.Failed("Vehicle OTA shell install failed: ${run.allOutput}")
            }

            var installedVersion: Int? = null
            repeat(8) {
                val response = runCatching {
                    dadb.shell(
                        "dumpsys package ${BuildConfig.APPLICATION_ID} | grep -m 1 -E 'versionCode'"
                    )
                }.getOrNull()
                if (response != null && response.exitCode == 0) {
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

            installSucceeded = true
            Result.Installed(installedVersion)
        } catch (e: Exception) {
            val text = e.message.orEmpty()
            if (text.contains("auth", ignoreCase = true) ||
                text.contains("unauthorized", ignoreCase = true) ||
                text.contains("authentication", ignoreCase = true) ||
                text.contains("public key", ignoreCase = true) ||
                text.contains("permission denied", ignoreCase = true)
            ) {
                Result.AuthPending
            } else {
                Result.Failed(text.ifBlank { e.javaClass.simpleName })
            }
        } finally {
            // Do not tear down the process-wide ADB transport after success.
            // The shell script has already requested the app restart and the
            // process may be in the replacement/relaunch window.
            if (!installSucceeded) {
                runCatching {
                    dadb.shell("rm -f $REMOTE_APK $REMOTE_SCRIPT 2>/dev/null")
                }
            }
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
            pm install -r -d $REMOTE_APK
            rc=${'$'}?
            if [ "${'$'}rc" -eq 0 ]; then
              rm -f $IN_PROGRESS 2>/dev/null
              am force-stop ${BuildConfig.APPLICATION_ID} 2>/dev/null
              am start -n ${BuildConfig.APPLICATION_ID}/.MainActivity --ez post_update true >/dev/null 2>&1
            fi
            exit ${'$'}rc
        """.trimIndent()
    }

    private fun writeScript(dadb: Dadb, script: String) {
        val nonce = "${System.nanoTime()}"
        val eof = "__DRIVE_APEX_EOF_${nonce}__"
        val writeCommand = "cat > $REMOTE_SCRIPT <<'$eof'\n$script\n$eof"
        val write = dadb.shell(writeCommand)
        if (write.exitCode != 0) {
            throw IllegalStateException("Vehicle OTA script staging failed: ${write.allOutput}")
        }
        dadb.shell("chmod 700 $REMOTE_SCRIPT 2>/dev/null")
    }
}
