package fr.toolmap.core.alert

import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.track.Geodesy
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/** Where a position stands against a followed line. */
data class Projection(
    /** Distance from the position to the nearest point of the line, in meters. */
    val offMeters: Double,
    /** Distance walked along the line up to that nearest point, in meters. */
    val alongMeters: Double,
    /** Index of the segment the nearest point lies on. */
    val segment: Int,
    val nearestLat: Double,
    val nearestLon: Double,
)

/**
 * A line to follow: how far a position is from it and how far along it the position is. Distances to the line use a
 * flat projection centred on the position (accurate to well under a meter within a few kilometers, which is all that
 * matters for an alert at tens of meters); distances along the line are geodesic.
 *
 * A line that comes back near itself (an out-and-back, a loop) is ambiguous: the nearest point may be on the way out or
 * on the way back. [project] takes the progress made so far as a hint and, among the candidates about as near as the
 * best one (and on another stretch of the line, not on the next segment), prefers the one closest to the hint.
 */
class TrackFollower(
    points: List<LonLat>,
    /** Follow the line from its last point to its first. */
    val reversed: Boolean = false,
) {
    private val line: List<LonLat> = dedupe(if (reversed) points.asReversed() else points)

    /** Distance along the line at each vertex. */
    private val cumulative: DoubleArray =
        DoubleArray(line.size).also { out ->
            for (i in 1 until line.size) {
                out[i] = out[i - 1] + Geodesy.distance(line[i - 1].lat, line[i - 1].lon, line[i].lat, line[i].lon)
            }
        }

    val totalMeters: Double get() = cumulative.lastOrNull() ?: 0.0

    /** Whether there is a line at all: at least two distinct points. */
    val usable: Boolean get() = line.size >= 2

    val start: LonLat? get() = line.firstOrNull()
    val end: LonLat? get() = line.lastOrNull()

    /** The same line, followed the other way. */
    fun reversedCopy(): TrackFollower = TrackFollower(line, reversed = !reversed)

    /** `null` when the line is unusable. [hintAlongMeters] is the progress known so far, to settle ambiguities. */
    fun project(
        lat: Double,
        lon: Double,
        hintAlongMeters: Double? = null,
    ): Projection? {
        if (!usable) return null
        var best: Candidate? = null
        for (i in 0 until line.size - 1) {
            val c = candidate(i, lat, lon)
            if (best == null || c.off < best.off) best = c
        }
        val nearest = best ?: return null
        var chosen = nearest
        if (hintAlongMeters != null) {
            // Another branch of the line (not the next segment of the same stretch) about as near as the best one.
            val branchGap = 2 * (nearest.off + AMBIGUITY_METERS) + BRANCH_MARGIN_METERS
            for (i in 0 until line.size - 1) {
                val c = candidate(i, lat, lon)
                if (c.off <= nearest.off + AMBIGUITY_METERS &&
                    abs(c.along - nearest.along) > branchGap &&
                    abs(c.along - hintAlongMeters) < abs(chosen.along - hintAlongMeters)
                ) {
                    chosen = c
                }
            }
        }
        return Projection(chosen.off, chosen.along, chosen.segment, chosen.lat, chosen.lon)
    }

    private class Candidate(
        val off: Double,
        val along: Double,
        val segment: Int,
        val lat: Double,
        val lon: Double,
    )

    private fun candidate(
        index: Int,
        lat: Double,
        lon: Double,
    ): Candidate {
        val a = line[index]
        val b = line[index + 1]
        val kx = cos(Math.toRadians(lat)) * METERS_PER_DEGREE
        val ax = (a.lon - lon) * kx
        val ay = (a.lat - lat) * METERS_PER_DEGREE
        val bx = (b.lon - lon) * kx
        val by = (b.lat - lat) * METERS_PER_DEGREE
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared <= 0.0) 0.0 else ((-ax * dx - ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val off = hypot(ax + t * dx, ay + t * dy)
        val along = cumulative[index] + t * (cumulative[index + 1] - cumulative[index])
        return Candidate(off, along, index, a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
    }

    private companion object {
        const val METERS_PER_DEGREE = 111_194.9266
        const val AMBIGUITY_METERS = 15.0
        const val BRANCH_MARGIN_METERS = 20.0

        /** Consecutive identical points make zero-length segments that carry no direction. */
        fun dedupe(points: List<LonLat>): List<LonLat> {
            val out = ArrayList<LonLat>(points.size)
            for (p in points) if (out.isEmpty() || out.last() != p) out += p
            return out
        }
    }
}
