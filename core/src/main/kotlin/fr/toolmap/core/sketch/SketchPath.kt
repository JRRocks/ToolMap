package fr.toolmap.core.sketch

import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.tiles.TileMath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.hypot

/**
 * A point of a sketch in map coordinates: EPSG:3857 meters, y pointing south as in SVG (y = -northing), so that a
 * sketch is a plain SVG path in meters, north up.
 */
data class XY(
    val x: Double,
    val y: Double,
) {
    operator fun plus(o: XY) = XY(x + o.x, y + o.y)

    operator fun minus(o: XY) = XY(x - o.x, y - o.y)

    operator fun times(k: Double) = XY(x * k, y * k)

    infix fun dot(o: XY) = x * o.x + y * o.y

    fun length() = hypot(x, y)

    fun normalized(): XY {
        val l = length()
        return if (l == 0.0) this else XY(x / l, y / l)
    }

    fun distanceTo(o: XY) = hypot(x - o.x, y - o.y)

    fun toLonLat(): LonLat =
        LonLat(
            lon = Math.toDegrees(x / TileMath.EARTH_RADIUS_M),
            lat = Math.toDegrees(2 * atan(exp(-y / TileMath.EARTH_RADIUS_M)) - PI / 2),
        )

    companion object {
        fun of(position: LonLat) = XY(TileMath.mercatorX(position.lon), -TileMath.mercatorY(position.lat))
    }
}

/** A cubic Bézier piece that starts where the previous one ends. */
data class Cubic(
    val c1: XY,
    val c2: XY,
    val end: XY,
)

/**
 * A smoothed stroke: a start point and the cubic pieces that follow it (none for a dot). Written as an SVG path
 * (`M x y c …`), in meters to the decimeter.
 */
