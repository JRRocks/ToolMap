package fr.toolmap.map

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** « Quitter ToolMap ? » names what goes on in the background and the button that stops it (P29.2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExitDialogTest {
    @get:Rule
    val rule = createComposeRule()

    private val recording =
        RunningService(
            RunningService.Kind.RECORDING,
            "Enregistrement de trace",
            "1 h 12 · 4,8 km",
            "Arrêter l'enregistrement",
        )
    private val sharing =
        RunningService(RunningService.Kind.SHARING, "Partage de position", "2 membres connectés", "Arrêter le partage")

    @Test
    fun `one service is named with its figures and the button of its notification`() {
        rule.setContent { ExitDialog(running = listOf(recording), onExit = {}, onDismiss = {}) }
        rule.onNodeWithText("Ceci continue en arrière-plan :").assertExists()
        rule.onNodeWithText("Enregistrement de trace").assertExists()
        rule.onNodeWithText("1 h 12 · 4,8 km").assertExists()
        rule.onNodeWithText(
            "Pour l'arrêter plus tard, touchez « Arrêter l'enregistrement » dans sa notification, ou rouvrez ToolMap.",
        ).assertExists()
    }

    @Test
    fun `several services each have a line and one sentence for all`() {
        rule.setContent { ExitDialog(running = listOf(recording, sharing), onExit = {}, onDismiss = {}) }
        rule.onNodeWithText("Partage de position").assertExists()
        rule.onNodeWithText("2 membres connectés").assertExists()
        rule.onNodeWithText(
            "Pour les arrêter plus tard, touchez leurs boutons « Arrêter » dans la notification de ToolMap, " +
                "ou rouvrez ToolMap.",
        ).assertExists()
    }

    @Test
    fun `with nothing running there is the question alone, and the buttons answer it`() {
        val answers = mutableListOf<String>()
        rule.setContent {
            ExitDialog(running = emptyList(), onExit = { answers += "exit" }, onDismiss = { answers += "stay" })
        }
        rule.onNodeWithText("Quitter ToolMap ?").assertExists()
        rule.onAllNodesWithText("Ceci continue en arrière-plan :").assertCountEquals(0)
        rule.onNodeWithText("Annuler").performClick()
        rule.onNodeWithText("Quitter").performClick()
        assertEquals(listOf("stay", "exit"), answers)
    }
}
