package com.driveapex

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.driveapex.audio.DriveSoundPipeline
import com.driveapex.diag.DriveApexLog

/**
 * Keeps the engine running when the drive screen is not in front.
 *
 * The head unit backgrounds this app constantly, and until now that stopped the
 * sound: the engine belonged to the Activity, so a navigation prompt or the OEM
 * launcher silenced the car. A foreground service is the only thing Android
 * treats as "this app is doing something the user asked for" -- it is what keeps
 * the process off the kill list and keeps its audio alive with the screen
 * elsewhere.
 *
 * The service owns nothing itself. [DriveSoundPipeline] holds the engine and the
 * feed loop; this exists to give them a reason to stay running and a
 * notification the driver can stop them from.
 */
class DriveSoundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            DriveApexLog.i("service", "stop requested from the notification")
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        DriveSoundPipeline.startSound(this)
        DriveApexLog.i("service", "drive sound service running in the foreground")
        // START_STICKY: if the head unit reclaims the process under memory
        // pressure, the sound is meant to come back rather than stay gone.
        return START_STICKY
    }

    override fun onDestroy() {
        DriveApexLog.i("service", "drive sound service stopping")
        DriveSoundPipeline.stopSound()
        super.onDestroy()
    }

    /**
     * The notification, and the type the platform requires with it.
     *
     * From API 34 a foreground service must declare what it is for, and playing
     * audio is mediaPlayback. Declaring it here and in the manifest is what
     * keeps startForeground from throwing on a modern head unit.
     */
    private fun goForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Drive sound", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while DriveApex is making the engine sound."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DriveSoundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle("DriveApex")
            .setContentText("Engine sound is running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(open)
            // The old int-icon overload on purpose: the Icon-based builder takes
            // a nullable icon that some OEM shades draw as a blank square, and a
            // platform drawable is the one thing every head unit has.
            .also { @Suppress("DEPRECATION") it.addAction(android.R.drawable.ic_media_pause, "STOP", stop) }
            .build()

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            // A refusal here means the sound would die with the screen, which is
            // worth a log line rather than a silent difference in behaviour.
            DriveApexLog.e("service", "startForeground refused; sound will not survive backgrounding", it)
        }
    }

    companion object {
        private const val CHANNEL_ID = "driveapex_drive_sound"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_STOP = "com.driveapex.action.STOP_SOUND"

        /** Starts the sound in a way that survives the screen going away. */
        fun start(context: Context) {
            val intent = Intent(context, DriveSoundService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { DriveApexLog.e("service", "could not start the drive sound service", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DriveSoundService::class.java)) }
        }
    }
}
