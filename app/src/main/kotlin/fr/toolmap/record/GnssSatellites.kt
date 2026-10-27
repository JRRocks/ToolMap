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

/**
 * How many satellites the GPS uses for its fix, as the platform's GNSS status reports them (the GPS box of the map).
 * Nothing is listened to until the flow is collected, and the listener is removed when it stops.
 */
class GnssSatellites
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** The number of satellites used in the fix, sent at each change. Sends nothing without the location permission. */
        @SuppressLint("MissingPermission")
        fun used(): Flow<Int> =
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
                            trySend(used)
                        }
                    }
                manager.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
                awaitClose { manager.unregisterGnssStatusCallback(callback) }
            }
    }
