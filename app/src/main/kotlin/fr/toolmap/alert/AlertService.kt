package fr.toolmap.alert

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import fr.toolmap.R
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.service.BackgroundNotification
import fr.toolmap.service.ServiceWakeLock
import fr.toolmap.ui.Formats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process alive and foreground (service type `location`, like the recorder and the sharing) with a partial
 * wake lock while something is watched, so that alerts are raised with the screen off. The watching itself is
 * [AlertController]'s; the service stops when nothing is watched any more.
 */
@AndroidEntryPoint
class AlertService : Service() {
    @Inject
    lateinit var controller: AlertController

    @Inject
    lateinit var settings: SettingsStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private val wakeLock by lazy { ServiceWakeLock(this, "ToolMap:alerts") }
    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    private fun locationServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            controller.stopAll()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                BackgroundNotification.NOTIFICATION_ID,
                BackgroundNotification.put(this, BackgroundNotification.Part.ALERTS, line()),
                locationServiceType(),
            )
        } catch (e: SecurityException) {
            // No location permission (revoked, or never granted): no position, so no alert.
            stopSelf()
            return START_NOT_STICKY
        }
        wakeLock.hold()
        observe()
        return START_NOT_STICKY
    }

    private fun observe() {
        if (observer != null) return
        observer =
            scope.launch {
                controller.ui.collect { ui ->
                    if (!ui.active) {
                        stopSelf()
                        return@collect
                    }
                    wakeLock.hold()
                    val text = textFor(ui)
                    if (text != lastText) {
                        lastText = text
                        BackgroundNotification.update(this@AlertService, BackgroundNotification.Part.ALERTS, line())
                    }
                }
            }
    }

    private fun textFor(ui: AlertUi): String {
        val follow = ui.follow
        val formats = Formats(settings.settings.value)
        val status = follow?.status
        return when {
            follow != null && status != null -> {
                getString(
                    R.string.background_following,
                    follow.name ?: getString(R.string.tracks_unnamed),
                    formats.distance(status.remainingMeters),
                )
            }

            follow != null -> {
                getString(R.string.follow_title, follow.name ?: getString(R.string.tracks_unnamed))
            }

            else -> {
                getString(
                    R.string.background_alerts,
                    resources.getQuantityString(R.plurals.alert_service_watching, ui.watches.size, ui.watches.size),
                )
            }
        }
    }

    /** The alerts' line in ToolMap's notification (P21.7); its button stops the track following and the alerts. */
    private fun line(): BackgroundNotification.Line {
        val ui = controller.ui.value
        val stop =
            PendingIntent.getService(
                this,
                ACTION_STOP.hashCode(),
                Intent(this, AlertService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return BackgroundNotification.Line(
            lastText.ifEmpty { textFor(ui) },
            getString(if (ui.follow != null) R.string.background_stop_following else R.string.background_stop_alerts),
            stop,
        )
    }

    override fun onDestroy() {
        wakeLock.release()
        scope.cancel()
        BackgroundNotification.remove(this, BackgroundNotification.Part.ALERTS)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "fr.toolmap.alert.STOP"

        /** The caller must hold the location permission. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AlertService::class.java))
        }
    }
}
