package fr.toolmap.core.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScaleBarTest {
    @Test
    fun `world at zoom 0 is 512 dp wide`() {
        // Equator: 40 075 016.69 m / 512 dp
        assertEquals(78271.517, ScaleBar.metersPerDp(0.0, 0.0), 0.001)
    }

    @Test
    fun `each zoom level halves the resolution and latitude scales by cosine`() {
        val z10 = ScaleBar.metersPerDp(0.0, 10.0)
        assertEquals(z10 / 2, ScaleBar.metersPerDp(0.0, 11.0), 1e-9)
        assertEquals(z10 * Math.cos(Math.toRadians(45.0)), ScaleBar.metersPerDp(45.0, 10.0), 1e-9)
    }

    @Test
    fun `camera zoom 12 near Grenoble is about 13_47 m per dp`() {
        // The camera uses 512 dp tiles: at zoom 12 the world is 512 * 4096 dp wide.
        // 40 075 016.69 m * cos(45.19 deg) / 2 097 152 dp = 13.4674 m (checked with Python).
        assertEquals(13.4674, ScaleBar.metersPerDp(45.19, 12.0), 0.001)
    }

    @Test
    fun `nice distances follow the 1-2-5 series`() {
        assertEquals(1.0, ScaleBar.niceDistance(1.0))
        assertEquals(1.0, ScaleBar.niceDistance(1.99))
        assertEquals(2.0, ScaleBar.niceDistance(2.0))
        assertEquals(2.0, ScaleBar.niceDistance(4.99))
        assertEquals(5.0, ScaleBar.niceDistance(5.0))
        assertEquals(5.0, ScaleBar.niceDistance(9.99))
        assertEquals(10.0, ScaleBar.niceDistance(10.0))
        assertEquals(1000.0, ScaleBar.niceDistance(1234.0))
        assertEquals(20000.0, ScaleBar.niceDistance(37000.0))
        assertEquals(0.2, ScaleBar.niceDistance(0.3), 1e-12)
        assertEquals(0.5, ScaleBar.niceDistance(0.99), 1e-12)
    }

    @Test
    fun `nice distance never exceeds its input and is within a factor 2_5`() {
        var value = 0.013
        repeat(200) {
            val nice = ScaleBar.niceDistance(value)
            assertTrue(nice <= value, "$nice > $value")
            assertTrue(nice * 2.5 > value, "$nice too small for $value")
            value *= 1.137
        }
    }

    @Test
    fun `invalid inputs give 0`() {
        assertEquals(0.0, ScaleBar.niceDistance(0.0))
        assertEquals(0.0, ScaleBar.niceDistance(-3.0))
        assertEquals(0.0, ScaleBar.niceDistance(Double.NaN))
        assertEquals(0.0, ScaleBar.niceDistance(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `labels use cm, m and km`() {
        assertEquals("50 cm", ScaleBar.format(0.5))
        assertEquals("20 m", ScaleBar.format(20.0))
        assertEquals("500 m", ScaleBar.format(500.0))
        assertEquals("1 km", ScaleBar.format(1000.0))
        assertEquals("20 km", ScaleBar.format(20000.0))
        assertEquals("2000 km", ScaleBar.format(2_000_000.0))
    }

    @Test
    fun `computed bar fits in the available width`() {
        for (zoom in listOf(2.0, 5.5, 9.0, 12.3, 15.0, 18.7)) {
            val bar = ScaleBar.compute(45.0, zoom, maxWidthDp = 120.0)
            assertTrue(bar.widthDp <= 120.0 + 1e-9, "zoom $zoom: ${bar.widthDp}")
            assertTrue(bar.widthDp > 120.0 / 2.5 - 1e-9, "zoom $zoom: too short ${bar.widthDp}")
            assertTrue(bar.label.isNotEmpty())
        }
    }

    @Test
    fun `with MapLibre's default tile size the tile zoom is camera zoom plus one`() {
        assertEquals(12.0, ZoomMapping.STANDARD.toTileZoom(11.0))
        assertEquals(11.0, ZoomMapping.STANDARD.toCameraZoom(12.0))
    }

    @Test
    fun `decimal degrees ignore the device locale`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE)
            assertEquals("44.80000, 5.90000", CoordinateFormat.decimalDegrees(44.8, 5.9))
            assertEquals("44.80000° N, 5.90000° W", CoordinateFormat.decimalDegreesWithHemisphere(44.8, -5.9))
            assertEquals("12.50000° S, 5.90000° E", CoordinateFormat.decimalDegreesWithHemisphere(-12.5, 5.9))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
