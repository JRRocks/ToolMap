package fr.toolmap.core.tiles

import fr.toolmap.core.geo.Bounds
import fr.toolmap.core.geo.LonLat
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TilePlannerTest {
    private val paris = LonLat(2.35, 48.85)

    @Test
    fun `the whole world has 1, 4, 16 tiles at zoom 0, 1, 2`() {
        val world = DownloadArea.Rectangle(Bounds(-180.0, -85.0, 179.999, 85.0))
        assertEquals(mapOf(0 to 1L, 1 to 4L, 2 to 16L), TilePlanner.count(world, 0..2))
    }

    @Test
    fun `a rectangle covers exactly the tiles between its corners`() {
        val area = DownloadArea.Rectangle(Bounds(2.30, 48.80, 2.40, 48.90))
        val a = TileMath.tileAt(2.30, 48.90, 14)
        val b = TileMath.tileAt(2.40, 48.80, 14)
        val tiles = TilePlanner.tiles(area, 14..14).toList()
        assertEquals((b.x - a.x + 1) * (b.y - a.y + 1), tiles.size)
        assertTrue(tiles.all { it.z == 14 && it.x in a.x..b.x && it.y in a.y..b.y })
        assertEquals(tiles.size.toLong(), TilePlanner.count(area, 14..14).getValue(14))
        assertEquals(tiles.size, tiles.toSet().size)
    }

    @Test
    fun `a tiny rectangle is a single tile at every zoom`() {
        val area = DownloadArea.Rectangle(Bounds(2.3501, 48.8501, 2.3502, 48.8502))
        assertEquals((8..12).associateWith { 1L }, TilePlanner.count(area, 8..12))
    }

    @Test
    fun `tiles come ordered by zoom then row then column`() {
        val area = DownloadArea.Rectangle(Bounds(2.0, 48.5, 2.7, 49.1))
        val tiles = TilePlanner.tiles(area, 9..10).toList()
        assertEquals(tiles.sortedWith(compareBy({ it.z }, { it.y }, { it.x })), tiles)
    }

    @Test
    fun `a polygon smaller than a tile gives that one tile`() {
        val c = TileMath.tileAt(paris.lon, paris.lat, 12)
        val box = TileMath.boundsLonLat(c.x, c.y, 12)
        val cx = (box.minX + box.maxX) / 2
        val cy = (box.minY + box.maxY) / 2
        val e = (box.maxX - box.minX) / 10
        val ring = listOf(LonLat(cx - e, cy - e), LonLat(cx + e, cy - e), LonLat(cx, cy + e))
        val tiles = TilePlanner.tiles(DownloadArea.Polygon(ring), 12..12).toList()
        assertEquals(listOf(TileId(12, c.x, c.y)), tiles)
    }

    @Test
    fun `a polygon larger than a tile and fully inside includes tiles with no vertex in them`() {
        // A big square around Paris at z13: the centre tile has no vertex and no edge in it.
        val ring =
            listOf(LonLat(2.20, 48.75), LonLat(2.50, 48.75), LonLat(2.50, 48.95), LonLat(2.20, 48.95))
        val tiles = TilePlanner.tiles(DownloadArea.Polygon(ring), 13..13).toSet()
        val centre = TileMath.tileAt(2.35, 48.85, 13)
        assertTrue(TileId(13, centre.x, centre.y) in tiles)
        val rect = TilePlanner.tiles(DownloadArea.Rectangle(Bounds(2.20, 48.75, 2.50, 48.95)), 13..13).toSet()
        // The polygon is a rectangle in lon/lat: Mercator edges are straight too, so both plans agree.
        assertEquals(rect, tiles)
    }

    @Test
    fun `a concave polygon leaves out the notch`() {
        // U shape opening to the north: the tile in the notch is excluded.
        val ring =
            listOf(
                LonLat(2.0, 48.0),
                LonLat(2.3, 48.0),
                LonLat(2.3, 48.3),
                LonLat(2.2, 48.3),
                LonLat(2.2, 48.1),
                LonLat(2.1, 48.1),
                LonLat(2.1, 48.3),
                LonLat(2.0, 48.3),
            )
        val z = 12
        val tiles = TilePlanner.tiles(DownloadArea.Polygon(ring), z..z).toSet()
        val notch = TileMath.tileAt(2.15, 48.25, z) // well inside the notch (x 2.1..2.2, y > 48.1)
        assertTrue(TileId(z, notch.x, notch.y) !in tiles)
        val arm = TileMath.tileAt(2.05, 48.25, z)
        assertTrue(TileId(z, arm.x, arm.y) in tiles)
        val bottom = TileMath.tileAt(2.15, 48.05, z)
        assertTrue(TileId(z, bottom.x, bottom.y) in tiles)
    }

    @Test
    fun `a polygon needs three points`() {
        assertFailsWith<IllegalArgumentException> {
            TilePlanner.tiles(DownloadArea.Polygon(listOf(paris, paris)), 5..5).toList()
        }
    }

    // Independent check for corridors: walk the path every few meters and measure the distance to each tile.
    private fun distanceToTile(
        path: List<LonLat>,
        tile: TileId,
    ): Double {
        val box = TileMath.boundsMercator(tile.x, tile.y, tile.z)
        var best = Double.MAX_VALUE
        for (i in 1 until path.size) {
            val a = path[i - 1]
            val b = path[i]
            val steps = 4000
            for (k in 0..steps) {
                val t = k.toDouble() / steps
                val lon = a.lon + (b.lon - a.lon) * t
                val lat = a.lat + (b.lat - a.lat) * t
                val mx = TileMath.mercatorX(lon)
                val my = TileMath.mercatorY(lat)
                val dx = max(max(box.minX - mx, 0.0), mx - box.maxX)
                val dy = max(max(box.minY - my, 0.0), my - box.maxY)
                best = minOf(best, hypot(dx, dy) * cos(Math.toRadians(lat)))
            }
        }
        return best
    }

    @Test
    fun `a corridor keeps every tile within the radius and none far from it`() {
        val path = listOf(LonLat(2.30, 48.84), LonLat(2.36, 48.87), LonLat(2.42, 48.86))
        val radius = 1_000.0
        val z = 14
        val tiles = TilePlanner.tiles(DownloadArea.Corridor(path, radius), z..z).toSet()
        // Every tile of the neighbourhood that really is within the radius must be present.
        val a = TileMath.tileAt(2.25, 48.92, z)
        val b = TileMath.tileAt(2.47, 48.80, z)
        var within = 0
        for (y in a.y..b.y) {
            for (x in a.x..b.x) {
                val d = distanceToTile(path, TileId(z, x, y))
                if (d <= radius) {
                    within++
                    assertTrue(TileId(z, x, y) in tiles, "tile $x,$y at ${d.toInt()} m is missing")
                }
            }
        }
        assertTrue(within > 10, "only $within tiles within the radius")
        // And the plan must not wander: nothing farther than the radius plus about one tile and a half.
        val tileMeters = 2 * TileMath.ORIGIN_SHIFT_M / TileMath.tilesPerAxis(z) * cos(Math.toRadians(48.85))
        for (t in tiles) assertTrue(distanceToTile(path, t) <= radius + tileMeters, "tile ${t.x},${t.y} too far")
    }

    @Test
    fun `a single point corridor is the disc around it`() {
        val z = 15
        val tiles = TilePlanner.tiles(DownloadArea.Corridor(listOf(paris), 50.0), z..z).toList()
        assertTrue(tiles.isNotEmpty() && tiles.size <= 9)
        val c = TileMath.tileAt(paris.lon, paris.lat, z)
        assertTrue(TileId(z, c.x, c.y) in tiles)
    }

    @Test
    fun `a wider corridor never has fewer tiles`() {
        val path = listOf(LonLat(2.30, 48.84), LonLat(2.42, 48.88))
        val narrow = TilePlanner.count(DownloadArea.Corridor(path, 200.0), 12..15)
        val wide = TilePlanner.count(DownloadArea.Corridor(path, 2_000.0), 12..15)
        for (z in 12..15) assertTrue(wide.getValue(z) >= narrow.getValue(z))
        assertTrue(wide.getValue(15) > narrow.getValue(15))
    }

    @Test
    fun `a zone that is too big is refused`() {
        val europe = DownloadArea.Rectangle(Bounds(-10.0, 35.0, 30.0, 60.0))
        assertFailsWith<TooManyTilesException> { TilePlanner.count(europe, 0..18) }
        assertFailsWith<TooManyTilesException> { TilePlanner.tiles(europe, 16..16).toList() }
        assertFailsWith<TooManyTilesException> {
            TilePlanner.tiles(DownloadArea.Rectangle(Bounds(2.0, 48.0, 3.0, 49.0)), 10..14, limit = 100).toList()
        }
    }

    @Test
    fun `size estimation multiplies the tile count`() {
        assertEquals(2_000_000L, TilePlanner.estimateBytes(100))
        assertEquals(500L, TilePlanner.estimateBytes(5, 100))
    }
}
