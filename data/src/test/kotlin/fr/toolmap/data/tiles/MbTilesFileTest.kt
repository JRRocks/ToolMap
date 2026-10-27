package fr.toolmap.data.tiles

import android.database.sqlite.SQLiteDatabase
import fr.toolmap.core.geo.Bounds
import fr.toolmap.core.tiles.TileMath
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MbTilesFileTest {
    private lateinit var dir: File
    private lateinit var mb: MbTilesFile

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 9)

    @Before
    fun setUp() {
        dir = createTempDirectory("mbtiles").toFile()
        mb = MbTilesFile.open(File(dir, "t.mbtiles"), mapOf("name" to "Test", "type" to "baselayer"))
    }

    @After
    fun tearDown() {
        mb.close()
        dir.deleteRecursively()
    }

    @Test
    fun `put then get returns the bytes, a content type and the download date`() {
        mb.put(14, 8460, 5950, StoredTile(png, "image/png", storedAtMillis = 1234L))
        val tile = assertNotNull(mb.get(14, 8460, 5950))
        assertContentEquals(png, tile.bytes)
        assertEquals("image/png", tile.contentType)
        assertEquals(1234L, tile.storedAtMillis)
        assertNull(mb.get(14, 8460, 5951))
        assertTrue(mb.contains(14, 8460, 5950))
        assertFalse(mb.contains(14, 8461, 5950))
    }

    @Test
    fun `putting the same tile again replaces it`() {
        mb.put(3, 1, 1, StoredTile(png, "image/png", 1L))
        mb.put(3, 1, 1, StoredTile(jpeg, "image/jpeg", 2L))
        val tile = assertNotNull(mb.get(3, 1, 1))
        assertContentEquals(jpeg, tile.bytes)
        assertEquals("image/jpeg", tile.contentType)
        assertEquals(2L, tile.storedAtMillis)
        assertEquals(1L, mb.tileCount())
    }

    @Test
    fun `rows are stored in the TMS order so other tools can read the file`() {
        mb.put(3, 5, 2, StoredTile(png, "image/png"))
        mb.close()
        val raw = SQLiteDatabase.openDatabase(File(dir, "t.mbtiles").path, null, SQLiteDatabase.OPEN_READONLY)
        raw.rawQuery("SELECT zoom_level, tile_column, tile_row FROM tiles", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(3, c.getInt(0))
            assertEquals(5, c.getInt(1))
            assertEquals(TileMath.tmsY(2, 3), c.getInt(2))
            assertEquals(5, c.getInt(2))
        }
        raw.close()
        mb = MbTilesFile.open(File(dir, "t.mbtiles"))
    }

    @Test
    fun `batch writes are visible and counted by zoom`() {
        val entries =
            (0 until 4).map { TileEntry(5, it, 2, StoredTile(png, "image/png")) } +
                TileEntry(6, 0, 0, StoredTile(jpeg, "image/jpeg"))
        mb.putAll(entries)
        assertEquals(5L, mb.tileCount())
        assertEquals(mapOf(5 to 4L, 6 to 1L), mb.tileCountByZoom())
        assertEquals(png.size * 4L + jpeg.size, mb.payloadBytes())
    }

    @Test
    fun `stored tiles of an area are reported in XYZ coordinates`() {
        mb.put(10, 100, 200, StoredTile(png, "image/png"))
        mb.put(10, 101, 200, StoredTile(png, "image/png"))
        mb.put(10, 101, 205, StoredTile(png, "image/png"))
        val found = mb.storedTiles(10, 100..101, 198..201)
        assertEquals(setOf(TileSet.pack(100, 200), TileSet.pack(101, 200)), found)
        assertEquals(TileSet.pack(100, 200).let { TileSet.x(it) to TileSet.y(it) }, 100 to 200)
        assertTrue(mb.storedTiles(10, 0..5, 0..5).isEmpty())
    }

    @Test
    fun `delete removes one tile and its date`() {
        mb.put(4, 3, 3, StoredTile(png, "image/png"))
        mb.delete(4, 3, 3)
        assertNull(mb.get(4, 3, 3))
        assertEquals(0L, mb.tileCount())
    }

    @Test
    fun `deleteInArea purges only the tiles inside the area and zoom range`() {
        val pos = TileMath.tileAt(2.35, 48.85, 12)
        val far = TileMath.tileAt(-1.5, 43.4, 12)
        mb.put(12, pos.x, pos.y, StoredTile(png, "image/png"))
        mb.put(12, far.x, far.y, StoredTile(png, "image/png"))
        mb.put(8, TileMath.tileAt(2.35, 48.85, 8).x, TileMath.tileAt(2.35, 48.85, 8).y, StoredTile(png, "image/png"))
        val removed = mb.deleteInArea(Bounds(2.3, 48.8, 2.4, 48.9), 10..14)
        assertEquals(1, removed)
        assertNull(mb.get(12, pos.x, pos.y))
        assertNotNull(mb.get(12, far.x, far.y))
        assertEquals(2L, mb.tileCount())
    }

    @Test
    fun `deleteOlderThan keeps recent tiles`() {
        mb.put(2, 0, 0, StoredTile(png, "image/png", 100L))
        mb.put(2, 1, 0, StoredTile(png, "image/png", 900L))
        assertEquals(1, mb.deleteOlderThan(500L))
        assertNull(mb.get(2, 0, 0))
        assertNotNull(mb.get(2, 1, 0))
    }

    @Test
    fun `clear empties the tiles but keeps the metadata`() {
        mb.put(2, 0, 0, StoredTile(png, "image/png"))
        mb.clear()
        assertEquals(0L, mb.tileCount())
        assertEquals("Test", mb.metadata()["name"])
    }

    @Test
    fun `metadata gets the format and the zoom range`() {
        mb.put(7, 60, 40, StoredTile(png, "image/png"))
        mb.put(9, 240, 160, StoredTile(png, "image/png"))
        mb.refreshMetadata()
        val metadata = mb.metadata()
        assertEquals("png", metadata["format"])
        assertEquals("7", metadata["minzoom"])
        assertEquals("9", metadata["maxzoom"])
        val bounds = assertNotNull(metadata["bounds"]).split(",").map(String::toDouble)
        assertEquals(4, bounds.size)
        assertTrue(bounds[0] < bounds[2] && bounds[1] < bounds[3])
        val tile = TileMath.boundsLonLat(60, 40, 7)
        assertEquals(tile.minX, bounds[0], 1e-9)
        assertEquals(tile.maxY, bounds[3], 1e-9)
    }

    @Test
    fun `initial metadata does not overwrite what the file already has`() {
        mb.close()
        mb = MbTilesFile.open(File(dir, "t.mbtiles"), mapOf("name" to "Other", "version" to "2"))
        assertEquals("Test", mb.metadata()["name"])
        assertEquals("2", mb.metadata()["version"])
    }

    @Test
    fun `tiles written by another tool have no date and never expire`() {
        mb.close()
        val raw = SQLiteDatabase.openDatabase(File(dir, "t.mbtiles").path, null, SQLiteDatabase.OPEN_READWRITE)
        raw.execSQL(
            "INSERT INTO tiles VALUES (1, 0, 1, X'FFD8FFE000')",
        )
        raw.close()
        mb = MbTilesFile.open(File(dir, "t.mbtiles"))
        val tile = assertNotNull(mb.get(1, 0, 0))
        assertEquals(StoredTile.UNKNOWN_DATE_MILLIS, tile.storedAtMillis)
        assertEquals("image/jpeg", tile.contentType)
        assertFalse(tile.isStale(Long.MAX_VALUE, 1L))
    }

    @Test
    fun `a corrupt file is moved aside and replaced`() {
        mb.close()
        val file = File(dir, "bad.mbtiles")
        file.writeText("this is not a sqlite database at all, just text that is long enough to look like a header....")
        mb = MbTilesFile.open(file)
        mb.put(1, 0, 0, StoredTile(png, "image/png"))
        assertNotNull(mb.get(1, 0, 0))
        assertTrue(File(dir, "bad.mbtiles.corrupt").exists())
        mb.close()
        mb = MbTilesFile.open(File(dir, "t.mbtiles"))
    }

    @Test
    fun `checkpoint and compact keep the data readable`() {
        repeat(50) { mb.put(6, it, 0, StoredTile(png, "image/png")) }
        mb.checkpoint()
        mb.deleteInArea(Bounds(-180.0, -85.0, 180.0, 85.0), 6..6)
        mb.compact()
        assertEquals(0L, mb.tileCount())
        assertTrue(mb.diskBytes() > 0)
    }

    @Test
    fun `content type is recognised from the first bytes`() {
        assertEquals("image/png", MbTilesFile.contentTypeOf(png))
        assertEquals("image/jpeg", MbTilesFile.contentTypeOf(jpeg))
        val webp = "RIFF....WEBPVP8 ".toByteArray(Charsets.US_ASCII)
        assertEquals("image/webp", MbTilesFile.contentTypeOf(webp))
        assertNull(MbTilesFile.contentTypeOf(byteArrayOf(1, 2, 3)))
        assertNull(MbTilesFile.contentTypeOf(ByteArray(0)))
    }

    @Test
    fun `coverage tells exact, deeper and missing tiles apart`() {
        mb.put(10, 100, 200, StoredTile(png, "image/png"))
        // A deeper tile inside tile 9/50/100 (children are 10/100..101 x 200..201).
        assertEquals(Coverage.FULL, mb.coverage(10, 100, 200))
        assertEquals(Coverage.PARTIAL, mb.coverage(9, 50, 100))
        assertEquals(Coverage.PARTIAL, mb.coverage(7, 12, 25)) // 3 levels above: 7 -> 10
        assertEquals(Coverage.NONE, mb.coverage(6, 6, 12)) // 4 levels above is beyond the depth
        assertEquals(Coverage.NONE, mb.coverage(10, 102, 200))
        assertEquals(Coverage.NONE, mb.coverage(9, 51, 100))
    }

    @Test
    fun `coverage is not fooled by tiles that are close but outside`() {
        mb.put(11, 203, 400, StoredTile(png, "image/png")) // parent 10/101/200
        assertEquals(Coverage.PARTIAL, mb.coverage(10, 101, 200))
        assertEquals(Coverage.NONE, mb.coverage(10, 101, 201))
        assertEquals(Coverage.NONE, mb.coverage(10, 100, 200))
    }
}
