package fr.toolmap.core.track

import kotlin.math.cos
import kotlin.math.sqrt

/** Douglas-Peucker simplification, iterative so that a 100 000-point track cannot overflow the stack. */
object PathSimplifier {
    private const val METERS_PER_DEGREE = Math.PI * Geodesy.EARTH_RADIUS_METERS / 180.0

    /**
     * Keeps the first and last points and every point that sticks out of the line by more than [toleranceMeters]
     * (measured on a flat projection around the track, accurate at the scale of a track).
     */
    fun simplify(
        points: List<TrackPoint>,
        toleranceMeters: Double,
    ): List<TrackPoint> {
        if (points.size < 3 || toleranceMeters <= 0.0) return points
        val cosLat = cos(Math.toRadians(points[points.size / 2].lat))
        val xs = DoubleArray(points.size) { points[it].lon * METERS_PER_DEGREE * cosLat }
        val ys = DoubleArray(points.size) { points[it].lat * METERS_PER_DEGREE }
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            var farthest = -1
            var farthestDistance = toleranceMeters
            for (i in first + 1 until last) {
                val d = distanceToSegment(xs[i], ys[i], xs[first], ys[first], xs[last], ys[last])
                if (d > farthestDistance) {
                    farthestDistance = d
                    farthest = i
                }
            }
            if (farthest >= 0) {
                keep[farthest] = true
                stack.addLast(intArrayOf(first, farthest))
                stack.addLast(intArrayOf(farthest, last))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun distanceToSegment(
        px: Double,
        py: Double,
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
    ): Double {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
        val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }
}
