package fr.toolmap.navigate

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Keeps « Aller vers un point » going with the screen off (P30.3, ADR-093): foreground (service type `location`, like
 * the alerts) with a partial wake lock, and its line in ToolMap's notification, « Vers Refuge du Pré · 3,1 km ·
 * arrivée 15:20 » with « Arrêter ». The navigation itself is [GoToController]'s; the service stops with it.
 */
@AndroidEntryPoint
class GoToService : Service() {
    @Inject
    lateinit var controller: GoToController

    @Inject
    lateinit var settings: SettingsStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private val wakeLock by lazy { ServiceWakeLock(this, "ToolMap:goto") }
    private var lastText = ""
    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    override fun onBind(intent: Intent?): IBinder? = null

    private fun locationServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            controller.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                BackgroundNotification.NOTIFICATION_ID,
                BackgroundNotification.put(this, BackgroundNotification.Part.GOTO, line()),
                locationServiceType(),
            )
        } catch (e: SecurityException) {
            // No location permission: the navigation stays on screen only.
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
                    if (ui.target == null || ui.previewing) {
                        stopSelf()
                        return@collect
                    }
                    wakeLock.hold()
                    val text = textFor(ui)
                    if (text != lastText) {
                        lastText = text
                        BackgroundNotification.update(this@GoToService, BackgroundNotification.Part.GOTO, line())
                    }
                }
            }
    }

    private fun textFor(ui: GoToUi): String {
        val name = ui.target?.name ?: getString(R.string.target_unnamed)
        val formats = Formats(settings.settings.value)
        val progress = ui.progress
        val left = progress?.remainingMeters ?: ui.guidance?.distanceMeters
        val eta = progress?.remainingMillis?.let { clock.format(Instant.ofEpochMilli(ui.nowMillis + it).atZone(ZONE)) }
        return when {
            ui.arrived -> getString(R.string.goto_background_arrived, name)
            left == null -> getString(R.string.goto_background_waiting, name)
            eta != null -> getString(R.string.goto_background_eta, name, formats.distance(left), eta)
            else -> getString(R.string.goto_background, name, formats.distance(left))
        }
    }

    /** The navigation's line in ToolMap's notification; its button stops it. */
    private fun line(): BackgroundNotification.Line {
        val stop =
            PendingIntent.getService(
                this,
                ACTION_STOP.hashCode(),
                Intent(this, GoToService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return BackgroundNotification.Line(
            lastText.ifEmpty { textFor(controller.ui.value) },
            getString(R.string.goto_stop),
            stop,
        )
    }

    override fun onDestroy() {
        wakeLock.release()
        scope.cancel()
        BackgroundNotification.remove(this, BackgroundNotification.Part.GOTO)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "fr.toolmap.navigate.STOP"
        private val ZONE: ZoneId = ZoneId.systemDefault()
    }
}

/** The service and the vibration of [GoToController], on Android. */
class AndroidGoToPlatform(
    private val context: Context,
) : GoToPlatform {
    override fun startService() {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, GoToService::class.java)) }
    }

    override fun stopService() {
        runCatching { context.stopService(Intent(context, GoToService::class.java)) }
    }

    override fun vibrate() {
        val vibrator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.getSystemService(context, VibratorManager::class.java)?.defaultVibrator
            } else {
                ContextCompat.getSystemService(context, Vibrator::class.java)
            }
        if (vibrator?.hasVibrator() == true) {
            runCatching { vibrator.vibrate(VibrationEffect.createWaveform(OFF_ROUTE_PATTERN, NO_REPEAT)) }
        }
    }

    private companion object {
        /** Two short pulses: « vous quittez le chemin ». */
        val OFF_ROUTE_PATTERN = longArrayOf(0, 180, 120, 180)
        const val NO_REPEAT = -1
    }
}
