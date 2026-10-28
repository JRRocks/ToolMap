package fr.toolmap.core.route

import fr.toolmap.core.track.Geodesy
import fr.toolmap.core.track.TrackPoint
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** A point placed by the user: a discreet passage that forces the way, or a named stop (name and waypoint icon). */
enum class StopKind { PASSAGE, NAMED }

/**
 * A point of interest of a route (P30.1, variant B of `docs/previews/p30-itineraires.html`): a name and a waypoint icon
 * at a place along the route or beside it (a spring 80 m off the path). Unlike a named stop it does not change the way.
 */
data class RoutePoi(
    val lat: Double,
    val lon: Double,
    val name: String? = null,
    /** GPX symbol: its waypoint icon. */
    val sym: String? = null,
) {
    val point: TrackPoint get() = TrackPoint(lat, lon)
}

/** Where a point of interest stands along a route: [alongMeters] from the start, [offMeters] beside the way. */
data class PoiPlace(
    val alongMeters: Double,
    val offMeters: Double,
)

data class RouteStop(
    val lat: Double,
    val lon: Double,
    val kind: StopKind = StopKind.PASSAGE,
    val name: String? = null,
    /** GPX symbol of a named stop: its waypoint icon. */
    val sym: String? = null,
) {
    val point: TrackPoint get() = TrackPoint(lat, lon)

    /** The same stop as a passage: no name, no icon. */
    fun discreet(): RouteStop = copy(kind = StopKind.PASSAGE, name = null, sym = null)
}

/** How a leg is drawn: by BRouter along the ways, or by hand as a straight line. */
enum class LegMode { ROUTED, STRAIGHT }

/**
 * Where a leg stands. [PENDING]: BRouter is asked (a straight line is shown meanwhile). [NO_DATA] and [FAILED]: BRouter
 * could not give a way (no data for the area, no path, time out), the leg is a straight line until recomputed.
 */
enum class LegState { OK, PENDING, NO_DATA, FAILED }

/** The way between two consecutive stops. [points] start exactly on the first stop and end exactly on the second. */
data class RouteLeg(
    val profile: String,
    val mode: LegMode,
    val state: LegState,
    val points: List<TrackPoint>,
    /** Travel time given by BRouter; `null` for a leg drawn by hand. */
    val timeMillis: Long? = null,
) {
    /** Drawn as a straight line: by choice, or because BRouter gave no way. */
    val isByHand: Boolean get() = mode == LegMode.STRAIGHT || state == LegState.NO_DATA || state == LegState.FAILED

    companion object {
        fun straight(
            from: RouteStop,
            to: RouteStop,
            profile: String,
            mode: LegMode = LegMode.STRAIGHT,
            state: LegState = LegState.OK,
        ): RouteLeg = RouteLeg(profile, mode, state, listOf(from.point, to.point))
    }
}

/**
 * What identifies the computation of a leg: its ends to a millionth of a degree (about 10 cm), the profile and the zones
 * it goes round (the `nogos` sent to BRouter, empty without zone; [AvoidZone.around]).
 */
data class LegKey(
    val fromLat: Long,
    val fromLon: Long,
    val toLat: Long,
    val toLon: Long,
    val profile: String,
    val nogos: String = "",
) {
    companion object {
        private const val SCALE = 1e6

        fun of(
            from: RouteStop,
            to: RouteStop,
            profile: String,
            zones: List<AvoidZone> = emptyList(),
        ): LegKey =
            LegKey(
                (from.lat * SCALE).roundToLong(),
                (from.lon * SCALE).roundToLong(),
                (to.lat * SCALE).roundToLong(),
                (to.lon * SCALE).roundToLong(),
                profile,
                AvoidZone.nogos(AvoidZone.around(zones, from, to)),
            )
    }
}

/**
 * The stops the user placed and, between two consecutive ones, a leg: what a computed route is made from, with the
 * [zones] its routed legs go round. Its [geometry] (the legs put end to end) is what is drawn, followed, printed and
 * exported.
 */
