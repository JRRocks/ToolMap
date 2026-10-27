package fr.toolmap.core.tiles

import fr.toolmap.core.geo.Bounds
import fr.toolmap.core.geo.LonLat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TilePlannerStoredTest {
    private val z = 13

    /** A fake store holding [stored] (x to y) at zoom [z]: answers the two box questions of countStored. */
    private class FakeStore(
        val stored: Set<Pair<Int, Int>>,
    ) {
        var listed = false

        fun countIn(
            xs: IntRange,
            ys: IntRange,
        ): Long = stored.count { (x, y) -> x in xs && y in ys }.toLong()

        fun storedIn(
            xs: IntRange,
            ys: IntRange,
        ): (Int, Int) -> Boolean {
            listed = true
            val inBox = stored.filter { (x, y) -> x in xs && y in ys }.toSet()
            return { x, y -> (x to y) in inBox }
        }
    }

    private fun count(
        area: DownloadArea,
        store: FakeStore,
        sampleLimit: Long = TilePlanner.STORED_SAMPLE_LIMIT,
    ) = TilePlanner.countStored(area, z, store::countIn, store::storedIn, sampleLimit)

    @Test
    fun `a rectangle counts the stored tiles of its box without listing them`() {
        val area = DownloadArea.Rectangle(Bounds(2.30, 48.80, 2.40, 48.90))
        val tiles = TilePlanner.tiles(area, z..z).toList()
        val outside = TileId(z, tiles.maxOf { it.x } + 1, tiles.first().y)
        val store = FakeStore((tiles.take(5) + outside).map { it.x to it.y }.toSet())
        assertEquals(5L, count(area, store))
        assertTrue(!store.listed)
    }

    @Test
    fun `a polygon counts only the stored tiles it covers, not those of its box`() {
        val ring = listOf(LonLat(2.20, 48.75), LonLat(2.50, 48.75), LonLat(2.20, 48.95))
        val area = DownloadArea.Polygon(ring)
        val inside = TilePlanner.tiles(area, z..z).map { it.x to it.y }.toSet()
        val box = TilePlanner.tiles(DownloadArea.Rectangle(Bounds(2.20, 48.75, 2.50, 48.95)), z..z)
        val outside = box.map { it.x to it.y }.filter { it !in inside }.toList()
        assertTrue(outside.isNotEmpty())
        val store = FakeStore(inside.take(7).toSet() + outside)
        assertEquals(7L, count(area, store))
    }

    @Test
    fun `nothing stored in the box gives zero without listing`() {
        val ring = listOf(LonLat(2.20, 48.75), LonLat(2.50, 48.75), LonLat(2.20, 48.95))
        val store = FakeStore(setOf(0 to 0))
        assertEquals(0L, count(DownloadArea.Polygon(ring), store))
        assertTrue(!store.listed)
    }

    @Test
    fun `beyond the sample limit a polygon scales the box count by its share of the box`() {
        val ring = listOf(LonLat(2.20, 48.75), LonLat(2.50, 48.75), LonLat(2.20, 48.95))
        val area = DownloadArea.Polygon(ring)
        val tiles = TilePlanner.tiles(area, z..z).toList()
        val xs = tiles.minOf { it.x }..tiles.maxOf { it.x }
        val ys = tiles.minOf { it.y }..tiles.maxOf { it.y }
        val everything = xs.flatMap { x -> ys.map { y -> x to y } }.toSet()
        val store = FakeStore(everything)
        // Every tile of the box is stored: the scaled count is exactly the polygon's own tiles.
        assertEquals(tiles.size.toLong(), count(area, store, sampleLimit = 1L))
        assertTrue(!store.listed)
    }
}
