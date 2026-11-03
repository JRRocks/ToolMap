package fr.toolmap.data.tiles

import fr.toolmap.core.layers.BlendMode
import fr.toolmap.core.layers.BlendRecipe
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomSet
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Tiles of blended layers (P26): read from each layer of the recipe, then drawn together. */
class BlendInterceptorTest {
    private val store = InMemoryTileStore()
    private val stats = TileStats()
    private val offline = OfflineMode(initial = true)

    /** What each blended tile was drawn from: the map and image of every layer, bottom first. */
    private val blends = mutableListOf<List<Pair<String, String?>>>()
    private val blender =
        TileBlender { layers ->
            blends += layers.map { (part, bytes) -> part.sourceId to bytes?.decodeToString() }
            "blended".encodeToByteArray()
        }

    private fun source(id: String) =
        TileSource(id = id, name = id, levels = listOf(LevelDef(ZoomSet.range(0, 18), listOf("http://x/{\$z}"))))

    private val client =
        OkHttpClient
            .Builder()
            .addInterceptor(
                TileInterceptor(
                    StaticSourceLookup(listOf(source("osm"), source("slopes"), source("ign"))),
                    store,
                    stats,
                    offline,
                    blender = blender,
                ),
            ).build()

    private fun stored(
        id: String,
        z: Int = 12,
    ) = store.put(TileKey(id, z, 2100, 1470), StoredTile(id.encodeToByteArray(), "image/png"))

    private fun tile(
        recipe: BlendRecipe,
        z: Int = 12,
    ): Response {
        val top = recipe.parts.last().sourceId
        val url = "https://${TileHost.HOST}/$top/$z/2100/1470?${recipe.query()}"
        return client.newCall(Request.Builder().url(url).build()).execute()
    }

    private val recipe =
        BlendRecipe(
            listOf(
                LayerConfig("", "osm"),
                LayerConfig("", "slopes", opacity = 0.5f, displayMinZoom = 13),
                LayerConfig("", "ign", blend = BlendMode.MULTIPLY),
            ),
        )

    @Test
    fun `a blended tile is drawn from the layers under it and its own`() {
        stored("osm")
        stored("ign")
        tile(recipe).use { response ->
            assertEquals(200, response.code)
            assertEquals("image/png", response.header("Content-Type"))
            assertContentEquals("blended".encodeToByteArray(), response.body!!.bytes())
        }
        // The slopes start at zoom 13: not part of this tile.
        assertEquals(listOf("osm" to "osm", "ign" to "ign"), blends.single())
        assertEquals(1, stats.snapshot().count(TileOutcome.COMPOSED))
    }

    @Test
    fun `a layer missing under it is drawn without it`() {
        stored("ign", z = 13)
        tile(recipe, z = 13).use { assertEquals(200, it.code) }
        assertEquals(listOf("osm" to null, "slopes" to null, "ign" to "ign"), blends.single())
    }

    @Test
    fun `without its own tile there is nothing to blend`() {
        stored("osm")
        tile(recipe).use { assertEquals(404, it.code) }
        assertEquals(emptyList(), blends)
    }
}
