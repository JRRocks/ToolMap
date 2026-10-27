package fr.toolmap.map

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tracks and waypoints panel of the flag (P14.3): each row runs its command, and drawing and waypoints are off
 * while a track is being drawn, as they were in the main menu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TracksMenuPanelTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `each row runs its command`() {
        val actions = mutableListOf<MenuAction>()
        var explored = false
        setContent(drawAllowed = true, waypointAllowed = true, onAction = { actions += it }, onExplorer = {
            explored =
                true
        })
        rule.onNodeWithText("Waypoint ici").performClick()
        rule.onNodeWithText("Trace").performClick()
        rule.onNodeWithText("Itinéraire").performClick()
        rule.onNodeWithText("Rechercher un lieu, des coordonnées").performClick()
        rule.onNodeWithText("Importer un fichier…").performClick()
        rule.onNodeWithText("Explorer les traces et waypoints").performClick()
        assertEquals(
            listOf(
                MenuAction.WAYPOINT,
                MenuAction.DRAW_TRACK,
                MenuAction.DRAW_ROUTE,
                MenuAction.SEARCH,
                MenuAction.IMPORT_TRACKS,
            ),
            actions,
        )
        assertTrue(explored)
    }

    @Test
    fun `drawing and waypoints are off while a track is drawn, search and explorer stay on`() {
        setContent(drawAllowed = false, waypointAllowed = false, onAction = {}, onExplorer = {})
        rule.onNodeWithText("Trace").assertIsNotEnabled()
        rule.onNodeWithText("Itinéraire").assertIsNotEnabled()
        rule.onNodeWithText("Waypoint ici").assertIsNotEnabled()
        rule.onNodeWithText("Rechercher un lieu, des coordonnées").assertIsEnabled()
        rule.onNodeWithText("Explorer les traces et waypoints").assertIsEnabled()
    }

    @Test
    fun `the explorer counts what the library holds, and the switch shows or hides everything`() {
        val shown = mutableListOf<Boolean>()
        setContent(
            drawAllowed = true,
            waypointAllowed = true,
            onAction = {},
            onExplorer = {},
            state = TracksMenuState(true, true, tracks = 1, routes = 2, waypoints = 12, allVisible = true),
            onShowAll = { shown += it },
        )
        rule.onNodeWithText("1 trace · 2 itinéraires · 12 waypoints").assertExists()
        rule.onNodeWithText("Tout afficher sur la carte").performClick()
        assertEquals(listOf(false), shown)
    }

    private fun setContent(
        drawAllowed: Boolean,
        waypointAllowed: Boolean,
        onAction: (MenuAction) -> Unit,
        onExplorer: () -> Unit,
        state: TracksMenuState = TracksMenuState(drawAllowed = drawAllowed, waypointAllowed = waypointAllowed),
        onShowAll: (Boolean) -> Unit = {},
    ) {
        rule.setContent {
            TracksMenuPanel(
                state = state,
                onAction = onAction,
                onExplorer = onExplorer,
                onHelp = {},
                maxHeight = 2000.dp,
                onShowAll = onShowAll,
            )
        }
    }
}
