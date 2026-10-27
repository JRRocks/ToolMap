package fr.toolmap.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fr.toolmap.MainActivity
import fr.toolmap.R
import fr.toolmap.core.alert.AlertEvent
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.ui.Formats

/**
 * Notification, vibration and sound of an alert, each as the settings say. The channel itself is silent and does not
 * vibrate: the sound and the vibration are played here, so that switching them off in ToolMap's settings really does.
 */
class AndroidAlertNotifier(
    private val context: Context,
    private val settings: SettingsStore,
) : AlertNotifier {
    override fun raise(event: AlertEvent) {
        val alerts = settings.settings.value.alerts
        val text = AlertTexts.of(context.resources, Formats(settings.settings.value), event)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)
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
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle(text.title)
                .setContentText(text.body)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (event == AlertEvent.BackOnTrack) builder.setTimeoutAfter(BACK_ON_TRACK_TIMEOUT_MILLIS)
        manager.notify(notificationId(event), builder.build())
        if (alerts.vibrate) vibrate(patternFor(event))
        if (alerts.sound && event != AlertEvent.BackOnTrack) sound()
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.alert_channel),
                NotificationManager.IMPORTANCE_HIGH,
            )
                .apply {
                    setSound(null, null)
                    enableVibration(false)
                },
        )
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.getSystemService(context, VibratorManager::class.java)?.defaultVibrator
            } else {
                ContextCompat.getSystemService(context, Vibrator::class.java)
            }
        if (vibrator?.hasVibrator() == true) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, NO_REPEAT))
        }
    }

    private fun sound() {
        runCatching {
            RingtoneManager.getRingtone(
                context,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            )?.play()
        }
    }

    private fun patternFor(event: AlertEvent): LongArray =
        when (event) {
            is AlertEvent.Proximity -> longArrayOf(0, 300, 150, 300)
            is AlertEvent.OffTrack -> longArrayOf(0, 600, 200, 600, 200, 600)
            AlertEvent.BackOnTrack -> longArrayOf(0, 150)
            AlertEvent.Arrived -> longArrayOf(0, 200, 100, 200, 100, 600)
        }

    private fun notificationId(event: AlertEvent): Int =
        when (event) {
            is AlertEvent.Proximity -> {
                PROXIMITY_BASE +
                    (event.place.id xor (event.place.id ushr 32)).toInt().and(ID_MASK)
            }

            // The same notification, so that "back on the line" replaces the warning.
            is AlertEvent.OffTrack, AlertEvent.BackOnTrack -> {
                OFF_TRACK_ID
            }

            AlertEvent.Arrived -> {
                ARRIVED_ID
            }
        }

    private companion object {
        const val CHANNEL_ID = "alerts"
        const val OFF_TRACK_ID = 4300
        const val ARRIVED_ID = 4301
        const val PROXIMITY_BASE = 4400
        const val ID_MASK = 0xFF
        const val NO_REPEAT = -1
        const val BACK_ON_TRACK_TIMEOUT_MILLIS = 15_000L
    }
}

/**
 * Starts and stops [AlertService]. A refusal (the app is not in the foreground) is not fatal: the alerts then only
 * work while the app is on screen.
 */
class AndroidAlertServiceControl(
    private val context: Context,
) : AlertServiceControl {
    override fun start() {
        runCatching { AlertService.start(context) }
    }

    override fun stop() {
        runCatching { context.stopService(Intent(context, AlertService::class.java)) }
    }
}
