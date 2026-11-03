package fr.toolmap.core.sketch

import kotlin.math.sqrt

/**
 * The partial eraser of P28: it works on raw points, before smoothing. Every part of a stroke inside the eraser disc
 * goes away, including a segment that only crosses it (a fast finger leaves sparse points), and the stroke is cut
 * into the pieces left on each side. Each piece is smoothed on its own when the sketch is saved.
 */
object SketchEraser {
    fun erase(
        strokes: List<RawStroke>,
        center: XY,
        radius: Double,
    ): List<RawStroke> = strokes.flatMap { s -> cut(s.points, center, radius).map { RawStroke(s.style, it) } }

    /** The pieces of [points] left outside the disc; a lone point (a dot) stays or goes whole. */
    fun cut(
        points: List<XY>,
        center: XY,
        radius: Double,
    ): List<List<XY>> {
        if (points.size == 1) return if (points[0].distanceTo(center) <= radius) emptyList() else listOf(points)
        val pieces = ArrayList<MutableList<XY>>()
        var open = false
        for (i in 0 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            val ranges = outside(a, b, center, radius)
            for ((s, e) in ranges) {
                val p = if (s == 0.0) a else a + (b - a) * s
                val q = if (e == 1.0) b else a + (b - a) * e
                if (s == 0.0 && open) {
                    pieces.last() += q
                } else {
                    pieces += mutableListOf(p, q)
                }
            }
            open = ranges.lastOrNull()?.second == 1.0
        }
        return pieces.filter { piece -> piece.zipWithNext().any { (p, q) -> p != q } }
    }

    /** The parameter ranges of segment [a]-[b] that lie outside the disc. */
    private fun outside(
        a: XY,
        b: XY,
        center: XY,
        radius: Double,
    ): List<Pair<Double, Double>> {
        val d = b - a
        val f = a - center
        val qa = d dot d
        val qb = 2 * (f dot d)
        val qc = (f dot f) - radius * radius
        if (qa == 0.0) return if (qc > 0) listOf(0.0 to 1.0) else emptyList()
        val discriminant = qb * qb - 4 * qa * qc
        if (discriminant <= 0) return listOf(0.0 to 1.0)
        val root = sqrt(discriminant)
        val tIn = (-qb - root) / (2 * qa)
        val tOut = (-qb + root) / (2 * qa)
        if (tOut <= 0.0 || tIn >= 1.0) return listOf(0.0 to 1.0)
        val out = ArrayList<Pair<Double, Double>>(2)
        if (tIn > 0.0) out += 0.0 to tIn
        if (tOut < 1.0) out += tOut to 1.0
        return out
    }
}
