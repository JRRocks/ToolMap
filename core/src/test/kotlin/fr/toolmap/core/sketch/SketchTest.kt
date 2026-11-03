package fr.toolmap.core.sketch

import fr.toolmap.core.geo.LonLat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SketchTest {
    private val origin = XY.of(LonLat(5.95, 45.52))

    /** Distance from [p] to the closest point of a densely sampled [path]. */
    private fun distanceToPath(
        p: XY,
        path: SketchPath,
    ): Double = path.sample(0.001).zipWithNext().minOf { (a, b) -> segmentDistance(p, a, b) }

    private fun segmentDistance(
        p: XY,
        a: XY,
        b: XY,
    ): Double {
        val d = b - a
        val l2 = d dot d
        if (l2 == 0.0) return p.distanceTo(a)
        val t = (((p - a) dot d) / l2).coerceIn(0.0, 1.0)
        return p.distanceTo(a + d * t)
    }

    private fun arc(
        count: Int,
        radius: Double,
        noise: Double = 0.0,
        seed: Int = 1,
    ): List<XY> {
        val random = Random(seed)
        return List(count) { i ->
            val a = PI * i / (count - 1)
            origin + XY(radius * cos(a), radius * sin(a)) +
                XY((random.nextDouble() * 2 - 1) * noise, (random.nextDouble() * 2 - 1) * noise)
        }
    }

    @Test
    fun `an arc is fitted by a few curves that stay within the tolerance`() {
        val raw = arc(200, radius = 100.0)
        val path = SketchSmoother.fit(raw, tolerance = 0.5)
        assertTrue(path.curves.size in 1..6, "${path.curves.size} curves")
        assertEquals(raw.first(), path.start)
        assertEquals(raw.last(), path.end)
        assertTrue(raw.all { distanceToPath(it, path) <= 0.5 * 1.05 }, "a raw point is too far from the curve")
    }

    @Test
    fun `a stronger smoothing gives fewer curves on a shaky stroke`() {
        val raw = arc(300, radius = 100.0, noise = 1.5)
        val low = SketchSmoother.fit(raw, tolerance = 1.0).curves.size
        val high = SketchSmoother.fit(raw, tolerance = 5.0).curves.size
        assertTrue(high < low, "high $high, low $low")
    }

    @Test
    fun `a sharp corner is kept as the end of a curve`() {
        val corner = origin + XY(100.0, 0.0)
        val raw = List(51) { origin + XY(it * 2.0, 0.0) } + List(50) { corner + XY(0.0, (it + 1) * 2.0) }
        val path = SketchSmoother.fit(raw, tolerance = 0.5)
        assertTrue(path.curves.any { it.end == corner }, "the corner is smoothed away")
    }

    @Test
    fun `a tap gives a dot and two points a straight piece`() {
        assertEquals(SketchPath(origin), SketchSmoother.fit(listOf(origin, origin + XY(0.01, 0.0)), tolerance = 1.0))
        val line = SketchSmoother.fit(listOf(origin, origin + XY(30.0, 0.0)), tolerance = 1.0)
        assertEquals(1, line.curves.size)
        assertTrue(line.sample(0.01).all { abs(it.y - origin.y) < 1e-9 })
        assertFailsWith<IllegalArgumentException> { SketchSmoother.fit(emptyList(), 1.0) }
    }

    @Test
    fun `the SVG path is short, relative and read back to the decimeter`() {
        val path = SketchSmoother.fit(arc(120, radius = 60.0, noise = 0.3), tolerance = 0.8)
        val svg = path.toSvg()
        assertTrue(svg.startsWith("M") && " c " in svg, svg)
        assertTrue('e' !in svg && ',' !in svg, svg)
        val back = SketchPath.parse(svg)
        assertEquals(path.curves.size, back.curves.size)
        val expected = listOf(path.start) + path.curves.flatMap { listOf(it.c1, it.c2, it.end) }
        val read = listOf(back.start) + back.curves.flatMap { listOf(it.c1, it.c2, it.end) }
        expected.zip(read).forEach { (e, r) -> assertTrue(e.distanceTo(r) <= 0.075, "$e read as $r") }
        // Decimeters do not drift along the path: the end is within one rounding of the original end.
        assertTrue(path.end.distanceTo(back.end) <= 0.075)
        assertEquals(svg, back.toSvg())
    }

    @Test
    fun `parse accepts absolute commands and lines and refuses the rest`() {
        val p = SketchPath.parse("M10 -20 L 13 -16 C 14,-15 15,-14 16.5,-13.5 l 1e1 0")
        assertEquals(XY(10.0, -20.0), p.start)
        assertEquals(3, p.curves.size)
        assertEquals(XY(13.0, -16.0), p.curves[0].end)
        assertEquals(XY(26.5, -13.5), p.end)
        assertEquals("M0 0", SketchPath(XY(0.0, 0.0)).toSvg())
        assertFailsWith<IllegalArgumentException> { SketchPath.parse("M0 0 Q 1 1 2 2") }
        assertFailsWith<IllegalArgumentException> { SketchPath.parse("L 1 1") }
    }

    @Test
    fun `map coordinates go back to the same position`() {
        val position = LonLat(5.95, 45.52)
        val back = XY.of(position).toLonLat()
        assertEquals(position.lon, back.lon, 1e-9)
        assertEquals(position.lat, back.lat, 1e-9)
        // North up: a point further north has a smaller y, as in SVG.
        assertTrue(XY.of(LonLat(5.95, 45.6)).y < XY.of(position).y)
    }

    @Test
    fun `the eraser cuts a stroke in two and removes the part under it`() {
        val line = List(11) { origin + XY(it * 10.0, 0.0) }
        val pieces = SketchEraser.cut(line, origin + XY(50.0, 0.0), radius = 12.0)
        assertEquals(2, pieces.size)
        assertEquals(origin, pieces[0].first())
        assertEquals(38.0, pieces[0].last().x - origin.x, 1e-9)
        assertEquals(62.0, pieces[1].first().x - origin.x, 1e-9)
        assertEquals(line.last(), pieces[1].last())
        assertEquals(listOf(0.0, 10.0, 20.0, 30.0, 38.0), pieces[0].map { it.x - origin.x })
    }

    @Test
    fun `the eraser also cuts a segment that only crosses it`() {
        val line = listOf(origin, origin + XY(100.0, 0.0))
        val pieces = SketchEraser.cut(line, origin + XY(50.0, 3.0), radius = 5.0)
        assertEquals(2, pieces.size)
        assertEquals(46.0, pieces[0].last().x - origin.x, 1e-9)
        assertEquals(54.0, pieces[1].first().x - origin.x, 1e-9)
    }

    @Test
    fun `the eraser leaves strokes it misses and removes those it covers`() {
        val style = SketchStyle(SketchPalette.COLORS[0], widthMeters = 3.0, smoothingMeters = 1.0)
        val far = RawStroke(style, listOf(origin, origin + XY(10.0, 0.0)))
        val under = RawStroke(style, listOf(origin + XY(0.0, 100.0), origin + XY(2.0, 100.0)))
        val dot = RawStroke(style, listOf(origin + XY(1.0, 101.0)))
        val left = SketchEraser.erase(listOf(far, under, dot), origin + XY(1.0, 100.0), radius = 5.0)
        assertEquals(listOf(far), left)
    }

    @Test
    fun `width and smoothing are kept in map meters and hide the sketch when thinner than a pixel`() {
        val style =
            SketchScale.style(
                SketchPalette.COLORS[4],
                widthPx = 6,
                smoothing = SketchSmoothing.MEDIUM,
                cameraZoom = 15.0,
            )
        val mpp = SketchScale.metersPerPixel(15.0)
        assertEquals(6 * mpp, style.widthMeters, 1e-9)
        assertEquals(2.5 * mpp, style.smoothingMeters, 1e-9)
        // Each zoom level out halves the width on screen: 6 px at zoom 15 is 1 px near zoom 12.4.
        val path = SketchPath(origin, listOf(Cubic(origin, origin, origin + XY(5.0, 0.0))))
        val sketch = Sketch("Croquis du 3 nov.", listOf(SketchStroke(style, path)))
        assertEquals(15.0 - 2.585, assertNotNull(sketch.minVisibleZoom()), 0.001)
        assertNull(Sketch("vide", emptyList()).minVisibleZoom())
        assertEquals(40 * mpp, SketchScale.style(0, 99, SketchSmoothing.LOW, 15.0).widthMeters, 1e-9)
        // About 2.3 m on the ground for 6 px at zoom 15, at 45.5° N (the label under the slider).
        assertEquals(
            style.widthMeters * cos(Math.toRadians(45.5)),
            SketchScale.groundMeters(style.widthMeters, 45.5),
            1e-12,
        )
    }

    @Test
    fun `a saved stroke reopened and saved again stays in place`() {
        val style = SketchStyle(SketchPalette.COLORS[1], widthMeters = 4.0, smoothingMeters = 1.0)
        val saved = RawStroke(style, arc(150, radius = 80.0, noise = 0.4)).smoothed()
        val again = saved.toRaw().smoothed()
        assertTrue(saved.path.sample(0.05).all { distanceToPath(it, again.path) <= 1.0 })
    }
}
