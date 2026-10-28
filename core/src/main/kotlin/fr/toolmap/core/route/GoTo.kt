package fr.toolmap.core.route

import fr.toolmap.core.alert.TrackFollower
import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.track.Geodesy
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.core.track.TrackStatsCalculator
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong

/**
 * The settings of « Aller vers un point » (P30.3, variant C of `docs/previews/p30-itineraires.html`): BRouter is asked
 * again every [intervalMillis], and at once when the walker is more than [offRouteMeters] from the way.
 */
data class GoToRules(
    val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    val offRouteMeters: Double = DEFAULT_OFF_ROUTE_METERS,
) {
    companion object {
        const val DEFAULT_INTERVAL_MILLIS = 120_000L
        const val DEFAULT_OFF_ROUTE_METERS = 50.0

        /** The intervals offered, in minutes. */
        val INTERVAL_MINUTES = listOf(1, 2, 5, 10)

        /** The distances off the way offered, in meters. */
        val OFF_ROUTE_METERS = listOf(25, 50, 100)

        /** The BRouter profiles of the start sheet: Randonnée, Vélo, VTT, Voiture. */
        val PROFILES = listOf(RoutingProfiles.HIKING, RoutingProfiles.BIKE, RoutingProfiles.MTB, RoutingProfiles.CAR)

        /** The rules of the settings, an unknown value taken as the nearest one offered. */
        fun of(
            intervalMinutes: Int,
            offRouteMeters: Int,
        ) = GoToRules(
            intervalMillis = INTERVAL_MINUTES.minBy { abs(it - intervalMinutes) } * MILLIS_PER_MINUTE,
            offRouteMeters = OFF_ROUTE_METERS.minBy { abs(it - offRouteMeters) }.toDouble(),
        )

        private const val MILLIS_PER_MINUTE = 60_000L
    }
}

/** A way to the target, as BRouter gave it from where the walker stood ([from]) at [computedAtMillis]. */
class GoToWay(
    val points: List<TrackPoint>,
    val profile: String,
    /** BRouter's time for the whole way; `null` for a walking profile (DIN 33466 is used) or when it gave none. */
    val timeMillis: Long?,
    val computedAtMillis: Long,
    from: TrackPoint? = null,
) {
    val follower = TrackFollower(points.map { LonLat(it.lon, it.lat) })
    val totalMeters: Double get() = follower.totalMeters

    /**
     * How far the walker stood from the way when it was computed: BRouter starts it on the nearest path, which may be
     * far from a walker in a field; being off the way is counted beyond that distance.
     */
    val startOffMeters: Double = from?.let { follower.project(it.lat, it.lon, null)?.offMeters } ?: 0.0

    /** Distance along the way at each of its points, to cut what remains. */
    private val cumulative: DoubleArray =
        DoubleArray(points.size).also { out ->
            for (i in 1 until points.size) {
                out[i] =
                    out[i - 1] + Geodesy.distance(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
            }
        }

    /** The points of the way from [alongMeters] to its end. */
    fun after(alongMeters: Double): List<TrackPoint> {
        val first = cumulative.indexOfFirst { it > alongMeters }
        if (first < 0) return listOfNotNull(points.lastOrNull())
        return points.subList(first, points.size)
    }

    /** The point of the way [alongMeters] from its start. */
    fun pointAt(alongMeters: Double): TrackPoint? {
        if (points.isEmpty()) return null
        val d = alongMeters.coerceIn(0.0, cumulative.last())
        val i = cumulative.indexOfFirst { it >= d }.coerceAtLeast(1).coerceAtMost(points.lastIndex)
        val a = points[i - 1]
        val b = points[i]
        val span = cumulative[i] - cumulative[i - 1]
        val t = if (span <= 0.0) 0.0 else (d - cumulative[i - 1]) / span
        val ele = if (a.ele != null && b.ele != null) a.ele + (b.ele - a.ele) * t else a.ele ?: b.ele
        return TrackPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t, ele)
    }

    /** What is left of the way from [alongMeters]: drawn on the map from the walker's place on it. */
    fun remaining(alongMeters: Double): List<TrackPoint> = listOfNotNull(pointAt(alongMeters)) + after(alongMeters)
}

