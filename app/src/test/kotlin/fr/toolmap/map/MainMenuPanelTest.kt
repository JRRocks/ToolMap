package fr.toolmap.map

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
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

/**
 * The main menu does not repeat the settings: « Réglages… » leads to them (P11.11). Since P14.10 the frequent commands
 * are tiles, a block names the maps shown and opens « Cartes et calques » (no « Espace de travail » section, R2), and
 * « Plus… » holds what is new and the report of an error.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MainMenuPanelTest {
    @get:Rule
    val rule = createComposeRule()

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
    fun `hidden controls make the first tile give them back`() {
        setContent(controlsHidden = true)
        rule.onAllNodesWithText("Masquer").assertCountEquals(0)
        rule.onNodeWithText("Afficher").performClick()
        assertEquals(listOf(MenuAction.HIDE), actions)
    }

    @Test
    fun `the block of the maps shown names them and opens the maps panel, with no workspace section`() {
        setContent()
        rule.onAllNodesWithText("ESPACE DE TRAVAIL").assertCountEquals(0)
        rule.onNodeWithText("IGN Topo").assertExists()
        // Since P29.0 the subject of the block opens its grey line, no longer a kicker in capitals over the title.
        rule.onNodeWithText("Carte affichée · Pentes + Plan IGN").performClick()
        assertEquals(listOf(MenuAction.MAPS), actions)
    }

    @Test
    fun `the switch of the information on screen reports the change`() {
        val changes = mutableListOf<Boolean>()
        setContent(onCoordinates = { changes += it })
        rule.onNodeWithText("Informations à l'écran").performClick()
        assertEquals(listOf(true), changes)
    }

    @Test
    fun `Quitter is in the menu and runs the exit command`() {
        setContent()
        rule.onNodeWithText("Quitter").performScrollTo().performClick()
        assertEquals(listOf(MenuAction.EXIT), actions)
    }

    @Test
    fun `the commands of the tracks panel and the zone download are not in the menu`() {
        setContent()
        // Drawing, waypoints and search are in the flag's panel (P14.3); the zone is saved from the maps panel (R3).
        for (page in listOf("main", "more")) {
            if (page == "more") rule.onNodeWithText("Plus…").performScrollTo().performClick()
            rule.onAllNodesWithText("Dessiner une trace").assertCountEquals(0)
            rule.onAllNodesWithText("Trace").assertCountEquals(0)
            rule.onAllNodesWithText("Waypoint ici").assertCountEquals(0)
            rule.onAllNodesWithText("Rechercher un lieu, des coordonnées").assertCountEquals(0)
            rule.onAllNodesWithText("Télécharger la zone affichée…").assertCountEquals(0)
        }
    }

    @Test
    fun `Plus offers what is new and the report of an error, and no second way to the backup`() {
        setContent()
        rule.onNodeWithText("Plus…").performScrollTo().performClick()
        rule.onNodeWithText("Nouveautés").performScrollTo().performClick()
        rule.onNodeWithText("Signaler une erreur").performScrollTo().performClick()
        assertEquals(listOf(MenuAction.WHATS_NEW, MenuAction.REPORT_ERROR), actions)
        rule.onAllNodesWithText("Sauvegarde et restauration…").assertCountEquals(0)
    }

    @Test
    fun `the tile diagnostics are not offered outside debug builds`() {
        setContent(diagnostics = false)
        rule.onNodeWithText("Plus…").performScrollTo().performClick()
        rule.onAllNodesWithText("Diagnostic des tuiles").assertCountEquals(0)
    }

    @Test
    fun `the tile diagnostics are in Plus in debug builds`() {
        setContent(diagnostics = true)
        rule.onNodeWithText("Plus…").performScrollTo().performClick()
        rule.onAllNodesWithText("Diagnostic des tuiles").assertCountEquals(1)
    }

    private val actions = mutableListOf<MenuAction>()

    private fun setContent(
        diagnostics: Boolean = false,
        toolsAllowed: Boolean = true,
        controlsHidden: Boolean = false,
        onCoordinates: (Boolean) -> Unit = {},
    ) {
        rule.setContent {
            MainMenuPanel(
                state =
                    MainMenuState(
                        showCoordinates = false,
                        displayName = "IGN Topo",
                        displayLayers = listOf("Pentes", "Plan IGN"),
                        measureAllowed = toolsAllowed,
                        printAllowed = toolsAllowed,
                        controlsHidden = controlsHidden,
                        diagnostics = diagnostics,
                    ),
                onAction = { actions += it },
                onCoordinates = onCoordinates,
                maxHeight = 2000.dp,
            )
        }
    }
}
