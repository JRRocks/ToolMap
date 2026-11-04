package fr.toolmap.core.sketch

import fr.toolmap.core.geo.LonLat
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SketchShapeTest {
    private val origin = XY.of(LonLat(5.95, 45.52))
    private val style = SketchStyle(SketchPalette.COLORS[0], widthMeters = 8.0, smoothingMeters = 3.0)
    private val corners =
        listOf(origin, origin + XY(400.0, -50.0), origin + XY(520.0, 300.0), origin + XY(80.0, 380.0))

    private fun near(
        a: List<XY>,
        b: List<XY>,
        tolerance: Double,
    ) {
        assertEquals(a.size, b.size)
        for ((p, q) in a.zip(b)) assertTrue(abs(p.x - q.x) <= tolerance && abs(p.y - q.y) <= tolerance, "$p $q")
    }

    @Test
    fun `a zone keeps its corners exactly, closed by a last side, and is never smoothed`() {
        val zone = RawStroke(style, corners, SketchShape(closed = true, fill = SketchFill.HATCH)).smoothed()
        assertEquals(corners.size, zone.path.curves.size, "one side per corner, the closing one included")
        assertEquals(corners[0], zone.path.end)
        assertEquals(corners, zone.path.vertices(closed = true))
        // A side is straight: its control points lie on it.
        val side = zone.path.curves[0]
        val d = corners[1] - corners[0]
        for (c in listOf(side.c1, side.c2)) assertEquals(0.0, (c - corners[0]).x * d.y - (c - corners[0]).y * d.x, 1e-6)
        assertEquals(corners, zone.toRaw().points)
        assertEquals(zone.shape, zone.toRaw().shape)
    }

    @Test
    fun `a line is open and keeps its points, and both come back from their SVG path to the decimeter`() {
        val line = RawStroke(style, corners.take(3), SketchShape(closed = false)).smoothed()
        assertEquals(2, line.path.curves.size)
        assertEquals(corners.take(3), line.path.vertices(closed = false))
        val zone = RawStroke(style, corners, SketchShape(closed = true)).smoothed()
        near(corners, SketchPath.parse(zone.path.toSvg()).vertices(closed = true), 0.05)
        near(corners.take(3), SketchPath.parse(line.path.toSvg()).vertices(closed = false), 0.05)
    }

    @Test
    fun `a fill is stored by its code, and six combinations of a pattern and a colour have a name`() {
        for (fill in SketchFill.entries) assertEquals(fill, SketchFill.of(fill.code))
        assertEquals(SketchFill.NONE, SketchFill.of(null))
        assertEquals(SketchFill.NONE, SketchFill.of("plaid"))
        assertEquals(SketchFillPreset.FORBIDDEN, SketchFillPreset.of(SketchFill.HATCH, SketchPalette.COLORS[0]))
        assertEquals(SketchFillPreset.WET, SketchFillPreset.of(SketchFill.WAVES, SketchPalette.COLORS[4]))
        assertNull(SketchFillPreset.of(SketchFill.DOTS, SketchPalette.COLORS[1]), "orange dots have no name")
        assertEquals(6, SketchFillPreset.entries.map { it.fill to it.color }.toSet().size)
    }

    @Test
    fun `every pattern repeats over its tile, so that a tile drawn alone has no seam`() {
        for (fill in SketchFill.entries.filter { it.patterned }) {
            val tile = SketchFills.tile(fill)!!
            val unit = 3.0
            val w = tile.x * unit
            val h = tile.y * unit
            val here = SketchFills.marks(fill, XY(0.0, 0.0), XY(w, h), unit)
            val next = SketchFills.marks(fill, XY(w, h), XY(2 * w, 2 * h), unit)
            assertTrue(here.lines.isNotEmpty() || here.dots.isNotEmpty(), "$fill draws something")

            // Every mark of the next tile, moved back by one tile, is a mark of this one.
            fun key(p: XY) = Math.round(p.x * 1000) to Math.round(p.y * 1000)
            val dots = here.dots.map(::key).toSet()
            for (d in next.dots) assertTrue(key(d - XY(w, h)) in dots, "$fill: dot $d")
            if (fill == SketchFill.WAVES) {
                // A wave has the same height one length further, and the rows are one tile apart.
                for (line in here.lines) {
                    val ys = line.associate { Math.round(it.x * 1000) to it.y }
                    for (p in line) ys[Math.round((p.x + w) * 1000)]?.let { assertEquals(p.y, it, 1e-6) }
                }
                val bases = here.lines.map { it[0].y }.sorted()
                for ((p, q) in bases.zipWithNext()) assertEquals(h, q - p, 1e-6)
            } else {
                val lines = here.lines.map { (a, b) -> lineKey(a, b) }.toSet()
                for ((a, b) in next.lines) {
                    assertTrue(lineKey(a - XY(w, h), b - XY(w, h)) in lines, "$fill: line $a $b")
                }
            }
        }
        assertNull(SketchFills.tile(SketchFill.TINT))
        assertTrue(SketchFills.marks(SketchFill.NONE, XY(0.0, 0.0), XY(10.0, 10.0), 1.0).lines.isEmpty())
    }

    /** A straight line by its direction and its offset from the origin, whatever its ends. */
    private fun lineKey(
        a: XY,
        b: XY,
    ): Pair<Long, Long> {
        var d = (b - a).normalized()
        if (d.x < -1e-9 || (abs(d.x) <= 1e-9 && d.y < 0)) d = d * -1.0
        return Math.round(Math.atan2(d.y, d.x) * 1000) to Math.round((a.x * d.y - a.y * d.x) * 1000)
    }

    @Test
    fun `hatching is drawn 9 dp apart, at 45 degrees`() {
        val marks = SketchFills.marks(SketchFill.HATCH, XY(0.0, 0.0), XY(100.0, 100.0), unit = 1.0)
        assertEquals(2.4, marks.lineWidth, 1e-9)
        val offsets = marks.lines.map { (a, _) -> a.x + a.y }.sorted()
        for ((p, q) in offsets.zipWithNext()) assertEquals(9.0 * Math.sqrt(2.0), q - p, 1e-9)
        val (a, b) = marks.lines[0]
        assertEquals(-1.0, (b.y - a.y) / (b.x - a.x), 1e-9)
    }
}
