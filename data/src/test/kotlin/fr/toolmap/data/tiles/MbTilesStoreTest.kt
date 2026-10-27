package fr.toolmap.data.tiles

import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.MultiZoomDraft
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomBand
import fr.toolmap.core.source.ZoomSet
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MbTilesStoreTest {
    private lateinit var dir: File
    private lateinit var store: MbTilesStore
    private val errors = mutableListOf<Throwable>()
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

    private val osm =
        TileSource(
            id = "osm",
            name = "OpenStreetMap",
            copyright = "© OpenStreetMap",
            levels =
                listOf(
                    LevelDef(ZoomSet.range(0, 19), listOf("https://tile.openstreetmap.org/{\$z}/{\$x}/{\$y}.png")),
                ),
        )

    @Before
    fun setUp() {
        dir = createTempDirectory("store").toFile()
        store = MbTilesStore(dir, StaticSourceLookup(listOf(osm)), errors::add)
    }

    private fun reopen(vararg sources: TileSource) {
        store.close()
        store = MbTilesStore(dir, StaticSourceLookup(sources.toList()), errors::add)
    }

    @After
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    @Test
    fun `tiles of different sources go to different files`() {
        store.put(TileKey("osm", 3, 1, 1), StoredTile(png, "image/png"))
        store.put(
            TileKey("other", 3, 1, 1),
            StoredTile(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), "image/jpeg"),
        )
        assertEquals("image/png", store.get(TileKey("osm", 3, 1, 1))?.contentType)
        assertEquals("image/jpeg", store.get(TileKey("other", 3, 1, 1))?.contentType)
        assertNull(store.get(TileKey("osm", 3, 2, 1)))
        assertTrue(File(dir, "osm.mbtiles").exists())
        assertTrue(File(dir, "other.mbtiles").exists())
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `a file created for a known source carries its descriptive metadata`() {
        val metadata = store.fileFor("osm").metadata()
        assertEquals("OpenStreetMap", metadata["name"])
        assertEquals("baselayer", metadata["type"])
        assertEquals("© OpenStreetMap", metadata["attribution"])
        assertEquals("osm", metadata["toolmap-source-id"])
    }

    @Test
    fun `stored tiles survive closing and reopening the store`() {
        store.put(TileKey("osm", 5, 10, 12), StoredTile(png, "image/png", 77L))
        store.close()
        store = MbTilesStore(dir)
        val tile = assertNotNull(store.get(TileKey("osm", 5, 10, 12)))
        assertContentEquals(png, tile.bytes)
        assertEquals(77L, tile.storedAtMillis)
    }

    @Test
    fun `source ids that are not filename safe get a safe, collision free name`() {
        val a = MbTilesStore.fileNameFor("../etc/passwd")
        val b = MbTilesStore.fileNameFor("..\\etc\\passwd")
        assertFalse(a.contains("/") || a.contains(".."))
        assertTrue(a.endsWith(".mbtiles"))
        assertTrue(a != b)
        assertEquals("ign-scan25.mbtiles", MbTilesStore.fileNameFor("ign-scan25"))
        store.put(TileKey("../etc/passwd", 1, 0, 0), StoredTile(png, "image/png"))
        assertNotNull(store.get(TileKey("../etc/passwd", 1, 0, 0)))
        assertEquals(1, dir.listFiles { f -> f.name.endsWith(".mbtiles") }!!.size)
    }

    @Test
    fun `the list of stored sources reads the original ids`() {
        store.put(TileKey("osm", 1, 0, 0), StoredTile(png, "image/png"))
        store.put(TileKey("my source/2", 1, 0, 0), StoredTile(png, "image/png"))
        store.close()
        store = MbTilesStore(dir)
        assertEquals(listOf("my source/2", "osm").sorted(), store.storedSourceIds())
    }

    @Test
    fun `deleting a source removes its files`() {
        store.put(TileKey("osm", 1, 0, 0), StoredTile(png, "image/png"))
        store.deleteSource("osm")
        assertFalse(File(dir, "osm.mbtiles").exists())
        assertNull(store.get(TileKey("osm", 1, 0, 0)))
        assertTrue(File(dir, "osm.mbtiles").exists()) // reopened empty by the get above
        assertEquals(0L, store.fileFor("osm").tileCount())
    }

    @Test
    fun `a closed store reports the error instead of throwing`() {
        val file = store.fileFor("osm")
        file.close()
        assertNull(store.get(TileKey("osm", 1, 0, 0)))
        store.put(TileKey("osm", 1, 0, 0), StoredTile(png, "image/png"))
        assertEquals(2, errors.size)
    }

    @Test
    fun `concurrent writers and readers do not lose tiles`() {
        val pool = Executors.newFixedThreadPool(6)
        try {
            val jobs =
                (0 until 6).map { worker ->
                    pool.submit {
                        for (i in 0 until 40) {
                            val key = TileKey("osm", 8, worker * 40 + i, 3)
                            store.put(key, StoredTile(png, "image/png"))
                            assertNotNull(store.get(key))
                        }
                    }
                }
            jobs.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(240L, store.fileFor("osm").tileCount())
        assertTrue(errors.isEmpty(), "errors: $errors")
    }

    @Test
    fun `release lets the file be copied and reopens on next use`() {
        store.put(TileKey("osm", 2, 1, 1), StoredTile(png, "image/png"))
        store.release("osm")
        val copy = File(dir, "copy.mbtiles")
        File(dir, "osm.mbtiles").copyTo(copy)
        assertNotNull(store.get(TileKey("osm", 2, 1, 1)))
        MbTilesFile.open(copy).use { assertEquals(1L, it.tileCount()) }
    }

    @Test
    fun `maps described by different files but serving the same tiles share one file`() {
        // The same OpenStreetMap server as written by another .aqx: other scheme, host case and an extra key.
        val copy =
            TileSource(
                id = "OSM_STANDARD",
                name = "OSM (copie)",
                levels =
                    listOf(
                        LevelDef(
                            ZoomSet.range(0, 18),
                            listOf("http://Tile.OpenStreetMap.org/{\$z}/{\$x}/{\$y}.png"),
                        ),
                    ),
            )
        val elsewhere =
            TileSource(
                id = "other-server",
                name = "Autre",
                levels = listOf(LevelDef(ZoomSet.range(0, 18), listOf("https://example.org/{\$z}/{\$x}/{\$y}.png"))),
            )
        reopen(osm, copy, elsewhere)
        store.put(TileKey("OSM_STANDARD", 4, 3, 2), StoredTile(png, "image/png"))
        assertContentEquals(png, store.get(TileKey("osm", 4, 3, 2))?.bytes)
        assertTrue(store.contains(TileKey("osm", 4, 3, 2)))
        assertEquals(setOf("osm"), store.storageIdsOf("OSM_STANDARD"))
        assertFalse(File(dir, "OSM_STANDARD.mbtiles").exists())
        // Another server gets a file named after its tiles, not after the map.
        val other = store.storageIdsOf("other-server").single()
        assertTrue(other.startsWith("t-"), other)
        assertNull(store.get(TileKey("other-server", 4, 3, 2)))
        assertEquals(listOf("osm", "OSM_STANDARD"), store.usersOf("osm", listOf(osm, copy, elsewhere)).map { it.id })
        assertEquals("OpenStreetMap", store.fileFor("osm").metadata()["name"])
    }

    @Test
    fun `a multi-zoom map reads the files of the maps it shows and never stores a forced zoom`() {
        val multi =
            MultiZoomDraft("Multi", listOf(ZoomBand(0, 10, "osm"), ZoomBand(11, 12, "osm", 13)))
                .toSource("mz", StaticSourceLookup(listOf(osm))::find)
        reopen(osm, multi)
        store.put(TileKey("osm", 9, 1, 1), StoredTile(png, "image/png"))
        assertContentEquals(png, store.get(TileKey("mz", 9, 1, 1))?.bytes)
        assertEquals("osm", store.storageIdOf(TileKey("mz", 9, 1, 1)))
        // Zoom 12 is drawn from zoom 13 tiles: no tile of its own is ever stored.
        assertNull(store.storageIdOf(TileKey("mz", 12, 1, 1)))
        store.put(TileKey("mz", 12, 1, 1), StoredTile(png, "image/png"))
        assertNull(store.get(TileKey("mz", 12, 1, 1)))
        assertEquals(setOf("osm"), store.storageIdsOf("mz"))
        assertFalse(File(dir, "mz.mbtiles").exists())
        assertTrue(errors.isEmpty(), "errors: $errors")
    }
}
