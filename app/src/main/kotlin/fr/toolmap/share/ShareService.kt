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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Keeps the process alive and foreground as long as the device is in a group (P22.3, ADR-090): it listens to the
 * group (service type `specialUse`) and, while the position is shared, also locates (type `location`, like the
 * recorder); a partial wake lock keeps the fixes and the connection served with the screen off. The sharing itself is
 * [ShareController]'s; turning it off goes back to listening, leaving the group stops the service. It is sticky: if
 * the system kills the process (phone locked, memory), it restarts it, listening again and sharing if the user had not
 * stopped. The network is watched by the controller, as long as the group exists ([NetworkWatch]).
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

    /** The position is shared (types `location` and `specialUse`), not only listened to (`specialUse`). */
    private var sharingNow = false
    private val notifiedSos = HashSet<Long>()
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Created once with its importance; its name follows the app's (« Alertes du groupe » since P22).
        manager.createNotificationChannel(
            NotificationChannel(
                SOS_CHANNEL_ID,
                getString(R.string.group_alert_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val active = prefs.getBoolean(KEY_ACTIVE, false)
        val action = intent?.action
        EventLog.event(
            "share.service.start",
            "action" to (action ?: if (intent == null) "restart" else "start"),
            "active" to active,
            "flags" to flags,
            "startId" to startId,
        )
        controller.load()
        val inGroup = controller.state.value.inGroup
        // A restart by the system (null intent) carries on the listening of a group, and a sharing not stopped.
        if (intent == null && !inGroup) return finish()
        var share =
            when {
                intent == null -> active
                action == ACTION_STOP -> false
                action == ACTION_LISTEN -> controller.state.value.sharing
                else -> true
            }
        if (!goForeground(share)) {
            // Android 12+ may refuse the type `location` from the background (no permission "while in use", or
            // revoked): the device keeps listening and the user turns the sharing on again from the app.
            if (!share || !goForeground(false)) return finish()
            share = false
        }
        // Started for nothing (the group was left, or none after a restart of the phone): the notification goes.
        if (!inGroup && !share) return finish()
        if (share) {
            prefs.edit().putBoolean(KEY_ACTIVE, true).apply()
            controller.setSharing(true)
        } else {
            prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
            if (action == ACTION_STOP) controller.setSharing(false)
        }
        observe()
        return START_STICKY
    }

    /** Puts the service in the foreground for listening or sharing; `false` when Android refuses it. */
    private fun goForeground(share: Boolean): Boolean {
        val ui = controller.state.value
        return try {
            ServiceCompat.startForeground(
                this,
                BackgroundNotification.NOTIFICATION_ID,
                BackgroundNotification.put(this, BackgroundNotification.Part.SHARING, line(ui, share)),
                foregroundTypes(share),
            )
            sharingNow = share
            true
        } catch (e: SecurityException) {
            EventLog.event(
                "share.service.refused",
                "share" to share,
                "error" to "SecurityException",
                "why" to e.message,
            )
            false
        } catch (e: IllegalStateException) {
            EventLog.event(
                "share.service.refused",
                "share" to share,
                "error" to e.javaClass.simpleName,
                "why" to e.message,
            )
            false
        }
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
                    // Out of the group (left, or removed by the others): nothing to listen to any more.
                    if (!ui.inGroup) {
                        finish()
                        return@collect
                    }
                    // Sharing is switched on before the observer starts, so "off" means stopped or no GPS: the
                    // device goes back to listening (P22.3), without the type `location`.
                    if (sharingNow && !ui.sharing) {
                        prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
                        if (!goForeground(false)) {
                            finish()
                            return@collect
                        }
                    }
                    updateWakeLock(true)
                    notifySos(ui)
                    val now = System.currentTimeMillis()
                    val text = if (sharingNow) textFor(ui) else listeningText(ui)
                    val alert = ui.alerts.firstOrNull()
                    if (text != lastText || alert != lastAlert || now - lastNotified >= NOTIFY_EVERY_MILLIS) {
                        lastText = text
                        lastAlert = alert
                        lastNotified = now
                        BackgroundNotification.update(
                            this@ShareService,
                            BackgroundNotification.Part.SHARING,
                            line(ui, sharingNow),
                        )
                    }
                }
            }
    }

    /**
     * A high-priority notification for each alert of the group not yet seen, taken back once it is; an alert lifted
     * while notified is replaced by « Alerte levée par … », without sound, gone after [LIFTED_NOTIFICATION_MS] (P22).
     */
    private fun notifySos(ui: ShareUi) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val pending = ui.sos.filter { !it.acknowledged }
        for (alert in pending) {
            if (!notifiedSos.add(alert.memberId)) continue
            manager.notify(
                sosNotificationId(alert.memberId),
                NotificationCompat
                    .Builder(this, SOS_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(getString(R.string.group_alert_from, alert.name))
                    .setContentText(alert.text)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build(),
            )
        }
        val still = pending.map { it.memberId }.toSet()
        val time = DateTimeFormatter.ofPattern("HH:mm")
        for (id in notifiedSos.toList()) {
            if (id in still) continue
            val lifted = ui.sosLifted.firstOrNull { it.memberId == id }
            if (lifted != null) {
                val at = time.format(Instant.ofEpochMilli(lifted.alertAtMs).atZone(ZoneId.systemDefault()))
                manager.notify(
                    sosNotificationId(id),
                    NotificationCompat
                        .Builder(this, SOS_CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(getString(R.string.group_alert_lifted_by, lifted.name))
                        .setContentText(getString(R.string.group_alert_lifted_text, lifted.name, at))
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .setSilent(true)
                        .setTimeoutAfter(LIFTED_NOTIFICATION_MS)
                        .build(),
                )
            } else {
                manager.cancel(sosNotificationId(id))
            }
            if (ui.sos.none { it.memberId == id }) notifiedSos.remove(id)
        }
    }

    private fun sosNotificationId(memberId: Long): Int =
        SOS_NOTIFICATION_BASE + (memberId xor (memberId ushr 32)).toInt().and(SOS_ID_MASK)

    private fun textFor(ui: ShareUi): String {
        val linked = ui.others.count { it.status == MemberStatus.DIRECT }
        return resources.getQuantityString(R.plurals.share_notification_members, linked, linked)
    }

    /** « Groupe · 4 membres : à l'écoute », the line of the listening (P22.3, the group has no name). */
    private fun listeningText(ui: ShareUi): String =
        resources.getQuantityString(R.plurals.share_listening_line, ui.members.size, ui.members.size)

    private fun updateWakeLock(needed: Boolean) {
        if (needed) wakeLock.hold() else wakeLock.release()
    }

    /**
     * The sharing's line in ToolMap's notification (P21.7), with its stop button; while only listening, the line of
     * the group without a button (P22.3). A tap opens the sharing screen.
     */
    private fun line(
        ui: ShareUi,
        share: Boolean,
    ): BackgroundNotification.Line {
        val open =
            PendingIntent.getActivity(
                this,
                ACTION_OPEN.hashCode(),
                Intent(this, MainActivity::class.java).setAction(ACTION_OPEN).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        if (!share) return BackgroundNotification.Line(listeningText(ui), open = open)
        val stop =
            PendingIntent.getService(
                this,
                ACTION_STOP.hashCode(),
                Intent(this, ShareService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        // The most serious phone setting that may cut the sharing, on a line of its own (P21.5 o, c).
        val alert = ui.alerts.firstOrNull()?.let { "\n" + getString(it.line) }.orEmpty()
        val members = if (sharingNow) lastText.ifEmpty { textFor(ui) } else textFor(ui)
        return BackgroundNotification.Line(
            getString(R.string.background_sharing, members) + alert,
            getString(R.string.background_stop_sharing),
            stop,
            open,
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
        private const val LIFTED_NOTIFICATION_MS = 10 * 60_000L
        private const val NOTIFY_EVERY_MILLIS = 10_000L
        private const val ACTION_STOP = "fr.toolmap.share.STOP"
        private const val ACTION_LISTEN = "fr.toolmap.share.LISTEN"

        /** Opens the sharing screen, from the notification (P22.3). */
        const val ACTION_OPEN = "fr.toolmap.share.OPEN"
        internal const val PREFS = "share-service"
        internal const val KEY_ACTIVE = "active"

        /** Starts the service and, through it, the sharing; the caller must hold the location permission. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ShareService::class.java))
        }

        /**
         * Listens to the group with no sharing (P22.3): from the app on screen, or after the phone restarted or the app
         * was updated. Returns whether Android let the service start.
         */
        fun listen(context: Context): Boolean =
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ShareService::class.java).setAction(ACTION_LISTEN),
                )
            }.onFailure {
                EventLog.event("share.listen.refused", "error" to it.javaClass.simpleName, "why" to it.message)
            }
                .isSuccess

        /**
         * The foreground types (ADR-090): `specialUse` to listen to the group (Android 14 and later; none is needed
         * before), plus `location` while the position is shared.
         */
        fun foregroundTypes(
            share: Boolean,
            sdk: Int = Build.VERSION.SDK_INT,
        ): Int {
            var types = 0
            if (sdk >=
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            ) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            if (share && sdk >= Build.VERSION_CODES.Q) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            return types
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
