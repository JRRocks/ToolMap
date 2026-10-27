package fr.toolmap.record

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import fr.toolmap.core.eventlog.EventLog
import fr.toolmap.core.record.Fix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Where positions come from. The recorder and the target guidance only know this, so that tests can feed them. */
interface LocationSource {
    /** A cold flow of GPS fixes; collecting it starts the receiver, cancelling stops it. Fails if there is no GPS. */
    fun fixes(
        minIntervalMillis: Long,
        minDistanceMeters: Float,
    ): Flow<Fix>
}

/** The platform `LocationManager`, GPS provider only (no Google Play Services, no network positions). */
class AndroidLocationSource(
    private val context: Context,
) : LocationSource {
    @SuppressLint("MissingPermission")
    override fun fixes(
        minIntervalMillis: Long,
        minDistanceMeters: Float,
    ): Flow<Fix> =
        callbackFlow {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            // Several parts of the app may listen at once (recording, sharing): the log tells them apart.
            val client = Integer.toHexString(System.identityHashCode(this))
            val listener =
                object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        EventLog.detail(
                            "gps.fix",
                            "client" to client,
                            "acc" to location.accuracy.takeIf { location.hasAccuracy() },
                            "ageMs" to (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / NS_PER_MS,
                            "mock" to location.isMockCompat(),
                        )
                        trySend(location.toFix())
                    }

                    // Abstract before API 30, so they have to be here.
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(
                        provider: String?,
                        status: Int,
                        extras: Bundle?,
                    ) = Unit

                    override fun onProviderEnabled(provider: String) =
                        EventLog.event("gps.provider", "client" to client, "enabled" to true)

                    override fun onProviderDisabled(provider: String) =
                        EventLog.event("gps.provider", "client" to client, "enabled" to false)
                }
            EventLog.event(
                "gps.request",
                "client" to client,
                "intervalMs" to minIntervalMillis,
                "minM" to minDistanceMeters,
                "enabled" to runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull(),
            )
            try {
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    minIntervalMillis,
                    minDistanceMeters,
                    listener,
                    Looper.getMainLooper(),
                )
            } catch (e: SecurityException) {
                EventLog.event("gps.refused", "client" to client, "error" to "SecurityException")
                close(e)
            } catch (e: IllegalArgumentException) {
                EventLog.event(
                    "gps.refused",
                    "client" to client,
                    "error" to "IllegalArgumentException",
                    "why" to e.message,
                )
                close(e)
            }
            awaitClose {
                EventLog.event("gps.stop", "client" to client)
                manager.removeUpdates(listener)
            }
        }

    private companion object {
        const val NS_PER_MS = 1_000_000L
    }
}

private fun Location.isMockCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        isMock
    } else {
        @Suppress("DEPRECATION")
        isFromMockProvider
    }

/**
 * The altitude is the one above mean sea level where Android 14+ gives it; before that `getAltitude()` is by contract
 * the height above the WGS84 ellipsoid (many receivers give sea level anyway, but nothing says which), so it is not
 * marked as sea level.
 */
internal fun Location.toFix(): Fix {
    val msl = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasMslAltitude()
    val ele =
        when {
            msl -> mslAltitudeMeters
            hasAltitude() -> altitude
            else -> null
        }
    val vertical =
        when {
            msl && hasMslAltitudeAccuracy() -> mslAltitudeAccuracyMeters.toDouble()
            hasVerticalAccuracy() -> verticalAccuracyMeters.toDouble()
            else -> null
        }
    return Fix(
        lat = latitude,
        lon = longitude,
        ele = ele,
        timeMillis = time,
        accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
        speedMps = if (hasSpeed()) speed.toDouble() else null,
        verticalAccuracyMeters = vertical,
        eleIsMsl = msl,
    )
}
