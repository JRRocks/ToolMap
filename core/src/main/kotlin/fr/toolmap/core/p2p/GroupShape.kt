package fr.toolmap.core.p2p

import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.sketch.Cubic
import fr.toolmap.core.sketch.Sketch
import fr.toolmap.core.sketch.SketchFill
import fr.toolmap.core.sketch.SketchPath
import fr.toolmap.core.sketch.SketchShape
import fr.toolmap.core.sketch.SketchStroke
import fr.toolmap.core.sketch.SketchStyle
import fr.toolmap.core.sketch.XY
import fr.toolmap.core.track.PathSimplifier
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.cos
import kotlin.math.roundToLong

/** What a share of the group is (P27, 2026-11-03): a waypoint, a track, a sketch, or favorites (P25.4). */
enum class GroupItemKind(
    val code: Int,
) {
    WAYPOINT(0),
    TRACK(1),
    SKETCH(2),
    FAVORITES(3),
    ;

    companion object {
        /** An unknown kind (a later version) is shown as a waypoint at its anchor. */
        fun of(code: Int): GroupItemKind = entries.firstOrNull { it.code == code } ?: WAYPOINT
    }
}

/**
 * The geometry of a track or a sketch shared with the group, packed small enough for one frame of the mesh and one slot
 * of the server (`docs/specs/p2p-sharing.md` § 12): every number a zigzag varint of its difference with the previous
 * one, then the whole deflated. A track is in steps of 1e-5° (about a meter), simplified until it fits; a sketch keeps
 * its Bézier curves in decimeters of map meters, with the colour and width of each stroke.
 */
object GroupShape {
    /** Deflated bytes at most: the frame stays under the 16 KB of the mesh, its Base64 under the server's limit. */
    const val MAX_BYTES = 12 * 1024

    private const val DEG_STEP = 1e5
    private const val DM = 10.0
    private const val MAX_INFLATED = 256 * 1024

    /** More than any sketch that fits [MAX_BYTES] once drawn by hand (a few thousand curves at most). */
    private const val MAX_SKETCH_STROKES = 5_000
    private const val MAX_SKETCH_CURVES = 20_000
    private const val FIRST_TOLERANCE_M = 2.0
    private const val TOLERANCE_GROWTH = 2.0
    private const val MAX_TOLERANCE_M = 2_000.0
    private const val METERS_PER_DEGREE = 111_195.0
    private const val CM = 100.0

    /**
     * The segments of a track, simplified as little as needed to fit [maxBytes]; `null` when even a 2 km tolerance does
     * not (thousands of segments).
     */
    fun packTrack(
        segments: List<List<LonLat>>,
        maxBytes: Int = MAX_BYTES,
    ): ByteArray? {
        val kept = segments.filter { it.isNotEmpty() }
        var bytes = deflate(trackBytes(kept))
        var tolerance = FIRST_TOLERANCE_M
        while (bytes.size > maxBytes) {
            if (tolerance > MAX_TOLERANCE_M) return null
            bytes = deflate(trackBytes(kept.map { simplify(it, tolerance) }))
            tolerance *= TOLERANCE_GROWTH
        }
        return bytes
    }

    fun unpackTrack(bytes: ByteArray): List<List<LonLat>> {
        val r = VarReader(inflate(bytes))
        val segments = ArrayList<List<LonLat>>()
        var lat = 0L
        var lon = 0L
        repeat(r.count()) {
            val n = r.count()
            segments +=
                List(n) {
                    lat += r.signed()
                    lon += r.signed()
                    LonLat(lon / DEG_STEP, lat / DEG_STEP).also {
                        if (it.lat !in -90.0..90.0 ||
                            it.lon !in -180.0..180.0
                        ) {
                            throw WireException("track out of range")
                        }
                    }
                }
        }
        return segments
    }

    /** The strokes of a sketch; `null` when they do not fit [maxBytes]. */
    fun packSketch(
        strokes: List<SketchStroke>,
        maxBytes: Int = MAX_BYTES,
    ): ByteArray? {
        val w = VarWriter()
        w.count(strokes.size)
        var x = 0L
        var y = 0L

        fun point(p: XY) {
            val px = (p.x * DM).roundToLong()
            val py = (p.y * DM).roundToLong()
            w.signed(px - x).signed(py - y)
            x = px
            y = py
        }
        for (s in strokes) {
            w.unsigned(s.style.color.toLong() and 0xFFFFFFFFL)
            w.unsigned((s.style.widthMeters * CM).roundToLong().coerceAtLeast(1))
            w.unsigned((s.style.smoothingMeters * CM).roundToLong().coerceAtLeast(0))
            w.count(s.path.curves.size)
            point(s.path.start)
            for (c in s.path.curves) {
                point(c.c1)
                point(c.c2)
                point(c.end)
            }
        }
        // The shapes of P28.4 come after every stroke, so that an older app reads the strokes and ignores them: per
        // stroke, 0 for a freehand stroke, 1 for a line, 2 + the index of its fill for a zone.
        if (strokes.any { it.shape != null }) {
            for (s in strokes) {
                val shape = s.shape
                w.unsigned(
                    when {
                        shape == null -> 0L
                        !shape.closed -> 1L
                        else -> 2L + shape.fill.ordinal
                    },
                )
            }
        }
        return deflate(w.toByteArray()).takeIf { it.size <= maxBytes }
    }

