package fr.toolmap.record

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
import fr.toolmap.core.record.RecorderProfile
import fr.toolmap.core.record.RecorderState
import fr.toolmap.core.track.TrackFormat
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.service.BackgroundNotification
import fr.toolmap.service.ServiceWakeLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process alive and foreground while a track is being recorded (service type `location`), holds a partial
 * wake lock so that the CPU keeps handling fixes with the screen off, and has its line and Stop button in ToolMap's
 * notification ([BackgroundNotification]).
 * The recording itself is [RecordingController]'s.
 */
@AndroidEntryPoint
class RecordingService : Service() {
    @Inject
    lateinit var controller: RecordingController

    @Inject
    lateinit var settings: SettingsStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private val wakeLock by lazy { ServiceWakeLock(this, "ToolMap:recording") }
    private var lastNotified = 0L
    private var lastPhase: RecorderState? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /** The `location` foreground-service type exists from API 29; before that the type argument is ignored. */
    private fun locationServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        try {
            ServiceCompat.startForeground(
                this,
                BackgroundNotification.NOTIFICATION_ID,
                BackgroundNotification.put(this, BackgroundNotification.Part.RECORDING, line()),
                locationServiceType(),
            )
        } catch (e: SecurityException) {
            // No location permission (revoked, or never granted): nothing can be recorded.
            stopSelf()
            return START_NOT_STICKY
        }
        observe()
        scope.launch {
            when (intent?.action) {
                ACTION_START -> {
                    val name = intent.getStringExtra(EXTRA_NAME)
                    val profile = RecorderProfile.fromName(intent.getStringExtra(EXTRA_PROFILE))
                    runCatching { controller.start(name, profile) }
                }

                ACTION_PAUSE -> {
                    controller.pause()
                }

                ACTION_RESUME -> {
                    controller.resume()
                }

                ACTION_STOP -> {
                    controller.stop()
                }

                // The user chose to carry on with a recording found after a crash.
                ACTION_RESTORE -> {
                    controller.checkForRecovery()
                    if (controller.state.value.recoverable != null) controller.resumeRecovered() else stopSelf()
                }

                // A restart after the system killed the process: carry on if it was recording at the time.
                else -> {
                    controller.checkForRecovery()
                    val session = controller.state.value.recoverable
                    if (session != null && !session.paused) controller.resumeRecovered() else stopSelf()
                }
            }
            // Nothing is recording (a start that failed, a stop or a pause with nothing running): do not linger.
            if (controller.state.value.phase == RecorderState.IDLE) stopSelf()
        }
        return START_STICKY
    }

    private fun observe() {
        if (observer != null) return
        observer =
            scope.launch {
                var seenActive = false
                controller.state.collect { ui ->
                    updateWakeLock(ui.phase == RecorderState.RECORDING)
                    if (ui.phase != RecorderState.IDLE) seenActive = true
                    if (ui.phase == RecorderState.IDLE && seenActive) {
                        stopSelf()
                        return@collect
                    }
                    val now = System.currentTimeMillis()
                    if (ui.phase != lastPhase || now - lastNotified >= NOTIFY_EVERY_MILLIS) {
                        lastPhase = ui.phase
                        lastNotified = now
                        BackgroundNotification.update(
                            this@RecordingService,
                            BackgroundNotification.Part.RECORDING,
                            line(),
                        )
                    }
                }
            }
    }

    private fun updateWakeLock(needed: Boolean) {
        if (needed) wakeLock.hold() else wakeLock.release()
    }

    /** The recording's line in ToolMap's notification (P21.7): no Pause there, the app has it. */
    private fun line(): BackgroundNotification.Line {
        val ui = controller.state.value
        val paused = ui.phase == RecorderState.PAUSED
        return BackgroundNotification.Line(
            getString(
                if (paused) R.string.background_recording_paused else R.string.background_recording,
                TrackFormat.distance(ui.live.distanceMeters, units = settings.settings.value.units),
                TrackFormat.duration(ui.live.elapsedMillis),
            ),
            getString(R.string.background_stop_recording),
            action(ACTION_STOP),
        )
    }

    private fun action(name: String): PendingIntent =
        PendingIntent.getService(
            this,
            name.hashCode(),
            Intent(this, RecordingService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    override fun onDestroy() {
        updateWakeLock(false)
        scope.cancel()
        BackgroundNotification.remove(this, BackgroundNotification.Part.RECORDING)
        super.onDestroy()
    }

    companion object {
        private const val NOTIFY_EVERY_MILLIS = 5_000L

        /** A safety net: the lock is released by itself after this long even if the service is leaked. */

        const val ACTION_START = "fr.toolmap.record.START"
        const val ACTION_PAUSE = "fr.toolmap.record.PAUSE"
        const val ACTION_RESUME = "fr.toolmap.record.RESUME"
        const val ACTION_STOP = "fr.toolmap.record.STOP"
        const val ACTION_RESTORE = "fr.toolmap.record.RESTORE"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_PROFILE = "profile"

        /** Starts the service and, through it, a recording; the caller must hold the location permission. */
        fun start(
            context: Context,
            name: String?,
            profile: RecorderProfile,
        ) {
            val intent =
                Intent(context, RecordingService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_PROFILE, profile.name)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Starts the service to carry on with a recording found after a crash (it resumes whatever it finds). */
        fun restoreRecovered(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).setAction(ACTION_RESTORE),
            )
        }

        /** Sends [action] (pause, resume or stop) to the service that is recording. */
        fun send(
            context: Context,
            action: String,
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).setAction(action),
            )
        }
    }
}
