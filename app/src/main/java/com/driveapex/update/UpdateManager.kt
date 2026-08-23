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
import kotlin.concurrent.thread

/** In-app updater backed by the public DriveApex GitHub latest release. */
class UpdateManager(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private val apiUrl = "https://api.github.com/repos/mohannad087-spec/Drive-apex/releases/latest"
    private val apkName = "DriveApex.apk"

    fun checkSilently() = check(false)

    fun checkManually() = check(true)

    private fun check(manual: Boolean) {
        thread(name = "DriveApex-Updater") {
            runCatching {
                val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "DriveApex-Updater/${BuildConfig.VERSION_NAME}")
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    if (manual) handler.post { showInfo("Update check unavailable", "GitHub returned HTTP $code. The release channel may not be configured yet.") }
                    return@runCatching
                }

                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val body = json.optString("body")
                val remoteVersionCode = Regex("versionCode\\s*[:=]\\s*(\\d+)")
                    .find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val remoteVersionName = Regex("versionName\\s*[:=]\\s*([^\\n\\r]+)")
                    .find(body)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                val assetUrl = json.optJSONArray("assets")?.let { assets ->
                    (0 until assets.length()).asSequence()
                        .map { assets.getJSONObject(it) }
                        .firstOrNull { it.optString("name") == apkName }
                        ?.optString("browser_download_url")
                }

                if (remoteVersionCode == null || assetUrl.isNullOrBlank()) {
                    if (manual) handler.post {
                        showInfo("Update channel not ready", "No signed DriveApex.apk release is currently published on GitHub.")
                    }
                    return@runCatching
                }

                if (remoteVersionCode > BuildConfig.VERSION_CODE) {
                    handler.post { showUpdateDialog(remoteVersionCode, remoteVersionName, assetUrl) }
                } else if (manual) {
                    handler.post { showInfo("DriveApex is up to date", "Installed build: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") }
                }
            }.onFailure {
                if (manual) handler.post {
                    showInfo("Update check failed", it.message ?: "Unable to contact GitHub.")
                }
            }
        }
    }

    private fun showInfo(title: String, message: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showUpdateDialog(versionCode: Int, versionName: String, assetUrl: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val label = if (versionName.isBlank()) versionCode.toString() else "$versionName ($versionCode)"
        AlertDialog.Builder(activity)
            .setTitle("DriveApex update available")
            .setMessage("Version $label is ready. Download and install it now?")
            .setNegativeButton("Later", null)
            .setPositiveButton("Update") { _, _ -> downloadAndInstall(assetUrl) }
            .show()
    }

    private fun downloadAndInstall(assetUrl: String) {
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
                handler.post { install(apk) }
            }.onFailure {
                handler.post { showInfo("DriveApex update failed", it.message ?: "Unable to download the update.") }
            }
        }
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
