package fr.toolmap.core.route

import fr.toolmap.core.track.Geodesy
import fr.toolmap.core.track.TrackPoint
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

/**
 * Elevations for the legs drawn by hand (`docs/specs/routing.md` §5.6). BRouter's legs carry the elevations of its own
 * data; a straight leg has none, so its points are given those of the terrain tiles, like the points of a track (P5.5).
 *
 * In a route that follows the ways, a straight leg is sampled every [SPACING_METERS] along its line (straight on the
 * map), so that a ridge or a valley between its two stops counts in the climb. A route wholly drawn by hand is stored as
 * its stops (ADR-065): only its stops take an elevation, and the figures of the editor stay those of the saved route.
 *
 * A leg whose points cannot all have an elevation (a tile missing offline) is left as it is: none rather than half.
 * What was found is kept by the ends of the leg, so that undo, redo or a leg turned round do not ask again. Not
 * thread-safe: used from the UI thread.
 */
class HandLegElevations(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    /** A straight line from one stop to the next; [dense] when it is sampled along, not just at its ends. */
    data class Key(
        val ends: LegKey,
        val dense: Boolean,
    ) {
        val reversed: Key
            get() = Key(LegKey(ends.toLat, ends.toLon, ends.fromLat, ends.fromLon, ends.profile), dense)
    }

    private val known = LinkedHashMap<Key, List<TrackPoint>>(16, 0.75f, true)
    private val failed = HashSet<Key>()

    /**
     * The legs of [plan] that need elevations and are not known yet, each with the points to give one to (no
     * elevation yet); a leg that failed before is not asked again until [forgetFailures].
     */
    fun wanted(plan: RoutePlan): Map<Key, List<TrackPoint>> {
        val out = LinkedHashMap<Key, List<TrackPoint>>()
        val dense = !plan.isManual
        plan.legs.forEachIndexed { i, leg ->
            if (!needs(leg, dense)) return@forEachIndexed
            val key = keyOf(plan, i, dense)
            if (key in out || key in failed || lookup(key) != null) return@forEachIndexed
            out[key] = if (dense) samples(plan.stops[i].point, plan.stops[i + 1].point) else ends(plan, i)
        }
        return out
    }

    /** The answer for [key]: kept when every point has an elevation, else the leg is given up. */
    fun put(
        key: Key,
        points: List<TrackPoint>,
    ) {
        if (points.size >= 2 && points.all { it.ele != null }) {
            failed -= key
            known[key] = points
            while (known.size > capacity) known.remove(known.keys.first())
        } else {
            failed += key
        }
    }

    /** Tiles may come later (online again, area downloaded): the legs given up may be asked again. */
    fun forgetFailures() = failed.clear()

    /** [plan] with the elevations known for its legs drawn by hand; the same instance when nothing changes. */
    fun apply(plan: RoutePlan): RoutePlan {
        val dense = !plan.isManual
        var changed = false
        val legs =
            plan.legs.mapIndexed { i, leg ->
                if (!needs(leg, dense)) return@mapIndexed leg
                val points = lookup(keyOf(plan, i, dense)) ?: return@mapIndexed leg
                changed = true
                leg.copy(points = points)
            }
        return if (changed) RoutePlan(plan.stops, legs) else plan
    }

    private fun lookup(key: Key): List<TrackPoint>? = known[key] ?: known[key.reversed]?.reversed()

    private fun keyOf(
        plan: RoutePlan,
        leg: Int,
        dense: Boolean,
    ): Key = Key(LegKey.of(plan.stops[leg], plan.stops[leg + 1], ""), dense)

    private fun ends(
        plan: RoutePlan,
        leg: Int,
    ): List<TrackPoint> = listOf(plan.stops[leg].point, plan.stops[leg + 1].point)

    companion object {
        /** Terrarium is a 30 m model (SRTM): closer samples would add nothing. */
        const val SPACING_METERS = 30.0

        /** A very long leg is sampled more loosely, so that it stays a few hundred points. */
        const val MAX_POINTS = 500

        private const val DEFAULT_CAPACITY = 256

        /**
         * A leg drawn by hand still lacking elevations: one of its points has none, or (when [dense]) it is still its two
         * ends only while its line is long enough to be sampled.
         */
        fun needs(
            leg: RouteLeg,
            dense: Boolean,
        ): Boolean {
            if (!leg.isByHand || leg.points.size < 2) return false
            if (leg.points.any { it.ele == null }) return true
            return dense && leg.points.size == 2 && sampleCount(leg.points.first(), leg.points.last()) > 2
        }

        /** How many points [samples] gives between [from] and [to], both included. */
        fun sampleCount(
            from: TrackPoint,
            to: TrackPoint,
        ): Int {
            val steps = ceil(Geodesy.distance(from, to) / SPACING_METERS).toInt().coerceIn(1, MAX_POINTS - 1)
            return steps + 1
        }

        /**
         * Points along the straight line from [from] to [to] as the map draws it (linear in Web Mercator), at most
         * [SPACING_METERS] apart; the ends are [from] and [to] themselves, without elevation.
         */
        fun samples(
            from: TrackPoint,
            to: TrackPoint,
        ): List<TrackPoint> {
            val n = sampleCount(from, to) - 1
            val y0 = mercatorY(from.lat)
            val y1 = mercatorY(to.lat)
            return (0..n).map { k ->
                when (k) {
                    0 -> {
                        TrackPoint(from.lat, from.lon)
                    }

                    n -> {
                        TrackPoint(to.lat, to.lon)
                    }

                    else -> {
                        val t = k.toDouble() / n
                        TrackPoint(latitudeOf(y0 + (y1 - y0) * t), from.lon + (to.lon - from.lon) * t)
                    }
                }
            }
        }

        /** Web Mercator northing on the unit sphere: only ratios matter here. */
        private fun mercatorY(lat: Double): Double = ln(tan(Math.PI / 4 + Math.toRadians(lat) / 2))

        private fun latitudeOf(y: Double): Double = Math.toDegrees(2 * atan(exp(y)) - Math.PI / 2)
    }
}
