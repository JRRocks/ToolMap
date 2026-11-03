package fr.toolmap.core.sketch

import fr.toolmap.core.track.PathSimplifier
import kotlin.math.abs
import kotlin.math.acos

/**
 * Turns the raw points of a finger stroke into a few cubic Béziers (ADR-110): Douglas-Peucker finds the sharp corners,
 * which are kept, and each piece between two corners is fitted by Schneider's method (Graphics Gems, 1990), so that
 * no raw point is farther than the tolerance from the curve. A larger tolerance gives fewer, smoother curves.
 */
object SketchSmoother {
    /** A turn sharper than this, between two Douglas-Peucker legs, is a corner the curve keeps. */
    private const val CORNER_DEGREES = 70.0
    private const val MAX_REPARAMETERIZE = 4
    private const val MAX_DEPTH = 48

    fun fit(
        raw: List<XY>,
        tolerance: Double,
    ): SketchPath {
        require(raw.isNotEmpty()) { "Empty stroke" }
        require(tolerance > 0.0) { "Tolerance must be positive" }
        val points = dedupe(raw, tolerance / 8)
        if (points.size == 1) return SketchPath(points[0])
        val curves = ArrayList<Cubic>()
        val breaks = corners(points, tolerance)
        for (k in 0 until breaks.lastIndex) {
            val first = breaks[k]
            val last = breaks[k + 1]
            val t1 = (points[first + 1] - points[first]).normalized()
            val t2 = (points[last - 1] - points[last]).normalized()
            fitCubic(points, first, last, t1, t2, tolerance * tolerance, curves, 0)
        }
        return SketchPath(points[0], curves)
    }

    /**
     * Drops points closer than [minStep] to the last kept one; the last raw point ends the stroke, unless the whole
     * stroke stays within [minStep] of its start (a tap: a dot).
     */
    private fun dedupe(
        raw: List<XY>,
        minStep: Double,
    ): List<XY> {
        val out = ArrayList<XY>()
        out += raw[0]
        for (i in 1 until raw.size) {
            if (raw[i].distanceTo(out.last()) >= minStep) out += raw[i]
        }
        val last = raw.last()
        if (out.size > 1) out[out.lastIndex] = last
        return out
    }

    /** Indices where the stroke is cut: the ends and every sharp corner. */
    private fun corners(
        points: List<XY>,
        tolerance: Double,
    ): List<Int> {
        val keep =
            PathSimplifier.keptIndices(
                DoubleArray(points.size) { points[it].x },
                DoubleArray(points.size) { points[it].y },
                tolerance,
            )
        val skeleton = points.indices.filter { keep[it] }
        val out = arrayListOf(0)
        for (k in 1 until skeleton.lastIndex) {
            val i = skeleton[k]
            val a = (points[i] - points[skeleton[k - 1]]).normalized()
            val b = (points[skeleton[k + 1]] - points[i]).normalized()
            val turn = Math.toDegrees(acos((a dot b).coerceIn(-1.0, 1.0)))
            if (turn > CORNER_DEGREES && i - out.last() >= 1) out += i
        }
        out += points.lastIndex
        return out
    }

    private fun fitCubic(
        d: List<XY>,
        first: Int,
        last: Int,
        t1: XY,
        t2: XY,
        errorSq: Double,
        out: MutableList<Cubic>,
        depth: Int,
    ) {
        if (last - first == 1 || depth >= MAX_DEPTH) {
            val dist = d[last].distanceTo(d[first]) / 3
            out += Cubic(d[first] + t1 * dist, d[last] + t2 * dist, d[last])
            return
        }
        var u = chordLength(d, first, last)
        var bez = generate(d, first, last, u, t1, t2)
        var (maxError, split) = maxError(d, first, last, bez, u)
        if (maxError < errorSq) {
            out += Cubic(bez[1], bez[2], bez[3])
            return
        }
        if (maxError < errorSq * 4) {
            repeat(MAX_REPARAMETERIZE) {
                u = reparameterize(d, first, last, u, bez)
                bez = generate(d, first, last, u, t1, t2)
                val (e, s) = maxError(d, first, last, bez, u)
                if (e < errorSq) {
                    out += Cubic(bez[1], bez[2], bez[3])
                    return
                }
                maxError = e
                split = s
            }
        }
        val center = (d[split - 1] - d[split + 1]).normalized().let { if (it.length() == 0.0) t1 * -1.0 else it }
        fitCubic(d, first, split, t1, center, errorSq, out, depth + 1)
        fitCubic(d, split, last, center * -1.0, t2, errorSq, out, depth + 1)
    }