data class RoutePlan(
    val stops: List<RouteStop> = emptyList(),
    val legs: List<RouteLeg> = emptyList(),
    val zones: List<AvoidZone> = emptyList(),
    /** The points of interest of the route (P30.1): never sent to BRouter, they do not change the way. */
    val pois: List<RoutePoi> = emptyList(),
) {
    init {
        require(legs.size == maxOf(0, stops.size - 1)) { "${stops.size} stops need ${stops.size - 1} legs" }
    }

    /** The same plan with other stops, legs and zones: its points of interest stay. */
    fun with(
        stops: List<RouteStop>,
        legs: List<RouteLeg>,
        zones: List<AvoidZone> = this.zones,
    ): RoutePlan = RoutePlan(stops, legs, zones, pois)

    /** The legs end to end; the point shared by two legs is kept once. One stop alone is a single point. */
    val geometry: List<TrackPoint> by lazy {
        when {
            stops.isEmpty() -> {
                emptyList()
            }

            legs.isEmpty() -> {
                listOf(stops[0].point)
            }

            else -> {
                val out = ArrayList<TrackPoint>(legs.sumOf { it.points.size })
                legs.forEachIndexed { i, leg -> out.addAll(if (i == 0) leg.points else leg.points.drop(1)) }
                out
            }
        }
    }

    /** Index in [geometry] of every stop. */
    val stopIndices: List<Int> by lazy {
        val out = ArrayList<Int>(stops.size)
        var at = 0
        stops.indices.forEach { i ->
            out += at
            if (i < legs.size) at += legs[i].points.size - 1
        }
        out
    }

    fun key(leg: Int): LegKey = LegKey.of(stops[leg], stops[leg + 1], legs[leg].profile, zones)

    /** The zones leg [leg] goes round: those that hold neither of its stops (P19.8). */
    fun legZones(leg: Int): List<AvoidZone> = AvoidZone.around(zones, stops[leg], stops[leg + 1])

    /** Legs BRouter still has to compute, in order. */
    val pending: List<Int> get() = legs.indices.filter { legs[it].state == LegState.PENDING }

    /** Every leg drawn by hand by choice: a route drawn as before, with no routing in it. */
    val isManual: Boolean get() = legs.all { it.mode == LegMode.STRAIGHT }

    /** Distance from the start to each stop, along the geometry. */
    fun stopDistances(): List<Double> {
        var total = 0.0
        val out = ArrayList<Double>(stops.size)
        stops.indices.forEach { i ->
            out += total
            if (i < legs.size) {
                val p = legs[i].points
                for (j in 1 until p.size) total += Geodesy.distance(p[j - 1], p[j])
            }
        }
        return out
    }

    /** The leg whose line passes nearest to a position, with that distance in meters; `null` without legs. */
    fun nearestLeg(
        lat: Double,
        lon: Double,
    ): Pair<Int, Double>? {
        var bestLeg = -1
        var bestDistance = Double.MAX_VALUE
        legs.forEachIndexed { i, leg ->
            val p = leg.points
            for (j in 1 until p.size) {
                val d = distanceToSegment(lat, lon, p[j - 1], p[j])
                if (d < bestDistance) {
                    bestDistance = d
                    bestLeg = i
                }
            }
        }
        return if (bestLeg < 0) null else bestLeg to bestDistance
    }

    /** Index of the stop within [maxMeters] of a position, nearest first; `null` when none is that close. */
    fun nearestStop(
        lat: Double,
        lon: Double,
        maxMeters: Double,
    ): Int? {
        var best: Int? = null
        var bestDistance = maxMeters
        stops.forEachIndexed { i, s ->
            val d = Geodesy.distance(lat, lon, s.lat, s.lon)
            if (d <= bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    /** Index of the point of interest within [maxMeters] of a position, nearest first; `null` when none is that close. */
    fun nearestPoi(
        lat: Double,
        lon: Double,
        maxMeters: Double,
    ): Int? {
        var best: Int? = null
        var bestDistance = maxMeters
        pois.forEachIndexed { i, p ->
            val d = Geodesy.distance(lat, lon, p.lat, p.lon)
            if (d <= bestDistance) {
                best = i
                bestDistance = d
            }
        }
        return best
    }

    /** Where point of interest [index] stands along the geometry; `null` without a line (fewer than two points). */
    fun placeOf(index: Int): PoiPlace? {
        val poi = pois.getOrNull(index) ?: return null
        val g = geometry
        if (g.size < 2) return null
        var along = 0.0
        var bestAlong = 0.0
        var bestOff = Double.MAX_VALUE
        for (j in 1 until g.size) {
            val a = g[j - 1]
            val b = g[j]
            val length = Geodesy.distance(a.lat, a.lon, b.lat, b.lon)
            val t = fractionOnSegment(poi.lat, poi.lon, a, b)
            val off = distanceToSegment(poi.lat, poi.lon, a, b)
            if (off < bestOff) {
                bestOff = off
                bestAlong = along + t * length
            }
            along += length
        }
        return PoiPlace(bestAlong, bestOff)
    }

    /** The zone a position lies in, the one whose centre is nearest when several overlap; `null` outside them all. */
    fun zoneAt(
        lat: Double,
        lon: Double,
    ): Int? =
        zones.indices
            .filter { zones[it].contains(lat, lon) }
            .minByOrNull { Geodesy.distance(lat, lon, zones[it].lat, zones[it].lon) }

    /** A loop: the last stop is back on the first. */
    val isLoop: Boolean
        get() =
            stops.size >= 3 &&
                Geodesy.distance(stops.first().lat, stops.first().lon, stops.last().lat, stops.last().lon) < LOOP_METERS

    companion object {
        /** Ends closer than this make a loop. */
        const val LOOP_METERS = 1.0

        /** Distance from a position to a short segment, on a local flat projection (exact enough within a few km). */
        internal fun distanceToSegment(
            lat: Double,
            lon: Double,
            a: TrackPoint,
            b: TrackPoint,
        ): Double {
            val k = Math.toRadians(1.0) * Geodesy.EARTH_RADIUS_METERS
            val c = cos(Math.toRadians(lat))
            val ax = (a.lon - lon) * c * k
            val ay = (a.lat - lat) * k
            val bx = (b.lon - lon) * c * k
            val by = (b.lat - lat) * k
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val x = ax + t * dx
            val y = ay + t * dy
            return sqrt(x * x + y * y)
        }

        /** Where the point of segment [a]–[b] nearest a position falls, from 0 at [a] to 1 at [b] (same projection). */
        internal fun fractionOnSegment(
            lat: Double,
            lon: Double,
            a: TrackPoint,
            b: TrackPoint,
        ): Double {
            val c = cos(Math.toRadians(lat))
            val ax = (a.lon - lon) * c
            val ay = a.lat - lat
            val dx = (b.lon - a.lon) * c
            val dy = b.lat - a.lat
            val len2 = dx * dx + dy * dy
            return if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        }
    }
}
