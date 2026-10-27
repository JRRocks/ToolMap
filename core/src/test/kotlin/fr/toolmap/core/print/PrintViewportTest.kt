package fr.toolmap.core.print

import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.geo.ScaleBar
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrintViewportTest {
    private val a4 = PageSetup()

    @Test
    fun `the frame covers its ground size at the scale`() {
        // 277 mm at 1:25 000 is 6 925 m; the frame of an A4 landscape sheet is 263.8 x 148.2 mm.
        assertEquals(6_925.0, PrintViewport.groundMeters(277.0, 25_000.0), 1e-9)
        val zoom = 13.0
        val (w, h) = PrintViewport.frameDp(a4, 25_000.0, 45.0, zoom)
        val metersPerDp = ScaleBar.metersPerDp(45.0, zoom)
        assertEquals(a4.frameWidthMm * 25.0, w * metersPerDp, 1e-6)
        assertEquals(a4.frameHeightMm * 25.0, h * metersPerDp, 1e-6)
    }

    @Test
    fun `the camera zoom is the inverse of the metres under a dp`() {
        for (lat in listOf(0.0, 45.0, 60.0)) {
            for (zoom in listOf(3.5, 12.0, 17.25)) {
                assertEquals(zoom, ScaleBar.cameraZoomFor(lat, ScaleBar.metersPerDp(lat, zoom)), 1e-9)
            }
        }
    }

    @Test
    fun `fitting the frame leaves it inside the available room and touching one side`() {
        val (availableW, availableH) = 360.0 to 400.0
        val zoom = PrintViewport.zoomToFit(a4, 50_000.0, 45.0, availableW, availableH)
        val (w, h) = PrintViewport.frameDp(a4, 50_000.0, 45.0, zoom)
        assertTrue(w <= availableW + 1e-6 && h <= availableH + 1e-6, "$w x $h")
        assertTrue(abs(w - availableW) < 1e-6 || abs(h - availableH) < 1e-6, "$w x $h")
        // A4 landscape is wider than 360 x 400 allows: the width is what limits.
        assertEquals(availableW, w, 1e-6)
    }

    @Test
    fun `a larger scale denominator needs a smaller zoom to fit`() {
        val near = PrintViewport.zoomToFit(a4, 10_000.0, 45.0, 360.0, 400.0)
        val far = PrintViewport.zoomToFit(a4, 100_000.0, 45.0, 360.0, 400.0)
        assertTrue(far < near)
        assertEquals(Math.log(10.0) / Math.log(2.0), near - far, 1e-9)
    }

    @Test
    fun `the pages of an atlas fit the room around their middle`() {
        // Two A4 landscape pages at 1:25 000, side by side along the parallel of 45° N.
        val first = FrameProjection(45.0, 6.0, 25_000.0, a4.frameWidthMm, a4.frameHeightMm)
        val second = FrameProjection(45.0, 6.08, 25_000.0, a4.frameWidthMm, a4.frameHeightMm)
        val corners =
            listOf(first, second).flatMap { f ->
                listOf(f.toLonLat(0.0, 0.0), f.toLonLat(f.frameWidthMm, f.frameHeightMm))
            }
        val fit = assertNotNull(PrintViewport.fit(corners, 360.0, 400.0))
        assertEquals(6.04, fit.lon, 1e-6)
        assertEquals(45.0, fit.lat, 1e-3)
        // One page alone would fit with a larger zoom; the two together are wider and limited by the width.
        val one = PrintViewport.zoomToFit(a4, 25_000.0, 45.0, 360.0, 400.0)
        assertTrue(fit.zoom < one)
        val span = (corners.maxOf { it.lon } - corners.minOf { it.lon }) * 111_320.0 * Math.cos(Math.toRadians(45.0))
        assertEquals(360.0, span / ScaleBar.metersPerDp(fit.lat, fit.zoom), 1.0)
        assertNull(PrintViewport.fit(emptyList(), 360.0, 400.0))
    }

    private fun abs(x: Double) = kotlin.math.abs(x)
}

class PrintFormatTest {
    @Test
    fun `the file name carries the title, the scale and the date`() {
        assertEquals(
            "ToolMap_Belledonne_1-25000_2026-10-02.pdf",
            PrintFormat.fileName("Belledonne", 25_000.0, "2026-10-02"),
        )
        assertEquals(
            "ToolMap_Col_de_la_Croix_1-50000_2026-10-02.pdf",
            PrintFormat.fileName("  Col de la Croix ", 50_000.0, "2026-10-02"),
        )
        assertEquals("ToolMap_carte_1-10000_2026-10-02.pdf", PrintFormat.fileName("", 10_000.0, "2026-10-02"))
        assertEquals(
            "ToolMap_IGN_Cartes_Nivolet_1-25000_2026-10-02.pdf",
            PrintFormat.fileName("IGN Cartes · Nivolet", 25_000.0, "2026-10-02"),
        )
    }

    @Test
    fun `the ground size has the same decimals on both sides and the separator of the language`() {
        val france = Locale.FRANCE
        assertEquals("6,6 km" to "3,7 km", PrintFormat.groundSize(6_595.0, 3_705.0, france))
        assertEquals("13.2 km" to "7.4 km", PrintFormat.groundSize(13_190.0, 7_410.0, Locale.US))
        assertEquals("850 m" to "480 m", PrintFormat.groundSize(850.0, 480.0, france))
        assertEquals("132 km" to "74 km", PrintFormat.groundSize(131_900.0, 74_100.0, france))
    }

    @Test
    fun `forbidden characters never reach the file name`() {
        val name = PrintFormat.fileName("a/b\\c:d*e?f\"g<h>i|j\nk", 25_000.0, "2026-10-02")
        assertTrue(name.none { it in "/\\:*?\"<>|\n" }, name)
        assertEquals("ToolMap_carte_1-25000_2026-10-02.pdf", PrintFormat.fileName("///", 25_000.0, "2026-10-02"))
        assertTrue(PrintFormat.fileName("x".repeat(500), 25_000.0, "2026-10-02").length < 100)
    }

    @Test
    fun `the scale is written with no-break spaces and the declination with minutes`() {
        val texts = PrintTexts()
        assertEquals("1:25\u00A0000", PrintFormat.scale(25_000.0, texts))
        assertEquals("1:500", PrintFormat.scale(500.0, texts))
        assertEquals("2°15′ E", PrintFormat.declination(2.25, "E", "W"))
        assertEquals("0°30′ W", PrintFormat.declination(-0.5, "E", "W"))
    }
}
