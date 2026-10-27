package fr.toolmap.storage

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.toolmap.core.source.BuiltinSources
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.storage.StorageManager
import fr.toolmap.data.tiles.MbTilesStore
import fr.toolmap.data.tiles.StoredTile
import fr.toolmap.data.tiles.TileKey
import fr.toolmap.testing.stopTurningLooper
import fr.toolmap.ui.theme.ToolMapTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals

/**
 * « Stockage hors ligne » since P29.7 (« Poids »): the gauge of the phone first, the maps from the heaviest in one
 * block, the import as an icon of the bar, the empty storage leading to the selection of a zone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h2000dp")
class StorageScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val root: File = createTempDirectory("storage-screen").toFile()
    private val repository = SourceRepository(File(root, "sources"), BuiltinSources.load())
    private val store = MbTilesStore(File(root, "tiles"), repository)
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var vm: StorageViewModel
    private var zoneAsked = 0

    @After
    fun tearDown() {
        vm.stopTurningLooper()
        store.close()
        root.deleteRecursively()
    }

    private fun setContent(tiles: Boolean) {
        // 10 000 blocks of 4 096 bytes, 4 000 free: 41,0 Mo in all, 24,6 Mo used.
        ShadowStatFs.registerStats(app.filesDir, 10_000, 4_000, 4_000)
        if (tiles) {
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
            for (x in 0 until 20) store.put(TileKey("ign-maps", 14, x, x), StoredTile(png, "image/png", 1L))
            for (x in 0 until 5) store.put(TileKey("osm", 12, x, x), StoredTile(png, "image/png", 1L))
        }
        vm = StorageViewModel(app, StorageManager(store, repository), repository)
        rule.setContent {
            ToolMapTheme { StorageScreen(vm = vm, onBack = {}, onSelectZone = { zoneAsked++ }) }
        }
        val end = System.currentTimeMillis() + 10_000
        while (vm.state.value.busy || vm.state.value.spaceTotal == 0L) {
            check(System.currentTimeMillis() < end) { "Timed out waiting for the storage" }
            rule.waitForIdle()
            Thread.sleep(50)
        }
        rule.waitForIdle()
    }

    @Test
    fun `the gauge of the phone comes first, then the maps from the heaviest`() {
        setContent(tiles = true)
        rule.onNodeWithText("utilisés", substring = true).assertExists()
        rule.onNodeWithText("Tuiles de ToolMap", substring = true).assertExists()
        rule.onNodeWithText("De la plus lourde à la plus légère").assertExists()
        val top = { text: String -> rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(true, top("IGN Cartes") < top("OpenStreetMap"))
        rule.onNodeWithContentDescription("Importer un fichier .mbtiles").assertExists()
    }

    @Test
    fun `the empty storage leads to the selection of a zone`() {
        setContent(tiles = false)
        rule.onNodeWithText("Aucune tuile stockée pour l'instant.").assertExists()
        rule.onNodeWithText("Sélectionner et enregistrer une zone").performClick()
        assertEquals(1, zoneAsked)
    }
}
