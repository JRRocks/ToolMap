package fr.toolmap.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import fr.toolmap.MainActivity
import fr.toolmap.R

/**
 * The notification of an update (P31, variant B): the progress of the download while its dialog is closed, then « prête
 * à installer », which opens the app, where Android's confirmation waits. One low-importance channel.
 */
object UpdateNotifications {
    private const val CHANNEL_ID = "app-update"
    private const val NOTIFICATION_ID = 0x7031
    private const val PERCENT = 100

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    fun progress(
        context: Context,
        version: String,
        done: Long,
        total: Long,
    ) {
        val percent = if (total > 0) (done * PERCENT / total).toInt() else 0
        notify(
            context,
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(R.string.update_progress_title, version))
                .setContentText(
                    context.getString(
                        R.string.update_progress_bytes,
                        Formatter.formatShortFileSize(context, done),
                        Formatter.formatShortFileSize(context, total),
                    ),
                ).setProgress(PERCENT, percent, total <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(context)),
        )
    }

    fun ready(
        context: Context,
        version: String,
    ) = notify(
        context,
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.update_ready_title, version))
            .setContentText(context.getString(R.string.update_ready_text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context)),
    )

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    private fun notify(
        context: Context,
        builder: NotificationCompat.Builder,
    ) {
        val allowed =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
