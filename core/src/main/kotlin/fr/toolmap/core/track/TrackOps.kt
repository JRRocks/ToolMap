package fr.toolmap.core.track

/**
 * Whole-track operations of the editor (reverse, split, merge, trim, thin out). Pure functions on lists of points:
 * nothing is stored here, the caller decides what to do with the results.
 */
object TrackOps {
    /**
     * The track walked the other way: segments and points in reverse order. Times are dropped: they would run
     * backwards, which breaks speeds and durations, and a reversed track was never recorded in that direction.
     */
    fun reverse(segments: List<List<TrackPoint>>): List<List<TrackPoint>> =
        segments.asReversed().map { s -> s.asReversed().map { it.copy(timeMillis = null) } }

    /** Every point on one segment, in order: closes the gaps between segments. */
    fun join(segments: List<List<TrackPoint>>): List<TrackPoint> = segments.flatten()

    /**
     * Cuts [points] at [index]: the first part ends with that point and the second starts with it, so that the two
     * tracks touch. Both parts have at least two points, so [index] must be strictly inside the line.
     */
    fun split(
        points: List<TrackPoint>,
        index: Int,
    ): Pair<List<TrackPoint>, List<TrackPoint>> {
        require(index in 1 until points.lastIndex) { "cannot split at $index of ${points.size} points" }
        return points.subList(0, index + 1).toList() to points.subList(index, points.size).toList()
    }

    /**
     * [second] after [first]. The two stay separate segments (a gap between them is not walked) unless the end of the
     * first and the start of the second are within [joinWithinMeters], in which case they become one segment and the
     * duplicated point is kept once.
     */
    fun merge(
        first: List<List<TrackPoint>>,
        second: List<List<TrackPoint>>,
        joinWithinMeters: Double = 0.0,
    ): List<List<TrackPoint>> {
        val a = first.filter { it.isNotEmpty() }
        val b = second.filter { it.isNotEmpty() }
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val end = a.last().last()
        val start = b.first().first()
        if (joinWithinMeters > 0.0 && Geodesy.distance(end, start) <= joinWithinMeters) {
            val joined = a.last() + b.first().drop(if (end.lat == start.lat && end.lon == start.lon) 1 else 0)
            return a.dropLast(1) + listOf(joined) + b.drop(1)
        }
        return a + b
    }

    /** The points from [from] to [to], both included (the ends are cropped away). */
    fun trim(
        points: List<TrackPoint>,
        from: Int,
        to: Int,
    ): List<TrackPoint> {
        require(from in points.indices && to in points.indices && from <= to) {
            "bad range $from..$to of ${points.size}"
        }
        return points.subList(from, to + 1).toList()
    }

    /** Douglas-Peucker thinning, ends kept; points that carry times or extensions are not special. */
    fun simplify(
        points: List<TrackPoint>,
        toleranceMeters: Double,
    ): List<TrackPoint> = PathSimplifier.simplify(points, toleranceMeters)

    /** Point halfway between two others: positions, elevations and times averaged when both sides have them. */
    fun midpoint(
        a: TrackPoint,
        b: TrackPoint,
    ): TrackPoint =
        TrackPoint(
            lat = (a.lat + b.lat) / 2,
            lon = (a.lon + b.lon) / 2,
            ele = if (a.ele != null && b.ele != null) (a.ele + b.ele) / 2 else null,
            timeMillis = if (a.timeMillis != null && b.timeMillis != null) (a.timeMillis + b.timeMillis) / 2 else null,
        )

    /** Length of a polyline in meters. */
    fun length(points: List<TrackPoint>): Double {
        var total = 0.0
        for (i in 1 until points.size) total += Geodesy.distance(points[i - 1], points[i])
        return total
    }
}
