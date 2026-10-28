package fr.toolmap.core.route

import fr.toolmap.core.track.Geodesy
import fr.toolmap.core.track.TrackPoint
import java.util.Locale

/**
 * A circle the routed legs must not enter (« Zone à éviter », P19.8, `docs/specs/routing.md` §5.8): one of the `nogos`
 * of BRouter. Legs drawn by hand are not bound by it.
 */
data class AvoidZone(
    val lat: Double,
    val lon: Double,
    val radiusMeters: Int,
) {
    init {
        require(radiusMeters > 0) { "radius $radiusMeters" }
    }

    fun contains(
        lat: Double,
        lon: Double,
    ): Boolean = Geodesy.distance(this.lat, this.lon, lat, lon) <= radiusMeters

    /** The line [points] passes through the circle. */
    fun crosses(points: List<TrackPoint>): Boolean {
        if (points.size == 1) return contains(points[0].lat, points[0].lon)
        for (j in 1 until points.size) {
            if (RoutePlan.distanceToSegment(lat, lon, points[j - 1], points[j]) <= radiusMeters) return true
        }
        return false
    }

    /** [other] lies wholly inside this circle: a way that kept out of this one kept out of [other] too. */
    fun covers(other: AvoidZone): Boolean =
        Geodesy.distance(lat, lon, other.lat, other.lon) + other.radiusMeters <= radiusMeters

    companion object {
        /**
         * The [zones] a leg from [from] to [to] can go round: one that holds an end of the leg is left out, since BRouter
         * finds no way out of it (« pas de chemin », checked with BRouter 1.7.10) and the leg has to reach its stop.
         */
        fun around(
            zones: List<AvoidZone>,
            from: RouteStop,
            to: RouteStop,
        ): List<AvoidZone> = zones.filter { !it.contains(from.lat, from.lon) && !it.contains(to.lat, to.lon) }

        /** The `nogos` of BRouter: `lon,lat,radius` (radius in whole meters) joined by `|`; empty without zone. */
        fun nogos(zones: List<AvoidZone>): String =
            zones.joinToString("|") {
                String.format(Locale.ROOT, "%.7f,%.7f,%d", it.lon, it.lat, it.radiusMeters)
            }
    }
}
