package fr.toolmap.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import fr.toolmap.MainActivity
import fr.toolmap.R
import java.util.EnumMap

/**
 * ToolMap's single notification while it works in the background (P21.7, variant B of
 * `docs/previews/p21-notification.html`): « ToolMap est actif en arrière-plan », then a line and a stop button for
 * each foreground service at work. The services all put it up under [NOTIFICATION_ID]: Android keeps it while one of
 * them is still in the foreground and takes it back with the last one. Its channel is of default importance, so that
 * the ToolMap icon shows in the status bar (Android hides the icon of a "silent" notification), with no sound and no
 * vibration.
 */
internal object BackgroundNotification {
    /** The services that share the notification, in the order of their lines and buttons (three at most). */
    enum class Part { SHARING, RECORDING, ALERTS }

    /** What one service shows: its line, and its stop button. */
    class Line(
        val text: String,
        val stopLabel: String,
        val stop: PendingIntent,
    )

    const val NOTIFICATION_ID = 4100
    const val CHANNEL_ID = "background"

    /** The channels of the three notifications it replaces (up to 0.4.62). */
    private val OLD_CHANNELS = listOf("sharing", "recording", "alerts-service")
    private const val COLOR = 0xFF2E6B3A.toInt()

    private val lines = EnumMap<Part, Line>(Part::class.java)

    /** Puts [part]'s line and returns the notification to give to `startForeground`. */
    fun put(
        context: Context,
        part: Part,
        line: Line,
    ): Notification =
        synchronized(lines) {
            lines[part] = line
            build(context)
        }

    /** Puts [part]'s line and shows the notification again. */
    fun update(
        context: Context,
        part: Part,
        line: Line,
    ) {
        manager(context).notify(NOTIFICATION_ID, put(context, part, line))
    }

    /**
     * Takes [part]'s line out as its service stops. The notification goes with the last service (Android takes it
     * back); while others run, it is shown again without the line.
     */
    fun remove(
        context: Context,
        part: Part,
    ) {
        val rest =
            synchronized(lines) {
                if (lines.remove(part) == null || lines.isEmpty()) return
                build(context)
            }
        manager(context).notify(NOTIFICATION_ID, rest)
    }

    /** Called with [lines] held. */
    private fun build(context: Context): Notification {
        ensureChannel(context)
        val shown = lines.values.toList()
        val text = shown.joinToString("\n") { it.text }
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_toolmap)
                .setColor(COLOR)
                .setContentTitle(context.getString(R.string.background_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                // Shown at once: Android 12+ may otherwise hold a service's notification back for 10 s.
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        for (line in shown) builder.addAction(0, line.stopLabel, line.stop)
        return builder.build()
    }

    /** Whether the notification can be seen: ToolMap may post, and its channel is not turned off. */
    fun visible(context: Context): Boolean {
        val manager = manager(context)
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun ensureChannel(context: Context) {
        val manager = manager(context)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.background_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        // Android refuses to delete a channel still used by a service's notification: none is, after an update.
        for (old in OLD_CHANNELS) runCatching { manager.deleteNotificationChannel(old) }
    }

    private fun manager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
