package fr.toolmap.data.tiles

import fr.toolmap.data.backup.RealRestoreFileOps
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The offline maps of a backup merge into the phone's (P33, ADR-106): what is missing comes, the most recent wins. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MbTilesMergeTest {
    private val dir: File = createTempDirectory("merge").toFile()

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    private fun tile(
        mark: Int,
        date: Long,
    ) = StoredTile(png + mark.toByte(), "image/png", storedAtMillis = date)

    private fun file(
        name: String,
        vararg tiles: Pair<Triple<Int, Int, Int>, StoredTile>,
    ): File {
        val file = File(dir, name)
        MbTilesFile.open(file).use { mb ->
            mb.putAll(tiles.map { (at, t) -> TileEntry(at.first, at.second, at.third, t) })
            mb.checkpoint()
        }
        return file
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `missing tiles are added and a tile on both sides keeps the most recent`() {
        val phone =
            file(
                "phone.mbtiles",
                Triple(12, 1, 1) to tile(1, date = 100),
                Triple(12, 1, 2) to tile(2, date = 500),
                Triple(12, 1, 3) to tile(3, date = StoredTile.UNKNOWN_DATE_MILLIS),
                Triple(13, 9, 9) to tile(4, date = 100),
            )
        val backup =
            file(
                "backup.mbtiles",
                // Newer than the phone's: replaces it.
                Triple(12, 1, 1) to tile(11, date = 200),
                // Older than the phone's: the phone's stays.
                Triple(12, 1, 2) to tile(12, date = 300),
                // The phone's has no date: it counts as the oldest.
                Triple(12, 1, 3) to tile(13, date = 1),
                // Missing on the phone: added.
                Triple(14, 5, 5) to tile(14, date = 50),
            )
        val written = MbTilesFile.open(phone).use { it.mergeFrom(backup) }
        assertEquals(3, written)
        MbTilesFile.open(phone).use { mb ->
            assertContentEquals(png + 11, mb.get(12, 1, 1)!!.bytes)
            assertEquals(200L, mb.get(12, 1, 1)!!.storedAtMillis)
            assertContentEquals(png + 2, mb.get(12, 1, 2)!!.bytes)
            assertContentEquals(png + 13, mb.get(12, 1, 3)!!.bytes)
            assertContentEquals(png + 14, mb.get(14, 5, 5)!!.bytes)
            assertEquals(50L, mb.get(14, 5, 5)!!.storedAtMillis)
            // A tile only the phone has is never removed.
            assertContentEquals(png + 4, mb.get(13, 9, 9)!!.bytes)
            assertEquals(mapOf(12 to 3L, 13 to 1L, 14 to 1L), mb.tileCountByZoom())
        }
    }

    @Test
    fun `the restore merges through the real file operations and refreshes the zooms of the map`() {
        val phone = file("phone.mbtiles", Triple(10, 0, 0) to tile(1, date = 10))
        MbTilesFile.open(phone).use { it.refreshMetadata() }
        val backup = file("backup.mbtiles", Triple(15, 3, 3) to tile(2, date = 20))
        RealRestoreFileOps.mergeTiles(backup, phone)
        MbTilesFile.open(phone).use { mb ->
            assertEquals("10", mb.metadata()["minzoom"])
            assertEquals("15", mb.metadata()["maxzoom"])
        }
    }

    @Test
    fun `merging the same file twice changes nothing more`() {
        val phone = file("phone.mbtiles", Triple(10, 0, 0) to tile(1, date = 10))
        val backup =
            file(
                "backup.mbtiles",
                Triple(10, 0, 0) to tile(2, date = 20),
                Triple(10, 0, 1) to tile(3, date = 5),
            )
        MbTilesFile.open(phone).use { assertEquals(2, it.mergeFrom(backup)) }
        MbTilesFile.open(phone).use { assertEquals(0, it.mergeFrom(backup)) }
    }
}
