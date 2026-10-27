package fr.toolmap.core.tiles

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TileMathTest {
    private fun assertNear(
        expected: Double,
        actual: Double,
        tolerance: Double = 1e-6,
    ) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    @Test
    fun `quadkey matches the Bing reference`() {
        assertEquals("213", TileMath.quadKey(3, 5, 3))
        assertEquals("", TileMath.quadKey(0, 0, 0))
        assertEquals("0", TileMath.quadKey(0, 0, 1))
        assertEquals("1", TileMath.quadKey(1, 0, 1))
        assertEquals("2", TileMath.quadKey(0, 1, 1))
        assertEquals("3", TileMath.quadKey(1, 1, 1))
    }

    @Test
    fun `quadkey length equals the zoom`() {
        assertEquals(14, TileMath.quadKey(8460, 5950, 14).length)
    }

    @Test
    fun `tms row is the flipped xyz row`() {
        assertEquals(2, TileMath.tmsY(5, 3))
        assertEquals(7, TileMath.tmsY(0, 3))
        assertEquals(0, TileMath.tmsY(7, 3))
        assertEquals(0, TileMath.tmsY(0, 0))
    }

    @Test
    fun `world tile bounds`() {
        val lonLat = TileMath.boundsLonLat(0, 0, 0)
        assertNear(-180.0, lonLat.minX)
        assertNear(180.0, lonLat.maxX)
        assertNear(-85.0511287798, lonLat.minY, 1e-8)
        assertNear(85.0511287798, lonLat.maxY, 1e-8)
        val mercator = TileMath.boundsMercator(0, 0, 0)
        assertNear(-TileMath.ORIGIN_SHIFT_M, mercator.minX)
        assertNear(TileMath.ORIGIN_SHIFT_M, mercator.maxY)
    }

    @Test
    fun `tileAt matches an independent computation`() {
        // Reference values computed separately in Python (math.asinh / math.tan formulas):
        // IGN preview location 5.90,44.80 is tile 14/8460/5906 and 12/2115/1476.
        assertEquals(TileCoord(8460, 5906), TileMath.tileAt(5.90, 44.80, 14))
        assertEquals(TileCoord(2115, 1476), TileMath.tileAt(5.90, 44.80, 12))
        assertEquals(TileCoord(0, 0), TileMath.tileAt(-180.0, 85.0, 0))
        assertEquals(TileCoord(1, 1), TileMath.tileAt(179.99, -85.0, 1))
    }

    @Test
    fun `tileAt is clamped at the edges of the map`() {
        assertEquals(TileCoord(0, 0), TileMath.tileAt(-200.0, 89.9, 5))
        assertEquals(TileCoord(31, 31), TileMath.tileAt(180.0, -89.9, 5))
    }

    @Test
    fun `a tile contains the point it was computed from`() {
        for (zoom in listOf(3, 9, 14, 18)) {
            val tile = TileMath.tileAt(5.90, 44.80, zoom)
            val bounds = TileMath.boundsLonLat(tile.x, tile.y, zoom)
            assertTrue(5.90 in bounds.minX..bounds.maxX, "lon not in $bounds at zoom $zoom")
            assertTrue(44.80 in bounds.minY..bounds.maxY, "lat not in $bounds at zoom $zoom")
        }
    }

    @Test
    fun `row 5950 at zoom 14 is the tile checked with curl in session 1`() {
        // Tile 14/8460/5950 answered HTTP 200 on the IGN service; it lies around 44.11 N, not at the preview point.
        val bounds = TileMath.boundsLonLat(8460, 5950, 14)
        assertNear(44.1034, bounds.minY, 1e-4)
        assertNear(44.1191, bounds.maxY, 1e-4)
    }

    @Test
    fun `adjacent tiles share an edge`() {
        val left = TileMath.boundsLonLat(10, 20, 6)
        val right = TileMath.boundsLonLat(11, 20, 6)
        assertNear(left.maxX, right.minX, 1e-12)
        val top = TileMath.boundsMercator(10, 20, 6)
        val bottom = TileMath.boundsMercator(10, 21, 6)
        assertNear(top.minY, bottom.maxY, 1e-6)
    }

    @Test
    fun `ground resolution`() {
        // Classic value: 156543.03 m/px at zoom 0 on the equator (256 px tiles).
        assertNear(156_543.03392804097, TileMath.metersPerPixel(0.0, 0), 1e-6)
        // Halves at each zoom and shrinks with cos(latitude).
        assertNear(TileMath.metersPerPixel(0.0, 10) / 2, TileMath.metersPerPixel(0.0, 11), 1e-9)
        assertNear(TileMath.metersPerPixel(0.0, 10) * 0.5, TileMath.metersPerPixel(60.0, 10), 1e-9)
        // 512 px tiles halve the resolution.
        assertNear(TileMath.metersPerPixel(45.0, 12) / 2, TileMath.metersPerPixel(45.0, 12, 512), 1e-9)
    }

    @Test
    fun `out of range tiles are rejected`() {
        assertFailsWith<IllegalArgumentException> { TileMath.quadKey(8, 0, 3) }
        assertFailsWith<IllegalArgumentException> { TileMath.tmsY(-1, 3) }
        assertFailsWith<IllegalArgumentException> { TileMath.tilesPerAxis(31) }
        assertFailsWith<IllegalArgumentException> { TileMath.boundsLonLat(0, 2, 1) }
    }
}
