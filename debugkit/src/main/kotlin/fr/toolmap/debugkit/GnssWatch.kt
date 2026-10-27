package fr.toolmap.debugkit

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import fr.toolmap.core.eventlog.EventLog

/**
 * What the GNSS chip does while the test mode is on: started, stopped, time to first fix, and every [STATUS_EVERY_MS]
 * the satellites seen and used with their signal strength. Explains a position that comes late or badly.
 */
internal class GnssWatch(
    private val context: Context,
) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var callback: GnssStatus.Callback? = null
    private var lastStatusAt = 0L

    @SuppressLint("MissingPermission") // checked just before
    fun start() {
        if (callback != null || manager == null) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            EventLog.event("gnss.refused")
            return
        }
        val cb = Callback()
        val ok =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    manager.registerGnssStatusCallback(context.mainExecutor, cb)
                } else {
                    manager.registerGnssStatusCallback(cb, Handler(Looper.getMainLooper()))
                }
            }.getOrDefault(false)
        if (ok) callback = cb
        EventLog.event("gnss.watch", "on" to ok)
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        runCatching { manager?.unregisterGnssStatusCallback(cb) }
        EventLog.event("gnss.watch", "on" to false)
    }

    private inner class Callback : GnssStatus.Callback() {
        override fun onStarted() = EventLog.event("gnss.start")

        override fun onStopped() = EventLog.event("gnss.stop")

        override fun onFirstFix(ttffMillis: Int) = EventLog.event("gnss.firstfix", "ttffMs" to ttffMillis)

        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val now = EventLog.clock()
            if (now - lastStatusAt < STATUS_EVERY_MS) return
            lastStatusAt = now
            var used = 0
            var usedCn0 = 0.0
            var best = 0f
            val systems = sortedSetOf<String>()
            for (i in 0 until status.satelliteCount) {
                val cn0 = status.getCn0DbHz(i)
                if (cn0 > best) best = cn0
                if (status.usedInFix(i)) {
                    used++
                    usedCn0 += cn0
                    systems += system(status.getConstellationType(i))
                }
            }
            EventLog.event(
                "gnss.status",
                "seen" to status.satelliteCount,
                "used" to used,
                "cn0" to if (used > 0) usedCn0 / used else null,
                "best" to best,
                "systems" to systems.joinToString(",").ifEmpty { null },
            )
        }
    }

    private fun system(type: Int): String =
        when (type) {
            GnssStatus.CONSTELLATION_GPS -> "gps"
            GnssStatus.CONSTELLATION_GLONASS -> "glo"
            GnssStatus.CONSTELLATION_GALILEO -> "gal"
            GnssStatus.CONSTELLATION_BEIDOU -> "bds"
            GnssStatus.CONSTELLATION_QZSS -> "qzss"
            GnssStatus.CONSTELLATION_SBAS -> "sbas"
            else -> "other"
        }

    private companion object {
        const val STATUS_EVERY_MS = 5_000L
    }
}
