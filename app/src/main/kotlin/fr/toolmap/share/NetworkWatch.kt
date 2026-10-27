package fr.toolmap.share

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import fr.toolmap.core.eventlog.EventLog

/**
 * Follows the phone's default network and clock for the server connection of a group (P21.5 f, h, n, p), as long as
 * the group exists: not only while the sharing service runs, since a member who does not share still talks to the
 * server.
 */
fun interface NetworkWatch {
    /** Reports to [listener] (from a system thread) until the returned handle is closed. */
    fun start(listener: Listener): AutoCloseable

    interface Listener {
        /** [name] is the default network now; [retry]: it is a new one (or usable again), try the server at once. */
        fun onNetwork(
            name: String,
            retry: Boolean,
        )

        /** Android cuts the app off the network or lets it back (app in the background, data saver, Doze). */
        fun onBlocked(blocked: Boolean)

        /** The default network is gone, none took its place yet (P21.5 p): the next [onNetwork] ends this. */
        fun onOffline() = Unit

        /** The phone's clock was set, by hand or by the network (P21.5 n). */
        fun onClockChanged() = Unit
    }
}

/** The [NetworkWatch] of Android, on the default network callback. */
class AndroidNetworkWatch(
    private val context: Context,
) : NetworkWatch {
    override fun start(listener: NetworkWatch.Listener): AutoCloseable {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return AutoCloseable {}
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                private var current: Network? = null
                private var validated = true
                private var blocked = false

                override fun onAvailable(network: Network) {
                    // Also called at once for the network in use when registering: that one needs no reconnection.
                    val previous = current
                    current = network
                    val retry = previous != network && (previous != null || !validated)
                    EventLog.event("share.net.available", "net" to network, "previous" to previous, "retry" to retry)
                    listener.onNetwork(network.toString(), retry)
                }

                override fun onLost(network: Network) {
                    EventLog.event("share.net.lost", "net" to network, "current" to (network == current))
                    if (network == current) {
                        validated = false
                        listener.onOffline()
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    // Back in coverage on the same network: it is usable again (validated by the system).
                    val now = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (now != validated) EventLog.event("share.net.validated", "net" to network, "validated" to now)
                    if (now && !validated) listener.onNetwork(network.toString(), retry = true)
                    validated = now
                }

                // Android 10+; also called once when registering.
                override fun onBlockedStatusChanged(
                    network: Network,
                    blocked: Boolean,
                ) {
                    if (blocked == this.blocked) return
                    this.blocked = blocked
                    EventLog.event("share.net.blocked", "net" to network, "blocked" to blocked)
                    listener.onBlocked(blocked)
                }
            }
        val registered = runCatching { connectivity.registerDefaultNetworkCallback(callback) }.isSuccess
        val clock =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) = listener.onClockChanged()
            }
        val watchesClock =
            runCatching {
                ContextCompat.registerReceiver(
                    context,
                    clock,
                    IntentFilter(Intent.ACTION_TIME_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }.isSuccess
        return AutoCloseable {
            if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) }
            if (watchesClock) runCatching { context.unregisterReceiver(clock) }
        }
    }
}
