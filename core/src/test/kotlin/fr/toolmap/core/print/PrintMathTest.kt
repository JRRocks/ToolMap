package fr.toolmap.core.print

import fr.toolmap.core.tiles.TileMath
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrintMathTest {
    private val lat45 = 45.0

    @Test
    fun `ground resolution and tile zoom follow the reference table of the spec`() {
        // scale, dpi, r (m/px), z*, R(z*) (m/px)
        val table =
            listOf(
                listOf(25_000.0, 300.0, 2.117, 16.0, 1.689),
                listOf(25_000.0, 150.0, 4.233, 15.0, 3.378),
                listOf(50_000.0, 300.0, 4.233, 15.0, 3.378),
                listOf(100_000.0, 300.0, 8.467, 14.0, 6.756),
            )
        for ((scale, dpi, r, z, rz) in table) {
            assertEquals(r, PrintMath.groundResolution(scale, dpi.toInt()), 0.001, "r at 1:$scale / $dpi")
            assertEquals(z.toInt(), PrintMath.tileZoom(scale, dpi.toInt(), lat45), "z* at 1:$scale / $dpi")
            assertEquals(rz, PrintMath.tileResolution(z.toInt(), lat45), 0.001, "R(z*) at 1:$scale / $dpi")
        }
    }

    @Test
    fun `a lighter file takes one level less and the zoom is bounded`() {
        assertEquals(15, PrintMath.tileZoom(25_000.0, 300, lat45, lighter = true))
        assertEquals(0, PrintMath.tileZoom(1e12, 150, lat45, lighter = true))
        assertEquals(24, PrintMath.tileZoom(1.0, 300, lat45))
    }

    @Test
    fun `tiles are sharp enough at the chosen zoom and not at the one below`() {
        for (scale in listOf(5_000.0, 10_000.0, 25_000.0, 50_000.0, 250_000.0)) {
            for (dpi in listOf(150, 200, 300)) {
                for (lat in listOf(0.0, 30.0, 45.0, 60.0)) {
                    val z = PrintMath.tileZoom(scale, dpi, lat)
                    val r = PrintMath.groundResolution(scale, dpi)
                    assertTrue(PrintMath.tileResolution(z, lat) <= r + 1e-9, "z=$z too coarse at 1:$scale/$dpi/$lat")
                    assertTrue(
                        PrintMath.tileResolution(z - 1, lat) > r,
                        "z=$z is not the smallest at 1:$scale/$dpi/$lat",
                    )
                }
            }
        }
    }

    @Test
    fun `an exact power of two does not jump a level because of rounding`() {
        // Pick the scale at which the equivalent zoom is exactly 15 at 45 degrees and 300 dpi.
        val r = PrintMath.tileResolution(15, lat45)
        val scale = r * 300 / 0.0254
        assertEquals(15.0, PrintMath.equivalentZoom(scale, 300, lat45), 1e-9)
        assertEquals(15, PrintMath.tileZoom(scale, 300, lat45))
    }

    @Test
    fun `an A4 landscape frame covers 13,19 by 7,41 km at 1 to 50 000`() {
        // 8 mm margin, 7 mm of labels and 1.6 mm of graduated band around; the footer (28.6 mm) under the map.
        val setup = PageSetup(Paper.A4, landscape = true)
        assertEquals(263.8, setup.frameWidthMm, 1e-9)
        assertEquals(148.2, setup.frameHeightMm, 1e-9)
        assertEquals(13_190.0, PrintMath.groundMeters(setup.frameWidthMm, 50_000.0), 1e-6)
        assertEquals(7_410.0, PrintMath.groundMeters(setup.frameHeightMm, 50_000.0), 1e-6)
        assertEquals(6_595.0, PrintMath.groundMeters(setup.frameWidthMm, 25_000.0), 1e-6)
        assertEquals(595, setup.widthPt.let { if (setup.landscape) setup.heightPt else it })
        assertEquals(842, setup.widthPt)
    }

    @Test
    fun `the edge error is about 0,075 percent for A4 at 1 to 50 000 and 45 degrees`() {
        assertEquals(0.075, PrintMath.edgeErrorPercent(190.0, 50_000.0, lat45), 0.002)
        assertEquals(0.0, PrintMath.edgeErrorPercent(190.0, 50_000.0, 0.0), 1e-12)
    }

    @Test
    fun `an A4 at 1 to 50 000 needs about 200 tiles per layer at zoom 15`() {
        val frame = FrameProjection(lat45, 6.0, 50_000.0, 277.0, 190.0)
        val b = frame.mercatorBounds
        val size = 2 * TileMath.ORIGIN_SHIFT_M / (1 shl 15)
        val cols =
            Math.floor((b.maxX + TileMath.ORIGIN_SHIFT_M) / size) -
                Math.floor((b.minX + TileMath.ORIGIN_SHIFT_M) / size) +
                1
        val rows =
            Math.floor((TileMath.ORIGIN_SHIFT_M - b.minY) / size) -
                Math.floor((TileMath.ORIGIN_SHIFT_M - b.maxY) / size) +
                1
        assertTrue(cols * rows in 170.0..260.0, "$cols x $rows")
    }

    @Test
    fun `a source stopped at zoom 14 is enlarged by 3,2 for a print at 1 to 25 000 and 300 dpi`() {
        val used = PrintMath.nativeZoom(wanted = 16, available = (6..14).toList())
        assertEquals(14, used)
        assertEquals(3.2, PrintMath.oversampling(14, 25_000.0, 300, lat45), 1e-9)
        assertEquals(1.0, PrintMath.oversampling(16, 25_000.0, 300, lat45), 1e-9)
        assertEquals(1.0, PrintMath.oversampling(17, 25_000.0, 300, lat45), 1e-9)
    }

    @Test
    fun `a source with gaps gives the nearest zoom above, else its highest`() {
        assertEquals(12, PrintMath.nativeZoom(11, listOf(4, 8, 12, 16)))
        assertEquals(16, PrintMath.nativeZoom(16, listOf(4, 8, 12, 16)))
        assertEquals(16, PrintMath.nativeZoom(18, listOf(4, 8, 12, 16)))
        assertEquals(4, PrintMath.nativeZoom(1, listOf(4, 8, 12, 16)))
        assertEquals(null, PrintMath.nativeZoom(5, emptyList()))
    }

    @Test
    fun `pixel sizes of the output`() {
        // A3 landscape at 300 dpi: a 386.8 x 235.2 mm frame is about 12.7 Mpx.
        val setup = PageSetup(Paper.A3, landscape = true)
        val w = PrintMath.pixels(setup.frameWidthMm, 300)
        val h = PrintMath.pixels(setup.frameHeightMm, 300)
        assertEquals(4_569, w)
        assertEquals(2_778, h)
        assertEquals(12.7, w.toLong() * h / 1e6, 0.1)
    }

    @Test
    fun `the page of a given paper has the standard point sizes`() {
        assertEquals(595 to 842, PageSetup(Paper.A4, landscape = false).let { it.widthPt to it.heightPt })
        assertEquals(842 to 595, PageSetup(Paper.A4, landscape = true).let { it.widthPt to it.heightPt })
        assertEquals(612 to 792, PageSetup(Paper.LETTER, landscape = false).let { it.widthPt to it.heightPt })
        assertEquals(842 to 1191, PageSetup(Paper.A3, landscape = false).let { it.widthPt to it.heightPt })
    }

    @Test
    fun `page issues`() {
        assertTrue(PageSetup().issues().isEmpty())
        assertTrue(PageIssue.MARGIN_RANGE in PageSetup(marginMm = -1.0).issues())
        assertTrue(PageIssue.MARGIN_RANGE in PageSetup(marginMm = 60.0).issues())
        assertTrue(PageIssue.CUSTOM_SIZE in PageSetup(Paper.CUSTOM, customWidthMm = 10.0).issues())
        assertTrue(
            PageIssue.FRAME_TOO_SMALL in
                PageSetup(Paper.CUSTOM, customWidthMm = 60.0, customHeightMm = 60.0, marginMm = 25.0).issues(),
        )
        assertEquals(
            PageSetup(Paper.CUSTOM, landscape = true, customWidthMm = 300.0, customHeightMm = 200.0).widthMm,
            300.0,
        )
    }

    @Test
    fun `the frame projection puts the centre at the middle and keeps the scale along both axes`() {
        val frame = FrameProjection(lat45, 6.0, 25_000.0, 277.0, 190.0)
        val centre = frame.toFrame(6.0, lat45)
        assertEquals(138.5, centre.xMm, 1e-9)
        assertEquals(95.0, centre.yMm, 1e-9)
        // 10 km east of the centre along the parallel is 400 mm at 1:25 000.
        val east = frame.toLonLat(centre.xMm + 100.0, centre.yMm)
        val metersEast = fr.toolmap.core.track.Geodesy.distance(lat45, 6.0, east.lat, east.lon)
        assertEquals(2_500.0, metersEast, 2_500.0 * 0.002)
        // And 2.5 km north (100 mm up) along the meridian, to within the scale error of the edge.
        val north = frame.toLonLat(centre.xMm, centre.yMm - 100.0)
        val metersNorth = fr.toolmap.core.track.Geodesy.distance(lat45, 6.0, north.lat, north.lon)
        assertEquals(2_500.0, metersNorth, 2_500.0 * 0.002)
        // Round trip.
        val p = frame.toFrame(6.05, 45.03)
        val back = frame.toLonLat(p.xMm, p.yMm)
        assertEquals(6.05, back.lon, 1e-9)
        assertEquals(45.03, back.lat, 1e-9)
        assertTrue(frame.contains(centre))
        assertTrue(!frame.contains(FramePoint(-1.0, 5.0)))
        val box = frame.lonLatBounds
        assertTrue(box.minX < 6.0 && box.maxX > 6.0 && box.minY < lat45 && box.maxY > lat45)
        assertTrue(abs(box.maxX - 6.0 - (6.0 - box.minX)) < 1e-9)
    }

    @Test
    fun `a ten kilometer segment is 200 millimeters at 1 to 50 000`() {
        val frame = FrameProjection(lat45, 6.0, 50_000.0, 277.0, 190.0)
        // Two points 10 km apart on the same parallel, symmetric around the centre of the frame.
        val halfDegrees = Math.toDegrees(5_000.0 / 6_371_000.0 / Math.cos(Math.toRadians(lat45)))
        val a = frame.toFrame(6.0 - halfDegrees, lat45)
        val b = frame.toFrame(6.0 + halfDegrees, lat45)
        // The criterion of the spec: 200 mm +/- 0.5 % on the paper (ellipsoid and sphere differ by a few thousandths).
        assertEquals(200.0, b.xMm - a.xMm, 1.0)
        assertEquals(a.yMm, b.yMm, 1e-9)
    }

    @Test
    fun `size estimates grow with pixels and pages`() {
        val (low, high) = PrintMath.estimatedBytes(14_500_000, 1)
        assertTrue(low in 1_000_000..5_000_000 && high in 20_000_000..40_000_000, "$low .. $high")
        assertTrue(abs(low * 3 - PrintMath.estimatedBytes(14_500_000, 3).first) <= 3)
    }
}
