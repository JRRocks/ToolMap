package fr.toolmap.data.tiles

import fr.toolmap.core.source.TileSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileStoreAndStatsTest {
    private fun tile(size: Int) = StoredTile(ByteArray(size), "image/png")

    @Test
    fun `store returns what was put`() {
        val store = InMemoryTileStore()
        val key = TileKey("s", 1, 2, 3)
        assertNull(store.get(key))
        store.put(key, tile(10))
        assertEquals(10, store.get(key)!!.bytes.size)
        assertEquals("image/png", store.get(key)!!.contentType)
        assertNull(store.get(TileKey("s", 1, 2, 4)))
    }

    @Test
    fun `least recently used tiles are evicted first`() {
        val store = InMemoryTileStore(maxBytes = 30)
        val a = TileKey("s", 0, 0, 0)
        val b = TileKey("s", 1, 0, 0)
        val c = TileKey("s", 1, 1, 0)
        val d = TileKey("s", 1, 1, 1)
        store.put(a, tile(10))
        store.put(b, tile(10))
        store.put(c, tile(10))
        store.get(a) // a becomes the most recently used
        store.put(d, tile(10))
        assertNotNull(store.get(a))
        assertNull(store.get(b), "b was the least recently used")
        assertNotNull(store.get(c))
        assertNotNull(store.get(d))
        assertEquals(30, store.sizeBytes())
        assertEquals(3, store.count())
    }

    @Test
    fun `replacing a tile keeps the size accounting right and oversized tiles are ignored`() {
        val store = InMemoryTileStore(maxBytes = 25)
        val key = TileKey("s", 0, 0, 0)
        store.put(key, tile(10))
        store.put(key, tile(20))
        assertEquals(20, store.sizeBytes())
        assertEquals(1, store.count())
        store.put(TileKey("s", 1, 0, 0), tile(26))
        assertNull(store.get(TileKey("s", 1, 0, 0)))
        assertEquals(20, store.sizeBytes())
    }

    @Test
    fun `stats count outcomes and zooms`() {
        val stats = TileStats()
        stats.record(TileOutcome.NETWORK_OK, z = 13, url = "https://a/1", httpCode = 200)
        stats.record(TileOutcome.STORE_HIT, z = 13)
        stats.record(TileOutcome.HTTP_ERROR, z = 14, url = "https://a/2", httpCode = 403, error = "boom")
        val snapshot = stats.snapshot()
        assertEquals(3, snapshot.total)
        assertEquals(1, snapshot.count(TileOutcome.STORE_HIT))
        assertEquals(0, snapshot.count(TileOutcome.OFFLINE_MISS))
        assertEquals(mapOf(13 to 2, 14 to 1), snapshot.perZoom)
        assertEquals(14, snapshot.lastZoom)
        assertEquals(403, snapshot.lastHttpCode)
        assertEquals("boom", snapshot.lastError)
        assertEquals("https://a/2", snapshot.lastUrl)
        stats.reset()
        assertEquals(0, stats.snapshot().total)
    }

    @Test
    fun `report lists every outcome`() {
        val stats = TileStats()
        stats.record(TileOutcome.NETWORK_OK, z = 14, url = "https://a/?apikey=abc", httpCode = 200)
        val report = stats.snapshot().report()
        TileOutcome.entries.forEach { assertTrue("${it.name.lowercase()}=" in report, "missing ${it.name}") }
        assertTrue("requests=1" in report)
        assertTrue("zoom_histogram=14:1" in report)
        assertTrue("last_url=https://a/?apikey=***" in report, report)
    }

    @Test
    fun `credentials are masked in urls`() {
        assertEquals("https://h/p?apikey=***&layer=x", redactUrl("https://h/p?apikey=secret&layer=x"))
        assertEquals("https://h/p?a=1&Token=***", redactUrl("https://h/p?a=1&Token=abc"))
        assertEquals("https://h/p?api_key=***", redactUrl("https://h/p?api_key=abc"))
        assertEquals("https://h/p?layer=x&style=normal", redactUrl("https://h/p?layer=x&style=normal"))
        assertEquals("https://h/p", redactUrl("https://h/p"))
    }

    @Test
    fun `tile template targets the fake host`() {
        assertEquals("https://tiles.toolmap.local/osm/{z}/{x}/{y}", TileHost.templateFor("osm"))
        assertEquals(
            "https://tiles.toolmap.local/ign-scan25/{z}/{x}/{y}",
            TileHost.templateFor(TileSource(id = "ign-scan25", name = "n")),
        )
    }

    @Test
    fun `source ids with spaces or slashes survive the round trip`() {
        for (id in listOf("IGN Plan v2", "a/b", "é 100%", "x?y#z")) {
            val url = TileHost.templateFor(id).replace("{z}", "1").replace("{x}", "2").replace("{y}", "3").toHttpUrl()
            assertEquals(TileHost.HOST, url.host)
            assertEquals(listOf(id, "1", "2", "3"), url.pathSegments, "id '$id' -> $url")
        }
    }

    @Test
    fun `offline mode can be toggled`() {
        val mode = OfflineMode()
        assertEquals(false, mode.isOffline())
        mode.set(true)
        assertEquals(true, mode.isOffline())
        assertEquals(true, mode.enabled.value)
    }
}
