package fr.toolmap.operation

import android.app.ForegroundServiceStartNotAllowedException
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
import fr.toolmap.MainActivity
import fr.toolmap.R
import fr.toolmap.core.eventlog.EventLog
import fr.toolmap.core.problem.Problems
import fr.toolmap.service.BackgroundNotification
import fr.toolmap.service.ServiceWakeLock
import fr.toolmap.share.ShareService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * What the notification of an operation says while this phone's position goes to it (P39.14, L4 A, image 07):
 * « Position partagée · [operation] », and « Hors réseau : envoi dès son retour » under it while the network is
 * missing ([offline], L4 bis a, image 09).
 */
data class OperationNotice(
    val operation: String,
    val offline: Boolean,
)

/**
 * Keeps ToolMap in the foreground while this phone shares its position in an operation (P39.14, L4), the app open or
 * not: its line in ToolMap's notification ([BackgroundNotification], the one of « Entre amis », same title, same
 * button), a partial wake lock for the fixes with the screen off, and the type `location`. The positions themselves
 * are sent by [OperationSession]; [OperationSession.notice] says what the line shows, and the service stops when it is
 * `null`: the sharing cut (the switch, or « Arrêter le partage », which leaves this phone in the operation), the
 * operation dissolved, this phone removed from it, or gone. A tap opens the screen of the sharing, the operation's.
 * It is sticky: killed by the system, it comes back with the operation stored on this phone.
 */
@AndroidEntryPoint
class OperationService : Service() {
    @Inject
    lateinit var session: OperationSession

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var foreground = false
    private val wakeLock by lazy { ServiceWakeLock(this, "ToolMap:operation") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val action = intent?.action
        EventLog.event("operation.service.start", "action" to (action ?: if (intent == null) "restart" else "start"))
        if (action == ACTION_STOP) session.setSharing(false)
        val notice = session.notice.value
        when {
            notice != null -> {
                if (!goForeground(notice)) return finish()
                observe()
            }

            // Started to be put in the foreground (startForegroundService) while the sharing just stopped: Android
            // wants startForeground all the same, the notification goes at once.
            intent != null && action != ACTION_STOP -> {
                goForeground(OperationNotice("", offline = false))
                return finish()
            }

            // Restarted by the system: the session opens the stored operation again; the line follows once it is
            // open, or the service stops.
            intent == null -> {
                scope.launch {
                    val again =
                        withTimeoutOrNull(RESTORE_MS) {
                            session.restored.first { it }
                            session.notice.value
                        }
                    if (again == null || !goForeground(again)) finish() else observe()
                }
            }

            else -> {
                return finish()
            }
        }
        return START_STICKY
    }

    /** Puts the service in the foreground with [notice]'s line; `false` when Android refuses it. */
    private fun goForeground(notice: OperationNotice): Boolean =
        try {
            ServiceCompat.startForeground(
                this,
                BackgroundNotification.NOTIFICATION_ID,
                BackgroundNotification.put(this, BackgroundNotification.Part.OPERATION, line(notice)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            foreground = true
            true
        } catch (e: SecurityException) {
            // Android 14+ refuses the type `location` without the permission « pendant l'utilisation ».
            Problems.expected("operation.service.foreground", e)
            false
        } catch (e: IllegalStateException) {
            // From the background (Android 12+), a refusal of the system; any other one (a type missing from the
            // manifest) is a bug.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                Problems.expected("operation.service.foreground_refused", e)
            } else {
                Problems.report("operation.service.foreground_state", e)
            }
            false
        }

    private fun observe() {
        if (observer != null) return
        observer =
            scope.launch {
                session.notice.collect { notice ->
                    if (notice == null) {
                        finish()
                        return@collect
                    }
                    wakeLock.hold()
                    BackgroundNotification.update(
                        this@OperationService,
                        BackgroundNotification.Part.OPERATION,
                        line(notice),
                    )
                }
            }
    }

    private fun finish(): Int {
        EventLog.event("operation.service.finish")
        observer?.cancel()
        wakeLock.release()
        if (foreground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
        return START_NOT_STICKY
    }

    /** The line of the operation (image 07 A, image 09), its button « Arrêter le partage », a tap that opens it. */
    private fun line(notice: OperationNotice): BackgroundNotification.Line {
        val open =
            PendingIntent.getActivity(
                this,
                OPEN_REQUEST,
                Intent(this, MainActivity::class.java)
                    .setAction(ShareService.ACTION_OPEN)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                ACTION_STOP.hashCode(),
                Intent(this, OperationService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return BackgroundNotification.Line(
            text(this, notice),
            getString(R.string.background_stop_sharing),
            stop,
            open,
        )
    }

    override fun onDestroy() {
        EventLog.event("operation.service.destroy")
        wakeLock.release()
        scope.cancel()
        BackgroundNotification.remove(this, BackgroundNotification.Part.OPERATION)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "fr.toolmap.operation.STOP"
        private const val OPEN_REQUEST = 0x0e7a
        private const val RESTORE_MS = 30_000L

        /** « Position partagée · Exercice Vallon », and « Hors réseau : envoi dès son retour » under it. */
        fun text(
            context: Context,
            notice: OperationNotice,
        ): String {
            val shared = context.getString(R.string.background_sharing, notice.operation)
            return if (notice.offline) shared + "\n" + context.getString(R.string.op_notification_offline) else shared
        }

        /** Puts the sharing of the operation in the foreground (the session, as soon as a position is to go). */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, OperationService::class.java))
            } catch (e: IllegalStateException) {
                // From the background (Android 12+), a refusal of the system: the positions go while the app runs.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                    Problems.expected("operation.service.start_refused", e)
                } else {
                    Problems.report("operation.service.start", e)
                }
            }
        }
    }
}
