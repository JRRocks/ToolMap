package fr.toolmap.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import fr.toolmap.R
import fr.toolmap.core.download.ZoneProgress

/** Notifications of the zone downloads: one low-importance channel, one ongoing notification per running job. */
object DownloadNotifications {
    private const val CHANNEL_ID = "zone-downloads"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        manager.createNotificationChannel(channel)
    }

    fun progress(
        context: Context,
        name: String,
        progress: ZoneProgress,
        pause: PendingIntent,
    ): Notification {
        val percent = (progress.fraction * PERCENT).toInt()
        return NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(name)
            .setContentText(
                context.getString(R.string.download_notification_progress, progress.processed, progress.total),
            )
            .setProgress(PERCENT, percent, progress.total == 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, context.getString(R.string.download_pause), pause)
            .build()
    }

    fun finished(
        context: Context,
        name: String,
        text: String,
    ): Notification =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(name)
            .setContentText(text)
            .setAutoCancel(true)
            .build()

    private const val PERCENT = 100
}