/** Where the walker stands on the way, and what remains of it. */
data class GoToProgress(
    /** Distance from the position to the way. */
    val offMeters: Double,
    val alongMeters: Double,
    val remainingMeters: Double,
    /** Smoothed climb left; `null` when the way has no elevation. */
    val remainingAscentMeters: Double?,
    /** Time left; `null` when it cannot be told (no BRouter time for a profile that is not walking). */
    val remainingMillis: Long?,
    /** The direction to walk: towards the way a little ahead, in degrees from the north. */
    val headingDegrees: Double,
    val offRoute: Boolean,
    val arrived: Boolean,
)

/** The rules of « Aller vers un point »: what remains of the way, and when to ask BRouter again (P30.3). */
object GoTo {
    /** Closer than this to the target is « Arrivé » (as the straight line, [fr.toolmap.core.geo.Navigation]). */
    const val ARRIVED_METERS = 15.0

    /** The arrow points at the way this far ahead of the walker. */
    const val LOOK_AHEAD_METERS = 40.0

    /** Off the way, BRouter is asked again at most this often: a way it cannot find is not asked for each second. */
    const val MIN_RETRY_MILLIS = 15_000L

    fun progress(
        way: GoToWay,
        lat: Double,
        lon: Double,
        rules: GoToRules,
        hintAlongMeters: Double? = null,
    ): GoToProgress? {
        val p = way.follower.project(lat, lon, hintAlongMeters) ?: return null
        val remaining = (way.totalMeters - p.alongMeters).coerceAtLeast(0.0)
        val stats = TrackStatsCalculator.compute(listOf(way.remaining(p.alongMeters)))
        val ascent = stats.ascentMeters.takeIf { stats.minEleMeters != null }
        val millis =
            when {
                RoutingProfiles.isWalking(way.profile) -> {
                    stats.estimatedWalkMillis
                }

                way.timeMillis != null && way.totalMeters > 0 -> {
                    (way.timeMillis * remaining / way.totalMeters).roundToLong()
                }

                else -> {
                    null
                }
            }
        val end = way.points.last()
        val arrived = Geodesy.distance(lat, lon, end.lat, end.lon) <= ARRIVED_METERS
        val ahead = way.pointAt(p.alongMeters + LOOK_AHEAD_METERS) ?: end
        // Off the way, the arrow leads back to it; on it, along it.
        val off = p.offMeters - way.startOffMeters
        val towards = if (off > rules.offRouteMeters / 2) TrackPoint(p.nearestLat, p.nearestLon) else ahead
        return GoToProgress(
            offMeters = p.offMeters,
            alongMeters = p.alongMeters,
            remainingMeters = remaining,
            remainingAscentMeters = ascent,
            remainingMillis = millis,
            headingDegrees = bearing(lat, lon, towards.lat, towards.lon),
            offRoute = off > rules.offRouteMeters,
            arrived = arrived,
        )
    }

    /**
     * Whether BRouter is asked again now: never asked yet, the interval of [rules] elapsed since the last question
     * ([askedAtMillis]; also after a question that gave no way), or the walker left the way ([MIN_RETRY_MILLIS] at
     * least after the last question); never once arrived.
     */
    fun needsWay(
        way: GoToWay?,
        progress: GoToProgress?,
        askedAtMillis: Long?,
        nowMillis: Long,
        rules: GoToRules,
    ): Boolean {
        if (progress?.arrived == true) return false
        if (askedAtMillis == null) return true
        val since = nowMillis - askedAtMillis
        return when {
            since >= rules.intervalMillis -> true
            way != null && progress?.offRoute == true -> since >= MIN_RETRY_MILLIS
            else -> false
        }
    }

    /** `1 min 40`, `40 s`: the time left before the next computation of the way. */
    fun countdown(millis: Long): String {
        val seconds = ((millis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).coerceAtLeast(0)
        val m = seconds / SECONDS_PER_MINUTE
        val s = seconds % SECONDS_PER_MINUTE
        return when {
            m == 0L -> "$s s"
            s == 0L -> "$m min"
            else -> "$m min ${s.toString().padStart(2, '0')}"
        }
    }

    /** Initial bearing from a position to another, in degrees from the north (0 to 360). */
    fun bearing(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
    ): Double {
        val dx = (toLon - fromLon) * cos(Math.toRadians((fromLat + toLat) / 2))
        val dy = toLat - fromLat
        val degrees = Math.toDegrees(atan2(dx, dy))
        return (degrees + FULL_TURN) % FULL_TURN
    }

    private const val FULL_TURN = 360.0
    private const val MILLIS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60L
}
