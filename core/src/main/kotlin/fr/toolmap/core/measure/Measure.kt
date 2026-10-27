package fr.toolmap.core.measure

import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.track.Geodesy
import fr.toolmap.core.track.TrackPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** What the measure tool computes from the points the user placed. */
data class MeasureResult(
    val pointCount: Int,
    /** Length of the line through the points, in meters (0 for fewer than two points). */
    val lengthMeters: Double,
    /** Length of the outline, closing segment included: needs at least three points. */
    val perimeterMeters: Double?,
    /** Area enclosed by the points taken as a polygon, in square meters: needs at least three points. */
    val areaSquareMeters: Double?,
    /** Initial bearing of the last segment, degrees clockwise from true north. */
    val lastBearingDegrees: Double?,
    /** Length of the last segment, in meters. */
    val lastSegmentMeters: Double?,
    /** Bearing and distance from the first to the last point (the "as the crow flies" figure). */
    val directBearingDegrees: Double?,
    val directMeters: Double?,
)

/**
 * Distance, area and bearing between points placed on the map (feature P9.2). Distances are great-circle on the
 * mean-radius sphere like everything else in the app (error under 0.5 %); areas are computed exactly on the WGS84
 * ellipsoid with a cylindrical equal-area projection, whose polygon edges deviate from geodesics by a negligible
 * amount for the sizes of a walk.
 */
object Measure {
    private const val A = 6_378_137.0
    private const val F = 1 / 298.257223563
    private val e2 = F * (2 - F)
    private val e = sqrt(e2)

    /** `q(φ)` of Snyder (3-12): proportional to the area between the equator and the parallel φ. */
    private fun q(sinPhi: Double): Double =
        (1 - e2) * (sinPhi / (1 - e2 * sinPhi * sinPhi) - ln((1 - e * sinPhi) / (1 + e * sinPhi)) / (2 * e))

    private val qPole = q(1.0)

    /** Radius of the sphere that has the area of the ellipsoid. */
    private val authalicRadius = A * sqrt(qPole / 2)

    fun measure(points: List<LonLat>): MeasureResult {
        val n = points.size
        val length = pathLength(points)
        val closed = n >= MIN_POLYGON_POINTS
        val last = if (n >= 2) points[n - 1] else null
        val before = if (n >= 2) points[n - 2] else null
        return MeasureResult(
            pointCount = n,
            lengthMeters = length,
            perimeterMeters = if (closed) length + distance(points[n - 1], points[0]) else null,
            areaSquareMeters = if (closed) areaSquareMeters(points) else null,
            lastBearingDegrees = if (last != null && before != null) bearing(before, last) else null,
            lastSegmentMeters = if (last != null && before != null) distance(before, last) else null,
            directBearingDegrees = if (last != null && n >= 2) bearing(points[0], last) else null,
            directMeters = if (last != null && n >= 2) distance(points[0], last) else null,
        )
    }

    fun pathLength(points: List<LonLat>): Double {
        var total = 0.0
        for (i in 1 until points.size) total += distance(points[i - 1], points[i])
        return total
    }

    fun distance(
        a: LonLat,
        b: LonLat,
    ): Double = Geodesy.distance(a.lat, a.lon, b.lat, b.lon)

    fun bearing(
        from: LonLat,
        to: LonLat,
    ): Double = Geodesy.bearing(from.lat, from.lon, to.lat, to.lon)

    /**
     * Area of the polygon through [points] (closed implicitly), in square meters, on the ellipsoid. Zero for fewer
     * than three points; the orientation of the points does not matter. A polygon that crosses the antimeridian is
     * handled by taking each longitude step the short way round.
     */
    fun areaSquareMeters(points: List<LonLat>): Double {
        val n = points.size
        if (n < MIN_POLYGON_POINTS) return 0.0
        var sum = 0.0
        for (i in 0 until n) {
            val a = points[i]
            val b = points[(i + 1) % n]
            var dLon = Math.toRadians(b.lon - a.lon)
            if (dLon > PI) dLon -= 2 * PI
            if (dLon < -PI) dLon += 2 * PI
            sum += dLon * (sinBeta(a.lat) + sinBeta(b.lat))
        }
        return abs(sum) * authalicRadius * authalicRadius / 2
    }

    /** Sine of the authalic latitude of a geodetic latitude in degrees. */
    private fun sinBeta(latDegrees: Double): Double = (q(sin(Math.toRadians(latDegrees))) / qPole).coerceIn(-1.0, 1.0)

    fun ofTrack(points: List<TrackPoint>): List<LonLat> = points.map { LonLat(it.lon, it.lat) }

    private const val MIN_POLYGON_POINTS = 3
}
