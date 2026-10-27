package fr.toolmap.wizard

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomSet
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.storage.StorageManager
import fr.toolmap.data.tiles.MbTilesStore
import fr.toolmap.data.wizard.CapabilitiesFetcher
import fr.toolmap.data.wizard.TileProbe
import fr.toolmap.testing.stopTurningLooper
import fr.toolmap.ui.theme.ToolMapTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The wizard since P29.6 (« Bascule »): it starts at the address of the kind chosen in the sheet, which is its title;
 * five named stops, a done one touched to go back; « Suivant : … » names what follows; no « Retour » at the start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h1600dp")
class SourceWizardScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val root: File = createTempDirectory("wizard-screen").toFile()
    private val repository =
        SourceRepository(
            File(root, "sources"),
            listOf(
                TileSource(
                    id = "osm",
                    name = "OpenStreetMap",
                    levels = listOf(LevelDef(ZoomSet.range(0, 19), listOf("https://t/{\$z}/{\$x}/{\$y}.png"))),
                ),
            ),
        )
    private val store = MbTilesStore(File(root, "tiles"), repository)
    private val vm =
        SourceWizardViewModel(
            RuntimeEnvironment.getApplication(),
            repository,
            StorageManager(store, repository),
            CapabilitiesFetcher(),
            TileProbe(),
        )

    @After
    fun tearDown() {
        vm.stopTurningLooper()
        store.close()
        root.deleteRecursively()
    }

    private fun setContent() {
        rule.setContent { ToolMapTheme { SourceWizardScreen(vm = vm, onClose = {}) } }
    }

    @Test
    fun `a new map is named by its kind and starts at the address, with no Retour`() {
        vm.start(null, null)
        vm.chooseKind(WizardKind.XYZ)
        setContent()
        rule.onNodeWithText("Adresse de tuiles (XYZ)").assertExists()
        rule.onNodeWithText("Nouvelle carte").assertExists()
        rule.onNodeWithContentDescription("Adresse, étape en cours").assertExists()
        listOf("Accès", "Zooms", "Test", "Enregistrer").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onAllNodesWithText("Retour").assertCountEquals(0)
        rule.onNodeWithText("Suivant : Accès").assertIsNotEnabled()
    }

    @Test
    fun `a stop done takes a tick and is touched to go back`() {
        vm.start(null, null)
        vm.chooseKind(WizardKind.XYZ)
        vm.setUrls("https://tiles.example.org/{\$z}/{\$x}/{\$y}.png")
        setContent()
        rule.onNodeWithText("Suivant : Accès").performClick()
        rule.onNodeWithText("Suivant : Zooms").assertExists()
        rule.onNodeWithText("Retour").assertExists()
        rule.onNodeWithContentDescription("Adresse, fait").performClick()
        assertEquals(WizardStep.ADDRESS, vm.state.value.step)
    }

    @Test
    fun `in a modification every stop can be touched`() {
        vm.start(null, null)
        vm.setName("Perso")
        vm.setUrls("https://tiles.example.org/{\$z}/{\$x}/{\$y}.png")
        val id = assertNotNull(vm.save())
        vm.start(id, null)
        setContent()
        rule.onNodeWithText("Modifier une carte").assertExists()
        rule.onNodeWithContentDescription("Enregistrer").performClick()
        assertEquals(WizardStep.SAVE, vm.state.value.step)
    }
}