    fun unpackSketch(bytes: ByteArray): List<SketchStroke> {
        val r = VarReader(inflate(bytes))
        var x = 0L
        var y = 0L

        fun point(): XY {
            x += r.signed()
            y += r.signed()
            return XY(x / DM, y / DM)
        }
        val strokeCount = r.count()
        if (strokeCount > MAX_SKETCH_STROKES) throw WireException("too many strokes")
        var curveCount = 0
        val strokes =
            List(strokeCount) {
                val style =
                    SketchStyle(
                        color = r.unsigned().toInt(),
                        widthMeters = r.unsigned() / CM,
                        smoothingMeters = r.unsigned() / CM,
                    )
                val curves = r.count()
                curveCount += curves
                if (curveCount > MAX_SKETCH_CURVES) throw WireException("too many curves")
                val start = point()
                SketchStroke(style, SketchPath(start, List(curves) { Cubic(point(), point(), point()) }))
            }
        // A sketch made up to cost millions of points once cut into lines (huge curves, a width of a centimeter) is
        // refused here, before the map, a list or an editor meets it (P36 audit: every member's phone ran out of
        // memory, at each start, since the sketch is kept by the group). Nothing drawn by hand comes near.
        if (!Sketch.isDrawable(strokes)) throw WireException("sketch too heavy to draw")
        if (!r.hasMore()) return strokes
        return strokes.map { s ->
            when (val code = r.unsigned()) {
                0L -> {
                    s
                }

                1L -> {
                    s.copy(shape = SketchShape(closed = false))
                }

                else -> {
                    s.copy(
                        shape =
                            SketchShape(
                                true,
                                SketchFill.entries.getOrElse(code.toInt() - 2) {
                                    SketchFill.NONE
                                },
                            ),
                    )
                }
            }
        }
    }

    private fun trackBytes(segments: List<List<LonLat>>): ByteArray {
        val w = VarWriter()
        w.count(segments.size)
        var lat = 0L
        var lon = 0L
        for (segment in segments) {
            w.count(segment.size)
            for (p in segment) {
                val la = (p.lat * DEG_STEP).roundToLong()
                val lo = (p.lon * DEG_STEP).roundToLong()
                w.signed(la - lat).signed(lo - lon)
                lat = la
                lon = lo
            }
        }
        return w.toByteArray()
    }

    private fun simplify(
        points: List<LonLat>,
        toleranceMeters: Double,
    ): List<LonLat> {
        if (points.size < 3) return points
        val cosLat = cos(Math.toRadians(points[points.size / 2].lat))
        val xs = DoubleArray(points.size) { points[it].lon * METERS_PER_DEGREE * cosLat }
        val ys = DoubleArray(points.size) { points[it].lat * METERS_PER_DEGREE }
        val keep = PathSimplifier.keptIndices(xs, ys, toleranceMeters)
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** The text of a `.toolmap` file of favorites (P25.4), deflated; `null` when it does not fit [maxBytes]. */
    fun packText(
        text: String,
        maxBytes: Int = MAX_BYTES,
    ): ByteArray? = deflate(text.toByteArray(Charsets.UTF_8)).takeIf { it.size <= maxBytes }

    fun unpackText(bytes: ByteArray): String = inflate(bytes).toString(Charsets.UTF_8)

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        try {
            d.setInput(data)
            d.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 16)
            val buf = ByteArray(4096)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            return out.toByteArray()
        } finally {
            d.end()
        }
    }

    private fun inflate(data: ByteArray): ByteArray {
        val i = Inflater()
        try {
            i.setInput(data)
            val out = ByteArrayOutputStream(data.size * 3)
            val buf = ByteArray(4096)
            while (!i.finished()) {
                val n = i.inflate(buf)
                if (n == 0 && (i.needsInput() || i.needsDictionary())) throw WireException("truncated shape")
                out.write(buf, 0, n)
                if (out.size() > MAX_INFLATED) throw WireException("shape too large")
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw WireException("bad shape")
        } finally {
            i.end()
        }
    }

    /** LEB128 varints; signed numbers zigzag-coded so that small negatives stay short. */
    private class VarWriter {
        private val out = ByteArrayOutputStream()

        fun unsigned(v: Long) =
            apply {
                var n = v
                while (n and 0x7FL.inv() != 0L) {
                    out.write(((n and 0x7F) or 0x80).toInt())
                    n = n ushr 7
                }
                out.write(n.toInt())
            }

        fun signed(v: Long) = unsigned((v shl 1) xor (v shr 63))

        fun count(n: Int) = unsigned(n.toLong())

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private class VarReader(
        private val data: ByteArray,
    ) {
        private var pos = 0

        fun hasMore(): Boolean = pos < data.size

        fun unsigned(): Long {
            var v = 0L
            var shift = 0
            while (true) {
                if (pos >= data.size || shift > 63) throw WireException("truncated shape")
                val b = data[pos++].toInt() and 0xFF
                v = v or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return v
                shift += 7
            }
        }

        fun signed(): Long = unsigned().let { (it ushr 1) xor -(it and 1) }

        /** A count can never exceed the bytes left: a forged one cannot make a huge list. */
        fun count(): Int {
            val n = unsigned()
            if (n < 0 || n > data.size - pos) throw WireException("bad count")
            return n.toInt()
        }
    }
}
