package fr.toolmap.data.storage

import android.database.sqlite.SQLiteDatabase
import fr.toolmap.core.geo.Bounds
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSetIdentity
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomSet
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.tiles.MbTilesFile
import fr.toolmap.data.tiles.MbTilesStore
import fr.toolmap.data.tiles.StoredTile
import fr.toolmap.data.tiles.TileKey
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StorageManagerTest {
    private lateinit var root: File
    private lateinit var store: MbTilesStore
    private lateinit var repository: SourceRepository
    private lateinit var manager: StorageManager
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

    private val osm =
        TileSource(
            id = "osm",
            name = "OpenStreetMap",
            levels =
                listOf(
                    LevelDef(ZoomSet.range(0, 19), listOf("https://tile.openstreetmap.org/{\$z}/{\$x}/{\$y}.png")),
                ),
        )

    @Before
    fun setUp() {
        root = createTempDirectory("storage").toFile()
        repository = SourceRepository(File(root, "sources"), listOf(osm))
        store = MbTilesStore(File(root, "tiles"), repository)
        manager = StorageManager(store, repository)
    }

    @After
    fun tearDown() {
        store.close()
        root.deleteRecursively()
    }

    private fun fill(
        sourceId: String,
        z: Int,
        range: IntRange,
        storedAt: Long = 1000L,
    ) {
        for (x in range) store.put(TileKey(sourceId, z, x, x), StoredTile(png + x.toByte(), "image/png", storedAt))
    }

    /** A minimal foreign MBTiles file (no ToolMap annex table), as another tool would write it. */
    private fun foreignMbTiles(
        name: String = "Foreign",
        format: String = "png",
        tileBytes: ByteArray? = png,
        tiles: Int = 3,
    ): ByteArray {
        val file = File(root, "foreign-${System.nanoTime()}.mbtiles")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
        db.execSQL("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
        db.execSQL("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)")
        db.execSQL("INSERT INTO metadata VALUES ('name', '$name'), ('format', '$format'), ('attribution', '© Foreign')")
        if (tileBytes != null) {
            repeat(tiles) { i ->
                db.execSQL(
                    "INSERT INTO tiles VALUES (?, ?, ?, ?)",
                    arrayOf<Any>(10 + i % 2, i, i, tileBytes + i.toByte()),
                )
            }
        }
        db.close()
        val bytes = file.readBytes()
        file.delete()
        return bytes
    }

    @Test
    fun `usage lists each stored source with its counts and sizes`() {
        fill("osm", 5, 0..9)
        fill("osm", 6, 0..4)
        fill("ghost", 3, 0..1)
        val usage = manager.usage().associateBy { it.sourceId }
        assertEquals(setOf("osm", "ghost"), usage.keys)
        val osmUsage = assertNotNull(usage["osm"])
        assertEquals("OpenStreetMap", osmUsage.name)
        assertEquals(15L, osmUsage.tiles)
        assertEquals(mapOf(5 to 10L, 6 to 5L), osmUsage.perZoom)
        assertTrue(osmUsage.diskBytes > 0)
        assertEquals("ghost", usage.getValue("ghost").name)
    }

    @Test
    fun `purge empties one source and leaves the others alone`() {
        fill("osm", 5, 0..9)
        fill("other", 5, 0..2)
        manager.purge("osm")
        assertEquals(0L, store.fileFor("osm").tileCount())
        assertEquals(3L, store.fileFor("other").tileCount())
    }

    @Test
    fun `purgeOlderThan removes only the old tiles`() {
        fill("osm", 5, 0..3, storedAt = 1_000L)
        fill("osm", 6, 0..4, storedAt = 9_000L)
        val removed = manager.purgeOlderThan("osm", 5_000L)
        assertEquals(4, removed)
        assertEquals(5L, store.fileFor("osm").tileCount())
    }

    @Test
    fun `purgeOlderThan keeps tiles whose date is unknown`() {
        fill("osm", 5, 0..2, storedAt = StoredTile.UNKNOWN_DATE_MILLIS)
        fill("osm", 6, 0..1, storedAt = 1_000L)
        assertEquals(2, manager.purgeOlderThan("osm", 5_000L))
        assertEquals(3L, store.fileFor("osm").tileCount())
    }

    @Test
    fun `purgeArea removes the tiles inside the area`() {
        fill("osm", 5, 0..9)
        // Tile (z5, x16, y10) is far from every stored tile (x = y = 0..9): the whole world at zoom 5 covers them all.
        val everything = Bounds(-180.0, -85.0, 180.0, 85.0)
        val removed = manager.purgeArea("osm", everything, 5..5)
        assertEquals(10, removed)
        assertEquals(0L, store.fileFor("osm").tileCount())
    }

    @Test
    fun `an exported package is a standard mbtiles file that can be imported back`() {
        fill("osm", 7, 0..4)
        val out = ByteArrayOutputStream()
        val size = manager.exportTo("osm", out)
        assertEquals(size, out.size().toLong())
        val staged = manager.stage(ByteArrayInputStream(out.toByteArray()))
        assertEquals("OpenStreetMap", staged.info.name)
        assertEquals(5L, staged.info.tiles)
        assertEquals(7, staged.info.minZoom)
        assertEquals(7, staged.info.maxZoom)
        assertEquals("osm", staged.info.sourceId)
        manager.discard(staged)
    }

    @Test
    fun `stage refuses what is not a raster mbtiles file`() {
        val text =
            assertFailsWith<PackageException> {
                manager.stage(ByteArrayInputStream("hello, not a database".toByteArray()))
            }
        assertEquals(PackageException.Reason.NOT_MBTILES, text.reason)

        val vector =
            assertFailsWith<PackageException> { manager.stage(ByteArrayInputStream(foreignMbTiles(format = "pbf"))) }
        assertEquals(PackageException.Reason.UNSUPPORTED_FORMAT, vector.reason)

        val notImages =
            assertFailsWith<PackageException> {
                manager.stage(ByteArrayInputStream(foreignMbTiles(tileBytes = byteArrayOf(1, 2, 3, 4))))
            }
        assertEquals(PackageException.Reason.UNSUPPORTED_FORMAT, notImages.reason)

        val empty =
            assertFailsWith<PackageException> { manager.stage(ByteArrayInputStream(foreignMbTiles(tileBytes = null))) }
        assertEquals(PackageException.Reason.EMPTY, empty.reason)
    }

    @Test
    fun `stage refuses a package above the size limit and leaves no temporary file`() {
        val bytes = foreignMbTiles()
        val e = assertFailsWith<PackageException> { manager.stage(ByteArrayInputStream(bytes), maxBytes = 1000) }
        assertEquals(PackageException.Reason.TOO_LARGE, e.reason)
        val leftovers = store.directory.listFiles { f -> f.name.startsWith(".import-") }.orEmpty()
        assertTrue(leftovers.isEmpty())
    }

    @Test
    fun `importing a foreign package as a new local map creates an offline-only source`() {
        val staged = manager.stage(ByteArrayInputStream(foreignMbTiles(name = "Carte de test é", tiles = 4)))
        assertEquals("© Foreign", staged.info.attribution)
        val id = manager.commit(staged, ImportTarget.NewLocalMap("Carte de test é"))

        val source = assertNotNull(repository.find(id))
        assertEquals("Carte de test é", source.name)
        assertTrue(source.levels.all { it.isLocalOnly })
        assertEquals(10, source.minZoom)
        assertEquals(11, source.maxZoom)
        assertEquals(4L, store.fileFor(id).tileCount())
        // Tiles written by other tools have no download date: they never expire.
        // The foreign file is TMS: its row 0 at zoom 10 is the XYZ row 1023.
        val tile = assertNotNull(store.get(TileKey(id, 10, 0, 1023)))
        assertEquals(StoredTile.UNKNOWN_DATE_MILLIS, tile.storedAtMillis)
        assertFalse(File(store.directory, ".import-x").exists())
        assertTrue(store.directory.listFiles { f -> f.name.startsWith(".import-") }.orEmpty().isEmpty())
    }

    @Test
    fun `two imports under the same name get distinct ids`() {
        val first =
            manager.commit(
                manager.stage(ByteArrayInputStream(foreignMbTiles())),
                ImportTarget.NewLocalMap("Ma carte"),
            )
        val second =
            manager.commit(
                manager.stage(ByteArrayInputStream(foreignMbTiles())),
                ImportTarget.NewLocalMap("Ma carte"),
            )
        assertTrue(first != second)
        assertNotNull(repository.find(first))
        assertNotNull(repository.find(second))
    }

    @Test
    fun `importing into an existing source merges without losing what was there`() {
        store.put(TileKey("osm", 10, 0, 1023), StoredTile(png, "image/png", 4242L))
        fill("osm", 20, 0..1)
        val staged = manager.stage(ByteArrayInputStream(foreignMbTiles(tiles = 3)))
        val id = manager.commit(staged, ImportTarget.Existing("osm"))
        assertEquals("osm", id)
        val file = store.fileFor("osm")
        // 2 existing tiles at z20 plus the 3 imported ones, one of which replaced the stored z10 tile.
        assertEquals(5L, file.tileCount())
        assertEquals(
            StoredTile.UNKNOWN_DATE_MILLIS,
            assertNotNull(store.get(TileKey("osm", 10, 0, 1023))).storedAtMillis,
        )
        assertNotNull(store.get(TileKey("osm", 20, 1, 1)))
    }

    @Test
    fun `an imported package into a fresh source keeps its tiles byte for byte`() {
        val staged = manager.stage(ByteArrayInputStream(foreignMbTiles(tiles = 2)))
        manager.commit(staged, ImportTarget.Existing("brand-new"))
        val tile = assertNotNull(store.get(TileKey("brand-new", 11, 1, 2046)))
        assertContentEquals(png + 1.toByte(), tile.bytes)
        assertEquals("image/png", tile.contentType)
        assertNull(store.get(TileKey("brand-new", 11, 0, 0)))
    }

    @Test
    fun `a local map can be deleted with its tiles but a regular source cannot`() {
        val id = manager.commit(manager.stage(ByteArrayInputStream(foreignMbTiles())), ImportTarget.NewLocalMap("Temp"))
        fill("osm", 5, 0..2)
        assertFalse(manager.deleteLocalMap("osm"))
        assertEquals(3L, store.fileFor("osm").tileCount())

        assertTrue(manager.deleteLocalMap(id))
        assertNull(repository.find(id))
        assertFalse(File(store.directory, MbTilesStore.fileNameFor(id)).exists())
        assertFalse(manager.deleteLocalMap(id))
    }

    @Test
    fun `slug builds a safe identifier`() {
        assertEquals("ma-carte-2024", StorageManager.slug("Ma carte 2024!"))
        assertEquals("map", StorageManager.slug("日本語"))
        assertEquals("carte-t", StorageManager.slug("  Carte  é  t  "))
    }

    private fun mapOn(
        id: String,
        url: String,
        zooms: ZoomSet = ZoomSet.range(0, 18),
    ) = TileSource(id = id, name = "Carte $id", levels = listOf(LevelDef(zooms, listOf(url))))

    @Test
    fun `maps serving the same tiles share one storage, counted for each of them`() {
        // The OpenStreetMap server written by another .aqx file.
        repository.save(mapOn("OSM_COPY", "http://TILE.openstreetmap.org/{\$z}/{\$x}/{\$y}.png"))
        repository.save(mapOn("elsewhere", "https://example.org/{\$z}/{\$x}/{\$y}.png"))
        fill("OSM_COPY", 5, 0..2)
        fill("osm", 6, 0..1)
        fill("elsewhere", 5, 0..0)
        val usages = manager.usage()
        val shared = usages.single { it.sourceId == "osm" }
        assertEquals(5L, shared.tiles)
        assertEquals(listOf("osm", "OSM_COPY"), shared.usedBy)
        assertEquals("OpenStreetMap, Carte OSM_COPY", shared.name)
        val bySource = manager.usageBySource(usages)
        assertEquals(5L, bySource.getValue("osm").first)
        assertEquals(5L, bySource.getValue("OSM_COPY").first)
        assertEquals(1L, bySource.getValue("elsewhere").first)
    }

    @Test
    fun `changing the address of a map frees only the tiles no other map shows`() {
        repository.save(mapOn("OSM_COPY", "https://tile.openstreetmap.org/{\$z}/{\$x}/{\$y}.png"))
        repository.save(mapOn("elsewhere", "https://example.org/{\$z}/{\$x}/{\$y}.png"))
        fill("OSM_COPY", 5, 0..2)
        fill("elsewhere", 5, 0..2)
        val moved = mapOn("x", "https://moved.example.org/{\$z}/{\$x}/{\$y}.png")
        // OpenStreetMap still shows the tiles of the copy: nothing to free.
        assertTrue(manager.unusedAfterReplacing("OSM_COPY", moved.copy(id = "OSM_COPY")).isEmpty())
        assertEquals(store.storageIdsOf("elsewhere"), manager.unusedAfterReplacing("elsewhere", moved))
        // The same address under another form keeps the tiles.
        val same = mapOn("elsewhere", "http://EXAMPLE.org/{\$z}/{\$x}/{\$y}.png")
        assertTrue(manager.unusedAfterReplacing("elsewhere", same).isEmpty())
    }

    @Test
    fun `files written before shared storage are merged into the storage of their tiles`() {
        repository.save(mapOn("IGN_COPY", "https://tile.openstreetmap.org/{\$z}/{\$x}/{\$y}.png"))
        store.directory.mkdirs()
        // Before ADR-031 the tiles of an imported map were kept in a file named after it.
        MbTilesFile.open(File(store.directory, "IGN_COPY.mbtiles"), mapOf("toolmap-source-id" to "IGN_COPY")).use {
            for (x in 0..4) it.put(7, x, x, StoredTile(png + x.toByte(), "image/png", 1000L))
        }
        fill("osm", 7, 3..3, storedAt = 5000L)

        assertEquals(1, manager.migrateLegacyFiles())
        assertFalse(File(store.directory, "IGN_COPY.mbtiles").exists())
        assertEquals(5L, store.fileFor("osm").tileCount())
        assertContentEquals(png + 1.toByte(), assertNotNull(store.get(TileKey("osm", 7, 1, 1))).bytes)
        // A tile already in the shared storage is kept as it was.
        assertEquals(5000L, assertNotNull(store.get(TileKey("IGN_COPY", 7, 3, 3))).storedAtMillis)
        assertEquals(0, manager.migrateLegacyFiles())
    }

    @Test
    fun `tiles of an imported map that is now built in move to the storage of the built-in map`() {
        // SnowBase Map (OSMSNOWM) was imported from COM.109.AQX, its tiles stored under their hash; built in since
        // ADR-069, the same tiles are now kept under the built-in id.
        val imported = mapOn("OSMSNOWM", "http://tiles.opensnowmap.org/base_snow_map/{\$z}/{\$x}/{\$y}.png")
        val hashed = TileSetIdentity.storageIdOf(TileSetIdentity.allOf(imported).single())
        assertEquals("OSMSNOWM", store.keys.takenOverByBuiltins[hashed])
        store.directory.mkdirs()
        MbTilesFile.open(File(store.directory, "$hashed.mbtiles"), mapOf("toolmap-source-id" to hashed)).use {
            for (x in 0..2) it.put(13, x, x, StoredTile(png + x.toByte(), "image/png", 1000L))
        }

        assertEquals(1, manager.migrateLegacyFiles())
        assertFalse(File(store.directory, "$hashed.mbtiles").exists())
        assertEquals(3L, store.fileFor("OSMSNOWM").tileCount())
        assertContentEquals(png + 2.toByte(), assertNotNull(store.fileFor("OSMSNOWM").get(13, 2, 2)).bytes)
        assertEquals(0, manager.migrateLegacyFiles())
    }

    @Test
    fun `local packages and files of unknown maps are not migrated`() {
        val id = manager.commit(manager.stage(ByteArrayInputStream(foreignMbTiles())), ImportTarget.NewLocalMap("Pack"))
        fill("ghost", 3, 0..1)
        assertEquals(0, manager.migrateLegacyFiles())
        assertEquals(3L, store.fileFor(id).tileCount())
        assertEquals(2L, store.fileFor("ghost").tileCount())
    }
}
