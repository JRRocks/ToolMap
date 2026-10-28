package fr.toolmap.maps

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import fr.toolmap.core.download.ZoneShape
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.ui.theme.ToolMapTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The sheet of « Enregistrer une zone » since P29.7 (« Poids »): the weight in large with the room left, the maximal
 * zoom, the maps folded in one line that unfolds their rows (zooms in words, strips with their ends), the button
 * across the sheet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h2000dp")
class SaveAreaPanelTest {
    @get:Rule
    val rule = createComposeRule()

    private val fixture = MapsPanelFixture()

    @After
    fun tearDown() = fixture.close()

    private fun open() {
        fixture.maps.openSaveArea(ZoneShape.Rectangle(5.90, 45.40, 6.00, 45.50), 12, fixture.vm.state.value.stack)
        rule.setContent {
            ToolMapTheme {
                val area by fixture.maps.area.collectAsState()
                area?.let { ui ->
                    SaveAreaPanel(ui = ui, currentTileZoom = 12.0, maps = fixture.maps, onSave = {
                    }, onClose = {}, onHelp = {})
                }
            }
        }
        await { fixture.maps.area.value?.estimating == false }
    }

    private fun await(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!done()) {
            check(System.currentTimeMillis() < end) { "Timed out waiting for the estimate" }
            rule.waitForIdle()
            Thread.sleep(50)
        }
        rule.waitForIdle()
    }

    @Test
    fun `a band around a track has its width, which a touch lets type`() {
        val line = listOf(TrackPoint(45.40, 5.90), TrackPoint(45.45, 5.95), TrackPoint(45.42, 6.00))
        fixture.maps.openCorridor("Tour du Granier", line, fixture.vm.state.value.stack)
        rule.setContent {
            ToolMapTheme {
                val area by fixture.maps.area.collectAsState()
                area?.let { ui ->
                    SaveAreaPanel(ui = ui, currentTileZoom = 12.0, maps = fixture.maps, onSave = {
                    }, onClose = {}, onHelp = {})
                }
            }
        }
        await { fixture.maps.area.value?.estimating == false }
        rule.onNodeWithText("Enregistrer autour du tracé").assertExists()
        rule.onNodeWithText("Largeur de chaque côté").assertExists()
        rule.onNodeWithText("± 500 m").assertExists()
        rule.onNodeWithText("Enregistrer la bande").assertExists()
        // A touch on the width opens the field to type it (the field itself: CorridorWidthDialogTest).
        rule.onNodeWithText("± 500 m").assert(hasClickAction())
        // A width typed to the meter is shown as typed.
        fixture.maps.setCorridorRadius(777)
        await { fixture.maps.area.value?.estimating == false }
        assertEquals(777, fixture.maps.area.value!!.corridor!!.radiusMeters)
        rule.onNodeWithText("± 777 m").assertExists()
    }

    @Test
    fun `the band, the weight to fetch and the button across the sheet`() {
        open()
        rule.onNodeWithText("Enregistrer une zone").assertExists()
        rule.onNodeWithText("à télécharger", substring = true).assertExists()
        rule.onNodeWithText("Zoom maximal").assertExists()
        rule.onNodeWithText("Enregistrer la zone").assertExists()
        rule.onNodeWithContentDescription("Aide et à propos").assertExists()
    }

    @Test
    fun `the maps are one line that unfolds their rows, in words and with the ends of the strips`() {
        open()
        rule.onNodeWithText("3 sur 3", substring = true).assertExists()
        rule.onAllNodesWithText("Zooms 1 à", substring = true).assertCountEquals(0)
        rule.onNodeWithText("Cartes").performClick()
        rule.onNodeWithText("Zooms 1 à 15 (sur 18)", substring = true).assertExists()
        rule.onAllNodesWithText("0").assertCountEquals(3)
        // The third row is below the fold of the list: scrolled to, as a finger would.
        rule.onNodeWithText("IGN Cartes").performScrollTo().performClick()
        await { fixture.maps.area.value?.estimating == false }
        rule.onNodeWithText("2 sur 3", substring = true).assertExists()
        rule.onNodeWithText("Non cochée").assertExists()
    }
}
