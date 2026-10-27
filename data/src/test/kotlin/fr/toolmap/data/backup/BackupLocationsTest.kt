package fr.toolmap.data.backup

import fr.toolmap.core.backup.BackupArea
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals

class BackupLocationsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var card: File
    private lateinit var locations: BackupLocations

    @Before
    fun setUp() {
        filesDir = temp.newFolder("files")
        card = temp.newFolder("card", "tiles")
        locations = BackupLocations(filesDir, File(temp.root, "databases/tracks.db"), tilesDir = card)
    }

    @Test
    fun `the tile files are read and restored in the folder in use, not in the phone's folder`() {
        assertEquals(File(card, "osm.mbtiles"), locations.fileFor("tiles/osm.mbtiles"))
        assertEquals(listOf(card), locations.targets(BackupArea.TILES))
    }

    @Test
    fun `a backup lists the tile files of the folder in use`() {
        File(card, "osm.mbtiles").writeText("tiles")
        File(filesDir, "tiles").mkdirs()
        assertEquals(listOf("tiles/osm.mbtiles"), locations.liveFiles(BackupArea.TILES).map { it.first })
    }

    @Test
    fun `without a folder given, the phone's own folder is the one of the tiles`() {
        val phoneOnly = BackupLocations(filesDir, File(temp.root, "databases/tracks.db"))
        assertEquals(File(filesDir, "tiles"), phoneOnly.tilesDir)
        assertEquals(File(File(filesDir, "tiles"), "osm.mbtiles"), phoneOnly.fileFor("tiles/osm.mbtiles"))
    }
}
