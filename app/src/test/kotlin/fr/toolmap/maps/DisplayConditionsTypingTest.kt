package fr.toolmap.maps

import android.app.Application
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import fr.toolmap.core.layers.LayerStack
import fr.toolmap.core.layers.RangeLogic
import fr.toolmap.ui.theme.ToolMapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** « Conditions d'affichage » with typed values (P23.2, variant A validated on 2026-11-04). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h900dp")
class DisplayConditionsTypingTest {
    @get:Rule
    val rule = createComposeRule()

    private val bands = mutableListOf<Pair<Int?, Int?>>()
    private val zooms = mutableListOf<Pair<Int?, Int?>>()

    private fun setContent(
        minElevation: Int?,
        maxElevation: Int?,
    ) {
        val layer =
            LayerStack()
                .add("osm")
                .layers
                .single()
                .copy(displayMinElevation = minElevation, displayMaxElevation = maxElevation)
        rule.setContent {
            ToolMapTheme {
                DisplayConditionsPanel(
                    layer = layer,
                    name = "OpenStreetMap",
                    currentZoom = 12.0,
                    centerElevation = 2158.0,
                    onZoomRange = { low, high -> zooms += low to high },
                    onElevationRange = { low, high, _: RangeLogic -> bands += low to high },
                    onClose = {},
                )
            }
        }
    }

    @Test
    fun `a band typed beyond the bar shows its exact value and keeps it when the other handle moves`() {
        setContent(1500, 6200)
        rule.onNodeWithContentDescription("Modifier Altitude : 1500 à 6200").assertExists()
        rule.onNodeWithText("6200 m · au-delà de la barre").assertExists()
        // A touch near the start of the bar moves the minimum only: the 6200 m typed stays.
        rule.onNodeWithContentDescription("Plage d'altitude du calque").performTouchInput {
            click(percentOffset(0.1f, 0.5f))
        }
        rule.waitForIdle()
        assertEquals(6200, bands.single().second)
        assertEquals(true, (bands.single().first ?: 0) < 1500)
    }

    @Test
    fun `the pill opens the window, where a band is typed to the metre`() {
        setContent(1500, null)
        rule.onNodeWithContentDescription("Modifier Altitude : dès 1500 m").performClick()
        rule.onNodeWithText("Altitude du calque").assertExists()
        rule.onNode(hasSetTextAction() and hasText("À")).performTextReplacement("6237")
        rule.onNodeWithText("OK").performClick()
        rule.waitForIdle()
        assertEquals(listOf<Pair<Int?, Int?>>(1500 to 6237), bands)
    }

    @Test
    fun `a value out of its limits keeps OK greyed and says the limits`() {
        setContent(null, null)
        rule.onNodeWithContentDescription("Modifier Zoom : tous").performClick()
        rule.onNode(hasSetTextAction() and hasText("À")).performTextReplacement("30")
        rule.onNodeWithText("Hors limites : 0 à 24", substring = true).assertExists()
        rule.onNodeWithText("OK").performClick()
        rule.waitForIdle()
        assertEquals(emptyList(), zooms)
        rule.onNode(hasSetTextAction() and hasText("À")).performTextReplacement("22")
        rule.onNodeWithText("OK").performClick()
        rule.waitForIdle()
        assertEquals(listOf<Pair<Int?, Int?>>(null to 22), zooms)
    }
}