data class SketchPath(
    val start: XY,
    val curves: List<Cubic> = emptyList(),
) {
    val end: XY get() = curves.lastOrNull()?.end ?: start

    /** The SVG `d` attribute: absolute start, then relative cubics, numbers in decimeters written as meters. */
    fun toSvg(): String {
        val sb = StringBuilder("M")
        var cx = tenths(start.x)
        var cy = tenths(start.y)
        sb.append(meters(cx)).append(' ').append(meters(cy))
        for (c in curves) {
            sb.append(" c")
            for (p in listOf(c.c1, c.c2, c.end)) {
                sb.append(' ').append(meters(tenths(p.x) - cx)).append(' ').append(meters(tenths(p.y) - cy))
            }
            cx = tenths(c.end.x)
            cy = tenths(c.end.y)
        }
        return sb.toString()
    }

    /** The stroke as a polyline: each cubic split until it is within [tolerance] meters of its chord. */
    fun sample(tolerance: Double): List<XY> {
        val out = ArrayList<XY>()
        out += start
        var from = start
        for (c in curves) {
            flatten(from, c.c1, c.c2, c.end, tolerance, 0, out)
            from = c.end
        }
        return out
    }

    /** The vertices of a path made by [through]: the start and the end of each side, the closing side left out. */
    fun vertices(closed: Boolean): List<XY> {
        val ends = curves.map { it.end }
        return listOf(start) + if (closed && ends.isNotEmpty()) ends.dropLast(1) else ends
    }

    companion object {
        private const val MAX_FLATTEN_DEPTH = 16

        /** Straight sides through [points] (a shape, P28.4), back to the first one when [closed]. */
        fun through(
            points: List<XY>,
            closed: Boolean,
        ): SketchPath {
            require(points.isNotEmpty()) { "A shape has at least one point" }
            val ring = if (closed && points.size > 2) points + points[0] else points
            return SketchPath(
                ring[0],
                ring.zipWithNext { a, b -> Cubic(a + (b - a) * (1.0 / 3), a + (b - a) * (2.0 / 3), b) },
            )
        }

        private fun tenths(v: Double): Long = Math.round(v * 10)

        private fun meters(t: Long): String {
            val a = abs(t)
            val s = if (a % 10 == 0L) "${a / 10}" else "${a / 10}.${a % 10}"
            return if (t < 0) "-$s" else s
        }

        private fun flatten(
            p0: XY,
            p1: XY,
            p2: XY,
            p3: XY,
            tolerance: Double,
            depth: Int,
            out: MutableList<XY>,
        ) {
            if (depth >= MAX_FLATTEN_DEPTH ||
                maxOf(distanceToLine(p1, p0, p3), distanceToLine(p2, p0, p3)) <= tolerance
            ) {
                out += p3
                return
            }
            val p01 = (p0 + p1) * 0.5
            val p12 = (p1 + p2) * 0.5
            val p23 = (p2 + p3) * 0.5
            val a = (p01 + p12) * 0.5
            val b = (p12 + p23) * 0.5
            val mid = (a + b) * 0.5
            flatten(p0, p01, a, mid, tolerance, depth + 1, out)
            flatten(mid, b, p23, p3, tolerance, depth + 1, out)
        }

        private fun distanceToLine(
            p: XY,
            a: XY,
            b: XY,
        ): Double {
            val d = b - a
            val l = d.length()
            if (l == 0.0) return p.distanceTo(a)
            return abs((p.x - a.x) * d.y - (p.y - a.y) * d.x) / l
        }

        /**
         * Reads a path written by [toSvg]; also accepts absolute `M L C` and relative `m l c` (a line becomes a
         * straight cubic). Anything else is refused.
         */
        fun parse(d: String): SketchPath {
            val tokens = tokenize(d)
            require(tokens.isNotEmpty() && (tokens[0] == "M" || tokens[0] == "m")) { "Path must start with M: $d" }
            var i = 0
            var command = ' '
            var current = XY(0.0, 0.0)
            var start: XY? = null
            val curves = ArrayList<Cubic>()

            fun number(): Double =
                tokens.getOrNull(i++)?.toDoubleOrNull() ?: throw IllegalArgumentException("Bad path: $d")

            fun point(relative: Boolean): XY {
                val p = XY(number(), number())
                return if (relative) current + p else p
            }
            while (i < tokens.size) {
                val t = tokens[i]
                if (t.length == 1 && t[0].isLetter()) {
                    command = t[0]
                    i++
                    if (command == 'Z' || command == 'z') continue
                }
                when (command) {
                    'M', 'm' -> {
                        require(start == null) { "A sketch stroke has one subpath: $d" }
                        current = point(command == 'm')
                        start = current
                        command = if (command == 'm') 'l' else 'L'
                    }

                    'L', 'l' -> {
                        val e = point(command == 'l')
                        curves += Cubic(current + (e - current) * (1.0 / 3), current + (e - current) * (2.0 / 3), e)
                        current = e
                    }

                    'C', 'c' -> {
                        val rel = command == 'c'
                        val c1 = point(rel)
                        val c2 = point(rel)
                        val e = point(rel)
                        curves += Cubic(c1, c2, e)
                        current = e
                    }

                    else -> {
                        throw IllegalArgumentException("Unsupported path command '$command': $d")
                    }
                }
            }
            return SketchPath(start ?: throw IllegalArgumentException("Empty path"), curves)
        }

        private fun tokenize(d: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            while (i < d.length) {
                val ch = d[i]
                when {
                    ch.isWhitespace() || ch == ',' -> {
                        i++
                    }

                    ch.isLetter() && ch != 'e' && ch != 'E' -> {
                        out += ch.toString()
                        i++
                    }

                    else -> {
                        val begin = i
                        i++
                        while (i < d.length) {
                            val c = d[i]
                            val exponentSign = (c == '-' || c == '+') && (d[i - 1] == 'e' || d[i - 1] == 'E')
                            if (c.isDigit() || c == '.' || c == 'e' || c == 'E' || exponentSign) i++ else break
                        }
                        out += d.substring(begin, i)
                    }
                }
            }
            return out
        }
    }
}
