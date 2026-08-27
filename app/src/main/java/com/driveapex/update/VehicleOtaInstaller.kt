package com.driveapex.update

import android.content.Context
import com.driveapex.BuildConfig
import dadb.Dadb
import java.io.File

/** BYD/DiLink vehicle OTA installer using the already-authorized local ADB transport. */
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

        val dadb = VehicleAdbConnection(context).connect()
            ?: return if (VehicleAdbConnection.isAuthPending()) Result.AuthPending else Result.AdbUnavailable

        var installSucceeded = false
        return try {
            dadb.push(apk, REMOTE_APK)
            val marker = "${BuildConfig.VERSION_NAME} (${expectedVersionCode})"
            writeScript(dadb, buildInstallerScript(marker))

            val run = dadb.shell("sh $REMOTE_SCRIPT")
            val output = run.allOutput.trim()
            if (run.exitCode != 0) {
                return Result.Failed("Vehicle package install failed (exit ${run.exitCode}): $output")
            }

            // Do not force-stop/relaunch the app from the installing shell.
            // That can terminate the caller while PackageManager is completing
            // its replacement transaction and can produce a rollback failure.
            var installedVersion: Int? = null
            repeat(10) {
                val response = runCatching {
                    dadb.shell("dumpsys package ${BuildConfig.APPLICATION_ID} | grep -m 1 -E 'versionCode'")
                }.getOrNull()
                if (response != null && response.exitCode == 0) {
                    installedVersion = Regex("versionCode=(\\d+)")
                        .find(response.allOutput)
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
                if (installedVersion != null) return@repeat
                Thread.sleep(500L)
            }

            if (installedVersion == null) {
                return Result.Failed("Vehicle package install returned success but installed version could not be verified")
            }
            if (installedVersion < expectedVersionCode) {
                return Result.Failed("Vehicle package install completed with version $installedVersion; expected $expectedVersionCode")
            }

            installSucceeded = true
            Result.Installed(installedVersion)
        } catch (e: Exception) {
            val text = e.message.orEmpty()
            if (text.contains("auth", true) || text.contains("unauthorized", true) ||
                text.contains("authentication", true) || text.contains("public key", true) ||
                text.contains("permission denied", true)) {
                Result.AuthPending
            } else {
                Result.Failed(text.ifBlank { e.javaClass.simpleName })
            }
        } finally {
            if (!installSucceeded) {
                runCatching { dadb.shell("rm -f $REMOTE_APK $REMOTE_SCRIPT 2>/dev/null") }
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
            pm install -r -d --user 0 $REMOTE_APK
            rc=${'$'}?
            if [ "${'$'}rc" -eq 0 ]; then
              rm -f $IN_PROGRESS 2>/dev/null
            fi
            exit ${'$'}rc
        """.trimIndent()
    }

    private fun writeScript(dadb: Dadb, script: String) {
        val nonce = System.nanoTime().toString()
        val eof = "__DRIVE_APEX_EOF_${nonce}__"
        val write = dadb.shell("cat > $REMOTE_SCRIPT <<'$eof'\n$script\n$eof")
        if (write.exitCode != 0) {
            throw IllegalStateException("Vehicle OTA script staging failed: ${write.allOutput}")
        }
        dadb.shell("chmod 700 $REMOTE_SCRIPT 2>/dev/null")
    }
}
