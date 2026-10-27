package fr.toolmap.core.tiles

import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.MapRef
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TileResolverTest {
    private fun map(
        id: String,
        vararg zooms: ZoomSet,
    ) = TileSource(id, id, levels = zooms.map { LevelDef(it, listOf("https://$id.example.org/{\$z}/{\$x}/{\$y}.png")) })

    private fun multi(
        id: String,
        vararg bands: Pair<ZoomSet, MapRef>,
    ) = TileSource(id, id, levels = bands.map { (zooms, ref) -> LevelDef(zooms, listOf(ref.toServer())) })

    private val scan = map("scan", ZoomSet.range(6, 16))
    private val maps = map("maps", ZoomSet.range(1, 18))
    private val catalogue = mutableMapOf(scan.id to scan, maps.id to maps)
    private val resolver = TileResolver { catalogue[it] }
    private val default = ScalingPolicy()

    @Test
    fun `a zoom the map serves is used as it is`() {
        val recipe = resolver.resolve(scan, 12, 2100, 1490, default)
        assertEquals(TileRecipe.Direct(scan, scan.levels.single(), TileId(12, 2100, 1490)), recipe)
    }

    @Test
    fun `below its first zoom a map is drawn by shrinking its first zoom`() {
        val recipe = resolver.resolve(scan, 5, 16, 11, default)
        assertIs<TileRecipe.Shrink>(recipe)
        assertEquals(1, recipe.depth)
        assertEquals(
            listOf(TileId(6, 32, 22), TileId(6, 33, 22), TileId(6, 32, 23), TileId(6, 33, 23)),
            recipe.leaves().map { it.tile },
        )
        // Three levels by default, then nothing: 4^3 = 64 tiles for one is the limit.
        assertEquals(64, resolver.resolve(scan, 3, 2, 1, default).leaves().size)
        assertEquals(TileRecipe.None, resolver.resolve(scan, 2, 1, 0, default))
        assertEquals(256, resolver.resolve(scan, 2, 1, 0, ScalingPolicy(4)).leaves().size)
    }

    @Test
    fun `above its last zoom a map is enlarged`() {
        val recipe = resolver.resolve(scan, 18, 4 * 2100 + 3, 4 * 1490 + 1, default)
        assertEquals(
            TileRecipe.Enlarge(TileRecipe.Direct(scan, scan.levels.single(), TileId(16, 2100, 1490)), 2, 3, 1),
            recipe,
        )
    }

    @Test
    fun `scaling off leaves the map empty outside its zooms`() {
        assertEquals(TileRecipe.None, resolver.resolve(scan, 5, 16, 11, ScalingPolicy.OFF))
        assertEquals(TileRecipe.None, resolver.resolve(scan, 17, 0, 0, ScalingPolicy.OFF))
    }

    @Test
    fun `a gap in the zoom list uses the nearest zoom, the coarser one on a tie`() {
        // Zoom values 3,5,7-16 as in the ski touring file: zoom 4 and 6 are missing.
        val gaps = map("gaps", ZoomSet.parse("3,5,7-16"))
        assertIs<TileRecipe.Enlarge>(resolver.resolve(gaps, 4, 5, 3, default))
        val six = resolver.resolve(gaps, 6, 33, 22, default)
        assertIs<TileRecipe.Enlarge>(six)
        assertEquals(5, six.leaves().single().tile.z)
        // Zoom 1 and 2 have no coarser zoom: zoom 3 is shrunk.
        assertEquals(setOf(3), resolver.resolve(gaps, 2, 1, 1, default).leaves().map { it.tile.z }.toSet())
    }

    @Test
    fun `a multi-zoom level shows the referenced map at the displayed zoom`() {
        val mz = multi("mz", ZoomSet.range(1, 11) to MapRef("maps"), ZoomSet.range(12, 16) to MapRef("scan"))
        catalogue[mz.id] = mz
        assertEquals(
            TileRecipe.Direct(maps, maps.levels.single(), TileId(9, 260, 180)),
            resolver.resolve(mz, 9, 260, 180, default),
        )
        assertEquals(
            TileRecipe.Direct(scan, scan.levels.single(), TileId(14, 8400, 5960)),
            resolver.resolve(mz, 14, 8400, 5960, default),
        )
        // Beyond its last band (16) the multi-zoom map enlarges its own zoom 16, i.e. SCAN 25.
        val z17 = resolver.resolve(mz, 17, 2 * 8400, 2 * 5960, default)
        assertIs<TileRecipe.Enlarge>(z17)
        assertEquals(TileId(16, 8400, 5960), z17.leaves().single().tile)
        assertEquals(scan, z17.leaves().single().source)
    }

    @Test
    fun `a forced zoom shrinks or enlarges the tiles of that zoom`() {
        // IGN Topo 25k: zooms 13-14 show zoom-15 tiles of IGN Cartes (the SCAN 25), 17-18 its zoom-16 tiles.
        val topo25 =
            multi(
                "topo25",
                ZoomSet.range(1, 12) to MapRef("maps"),
                ZoomSet.range(13, 14) to MapRef("maps", 15),
                ZoomSet.range(15, 16) to MapRef("maps"),
                ZoomSet.range(17, 18) to MapRef("maps", 16),
            )
        val z13 = resolver.resolve(topo25, 13, 4200, 2980, default)
        assertIs<TileRecipe.Shrink>(z13)
        assertEquals(2, z13.depth)
        assertEquals(16, z13.leaves().size)
        assertTrue(z13.leaves().all { it.source == maps && it.tile.z == 15 })
        assertEquals(TileId(15, 4 * 4200, 4 * 2980), z13.leaves().first().tile)
        val z18 = resolver.resolve(topo25, 18, 4 * 33600 + 1, 4 * 23840 + 2, default)
        assertEquals(
            TileRecipe.Enlarge(TileRecipe.Direct(maps, maps.levels.single(), TileId(16, 33600, 23840)), 2, 1, 2),
            z18,
        )
        // The forced zoom does not depend on the display scaling setting.
        assertEquals(16, resolver.resolve(topo25, 13, 4200, 2980, ScalingPolicy.OFF).leaves().size)
        assertEquals(mapOf(maps to setOf(15)), resolver.tileZooms(topo25, 14, default))
        assertEquals(mapOf(maps to setOf(16)), resolver.tileZooms(topo25, 17, default))
        assertEquals(mapOf(maps to setOf(12)), resolver.tileZooms(topo25, 12, default))
    }

    @Test
    fun `a forced zoom too far above is refused`() {
        // 6 levels below the forced zoom would need 4096 tiles for one.
        val far = multi("far", ZoomSet.range(1, 10) to MapRef("maps", 16))
        assertEquals(TileRecipe.None, resolver.resolve(far, 10, 0, 0, default))
        val near = multi("near", ZoomSet.range(12, 12) to MapRef("maps", 16))
        assertEquals(256, resolver.resolve(near, 12, 0, 0, default).leaves().size)
    }

    @Test
    fun `missing maps and cycles give nothing instead of looping`() {
        val orphan = multi("orphan", ZoomSet.range(1, 18) to MapRef("deleted"))
        assertEquals(TileRecipe.None, resolver.resolve(orphan, 10, 0, 0, default))
        val a = multi("a", ZoomSet.range(1, 18) to MapRef("b"))
        val b = multi("b", ZoomSet.range(1, 18) to MapRef("a"))
        catalogue += mapOf("a" to a, "b" to b)
        assertEquals(TileRecipe.None, resolver.resolve(a, 10, 0, 0, default))
        val self = multi("self", ZoomSet.range(1, 18) to MapRef("self"))
        catalogue["self"] = self
        assertEquals(TileRecipe.None, resolver.resolve(self, 10, 0, 0, default))
    }

    @Test
    fun `a tile never needs more than the leaf limit`() {
        // A chain of forced zooms that would multiply shrinking: 4 levels, then 4 more (65,536 tiles).
        val inner = multi("inner", ZoomSet.range(0, 20) to MapRef("maps", 18))
        val outer = multi("outer", ZoomSet.range(0, 20) to MapRef("inner", 14))
        catalogue += mapOf(inner.id to inner, outer.id to outer)
        assertEquals(TileRecipe.None, resolver.resolve(outer, 10, 0, 0, default))
    }
}
