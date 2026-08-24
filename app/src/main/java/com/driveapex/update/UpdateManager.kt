package com.driveapex.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import com.driveapex.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Production updater for the stable DriveApex GitHub Release channel.
 *
 * On a normal phone it uses Android's package installer.
 * On a BYD/DiLink head unit it uses the verified local ADB transport path.
 */
class UpdateManager(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private val manifestUrl = "https://github.com/mohannad087-spec/Drive-apex/releases/latest/download/DriveApex-update.json"
    private val apkName = "DriveApex-update.apk"
    private val prefs = activity.getSharedPreferences("driveapex_updater", Activity.MODE_PRIVATE)
    private val permissionInstallPendingKey = "permission_install_pending"
    private val vehicleInstallTargetKey = "vehicle_install_target_version"

    fun checkSilently() = check(false)

    fun checkManually() = check(true)

    fun onResume() {
        val pendingVehicleTarget = prefs.getInt(vehicleInstallTargetKey, -1)
        if (pendingVehicleTarget > 0 && BuildConfig.VERSION_CODE >= pendingVehicleTarget) {
            prefs.edit().remove(vehicleInstallTargetKey).apply()
        }

        if (!prefs.getBoolean(permissionInstallPendingKey, false)) return

        val pending = pendingApk()
        if (!pending.isFile || pending.length() <= 100_000L) {
            prefs.edit().remove(permissionInstallPendingKey).apply()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()) {
            prefs.edit().remove(permissionInstallPendingKey).apply()
            handler.post { installApk(pending) }
        }
    }

    private fun check(manual: Boolean) {
        thread(name = "DriveApex-Updater") {
            runCatching {
                val manifest = downloadManifest()
                val remoteVersionCode = manifest.optInt("versionCode", -1)
                val remoteVersionName = manifest.optString("versionName", "")
                val assetName = manifest.optString("assetName", "")
                val tag = manifest.optString("tag", "")
                val apkUrl = manifest.optString("apkUrl", "")
                val expectedSha256 = manifest.optString("sha256", "").lowercase()

                if (remoteVersionCode < 1) error("Update manifest has invalid versionCode")
                if (assetName != "DriveApex.apk") error("Update manifest references an unexpected APK")
                if (!tag.matches(Regex("^v\\d+\\.\\d+\\.\\d+$"))) error("Update manifest has invalid release tag")
                if (!apkUrl.startsWith("https://github.com/mohannad087-spec/Drive-apex/releases/download/$tag/") || !apkUrl.endsWith("/DriveApex.apk")) {
                    error("Update manifest has invalid APK URL")
                }
                if (expectedSha256.length != 64 || !expectedSha256.all { it in "0123456789abcdef" }) {
                    error("Update manifest has invalid SHA-256")
                }

                if (remoteVersionCode > BuildConfig.VERSION_CODE) {
                    handler.post {
                        showUpdateDialog(remoteVersionCode, remoteVersionName, apkUrl, expectedSha256)
                    }
                } else if (manual) {
                    handler.post {
                        showInfo(
                            "DriveApex is up to date",
                            "Installed: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nLatest: ${remoteVersionName.ifBlank { remoteVersionCode.toString() }} ($remoteVersionCode)"
                        )
                    }
                }
            }.onFailure {
                if (manual) {
                    handler.post {
                        showInfo("Update check failed", readableError(it))
                    }
                }
            }
        }
    }

    private fun downloadManifest(): JSONObject {
        val connection = openConnection(manifestUrl, 15000)
        if (connection.responseCode !in 200..299) {
            error("Update manifest unavailable (HTTP ${connection.responseCode})")
        }
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(text)
    }

    private fun showInfo(title: String, message: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showUpdateDialog(versionCode: Int, versionName: String, apkUrl: String, expectedSha256: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val label = if (versionName.isBlank()) versionCode.toString() else "$versionName ($versionCode)"
        AlertDialog.Builder(activity)
            .setTitle("DriveApex update available")
            .setMessage("Version $label is ready. Download and install it now?")
            .setNegativeButton("Later", null)
            .setPositiveButton("Update") { _, _ -> downloadAndInstall(apkUrl, expectedSha256, versionCode) }
            .show()
    }

    private fun downloadAndInstall(apkUrl: String, expectedSha256: String, expectedVersionCode: Int) {
        thread(name = "DriveApex-APK-Download") {
            runCatching {
                val target = pendingApk()
                val partial = File(activity.cacheDir, "$apkName.part")
                partial.delete()

                val connection = openConnection(apkUrl, 30000)
                if (connection.responseCode !in 200..299) {
                    error("APK download failed (HTTP ${connection.responseCode})")
                }

                connection.inputStream.use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }

                if (partial.length() < 100_000L) {
                    partial.delete()
                    error("Downloaded APK is unexpectedly small")
                }

                val actualSha256 = sha256(partial)
                if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    partial.delete()
                    error("APK integrity check failed")
                }

                target.delete()
                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }

                handler.post { installDownloadedApk(target, expectedVersionCode) }
            }.onFailure {
                handler.post { showInfo("DriveApex update failed", readableError(it)) }
            }
        }
    }

    private fun installDownloadedApk(apk: File, expectedVersionCode: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!apk.isFile || apk.length() <= 100_000L) {
            showInfo("DriveApex update failed", "The verified APK disappeared before installation.")
            return
        }

        if (VehicleOtaInstaller.isVehicleRuntime()) {
            prefs.edit().putInt(vehicleInstallTargetKey, expectedVersionCode).apply()
            thread(name = "DriveApex-Vehicle-OTA") {
                val result = VehicleOtaInstaller(activity).install(apk, expectedVersionCode)
                handler.post { handleVehicleInstallResult(result, apk, expectedVersionCode) }
            }
            return
        }

        installOrRequestPermission(apk)
    }

    private fun handleVehicleInstallResult(
        result: VehicleOtaInstaller.Result,
        apk: File,
        expectedVersionCode: Int
    ) {
        if (result is VehicleOtaInstaller.Result.Installed) return

        prefs.edit().remove(vehicleInstallTargetKey).apply()
        val message = when (result) {
            VehicleOtaInstaller.Result.NotVehicle ->
                "DriveApex detected no BYD vehicle runtime. No Android installer was opened."
            VehicleOtaInstaller.Result.AdbUnavailable ->
                "BYD local ADB transport is not reachable or is not authorized. No Android installer was opened."
            VehicleOtaInstaller.Result.AuthPending ->
                "BYD ADB authorization is still pending. Retry after the head unit accepts the key."
            is VehicleOtaInstaller.Result.Failed -> result.message
            is VehicleOtaInstaller.Result.Installed -> ""
        }

        AlertDialog.Builder(activity)
            .setTitle("Vehicle OTA failed")
            .setMessage(message)
            .setNegativeButton("OK", null)
            .setPositiveButton("Retry") { _, _ ->
                prefs.edit().putInt(vehicleInstallTargetKey, expectedVersionCode).apply()
                thread(name = "DriveApex-Vehicle-OTA-Retry") {
                    val retry = VehicleOtaInstaller(activity).install(apk, expectedVersionCode)
                    handler.post { handleVehicleInstallResult(retry, apk, expectedVersionCode) }
                }
            }
            .show()
    }

    private fun installOrRequestPermission(apk: File) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            prefs.edit().putBoolean(permissionInstallPendingKey, true).apply()
            AlertDialog.Builder(activity)
                .setTitle("Allow DriveApex updates")
                .setMessage("Android requires permission for DriveApex to install updates downloaded from GitHub. Enable it once, then return to DriveApex.")
                .setNegativeButton("Later", null)
                .setPositiveButton("Open Settings") { _, _ ->
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        )
                    )
                }
                .show()
            return
        }

        prefs.edit().remove(permissionInstallPendingKey).apply()
        installApk(apk)
    }

    private fun installApk(apk: File) {
        if (!apk.isFile || apk.length() <= 100_000L) return
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    private fun pendingApk(): File = File(activity.cacheDir, apkName)

    private fun openConnection(url: String, timeoutMs: Int): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", "DriveApex-Updater/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/json, application/octet-stream")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readableError(error: Throwable): String {
        return error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
    }
}
