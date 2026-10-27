package fr.toolmap.core.track

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Distances on the WGS84 sphere of mean radius, the usual accuracy for GPS tracks (error under 0.5 %). */
object Geodesy {
    const val EARTH_RADIUS_METERS = 6_371_008.8

    /** Great-circle distance in meters between two positions in degrees. */
    fun distance(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_METERS * asin(minOf(1.0, sqrt(a)))
    }

    fun distance(
        a: TrackPoint,
        b: TrackPoint,
    ): Double = distance(a.lat, a.lon, b.lat, b.lon)

    /** Initial great-circle bearing from the first position to the second, in degrees clockwise from north, `[0, 360)`. */
    fun bearing(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Smallest signed turn from heading [from] to bearing [to], in `[-180, 180)`: negative is to the left. */
    fun relativeBearing(
        from: Double,
        to: Double,
    ): Double = ((to - from) % 360.0 + 540.0) % 360.0 - 180.0
}
