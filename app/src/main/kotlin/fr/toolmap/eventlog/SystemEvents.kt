package fr.toolmap.eventlog

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import fr.toolmap.BuildConfig
import fr.toolmap.core.eventlog.EventLog

/**
 * What happens to the app from outside, written in the event log (P21): how the process started and how the previous
 * one ended, the activities coming and going, the network, the screen, the battery, power saving and Doze, the
 * location switch, the permissions. Started once with the main process: one receiver, one network callback.
 */
class SystemEvents(
    private val app: Application,
) {
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var startedActivities = 0
    private var lastPermissions = ""
    private var lastPower = ""
    private var lastLocation = ""
    private var lastBattery = ""
    private var lastNetwork = ""

    fun start() {
        logStart()
        logPastExits()
        logPower("start")
        logLocationSwitch()
        logPermissions()
        app.registerActivityLifecycleCallbacks(activities)
        app.registerComponentCallbacks(memory)
        registerReceiver()
        watchNetwork()
    }

    // ---- the process ------------------------------------------------------------------------------------------

    private fun logStart() {
        val state = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
        EventLog.event(
            "app.start",
            "version" to BuildConfig.VERSION_NAME,
            "build" to BuildConfig.BUILD_TYPE,
            "pid" to Process.myPid(),
            "sdk" to Build.VERSION.SDK_INT,
            "device" to Build.MANUFACTURER + " " + Build.MODEL,
            "importance" to state.importance,
            "forkMs" to SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime(),
            "bootS" to SystemClock.elapsedRealtime() / MS_PER_S,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching {
                val am = app.getSystemService(ActivityManager::class.java)
                am?.getHistoricalProcessStartReasons(1)?.firstOrNull()?.let {
                    EventLog.event(
                        "app.start.reason",
                        "reason" to startReason(it.reason),
                        "type" to it.startType,
                        "forceStopped" to it.wasForceStopped(),
                    )
                }
            }
        }
    }

    /** How the previous processes ended (Android 11+): killed for memory, by the user, a crash, an update... */
    private fun logPastExits() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = app.getSystemService(ActivityManager::class.java) ?: return
        val since = prefs.getLong(KEY_EXITS_UP_TO, 0L)
        val exits = runCatching { am.getHistoricalProcessExitReasons(app.packageName, 0, MAX_EXITS) }.getOrNull()
        val fresh = exits.orEmpty().filter { it.timestamp > since }.sortedBy { it.timestamp }
        for (e in fresh) {
            EventLog.event(
                "app.exit",
                "at" to e.timestamp,
                "reason" to exitReason(e.reason),
                "status" to e.status,
                "importance" to e.importance,
                "process" to e.processName,
                "pssKb" to e.pss,
                "rssKb" to e.rss,
                "why" to e.description,
            )
        }
        fresh.lastOrNull()?.let { last -> prefs.edit { putLong(KEY_EXITS_UP_TO, last.timestamp) } }
    }

    private fun exitReason(reason: Int): String =
        when (reason) {
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"

            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"

            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"

            ApplicationExitInfo.REASON_CRASH -> "CRASH"

            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"

            ApplicationExitInfo.REASON_ANR -> "ANR"

            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"

            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"

            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCES"

            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"

            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"

            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"

            ApplicationExitInfo.REASON_OTHER -> "OTHER"

            // Added in API 33 and 34; their values are fixed.
            REASON_FREEZER -> "FREEZER"

            REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"

            REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"

            else -> "R$reason"
        }

    /** Why the process was started (ApplicationStartInfo.START_REASON_*, API 35). */
    private fun startReason(reason: Int): String = START_REASONS.getOrNull(reason) ?: "S$reason"

    // ---- activities and memory --------------------------------------------------------------------------------

    private val activities =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(
                activity: Activity,
                savedInstanceState: Bundle?,
            ) = activity(activity, "created", "restored" to (savedInstanceState != null))

            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0) {
                    EventLog.event("app.foreground")
                    logPower("foreground")
                }
                activity(activity, "started")
            }

            override fun onActivityResumed(activity: Activity) {
                activity(activity, "resumed")
                // Back from the system settings: a permission or the location switch may have changed.
                logPermissions()
                logLocationSwitch()
            }

            override fun onActivityPaused(activity: Activity) = activity(activity, "paused")

            override fun onActivityStopped(activity: Activity) {
                activity(activity, "stopped")
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) EventLog.event("app.background")
            }

            override fun onActivitySaveInstanceState(
                activity: Activity,
                outState: Bundle,
            ) = Unit

            override fun onActivityDestroyed(activity: Activity) =
                activity(
                    activity,
                    "destroyed",
                    "finishing" to activity.isFinishing,
                    "config" to activity.isChangingConfigurations,
                )
        }

    private fun activity(
        activity: Activity,
        state: String,
        vararg more: Pair<String, Any?>,
    ) = EventLog.event("ui.activity", "name" to activity.javaClass.simpleName, "state" to state, *more)

    private val memory =
        object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = EventLog.event("app.trim", "level" to level)

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = EventLog.event("app.lowmem")
        }

    // ---- the phone ----------------------------------------------------------------------------------------------

    private fun registerReceiver() {
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_SHUTDOWN)
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                addAction(LocationManager.MODE_CHANGED_ACTION)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
        runCatching { ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
    }

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (val action = intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        logBattery(intent)
                    }

                    LocationManager.PROVIDERS_CHANGED_ACTION, LocationManager.MODE_CHANGED_ACTION -> {
                        logLocationSwitch()
                    }

                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED, PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                        logPower(action.substringAfterLast('.').lowercase())
                    }

                    Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                        EventLog.event("sys.airplane", "on" to intent.getBooleanExtra("state", false))
                    }

                    else -> {
                        EventLog.event("sys." + action?.substringAfterLast('.')?.lowercase())
                    }
                }
            }
        }

    /** Logged when the level, the charger or the temperature (in whole degrees) changes. */
    private fun logBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, PERCENT)
        val percent = if (level >= 0 && scale > 0) level * PERCENT / scale else null
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / TENTHS
        val key = "$percent:$plugged:$tempC"
        if (key == lastBattery) return
        lastBattery = key
        EventLog.event("sys.battery", "percent" to percent, "plugged" to plugged, "tempC" to tempC)
    }

    private fun logLocationSwitch() {
        val lm = app.getSystemService(LocationManager::class.java) ?: return
        val on = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled else null
        val gps = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull()
        val key = "$on:$gps"
        if (key == lastLocation) return
        lastLocation = key
        EventLog.event("sys.location", "on" to on, "gps" to gps)
    }

    /** Power saving, Doze, the battery optimization and the standby bucket: what lets Android cut the app off. */
    private fun logPower(why: String) {
        val pm = app.getSystemService(PowerManager::class.java) ?: return
        val am = app.getSystemService(ActivityManager::class.java)
        val restricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) am?.isBackgroundRestricted else null
        val bucket =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { app.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket }.getOrNull()
            } else {
                null
            }
        // What the power saver does to the location (GPS off screen off, P21.5 o); 0 = nothing.
        val locMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pm.locationPowerSaveMode else null
        val fields =
            arrayOf<Pair<String, Any?>>(
                "saver" to pm.isPowerSaveMode,
                "locMode" to locMode,
                "doze" to pm.isDeviceIdleMode,
                "optimized" to !pm.isIgnoringBatteryOptimizations(app.packageName),
                "restricted" to restricted,
                "bucket" to bucket,
                "interactive" to pm.isInteractive,
            )
        val key = fields.joinToString { "${it.second}" }
        if (key == lastPower && why != "start") return
        lastPower = key
        EventLog.event("sys.power", "why" to why, *fields)
    }

    private fun logPermissions() {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
        val fields =
            arrayOf<Pair<String, Any?>>(
                "fine" to granted(Manifest.permission.ACCESS_FINE_LOCATION),
                "coarse" to granted(Manifest.permission.ACCESS_COARSE_LOCATION),
                "notify" to NotificationManagerCompat.from(app).areNotificationsEnabled(),
                "camera" to granted(Manifest.permission.CAMERA),
            )
        val key = fields.joinToString { "${it.second}" }
        if (key == lastPermissions) return
        lastPermissions = key
        EventLog.event("app.perm", *fields)
    }

    // ---- network ------------------------------------------------------------------------------------------------

    private fun watchNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = EventLog.event("net.available", "net" to network)

                override fun onLost(network: Network) = EventLog.event("net.lost", "net" to network)

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    val transport =
                        when {
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                            else -> "other"
                        }
                    val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    val key = "$network:$transport:$validated"
                    if (key == lastNetwork) return
                    lastNetwork = key
                    EventLog.event(
                        "net.caps",
                        "net" to network,
                        "transport" to transport,
                        "validated" to validated,
                        "downKbps" to capabilities.linkDownstreamBandwidthKbps,
                        "metered" to !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    )
                }

                // Data saver or Doze cut the app off this network (Android 10+).
                override fun onBlockedStatusChanged(
                    network: Network,
                    blocked: Boolean,
                ) = EventLog.event("net.blocked", "net" to network, "blocked" to blocked)
            }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
    }

    private companion object {
        const val PREFS = "eventlog"
        const val KEY_EXITS_UP_TO = "exits_logged_up_to"
        const val MAX_EXITS = 16
        const val MS_PER_S = 1000L
        const val PERCENT = 100
        const val TENTHS = 10
        const val REASON_FREEZER = 14
        const val REASON_PACKAGE_STATE_CHANGE = 15
        const val REASON_PACKAGE_UPDATED = 16

        /** ApplicationStartInfo.START_REASON_* by value, 0 to 11. */
        val START_REASONS =
            listOf(
                "ALARM",
                "BACKUP",
                "BOOT_COMPLETE",
                "BROADCAST",
                "CONTENT_PROVIDER",
                "JOB",
                "LAUNCHER",
                "LAUNCHER_RECENTS",
                "OTHER",
                "PUSH",
                "SERVICE",
                "START_ACTIVITY",
            )
    }
}
