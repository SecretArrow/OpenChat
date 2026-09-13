package com.openchat.android.bg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.openchat.android.MainActivity
import com.openchat.android.R

/**
 * Keep-alive foreground service while terminal sessions / long-running
 * processes are attached (spec §14): a low-importance persistent notification
 * prevents Android from freezing the app process (which would kill the pty
 * children). Started by [RuntimeServiceController] when the first live session
 * appears and stopped when the last one dies.
 */
class RuntimeForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Open Chat runtime",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps Ubuntu terminal sessions and processes alive"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.notif_runtime_title))
            .setContentText(getString(R.string.notif_runtime_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "runtime"
        const val NOTIFICATION_ID = 1
    }
}

/**
 * Starts/stops [RuntimeForegroundService]; all failures are swallowed (the
 * service is best-effort — a refused start must never crash a session create).
 */
object RuntimeServiceController {

    /** Starts the keep-alive foreground service (§14). */
    fun start(context: Context) {
        runCatching {
            context.startForegroundService(Intent(context, RuntimeForegroundService::class.java))
        }
    }

    /** Stops the keep-alive service — only called when no live sessions remain. */
    fun stop(context: Context) {
        runCatching {
            context.stopService(Intent(context, RuntimeForegroundService::class.java))
        }
    }
}
