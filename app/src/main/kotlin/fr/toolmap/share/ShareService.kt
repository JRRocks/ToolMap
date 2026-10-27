package fr.toolmap.share

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import fr.toolmap.MainActivity
import fr.toolmap.R
import fr.toolmap.core.eventlog.EventLog
import fr.toolmap.core.p2p.MemberStatus
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
 * Keeps the process alive and foreground while the position is shared (service type `location`, like the recorder),
 * with a partial wake lock so that the GPS fixes and the data channels keep being served with the screen off. The
 * sharing itself is [ShareController]'s; the service stops with it. It is sticky: if the system kills the process
 * while sharing (phone locked, memory), it restarts it and the sharing carries on. The network is watched by the
 * controller, as long as the group exists ([NetworkWatch]).
 */
@AndroidEntryPoint
class ShareService : Service() {
    @Inject
    lateinit var controller: ShareController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private val wakeLock by lazy { ServiceWakeLock(this, "ToolMap:sharing") }
    private var lastNotified = 0L
    private var lastText = ""
    private var lastAlert: ShareAlert? = null
    private val notifiedSos = HashSet<Long>()
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(SOS_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    SOS_CHANNEL_ID,
                    getString(R.string.share_sos_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }

    private fun locationServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val active = prefs.getBoolean(KEY_ACTIVE, false)
        EventLog.event(
            "share.service.start",
            "action" to (intent?.action ?: if (intent == null) "restart" else "start"),
            "active" to active,
            "flags" to flags,
            "startId" to startId,
        )
        // A restart by the system (null intent) only carries on a sharing the user did not stop.
        if (intent == null && !active) return finish()
        try {
            ServiceCompat.startForeground(
                this,
                BackgroundNotification.NOTIFICATION_ID,
                BackgroundNotification.put(this, BackgroundNotification.Part.SHARING, line()),
                locationServiceType(),
            )
        } catch (e: SecurityException) {
            // No location permission (revoked, or never granted): nothing can be shared.
            EventLog.event("share.service.refused", "error" to "SecurityException", "why" to e.message)
            return finish()
        } catch (e: IllegalStateException) {
            // Android 12+ may refuse a foreground start from the background (restart without the permission
            // "while in use"): the user turns the sharing on again from the app.
            EventLog.event("share.service.refused", "error" to e.javaClass.simpleName, "why" to e.message)
            return finish()
        }
        controller.load()
        if (intent?.action == ACTION_STOP) {
            controller.setSharing(false)
            return finish()
        }
        prefs.edit().putBoolean(KEY_ACTIVE, true).apply()
        controller.setSharing(true)
        observe()
        return START_STICKY
    }

    /** Stops for good: the system must not bring the sharing back. */
    private fun finish(): Int {
        EventLog.event("share.service.finish")
        prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
        stopSelf()
        return START_NOT_STICKY
    }

    private fun observe() {
        if (observer != null) return
        // Not "immediate": a change made on the main thread is seen once it is done, so a passing "off" in the middle
        // of it cannot stop the service (P21.5 d).
        observer =
            scope.launch(Dispatchers.Main) {
                controller.state.collect { ui ->
                    // Sharing is switched on before the observer starts, so "off" means stopped or no GPS.
                    if (!ui.sharing) {
                        finish()
                        return@collect
                    }
                    updateWakeLock(ui.sharing)
                    notifySos(ui)
                    val now = System.currentTimeMillis()
                    val text = textFor(ui)
                    val alert = ui.alerts.firstOrNull()
                    if (text != lastText || alert != lastAlert || now - lastNotified >= NOTIFY_EVERY_MILLIS) {
                        lastText = text
                        lastAlert = alert
                        lastNotified = now
                        BackgroundNotification.update(this@ShareService, BackgroundNotification.Part.SHARING, line())
                    }
                }
            }
    }

    /** A high-priority notification for each SOS not yet seen, taken back once it is. */
    private fun notifySos(ui: ShareUi) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pending = ui.sos.filter { !it.acknowledged }
        for (alert in pending) {
            if (!notifiedSos.add(alert.memberId)) continue
            val open =
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            manager.notify(
                sosNotificationId(alert.memberId),
                NotificationCompat
                    .Builder(this, SOS_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(getString(R.string.share_sos_notification, alert.name))
                    .setContentText(alert.text)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build(),
            )
        }
        val still = pending.map { it.memberId }.toSet()
        for (id in notifiedSos.toList()) {
            if (id !in still) {
                manager.cancel(sosNotificationId(id))
                if (ui.sos.none { it.memberId == id }) notifiedSos.remove(id)
            }
        }
    }

    private fun sosNotificationId(memberId: Long): Int =
        SOS_NOTIFICATION_BASE + (memberId xor (memberId ushr 32)).toInt().and(SOS_ID_MASK)

    private fun textFor(ui: ShareUi): String {
        val linked = ui.others.count { it.status == MemberStatus.DIRECT }
        return resources.getQuantityString(R.plurals.share_notification_members, linked, linked)
    }

    private fun updateWakeLock(needed: Boolean) {
        if (needed) wakeLock.hold() else wakeLock.release()
    }

    /** The sharing's line in ToolMap's notification (P21.7). */
    private fun line(): BackgroundNotification.Line {
        val stop =
            PendingIntent.getService(
                this,
                ACTION_STOP.hashCode(),
                Intent(this, ShareService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val ui = controller.state.value
        // The most serious phone setting that may cut the sharing, on a line of its own (P21.5 o, c).
        val alert = ui.alerts.firstOrNull()?.let { "\n" + getString(it.line) }.orEmpty()
        return BackgroundNotification.Line(
            getString(R.string.background_sharing, lastText.ifEmpty { textFor(ui) }) + alert,
            getString(R.string.background_stop_sharing),
            stop,
        )
    }

    override fun onDestroy() {
        EventLog.event("share.service.destroy")
        updateWakeLock(false)
        scope.cancel()
        BackgroundNotification.remove(this, BackgroundNotification.Part.SHARING)
        super.onDestroy()
    }

    companion object {
        private const val SOS_CHANNEL_ID = "sharing-sos"
        private const val SOS_NOTIFICATION_BASE = 4200
        private const val SOS_ID_MASK = 0xFF
        private const val NOTIFY_EVERY_MILLIS = 10_000L
        private const val ACTION_STOP = "fr.toolmap.share.STOP"
        internal const val PREFS = "share-service"
        internal const val KEY_ACTIVE = "active"

        /** Starts the service and, through it, the sharing; the caller must hold the location permission. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ShareService::class.java))
        }

        /**
         * Brings back a sharing the user did not stop when the process was ended with no restart by the system
         * (forced stop, update of the app: P21.5 a). Called as the app comes on screen, when Android lets it start a
         * foreground service of type `location`. Returns whether the service was started.
         */
        fun resumeIfInterrupted(
            context: Context,
            inGroup: Boolean,
            sharing: Boolean,
        ): Boolean {
            val active = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVE, false)
            if (!active || !inGroup || sharing) return false
            val permitted =
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            EventLog.event("share.resume", "permitted" to permitted)
            if (!permitted) return false
            return runCatching { start(context) }
                .onFailure {
                    EventLog.event(
                        "share.resume.refused",
                        "error" to it.javaClass.simpleName,
                        "why" to it.message,
                    )
                }
                .isSuccess
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ShareService::class.java).setAction(ACTION_STOP))
        }
    }
}
