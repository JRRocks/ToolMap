package fr.toolmap.core.track

/**
 * The points of a track's segments end to end, with the distance walked at each of them. The gap between two
 * segments (a GPS loss, a pause) is not counted as distance.
 */
class TrackPath(
    segments: List<List<TrackPoint>>,
) {
    val points: List<TrackPoint> = segments.flatten()

    /** Index in [points] of the first point of each segment. */
    val segmentStarts: List<Int>

    /** Meters from the start along the path, one per point. */
    val cumulative: DoubleArray

    init {
        val starts = ArrayList<Int>()
        var offset = 0
        for (s in segments) {
            if (s.isNotEmpty()) starts += offset
            offset += s.size
        }
        segmentStarts = starts
        val startSet = starts.toHashSet()
        cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] =
                cumulative[i - 1] + if (i in startSet) 0.0 else Geodesy.distance(points[i - 1], points[i])
        }
    }

    val length: Double get() = cumulative.lastOrNull() ?: 0.0

    /** Position at [distance] meters from the start (clamped to the track), interpolated between points. */
    fun interpolate(distance: Double): TrackPoint? {
        if (points.isEmpty()) return null
        if (distance <= 0.0) return points.first()
        if (distance >= length) return points.last()
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cumulative[mid] <= distance) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        val span = cumulative[hi] - cumulative[lo]
        if (span <= 0.0) return a
        val f = (distance - cumulative[lo]) / span
        val ele = if (a.ele != null && b.ele != null) a.ele + (b.ele - a.ele) * f else a.ele ?: b.ele
        return TrackPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, ele)
    }

    /** Index of the point closest to a position (flat-earth metric, good enough to pick a point under a finger). */
    fun nearestIndex(
        lat: Double,
        lon: Double,
    ): Int {
        if (points.isEmpty()) return -1
        val cosLat = Math.cos(Math.toRadians(lat))
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in points.indices) {
            val dy = points[i].lat - lat
            val dx = (points[i].lon - lon) * cosLat
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /** Distance along the path of the point nearest to a position. */
    fun distanceOfNearest(
        lat: Double,
        lon: Double,
    ): Double = nearestIndex(lat, lon).let { if (it < 0) 0.0 else cumulative[it] }

    companion object {
        /**
         * Median of [window] (odd) neighbours: removes the one-point spikes of GPS and barometric altitudes. The window
         * shrinks symmetrically at the ends, so the first and last values are kept as they are.
         */
        fun medianFilter(
            values: DoubleArray,
            window: Int,
        ): DoubleArray {
            if (window <= 1 || values.size < 3) return values.copyOf()
            val out = DoubleArray(values.size)
            val buffer = DoubleArray(window)
            for (i in values.indices) {
                val half = minOf(window / 2, i, values.size - 1 - i)
                val n = 2 * half + 1
                for (k in 0 until n) buffer[k] = values[i - half + k]
                java.util.Arrays.sort(buffer, 0, n)
                out[i] = buffer[half]
            }
            return out
        }
    }
}
