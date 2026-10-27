package fr.toolmap.maps

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import fr.toolmap.ui.Formats
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.ToolMapTheme
import fr.toolmap.wizard.WizardKind
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * « Cartes disponibles » since P29.6 (« Bascule »): two segments, the radio of the base and the switches of the
 * layers, « Annuler » after a tap, the status in the bar, the loupe that also searches the online catalogue, the ⋮ of
 * the bar and the sheet « Ajouter une carte ».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h2000dp")
class AvailableMapsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val fixture = MapsPanelFixture()
    private val calls = ArrayList<String>()
    private val switch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @After
    fun tearDown() = fixture.close()

    private fun bottomBase() = fixture.vm.state.value.stack.baseLayer(fixture.vm::isBase)?.sourceId

    private fun setContent() {
        rule.setContent {
            ToolMapTheme {
                CompositionLocalProvider(LocalFormats provides Formats()) {
                    AvailableMapsScreen(
                        maps = fixture.maps,
                        vm = fixture.vm,
                        actions =
                            AvailableMapsActions(
                                onBack = {},
                                onOpenWizard = { calls += "wizard $it" },
                                onImport = { calls += "import" },
                                onOpenStorage = { calls += "storage" },
                                onNewMultiZoom = { calls += "multizoom" },
                                onNewMap = { calls += "new $it" },
                            ),
                    )
                }
            }
        }
    }

    @Test
    fun `the bar says what the map shows`() {
        setContent()
        // IGN Cartes at the bottom, OpenStreetMap and the slopes above it.
        rule.onNodeWithText("Fond : IGN Cartes · 2 calques").assertExists()
    }

    @Test
    fun `a message sent while the screen was away shows when it is back`() {
        // The multi-zoom editor saves and shows its map before « Cartes disponibles » is composed again.
        fixture.maps.notice("Carte affichée : Rando")
        setContent()
        rule.onNodeWithText("Carte affichée : Rando").assertExists()
    }

    @Test
    fun `a radio says the base, a tap replaces it and Annuler puts it back`() {
        setContent()
        rule.onNode(radio and hasText("IGN Cartes", substring = true)).assertIsSelected()
        rule.onAllNodesWithText("IGN Pentes montagne").assertCountEquals(0)
        rule.onNode(radio and hasText("OpenStreetMap", substring = true)).performClick()
        rule.waitForIdle()
        assertEquals("osm", bottomBase())
        rule.onNodeWithText("Carte affichée : OpenStreetMap").assertExists()
        rule.onNodeWithText("Annuler").performClick()
        rule.waitForIdle()
        assertEquals("ign-maps", bottomBase())
        rule.onNodeWithText("Fond : IGN Cartes · 2 calques").assertExists()
    }

    @Test
    fun `Calques lists the layers and the bases above the base, each with its switch`() {
        setContent()
        rule.onNodeWithText("Calques").performClick()
        rule.onNode(switch and hasText("IGN Pentes montagne", substring = true)).assertIsOn()
        rule.onNode(switch and hasText("OpenStreetMap", substring = true)).assertIsOn()
        rule.onAllNodesWithText("IGN Cartes").assertCountEquals(0)
        rule.onNode(switch and hasText("IGN Pentes montagne", substring = true)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("osm", "ign-maps"), fixture.vm.state.value.stack.topToBottom().map { it.sourceId })
        rule.onNode(switch and hasText("IGN Pentes montagne", substring = true)).assertIsOff()
    }

    @Test
    fun `the menu of a map has no Afficher comme fond and eight lines at most`() {
        setContent()
        rule.onAllNodesWithContentDescription("Menu de la carte")[0].performClick()
        rule.onAllNodesWithText("Afficher comme fond de carte").assertCountEquals(0)
        rule.onNodeWithText("Ajouter comme calque").assertExists()
        rule.onNodeWithText("Copier pour modifier").assertExists()
        rule.onNodeWithText("Masquer de la liste").assertExists()
    }

    @Test
    fun `the loupe searches the list and the online catalogue`() {
        setContent()
        rule.onNodeWithContentDescription("Rechercher une carte").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("waymarked")
        rule.onNodeWithText("Dans le catalogue en ligne").assertExists()
        rule.onNodeWithText("VTT (Waymarked Trails)").assertExists()
        rule.onNodeWithContentDescription("Fermer la recherche").performClick()
        rule.onNodeWithText("Fonds de carte").assertExists()
    }

    @Test
    fun `the storage and the hidden maps are in the menu of the bar`() {
        fixture.maps.hideFromList("osm")
        setContent()
        rule.onNodeWithContentDescription("Plus d'options").performClick()
        rule.onNodeWithText("Afficher la carte masquée (1)").assertExists()
        rule.onNodeWithText("Stockage hors ligne…").performClick()
        assertEquals(listOf("storage"), calls)
    }

    @Test
    fun `Ajouter une carte opens a sheet with every way, each described`() {
        setContent()
        rule.onNodeWithText("Ajouter une carte", useUnmergedTree = true).performClick()
        rule.onNodeWithText("Cartes de grands fournisseurs, ajoutées en un geste.").assertExists()
        rule.onNodeWithText("Service WMTS").performClick()
        rule.onNodeWithText("Ajouter une carte", useUnmergedTree = true).performClick()
        rule.onNodeWithText("Importer un fichier (.aqx, .mbtiles)").performClick()
        assertEquals(listOf("new ${WizardKind.WMTS}", "import"), calls)
    }

    @Test
    fun `the catalogue counts its segments and offers Afficher once a map is added`() {
        val shown = ArrayList<String>()
        rule.setContent {
            ToolMapTheme { OnlineCatalogScreen(fixture.maps, onShow = { shown += it }, onBack = {}) }
        }
        val all = fixture.maps.onlineCatalog.flatMap { it.entries }
        val layers = all.count { it.source.isLayer }
        rule.onNodeWithText("Fonds de carte · ${all.size - layers}").assertExists()
        rule.onNodeWithText("Calques · $layers").performClick()
        rule.onNodeWithText("VTT (Waymarked Trails)").assertExists()
        rule.onAllNodesWithText("OpenStreetMap France").assertCountEquals(0)
        rule.onAllNodesWithText("Ajouter")[0].performClick()
        rule.onNodeWithText("Afficher").performClick()
        assertEquals(listOf("cat-osm-hiking"), shown)
    }
}
