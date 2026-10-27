package fr.toolmap

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import fr.toolmap.crash.CrashReporter
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** « Nouveau dossier » and « Déplacer vers… » of the library of tracks and waypoints (P16.5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ToolMapApp::class, instrumentedPackages = ["org.maplibre.android"])
class TracksFoldersScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        (Thread.getDefaultUncaughtExceptionHandler() as? CrashReporter.Handler)?.let {
            Thread.setDefaultUncaughtExceptionHandler(it.previous)
        }
        File(RuntimeEnvironment.getApplication().filesDir, "crash").deleteRecursively()
    }

    @Test
    fun `a new folder shows in the library and offers to move to the top or into a sub-folder`() {
        // « Nouveau dossier » waits for a first item (P29.4): « Waypoint ici » records one at once.
        rule.onNodeWithContentDescription("Traces et waypoints").performClick()
        rule.onNodeWithText("Waypoint ici").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("enregistré", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("Traces et waypoints").performClick()
        rule.onNodeWithText("Liste des traces et waypoints").performClick()
        rule.onNodeWithText("Nouveau dossier").performClick()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Randos")
        rule.onNodeWithText("OK").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Randos").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("Dossier · 0 élément", substring = true).assertCountEquals(1)

        // The folder menu offers a sub-folder and a move; the move lists the top of the library.
        rule.onAllNodesWithContentDescription("Menu")[0].performClick()
        rule.onNodeWithText("Déplacer vers…").performClick()
        rule.onAllNodesWithText("Haut de la bibliothèque").assertCountEquals(1)
        // A folder is never offered itself as a destination: only the top of the library is left, and it is current.
        rule.onAllNodesWithText("Randos").assertCountEquals(1)
        rule.onNodeWithText("Annuler").performClick()
    }
}
