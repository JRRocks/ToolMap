package fr.toolmap.maps

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import fr.toolmap.core.layers.Workspace
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * « Cartes et calques » since P29.3 (« Strates »): the stack is the panel, its lines unfold in place, the favourites
 * are chips, the offline mode and the coverage are two switches, four tiles lead out (no « Importer .aqx » here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h2000dp")
class MapsRootPanelTest {
    @get:Rule
    val rule = createComposeRule()

    private val fixture = MapsPanelFixture()
    private val opened = mutableListOf<String>()
    private val deleted = mutableListOf<Workspace>()

    @After
    fun tearDown() = fixture.close()

    private fun setContent(zoom: Double = 12.0) {
        rule.setContent {
            val state by fixture.vm.state.collectAsState()
            MapsRootPanel(
                state = state,
                maps = fixture.maps,
                vm = fixture.vm,
                actions =
                    MapsRootActions(
                        onOpenAvailable = { opened += "available" },
                        onSaveArea = { opened += "zone" },
                        onOpenDownloads = { opened += "downloads" },
                        onOpenStorage = { opened += "storage" },
                        onFavoriteDeleted = { deleted += it },
                    ),
                currentZoom = zoom,
                maxHeight = 1900.dp,
            )
        }
    }

    @Test
    fun `the band says how many maps are shown and drawn at the zoom of the map`() {
        setContent(zoom = 12.0)
        // At zoom 12 the slopes and IGN Cartes are drawn; OpenStreetMap, kept to zooms 1 to 8, is not.
        rule.onNodeWithText("3 cartes\n2 dessinées au zoom 12").assertExists()
    }

    @Test
    fun `the stack is in the panel, top first, with no sheet to open`() {
        setContent()
        rule.onNodeWithText("Cartes affichées · du dessus vers le dessous").assertExists()
        val top = { text: String -> rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top("IGN Pentes montagne") < top("OpenStreetMap"))
        assertTrue(top("OpenStreetMap") < top("IGN Cartes"))
        rule.onNodeWithText("Visible aux zooms 1 à 8 · inactif à ce zoom", substring = true).assertExists()
    }

    @Test
    fun `a tap unfolds a line in place, with the opacity and the commands of the layer`() {
        setContent()
        rule.onAllNodesWithText("Opacité : 100 %").assertCountEquals(0)
        rule.onNodeWithText("IGN Cartes").performClick()
        rule.onNodeWithText("Opacité : 100 %").assertExists()
        for (command in listOf("Monter", "Descendre", "Conditions", "Couleurs", "Plus")) {
            rule.onNodeWithText(command).assertExists()
        }
        // Monter moves the layer up: IGN Cartes comes above OpenStreetMap.
        rule.onNodeWithText("Monter").performClick()
        rule.waitForIdle()
        val order = fixture.vm.state.value.stack.topToBottom().map { it.sourceId }
        assertEquals(listOf("ign-slopes", "ign-maps", "osm"), order)
    }

    @Test
    fun `the colours unfold under the commands`() {
        setContent()
        rule.onNodeWithText("IGN Cartes").performClick()
        rule.onNodeWithText("Couleurs").performClick()
        rule.onNodeWithText("Contraste : 0 %").assertExists()
        rule.onNodeWithText("Couleurs inversées").assertExists()
    }

    @Test
    fun `the eye hides a layer without unfolding its line`() {
        setContent()
        rule.onAllNodesWithContentDescription("Masquer la carte").onFirst().performClick()
        rule.waitForIdle()
        assertTrue(fixture.vm.state.value.stack.topToBottom().first().visible.not())
        rule.onAllNodesWithText("Opacité : 100 %").assertCountEquals(0)
    }

    @Test
    fun `a favourite chip offers Appliquer and Supprimer on a long press, and a delete can be undone`() {
        setContent()
        rule.onNodeWithText("Rando Multi-Zoom").performTouchInput { longClick() }
        rule.onNodeWithText("Appliquer").assertExists()
        rule.onNodeWithText("Supprimer").performClick()
        assertEquals(listOf("Rando Multi-Zoom"), deleted.map { it.name })
    }

    @Test
    fun `offline and coverage are switches, and four tiles lead out`() {
        setContent()
        val switch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        rule.onNode(switch and hasText("Hors ligne")).performScrollTo().assertIsOff()
        rule.onNode(switch and hasText("Couverture")).assertIsOff()
        rule.onNode(switch and hasText("Hors ligne")).performClick()
        rule.waitForIdle()
        assertTrue(fixture.vm.offline.value)
        for (tile in listOf("Cartes disponibles", "Enregistrer une zone", "Téléchargements", "Stockage")) {
            rule.onNodeWithText(tile).performScrollTo().performClick()
        }
        assertEquals(listOf("available", "zone", "downloads", "storage"), opened)
        rule.onAllNodesWithText("Importer .aqx").assertCountEquals(0)
    }
}
