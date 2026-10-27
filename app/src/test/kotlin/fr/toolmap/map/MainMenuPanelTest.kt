package fr.toolmap.map

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The main menu does not repeat the settings: « Réglages… » leads to them (P11.11). Since P29.2 (« Portée ») the four
 * tiles are its fixed foot, « Plus » unfolds in place (no page of its own) and « Quitter » is at the top, far from the
 * thumb; the block of the maps shown names the favourite or the maps, never « Pile personnalisée ».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MainMenuPanelTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the options that the settings repeat are gone and Reglages is still in the menu`() {
        setContent()
        rule.onAllNodesWithText("Réglages…").assertCountEquals(1)
        rule.onAllNodesWithText("Mode hors ligne").assertCountEquals(0)
        rule.onAllNodesWithText("Mode nuit (thème sombre)").assertCountEquals(0)
        rule.onAllNodesWithText("Netteté des cartes…").assertCountEquals(0)
        rule.onAllNodesWithText("Informations à l'écran").assertCountEquals(1)
    }

    @Test
    fun `the tiles run their commands`() {
        setContent()
        rule.onNodeWithText("Masquer").performClick()
        rule.onNodeWithText("Verrouiller").performClick()
        rule.onNodeWithText("Imprimer").performClick()
        rule.onNodeWithText("Mesurer").performClick()
        assertEquals(listOf(MenuAction.HIDE, MenuAction.LOCK, MenuAction.PRINT, MenuAction.MEASURE), actions)
    }

    @Test
    fun `print and measure are off while a tool is on`() {
        setContent(toolsAllowed = false)
        rule.onNodeWithText("Imprimer").assertIsNotEnabled()
        rule.onNodeWithText("Mesurer").assertIsNotEnabled()
    }

    @Test
    fun `the tiles stay at the foot while the rows scroll, Quitter comes before Reglages`() {
        setContent(maxHeight = 360)
        // The rows scroll in a panel too short for them; the foot does not.
        rule.onNodeWithText("Mesurer").assertExists()
        val top = { text: String -> rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top("Quitter") < top("Réglages…"))
        assertTrue(top("Plus") < top("Quitter"))
        assertTrue(top("Masquer") > top("Réglages…"))
    }

    @Test
    fun `with a favourite the block names it and lists the maps, and opens the maps panel`() {
        setContent(favorite = "IGN Topo")
        rule.onNodeWithText("IGN Topo").assertExists()
        rule.onNodeWithText("Cartes affichées · Pentes + Plan IGN").performScrollTo().performClick()
        assertEquals(listOf(MenuAction.MAPS), actions)
        rule.onAllNodesWithText("Pile personnalisée").assertCountEquals(0)
    }

    @Test
    fun `without a favourite the block names the maps and says there is none`() {
        setContent(favorite = null, layers = listOf("IGN Cartes"))
        rule.onNodeWithText("IGN Cartes").assertExists()
        rule.onNodeWithText("Carte affichée · sans favori").assertExists()
    }

    @Test
    fun `the switch rows are switches for TalkBack and report the change`() {
        val changes = mutableListOf<Boolean>()
        setContent(onCoordinates = { changes += it })
        val switch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        rule.onNode(
            switch and hasText("Informations à l'écran", substring = true),
            useUnmergedTree = false,
        ).assertIsOff()
        rule.onNode(switch and hasText("Mode nuit", substring = true)).assertIsOff()
        rule.onNodeWithText("Informations à l'écran").performScrollTo().performClick()
        assertEquals(listOf(true), changes)
    }

    @Test
    fun `the night mode line says what the switch does, then what it did`() {
        setContent(night = false)
        rule.onNodeWithText("Assombrit la carte à 60 %").assertExists()
    }

    @Test
    fun `the night mode line, switched on`() {
        setContent(night = true)
        rule.onNodeWithText("Carte assombrie à 60 %").assertExists()
        val switch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        rule.onNode(switch and hasText("Mode nuit", substring = true)).assertIsOn()
    }

    @Test
    fun `Quitter is in the menu and runs the exit command`() {
        setContent()
        rule.onNodeWithText("Quitter").performScrollTo().performClick()
        assertEquals(listOf(MenuAction.EXIT), actions)
    }

    @Test
    fun `Plus unfolds in place with what is new and the report of an error, and no second way to the backup`() {
        setContent()
        rule.onAllNodesWithText("Nouveautés").assertCountEquals(0)
        rule.onNodeWithText("Plus").performClick()
        rule.onNodeWithText("Nouveautés").performScrollTo().performClick()
        rule.onNodeWithText("Signaler une erreur").performScrollTo().performClick()
        assertEquals(listOf(MenuAction.WHATS_NEW, MenuAction.REPORT_ERROR), actions)
        rule.onAllNodesWithText("Sauvegarde et restauration…").assertCountEquals(0)
        // The rest of the menu is still there, under the unfolded row.
        rule.onNodeWithText("Réglages…").assertExists()
    }

    @Test
    fun `Back folds Plus before anything else`() {
        setContent()
        rule.onNodeWithText("Plus").performClick()
        rule.onNodeWithText("Nouveautés").assertExists()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onAllNodesWithText("Nouveautés").assertCountEquals(0)
        rule.onNodeWithText("Nouveautés, signaler une erreur").assertExists()
    }

    @Test
    fun `the commands of the tracks panel and the zone download are not in the menu`() {
        setContent()
        // Drawing, waypoints and search are in the flag's panel (P14.3); the zone is saved from the maps panel (R3).
        for (unfolded in listOf(false, true)) {
            if (unfolded) rule.onNodeWithText("Plus").performClick()
            rule.onAllNodesWithText("Dessiner une trace").assertCountEquals(0)
            rule.onAllNodesWithText("Trace").assertCountEquals(0)
            rule.onAllNodesWithText("Waypoint ici").assertCountEquals(0)
            rule.onAllNodesWithText("Rechercher un lieu, des coordonnées").assertCountEquals(0)
            rule.onAllNodesWithText("Télécharger la zone affichée…").assertCountEquals(0)
        }
    }

    @Test
    fun `the tile diagnostics are not offered outside debug builds`() {
        setContent(diagnostics = false)
        rule.onNodeWithText("Plus").performClick()
        rule.onAllNodesWithText("Diagnostic des tuiles").assertCountEquals(0)
    }

    @Test
    fun `the tile diagnostics are in Plus in debug builds`() {
        setContent(diagnostics = true)
        rule.onNodeWithText("Plus").performClick()
        rule.onNodeWithText("Diagnostic des tuiles").performScrollTo().performClick()
        assertEquals(listOf(MenuAction.DIAGNOSTICS), actions)
    }

    private val actions = mutableListOf<MenuAction>()

    private fun setContent(
        diagnostics: Boolean = false,
        toolsAllowed: Boolean = true,
        favorite: String? = "IGN Topo",
        layers: List<String> = listOf("Pentes", "Plan IGN"),
        night: Boolean = false,
        maxHeight: Int = 2000,
        onCoordinates: (Boolean) -> Unit = {},
    ) {
        rule.setContent {
            MainMenuPanel(
                state =
                    MainMenuState(
                        showCoordinates = false,
                        nightMode = night,
                        favorite = favorite,
                        displayLayers = layers,
                        measureAllowed = toolsAllowed,
                        printAllowed = toolsAllowed,
                        diagnostics = diagnostics,
                    ),
                onAction = { actions += it },
                onCoordinates = onCoordinates,
                maxHeight = maxHeight.dp,
            )
        }
    }
}
