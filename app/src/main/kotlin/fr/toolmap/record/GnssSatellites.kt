package fr.toolmap.record

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

/** The satellites the receiver sees, and those it uses for its fix. */
data class SatelliteCount(
    val used: Int,
    val seen: Int,
)

/**
 * How many satellites the GPS sees and uses for its fix, as the platform's GNSS status reports them (the GPS box of
 * the map, the waiting line of the positioning panel). Nothing is listened to until the flow is collected, and the
 * listener is removed when it stops.
 */
class GnssSatellites
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** The satellites seen and used in the fix, sent at each change. Sends nothing without the location permission. */
        @SuppressLint("MissingPermission")
        fun counts(): Flow<SatelliteCount> =
            callbackFlow {
                val granted =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    close()
                    return@callbackFlow
                }
                val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val callback =
                    object : GnssStatus.Callback() {
                        override fun onSatelliteStatusChanged(status: GnssStatus) {
                            var used = 0
                            for (index in 0 until status.satelliteCount) {
                                if (status.usedInFix(index)) used++
                            }
                            trySend(SatelliteCount(used = used, seen = status.satelliteCount))
                        }
                    }
                manager.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
                awaitClose { manager.unregisterGnssStatusCallback(callback) }
            }
    }
