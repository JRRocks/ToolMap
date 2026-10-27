package fr.toolmap.data.tiles

import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TileRelocatorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var source: File
    private lateinit var target: File

    @Before
    fun setUp() {
        source = temp.newFolder("phone", "tiles")
        target = File(temp.root, "card/tiles")
        File(source, "osm.mbtiles").writeBytes(ByteArray(3000) { it.toByte() })
        File(source, "osm.mbtiles-wal").writeBytes(ByteArray(40) { 1 })
        File(source, "t-1.mbtiles").writeBytes(ByteArray(10))
        File(source, "notes.txt").writeText("not a tile set")
    }

    @Test
    fun `lists the tile sets and their journals in name order, and nothing else`() {
        val names = TileRelocator(source, target).files().map { it.name }
        assertEquals(listOf("osm.mbtiles", "osm.mbtiles-wal", "t-1.mbtiles"), names)
    }

    @Test
    fun `copies every file at its size and reports the progress up to the total`() {
        val progress = mutableListOf<Pair<Long, Long>>()
        val copied = TileRelocator(source, target).copy { done, total -> progress += done to total }
        val expected = 3000L + 40L + 10L
        assertEquals(expected, copied)
        assertEquals(3000L, File(target, "osm.mbtiles").length())
        assertEquals(40L, File(target, "osm.mbtiles-wal").length())
        assertFalse(File(target, "notes.txt").exists())
        assertTrue(progress.isNotEmpty())
        assertEquals(expected to expected, progress.last())
    }

    @Test
    fun `the source stays until it is removed, once the copy is the folder in use`() {
        TileRelocator(source, target).copy()
        assertTrue(File(source, "osm.mbtiles").isFile)
        TileRelocator(source, target).removeSource()
        assertFalse(File(source, "osm.mbtiles").exists())
        assertTrue(File(target, "osm.mbtiles").isFile)
        assertTrue(File(source, "notes.txt").isFile, "only the tile files are removed")
    }

    @Test
    fun `a copy that fails leaves no file of the move in the target, and the source is untouched`() {
        // The third file cannot be written: its name is taken by a folder of the target.
        File(target, "t-1.mbtiles/inside").apply {
            parentFile?.mkdirs()
            writeText("taken")
        }
        assertFailsWith<IOException> { TileRelocator(source, target).copy() }
        assertFalse(File(target, "osm.mbtiles").exists())
        assertTrue(File(source, "osm.mbtiles").isFile)
    }

    @Test
    fun `journals left in the target by an earlier move are removed before the copy`() {
        target.mkdirs()
        File(target, "old-only.mbtiles-wal").writeBytes(ByteArray(5))
        TileRelocator(source, target).copy()
        assertFalse(
            File(target, "old-only.mbtiles-wal").exists(),
            "a stale journal would be applied to the new tile set",
        )
        assertTrue(File(target, "osm.mbtiles-wal").isFile)
    }

    @Test
    fun `a move onto the same folder is refused`() {
        assertFailsWith<IllegalArgumentException> { TileRelocator(source, source).copy() }
    }

    @Test
    fun `discarding the copies removes the files of the source only`() {
        TileRelocator(source, target).copy()
        File(target, "mine.txt").writeText("kept")
        TileRelocator(source, target).discardCopies()
        assertFalse(File(target, "osm.mbtiles").exists())
        assertTrue(File(target, "mine.txt").isFile)
    }

    @Test
    fun `tile files are the sets and their write-ahead journals`() {
        assertTrue(TileRelocator.isTileFile("a.mbtiles"))
        assertTrue(TileRelocator.isTileFile("a.mbtiles-wal"))
        assertTrue(TileRelocator.isTileFile("a.mbtiles-shm"))
        assertFalse(TileRelocator.isTileFile("a.mbtiles.tmp"))
        assertFalse(TileRelocator.isTileFile("tiles-location.txt"))
    }
}
