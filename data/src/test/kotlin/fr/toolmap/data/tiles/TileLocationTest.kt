package fr.toolmap.data.tiles

import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileLocationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var files: File
    private lateinit var location: TileLocation

    @Before
    fun setUp() {
        files = temp.newFolder("files")
        location = TileLocation(files)
    }

    @Test
    fun `the phone's own folder is in use until another one is recorded`() {
        assertEquals(File(files, "tiles"), location.defaultDirectory)
        assertEquals(location.defaultDirectory, location.active())
        assertNull(location.requestedMove())
    }

    @Test
    fun `the folder in use is read back by a new instance`() {
        val card = File(temp.root, "card/tiles")
        location.setActive(card)
        assertEquals(card.absolutePath, TileLocation(files).active().absolutePath)
    }

    @Test
    fun `a requested move is kept until it is cleared, and does not change the folder in use`() {
        val card = File(temp.root, "card/tiles")
        location.requestMove(card)
        assertEquals(card.absolutePath, location.requestedMove()?.absolutePath)
        assertEquals(location.defaultDirectory, location.active())
        location.clearMove()
        assertNull(location.requestedMove())
    }

    @Test
    fun `the record is written in one step, without a temporary file left behind`() {
        location.setActive(File(temp.root, "card/tiles"))
        assertTrue(File(files, "tiles-location.txt").isFile)
        assertFalse(File(files, "tiles-location.txt.tmp").exists())
    }

    @Test
    fun `an empty record means the phone's own folder`() {
        File(files, "tiles-location.txt").writeText("   \n")
        assertEquals(location.defaultDirectory, location.active())
    }
}
