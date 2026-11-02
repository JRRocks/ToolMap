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
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import fr.toolmap.core.layers.Workspace
import fr.toolmap.core.layers.Workspaces
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt
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
    private val updated = mutableListOf<Workspace>()

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
                        onFavoriteUpdated = { updated += it },
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
    fun `the colours have a hue in degrees, which Réinitialiser les couleurs puts back with the others`() {
        setContent()
        rule.onNodeWithText("IGN Cartes").performClick()
        rule.onNodeWithText("Couleurs").performClick()
        rule.onNodeWithText("Teinte : 0°").assertExists()
        val id = fixture.vm.state.value.stack.layers.first { it.sourceId == "ign-maps" }.id
        rule.runOnIdle {
            fixture.vm.setHue(id, 120f)
            fixture.vm.setAdjustments(id, 0.2f, 0f, 0f)
        }
        rule.onNodeWithText("Teinte : 120°").assertExists()
        rule.onNodeWithText("Réinitialiser les couleurs").performClick()
        rule.waitForIdle()
        val layer = fixture.vm.state.value.stack.layers.first { it.id == id }
        assertEquals(0f, layer.hue)
        assertEquals(0f, layer.contrast)
        rule.onNodeWithText("Teinte : 0°").assertExists()
    }

    @Test
    fun `sliding the hue turns the colours of the layer, in whole degrees`() {
        setContent()
        rule.onNodeWithText("IGN Cartes").performClick()
        rule.onNodeWithText("Couleurs").performClick()
        val hueSlider =
            SemanticsMatcher("the hue slider") {
                it.config.getOrElseNullable(SemanticsProperties.ProgressBarRangeInfo) { null }?.range == HUE_RANGE
            }
        rule.onNode(hueSlider).performTouchInput {
            down(center)
            moveTo(centerRight)
            up()
        }
        rule.waitForIdle()
        val hue = fixture.vm.state.value.stack.layers.first { it.sourceId == "ign-maps" }.hue
        assertTrue(hue > 150f, "hue: $hue")
        assertEquals(hue.roundToInt().toFloat(), hue)
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
    fun `a favourite changed since it was saved is updated in place after a short confirmation (variant B)`() {
        fixture.vm.saveWorkspace("Mes pentes")
        rule.waitUntil { fixture.vm.favorites.value.any { it.name == "Mes pentes" } }
        val slopes =
            fixture.vm.state.value.stack
                .topToBottom()
                .first()
        fixture.vm.setOpacity(slopes.id, 0.5f)
        setContent()
        rule.onNodeWithText("Mes pentes (modifié) · 3 cartes\n2 dessinées au zoom 12").assertExists()
        rule.onNodeWithText("Opacité 50 % (100 % dans le favori)").assertExists()
        rule.onNodeWithText("Enregistrer comme favori…").assertDoesNotExist()
        rule.onNodeWithText("Nouveau…").assertExists()
        rule.onNodeWithText("Mettre à jour").performScrollTo().performClick()
        rule.onNodeWithText("Mettre à jour « Mes pentes » ?").assertExists()
        rule.onNodeWithText("IGN Pentes montagne : 100 % → 50 %").assertExists()
        rule.onAllNodesWithText("Mettre à jour").onLast().performClick()
        rule.waitUntil {
            fixture.vm.favorites.value.any {
                it.name == "Mes pentes" &&
                    it.matches(fixture.vm.state.value.stack)
            }
        }
        assertEquals(listOf("Mes pentes"), updated.map { it.name })
        assertEquals(1, fixture.vm.favorites.value.count { it.name == "Mes pentes" })
        rule.onNodeWithText("Mes pentes · 3 cartes\n2 dessinées au zoom 12").assertExists()
        rule.onNodeWithText("Nouveau…").assertDoesNotExist()
    }

    @Test
    fun `a provided favourite updated by the user offers Rétablir l'original, and only that one`() {
        val rando = Workspaces.randoEte
        // Only its two OpenStreetMap layers are known here: the stack is not the favourite any more.
        fixture.vm.applyWorkspace(rando)
        setContent()
        rule.onNodeWithText("Rando été (modifié) · 2 cartes", substring = true).assertExists()
        rule.onNodeWithText("Mettre à jour").performScrollTo().performClick()
        rule.onNodeWithText("ign-pentes : retirée", substring = true).assertExists()
        rule.onAllNodesWithText("Mettre à jour").onLast().performClick()
        rule.waitUntil { fixture.vm.favorites.value.first() != rando }
        assertEquals(rando.id, fixture.vm.favorites.value.first().id)
        rule.onNodeWithText("Rando été · 2 cartes", substring = true).assertExists()

        rule.onNodeWithText("Rando été").performTouchInput { longClick() }
        rule.onNodeWithText("Rétablir l'original").performClick()
        rule.waitUntil { fixture.vm.favorites.value.first() == rando }
        rule.onNodeWithText("Rando été (modifié) · 2 cartes", substring = true).assertExists()

        rule.onNodeWithText("Rando Hiver").performTouchInput { longClick() }
        rule.onNodeWithText("Appliquer").assertExists()
        rule.onNodeWithText("Rétablir l'original").assertDoesNotExist()
    }

    @Test
    fun `a favourite chip offers Appliquer and Supprimer on a long press, and a delete can be undone`() {
        setContent()
        rule.onNodeWithText("Rando été").performTouchInput { longClick() }
        rule.onNodeWithText("Appliquer").assertExists()
        rule.onNodeWithText("Supprimer").performClick()
        assertEquals(listOf("Rando été"), deleted.map { it.name })
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
