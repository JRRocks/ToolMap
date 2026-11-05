package fr.toolmap.maps

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import fr.toolmap.core.download.ZoneJob
import fr.toolmap.core.download.ZoneProgress
import fr.toolmap.core.download.ZoneRequest
import fr.toolmap.core.download.ZoneShape
import fr.toolmap.core.download.ZoneStatus
import fr.toolmap.core.layers.BlendMode
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
 * « Cartes et calques » since P29.3 (« Strates »), lightened by P34: the stack is the panel, a touch on a line opens
 * the sheet of the map, the favourites are chips, « Cartes disponibles » and « Hors ligne… » lead out (no « Importer
 * .aqx » here); the offline mode and the coverage are the two switches of the « Hors ligne » page.
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
    private val renamedTo = mutableListOf<Pair<String, String>>()

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
                        onFavoriteRenamed = { previous, name -> renamedTo += previous.name to name },
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
    fun `the stack is in the panel, top first, inactif à ce zoom only where it is so`() {
        setContent()
        rule.onNodeWithText("Du dessus vers le dessous").assertExists()
        val top = { text: String -> rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top("IGN Pentes montagne") < top("OpenStreetMap"))
        assertTrue(top("OpenStreetMap") < top("IGN Cartes"))
        // Only OpenStreetMap, kept to zooms 1 to 8, is not drawn at zoom 12.
        rule.onAllNodesWithText("inactif à ce zoom").assertCountEquals(1)
        rule.onNodeWithText("OpenStreetMap").assert(hasText("inactif à ce zoom"))
        rule.onAllNodesWithContentDescription("Modifier Opacité : 100 %").assertCountEquals(0)
    }

    @Test
    fun `a tap opens the sheet of the layer in the panel, and the arrow comes back to the list (P34)`() {
        setContent()
        rule.onNodeWithText("IGN Cartes").performClick()
        rule.onNodeWithText("3e carte sur 3").assertExists()
        rule.onNodeWithText("Du dessus vers le dessous").assertDoesNotExist()
        rule.onNodeWithText("Actif au zoom 12").assertExists()
        rule.onNodeWithText("Visible à tous les zooms").assertExists()
        rule.onNodeWithContentDescription("Modifier Opacité : 100 %").assertExists()
        rule.onNodeWithText("Tous les zooms · toutes altitudes").assertExists()
        rule.onNodeWithText("Normal · couleurs d'origine").assertExists()
        rule.onNodeWithText("3e sur 3").assertExists()
        rule.onNodeWithText("Détails · mettre à jour · ne plus afficher").assertExists()
        rule.onNodeWithContentDescription("Descendre").assertIsNotEnabled()
        // Monter moves the layer up: IGN Cartes comes above OpenStreetMap, 2nd of 3.
        rule.onNodeWithContentDescription("Monter").performClick()
        rule.waitForIdle()
        val order = fixture.vm.state.value.stack.topToBottom().map { it.sourceId }
        assertEquals(listOf("ign-slopes", "ign-maps", "osm"), order)
        rule.onNodeWithText("2e carte sur 3").assertExists()
        rule.onNodeWithContentDescription("Retour").performClick()
        rule.onNodeWithText("Du dessus vers le dessous").assertExists()
    }

    @Test
    fun `the sheet of a layer not drawn at this zoom says from which zoom it is, and its switch hides it`() {
        setContent()
        rule.onNodeWithText("OpenStreetMap").performClick()
        rule.onNodeWithText("Inactif au zoom 12").assertExists()
        rule.onNodeWithText("Visible aux zooms 1 à 8").assertExists()
        rule.onNodeWithText("Zooms 1 à 8 · toutes altitudes").assertExists()
        rule.onNodeWithText("2e carte sur 3").assertExists()
        val switch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        rule.onNode(switch).performClick()
        rule.waitForIdle()
        assertTrue(fixture.vm.state.value.stack.layers.first { it.sourceId == "osm" }.visible.not())
        rule.onNodeWithText("Masquée").assertExists()
    }

    @Test
    fun `the opacity of the sheet is typed in its window (P23_4)`() {
        setContent()
        rule.onNodeWithText("IGN Cartes").performClick()
        rule.onNodeWithContentDescription("Modifier Opacité : 100 %").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("87")
        rule.onNodeWithText("OK").performClick()
        rule.waitForIdle()
        val layer = { fixture.vm.state.value.stack.layers.first { it.sourceId == "ign-maps" } }
        assertEquals(0.87f, layer().opacity, 1e-6f)
        rule.onNodeWithContentDescription("Modifier Opacité : 87 %").assertExists()
    }

    @Test
    fun `the hue is typed in its window (P23_4)`() {
        setContent()
        rule.onNodeWithText("IGN Cartes").performClick()
        val layer = { fixture.vm.state.value.stack.layers.first { it.sourceId == "ign-maps" } }
        rule.onNodeWithText("Couleurs").performClick()
        rule.onNodeWithContentDescription("Modifier Rotation des couleurs : 0°").performScrollTo().performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("-45")
        rule.onNodeWithText("OK").performClick()
        rule.waitForIdle()
        assertEquals(-45f, layer().hue)
    }

    @Test
    fun `Couleurs opens the colours page of the sheet, and the arrow comes back to the sheet (P34)`() {
        setContent()
        rule.onNodeWithText("IGN Cartes").performClick()
        rule.onNodeWithText("Couleurs").performClick()
        rule.onNodeWithText("3e carte sur 3").assertDoesNotExist()
        rule.onNodeWithText("Fusion").assert(hasText("Normal"))
        rule.onNodeWithText("Teinter").assert(hasText("Aucune"))
        for (pill in listOf("Contraste : 0 %", "Luminosité : 0 %", "Saturation : 0 %", "Rotation des couleurs : 0°")) {
            rule.onNodeWithContentDescription("Modifier $pill").assertExists()
        }
        val switch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        rule.onNode(switch and hasText("Couleurs inversées")).assertIsOff().performClick()
        rule.waitForIdle()
        assertTrue(fixture.vm.state.value.stack.layers.first { it.sourceId == "ign-maps" }.inverted)
        rule.onNodeWithContentDescription("Retour").performClick()
        rule.onNodeWithText("3e carte sur 3").assertExists()
        rule.onNodeWithText("Normal · couleurs inversées").assertExists()
    }

    @Test
    fun `Fusion opens the sheet of the modes, explained, and a touch chooses one and closes it`() {
        setContent()
        rule.onNodeWithText("IGN Cartes").performClick()
        rule.onNodeWithText("Couleurs").performClick()
        rule.onNodeWithText("Fusion").performClick()
        rule.onNodeWithText("Fusion d'IGN Cartes").assertExists()
        rule.onNodeWithText("Assombrir").assertExists()
        rule.onNodeWithText("garde les traits sombres des deux cartes").assertExists()
        rule.onNodeWithText("Produit Multiply").performClick()
        rule.waitForIdle()
        val layer = fixture.vm.state.value.stack.layers.first { it.sourceId == "ign-maps" }
        assertEquals(BlendMode.MULTIPLY, layer.blend)
        rule.onAllNodesWithText("Fusion d'IGN Cartes").assertCountEquals(0)
        rule.onNodeWithText("Fusion").assert(hasText("Produit"))
        rule.onNodeWithContentDescription("Retour").performClick()
        rule.onNodeWithText("Produit · couleurs d'origine").assertExists()
    }

    @Test
    fun `Teinter opens a sheet whose dots tint the layer, and Retirer la teinte takes it off`() {
        setContent()
        rule.onNodeWithText("IGN Cartes").performClick()
        rule.onNodeWithText("Couleurs").performClick()
        rule.onNodeWithText("Teinter").performClick()
        rule.onNodeWithText("Teinter IGN Cartes").assertExists()
        // No colour yet: no intensity.
        rule.onAllNodesWithText("Intensité").assertCountEquals(0)
        rule.onNodeWithContentDescription("bleu").performClick()
        rule.waitForIdle()
        val id = fixture.vm.state.value.stack.layers.first { it.sourceId == "ign-maps" }.id
        val layer = { fixture.vm.state.value.stack.layers.first { it.id == id } }
        assertEquals(0x1E88E5, layer().tint)
        assertEquals(0.5f, layer().tintStrength)
        rule.onNodeWithText("bleu · 50 %").assertExists()
        // The line of the page, under the sheet that names « Teinter » too.
        rule.onAllNodesWithText("Teinter").onFirst().assert(hasText("Bleu · 50 %"))
        rule.onNodeWithText("Intensité").assertExists()
        rule.onNodeWithText("Retirer la teinte").performClick()
        rule.waitForIdle()
        assertEquals(null, layer().tint)
        // The ✕ of the sheet, not the one of the panel's band under it.
        rule.onAllNodesWithContentDescription("Fermer").onLast().performClick()
        rule.onAllNodesWithText("Teinter IGN Cartes").assertCountEquals(0)
        rule.runOnIdle { fixture.vm.setTint(id, 0x123456, 0.3f) }
        rule.onNodeWithText("Teinter").assert(hasText("Couleur choisie · 30 %"))
        rule.onNodeWithContentDescription("Retour").performClick()
        rule.onNodeWithText("Normal · teinté").assertExists()
    }

    @Test
    fun `the colours have a hue in degrees, which Réinitialiser les couleurs puts back with the others`() {
        setContent()
        rule.onNodeWithText("IGN Cartes").performClick()
        rule.onNodeWithText("Couleurs").performClick()
        rule.onNodeWithContentDescription("Modifier Rotation des couleurs : 0°").assertExists()
        val id = fixture.vm.state.value.stack.layers.first { it.sourceId == "ign-maps" }.id
        rule.runOnIdle {
            fixture.vm.setHue(id, 120f)
            fixture.vm.setAdjustments(id, 0.2f, 0f, 0f)
        }
        rule.onNodeWithContentDescription("Modifier Rotation des couleurs : 120°").assertExists()
        rule.onNodeWithText("Réinitialiser les couleurs").performClick()
        rule.waitForIdle()
        val layer = fixture.vm.state.value.stack.layers.first { it.id == id }
        assertEquals(0f, layer.hue)
        assertEquals(0f, layer.contrast)
        rule.onNodeWithContentDescription("Modifier Rotation des couleurs : 0°").assertExists()
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
    fun `the eye hides a layer without opening its sheet`() {
        setContent()
        rule.onAllNodesWithContentDescription("Masquer la carte").onFirst().performClick()
        rule.waitForIdle()
        assertTrue(fixture.vm.state.value.stack.topToBottom().first().visible.not())
        rule.onAllNodesWithContentDescription("Modifier Opacité : 100 %").assertCountEquals(0)
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
    fun `a map added since the favourite says so in the accent line (choice G, P34)`() {
        val osm =
            fixture.vm.state.value.stack.layers
                .first { it.sourceId == "osm" }
        fixture.vm.removeLayer(osm.id)
        fixture.vm.saveWorkspace("Sans OSM")
        rule.waitUntil { fixture.vm.favorites.value.any { it.name == "Sans OSM" } }
        fixture.vm.addLayer("osm")
        setContent()
        rule.onNodeWithText("OpenStreetMap").assert(hasText("Ajoutée depuis le favori"))
        rule.onNodeWithText("Mettre à jour").assertExists()
    }

    @Test
    fun `Plus opens a menu over the sheet, whose Ne plus afficher takes the map off and comes back to the list`() {
        setContent()
        rule.onNodeWithText("OpenStreetMap").performClick()
        rule.onNodeWithText("Plus").performClick()
        for (item in listOf("Détails", "Forcer la mise à jour", "Ne plus afficher les autres")) {
            rule.onNodeWithText(item).assertExists()
        }
        rule.onNodeWithText("Forcer la mise à jour").performClick()
        rule.waitForIdle()
        assertTrue("osm" in fixture.maps.forced.value)
        rule.onNodeWithText("Plus").performClick()
        rule.onNodeWithText("Ne plus afficher").performClick()
        rule.waitForIdle()
        val order = fixture.vm.state.value.stack.topToBottom().map { it.sourceId }
        assertEquals(listOf("ign-slopes", "ign-maps"), order)
        rule.onNodeWithText("Du dessus vers le dessous").assertExists()
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
    fun `a long press opens the sheet of the favourite, whose Supprimer can be undone`() {
        setContent()
        rule.onNodeWithText("Rando été").performTouchInput { longClick() }
        rule.onNodeWithText("Appliquer").assertExists()
        rule.onNodeWithText("Renommer…").assertExists()
        rule.onNodeWithText("Fichier .toolmap").assertExists()
        rule.onNodeWithText("6 cartes · favori fourni").assertExists()
        rule.onNodeWithText("Supprimer").performClick()
        assertEquals(listOf("Rando été"), deleted.map { it.name })
    }

    @Test
    fun `Renommer refuses the name of another favourite, then saves the new one with the same maps (P25_5)`() {
        setContent()
        rule.onNodeWithText("Rando été").performTouchInput { longClick() }
        rule.onNodeWithText("Renommer…").performClick()
        rule.onNodeWithText("Renommer le favori").assertExists()
        val field = hasSetTextAction()
        rule.onNode(field).performTextReplacement("rando hiver")
        rule.onNodeWithText("Un autre favori porte déjà ce nom.").assertExists()
        rule.onNodeWithText("Renommer").assertIsNotEnabled()
        rule.onNode(field).performTextReplacement("Rando été 2026")
        rule.onNodeWithText("Renommer").assertIsEnabled().performClick()
        rule.waitUntil { fixture.vm.favorites.value.any { it.name == "Rando été 2026" } }
        val renamed = fixture.vm.favorites.value.first()
        assertEquals(Workspaces.randoEte.id, renamed.id)
        assertEquals(Workspaces.randoEte.layers, renamed.layers)
        assertEquals(listOf("Rando été" to "Rando été 2026"), renamedTo)
        // A provided favourite renamed is the user's version: « Rétablir l'original » brings the name back.
        rule.onNodeWithText("Rando été 2026").performTouchInput { longClick() }
        rule.onNodeWithText("Nom et réglages livrés").assertExists()
        rule.onNodeWithText("Rétablir l'original").performClick()
        rule.waitUntil { fixture.vm.favorites.value.first() == Workspaces.randoEte }
    }

    @Test
    fun `Hors ligne opens a page of two switches and three ways out, and the list then says the offline mode (P34)`() {
        setContent()
        rule.onNodeWithText("Cartes disponibles").performScrollTo().performClick()
        rule.onNodeWithText("Hors ligne…").performScrollTo().performClick()
        rule.onNodeWithText("Tuiles stockées sur ce téléphone").assertExists()
        rule.onNodeWithText("Du dessus vers le dessous").assertDoesNotExist()
        val switch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        rule.onNode(switch and hasText("Couverture")).assertIsOff()
        rule.onNode(switch and hasText("Mode hors ligne")).assertIsOff().performClick()
        rule.waitForIdle()
        assertTrue(fixture.vm.offline.value)
        rule.onNodeWithText("Téléchargements").assert(hasText("Aucun en cours"))
        for (row in listOf("Enregistrer une zone", "Téléchargements", "Stockage")) {
            rule.onNodeWithText(row).performScrollTo().performClick()
        }
        assertEquals(listOf("available", "zone", "downloads", "storage"), opened)
        rule.onNodeWithContentDescription("Retour").performClick()
        rule.onNodeWithText("Hors ligne · activé").assertExists()
        rule.onNodeWithText("3 cartes\nHors ligne · tuiles stockées seulement").assertExists()
        rule.onAllNodesWithText("Importer .aqx").assertCountEquals(0)
    }

    @Test
    fun `the Hors ligne page says how far the downloads going are`() {
        val zone = ZoneShape.Rectangle(5.90, 45.40, 6.00, 45.50)
        fixture.jobs.save(
            ZoneJob(
                ZoneRequest("a", "Belledonne", listOf("osm"), 10, 14, zone),
                ZoneStatus.RUNNING,
                ZoneProgress(total = 200, downloaded = 80, alreadyStored = 4),
            ),
        )
        fixture.jobs.save(ZoneJob(ZoneRequest("b", "Vercors", listOf("osm"), 10, 14, zone), ZoneStatus.DONE))
        setContent()
        rule.onNodeWithText("Hors ligne…").performScrollTo().performClick()
        rule.onNodeWithText("Téléchargements").assert(hasText("1 en cours · 42 %"))
    }
}