    private fun chordLength(
        d: List<XY>,
        first: Int,
        last: Int,
    ): DoubleArray {
        val u = DoubleArray(last - first + 1)
        for (i in first + 1..last) u[i - first] = u[i - first - 1] + d[i].distanceTo(d[i - 1])
        val total = u.last()
        if (total > 0) for (i in u.indices) u[i] /= total
        return u
    }

    /** Least-squares control points along the end tangents (Schneider's GenerateBezier). */
    private fun generate(
        d: List<XY>,
        first: Int,
        last: Int,
        u: DoubleArray,
        t1: XY,
        t2: XY,
    ): Array<XY> {
        val p0 = d[first]
        val p3 = d[last]
        var c00 = 0.0
        var c01 = 0.0
        var c11 = 0.0
        var x0 = 0.0
        var x1 = 0.0
        for (i in u.indices) {
            val t = u[i]
            val mt = 1 - t
            val b0 = mt * mt * mt
            val b1 = 3 * t * mt * mt
            val b2 = 3 * t * t * mt
            val b3 = t * t * t
            val a1 = t1 * b1
            val a2 = t2 * b2
            c00 += a1 dot a1
            c01 += a1 dot a2
            c11 += a2 dot a2
            val tmp = d[first + i] - (p0 * (b0 + b1) + p3 * (b2 + b3))
            x0 += a1 dot tmp
            x1 += a2 dot tmp
        }
        val det = c00 * c11 - c01 * c01
        val segLength = p3.distanceTo(p0)
        val epsilon = 1e-6 * segLength
        val alphaL = if (abs(det) < 1e-12) 0.0 else (x0 * c11 - x1 * c01) / det
        val alphaR = if (abs(det) < 1e-12) 0.0 else (c00 * x1 - c01 * x0) / det
        if (alphaL < epsilon || alphaR < epsilon) {
            val dist = segLength / 3
            return arrayOf(p0, p0 + t1 * dist, p3 + t2 * dist, p3)
        }
        return arrayOf(p0, p0 + t1 * alphaL, p3 + t2 * alphaR, p3)
    }

    private fun maxError(
        d: List<XY>,
        first: Int,
        last: Int,
        bez: Array<XY>,
        u: DoubleArray,
    ): Pair<Double, Int> {
        var max = 0.0
        var split = (first + last) / 2
        for (i in first + 1 until last) {
            val v = at(bez, u[i - first]) - d[i]
            val e = v dot v
            if (e >= max) {
                max = e
                split = i
            }
        }
        return max to split
    }

    /** One Newton-Raphson step per point toward the nearest parameter on the curve. */
    private fun reparameterize(
        d: List<XY>,
        first: Int,
        last: Int,
        u: DoubleArray,
        bez: Array<XY>,
    ): DoubleArray {
        val q1 = Array(3) { (bez[it + 1] - bez[it]) * 3.0 }
        val q2 = Array(2) { (q1[it + 1] - q1[it]) * 2.0 }
        return DoubleArray(u.size) { k ->
            val t = u[k]
            val diff = at(bez, t) - d[first + k]
            val d1 = quadratic(q1, t)
            val d2 = q2[0] * (1 - t) + q2[1] * t
            val denominator = (d1 dot d1) + (diff dot d2)
            if (denominator == 0.0) t else (t - (diff dot d1) / denominator).coerceIn(0.0, 1.0)
        }
    }

    private fun at(
        b: Array<XY>,
        t: Double,
    ): XY {
        val mt = 1 - t
        return b[0] * (mt * mt * mt) + b[1] * (3 * mt * mt * t) + b[2] * (3 * mt * t * t) + b[3] * (t * t * t)
    }

    private fun quadratic(
        q: Array<XY>,
        t: Double,
    ): XY {
        val mt = 1 - t
        return q[0] * (mt * mt) + q[1] * (2 * mt * t) + q[2] * (t * t)
    }
}
