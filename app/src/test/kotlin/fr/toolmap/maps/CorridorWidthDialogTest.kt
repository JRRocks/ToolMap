package fr.toolmap.maps

import android.app.Application
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import fr.toolmap.ui.theme.ToolMapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The field of « Largeur de chaque côté » of a band around a track (P30.2), on its own: opened over the sheet mounted
 * on [MapsPanelFixture], it never let Robolectric idle in the CI (`AppNotIdleException`, 2026-10-28).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h2000dp")
class CorridorWidthDialogTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the width is typed to the meter and kept from 50 m to 5 km`() {
        val done = mutableListOf<Int>()
        var dismissed = false
        rule.setContent {
            ToolMapTheme {
                CorridorWidthDialog(radius = 500, onDone = { done += it }, onDismiss = { dismissed = true })
            }
        }
        rule.onNodeWithText("Largeur de chaque côté").assertExists()
        rule.onNodeWithText("De 50 à 5 000 m").assertExists()
        rule.onNode(hasSetTextAction()).performTextReplacement("777")
        rule.onNodeWithText("OK").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("9000")
        rule.onNodeWithText("OK").performClick()
        rule.onNodeWithText("Annuler").performClick()
        assertEquals(listOf(777, 5_000), done)
        assertTrue(dismissed)
    }
}
