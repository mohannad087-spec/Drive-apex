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

/** In-app updater backed by the public DriveApex GitHub latest release. */
class UpdateManager(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private val apiUrl = "https://api.github.com/repos/mohannad087-spec/Drive-apex/releases/latest"
    private val apkName = "DriveApex.apk"
    private val manifestName = "DriveApex-update.json"

    fun checkSilently() = check(false)

    fun checkManually() = check(true)

    private fun check(manual: Boolean) {
        thread(name = "DriveApex-Updater") {
            runCatching {
                val release = getLatestRelease()
                val assets = release.optJSONArray("assets") ?: error("Latest release has no assets")

                val manifestAsset = findAsset(assets, manifestName)
                    ?: error("Latest release has no update manifest")
                val apkAsset = findAsset(assets, apkName)
                    ?: error("Latest release has no DriveApex.apk")

                val manifestText = downloadText(manifestAsset.getString("browser_download_url"))
                val manifest = JSONObject(manifestText)
                val remoteVersionCode = manifest.optInt("versionCode", -1)
                val remoteVersionName = manifest.optString("versionName", "")
                val expectedSha256 = manifest.optString("sha256", "").lowercase()

                if (remoteVersionCode < 1 || expectedSha256.length != 64 || !expectedSha256.all { it in "0123456789abcdef" }) {
                    error("Update manifest is invalid")
                }

                if (remoteVersionCode > BuildConfig.VERSION_CODE) {
                    val actualAssetName = manifest.optString("assetName", apkName)
                    if (actualAssetName != apkAsset.optString("name")) {
                        error("Update manifest asset does not match release APK")
                    }
                    handler.post {
                        showUpdateDialog(remoteVersionCode, remoteVersionName, apkAsset.getString("browser_download_url"), expectedSha256)
                    }
                } else if (manual) {
                    handler.post {
                        showInfo(
                            "DriveApex is up to date",
                            "Installed build: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nLatest: ${remoteVersionName.ifBlank { remoteVersionCode.toString() }} ($remoteVersionCode)"
                        )
                    }
                }
            }.onFailure {
                if (manual) {
                    handler.post {
                        showInfo("Update check failed", it.message ?: "Unable to contact the DriveApex update channel.")
                    }
                }
            }
        }
    }

    private fun getLatestRelease(): JSONObject {
        val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "DriveApex-Updater/${BuildConfig.VERSION_NAME}")
        }
        val code = connection.responseCode
        if (code !in 200..299) error("GitHub returned HTTP $code")
        return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    }

    private fun findAsset(assets: org.json.JSONArray, name: String): JSONObject? {
        return (0 until assets.length())
            .asSequence()
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == name }
    }

    private fun downloadText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DriveApex-Updater/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/octet-stream")
        }
        if (connection.responseCode !in 200..299) error("Manifest download failed: HTTP ${connection.responseCode}")
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun showInfo(title: String, message: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showUpdateDialog(versionCode: Int, versionName: String, assetUrl: String, expectedSha256: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val label = if (versionName.isBlank()) versionCode.toString() else "$versionName ($versionCode)"
        AlertDialog.Builder(activity)
            .setTitle("DriveApex update available")
            .setMessage("Version $label is ready. Download and install it now?")
            .setNegativeButton("Later", null)
            .setPositiveButton("Update") { _, _ -> downloadAndInstall(assetUrl, expectedSha256) }
            .show()
    }

    private fun downloadAndInstall(assetUrl: String, expectedSha256: String) {
        thread(name = "DriveApex-APK-Download") {
            runCatching {
                val connection = (URL(assetUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "DriveApex-Updater/${BuildConfig.VERSION_NAME}")
                    setRequestProperty("Accept", "application/octet-stream")
                }
                if (connection.responseCode !in 200..299) error("APK download failed: HTTP ${connection.responseCode}")
                val apk = File(activity.cacheDir, apkName)
                connection.inputStream.use { input -> apk.outputStream().use { output -> input.copyTo(output) } }
                if (apk.length() < 100_000L) error("Downloaded APK is unexpectedly small")

                val actualSha = sha256(apk)
                if (!actualSha.equals(expectedSha256, ignoreCase = true)) {
                    apk.delete()
                    error("APK integrity check failed")
                }
                handler.post { install(apk) }
            }.onFailure {
                handler.post { showInfo("DriveApex update failed", it.message ?: "Unable to download the update.") }
            }
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

    private fun install(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("Allow DriveApex updates")
                .setMessage("Android requires permission for DriveApex to install updates downloaded from GitHub. Enable it once, then return to DriveApex and press Check for Update again.")
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

        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}
