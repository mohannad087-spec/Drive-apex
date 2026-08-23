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

/** In-app updater backed by the public DriveApex GitHub `latest` release. */
class UpdateManager(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private val apiUrl = "https://api.github.com/repos/mohannad087-spec/Drive-apex/releases/tags/latest"
    private val apkName = "DriveApex.apk"

    fun checkSilently() {
        thread(name = "DriveApex-Updater") {
            runCatching {
                val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "DriveApex-Updater")
                }
                if (connection.responseCode !in 200..299) return@runCatching
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val body = json.optString("body")
                val remoteVersionCode = Regex("versionCode\\s*[:=]\\s*(\\d+)")
                    .find(body)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@runCatching
                val assetUrl = json.optJSONArray("assets")?.let { assets ->
                    (0 until assets.length()).asSequence()
                        .map { assets.getJSONObject(it) }
                        .firstOrNull { it.optString("name") == apkName }
                        ?.optString("browser_download_url")
                } ?: return@runCatching
                if (remoteVersionCode <= BuildConfig.VERSION_CODE || assetUrl.isNullOrBlank()) return@runCatching
                handler.post { showUpdateDialog(remoteVersionCode, assetUrl) }
            }
        }
    }

    private fun showUpdateDialog(versionCode: Int, assetUrl: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle("DriveApex update available")
            .setMessage("A newer DriveApex build ($versionCode) is ready. Download and install it now?")
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
                    setRequestProperty("User-Agent", "DriveApex-Updater")
                    setRequestProperty("Accept", "application/octet-stream")
                }
                if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                val apk = File(activity.cacheDir, apkName)
                connection.inputStream.use { input -> apk.outputStream().use { output -> input.copyTo(output) } }
                handler.post { install(apk) }
            }.onFailure {
                handler.post {
                    AlertDialog.Builder(activity)
                        .setTitle("DriveApex update failed")
                        .setMessage(it.message ?: "Unable to download the update.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun install(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("Allow DriveApex updates")
                .setMessage("Android requires permission for DriveApex to install updates downloaded from GitHub. Enable it once, then return to DriveApex.")
                .setNegativeButton("Later", null)
                .setPositiveButton("Open Settings") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                    activity.startActivity(intent)
                }
            return
        }

        val uri: Uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}
