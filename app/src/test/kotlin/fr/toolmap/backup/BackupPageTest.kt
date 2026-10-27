package fr.toolmap.backup

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.toolmap.core.backup.BackupManifest
import fr.toolmap.core.backup.RestoreReport
import fr.toolmap.ui.theme.ToolMapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * « Sauvegarde et restauration » since P29.9 (« Loupe »): « Sauvegarder » and « Restaurer » on a segmented button, one
 * main action each; a restore waiting opens the screen on « Restaurer », its card first; the report hides with
 * « Masquer »; nothing can be started while a task runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h1600dp")
class BackupPageTest {
    @get:Rule
    val rule = createComposeRule()

    private var created = 0
    private var chosen = 0
    private var hidden = 0

    private fun setContent(start: BackupUi) {
        rule.setContent {
            var ui by remember { mutableStateOf(start) }
            ToolMapTheme {
                BackupPage(
                    ui = ui,
                    snackbar = remember { SnackbarHostState() },
                    onBack = {},
                    onIncludeTiles = { ui = ui.copy(includeTiles = it) },
                    onCreate = { created++ },
                    onChoose = { chosen++ },
                    onRestart = {},
                    onCancelPending = { ui = ui.copy(pending = null) },
                    onHideReport = {
                        hidden++
                        ui = ui.copy(report = null)
                    },
                )
            }
        }
    }

    @Test
    fun `the screen opens on Sauvegarder, what is not saved read before the button`() {
        setContent(BackupUi(tilesBytes = 18_900_000))
        rule.onAllNodesWithText("Contenu").assertCountEquals(1)
        rule.onAllNodesWithText("Occupent 18", substring = true).assertCountEquals(1)
        val top = { text: String -> rule.onNodeWithText(text, substring = true).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(true, top("Pas sauvegardés") < top("Créer une sauvegarde…"))
        rule.onAllNodesWithText("Choisir une sauvegarde…").assertCountEquals(0)
        rule.onNodeWithText("Créer une sauvegarde…").performClick()
        assertEquals(1, created)
    }

    @Test
    fun `Restaurer shows its own action only`() {
        setContent(BackupUi())
        rule.onNodeWithText("Restaurer").performClick()
        rule.onAllNodesWithText("Créer une sauvegarde…").assertCountEquals(0)
        rule.onNodeWithText("Choisir une sauvegarde…").performClick()
        assertEquals(1, chosen)
    }

    @Test
    fun `a restore waiting opens the screen on Restaurer, its card first`() {
        setContent(BackupUi(pending = BackupManifest(createdAtMillis = 1_760_000_000_000L)))
        rule.onAllNodesWithText("Restauration prête").assertCountEquals(1)
        rule.onAllNodesWithText("Redémarrer maintenant").assertCountEquals(1)
        val top = { text: String -> rule.onNodeWithText(text, substring = true).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(true, top("Restauration prête") < top("Choisissez un fichier"))
        rule.onNodeWithText("Annuler").performClick()
        rule.onAllNodesWithText("Restauration prête").assertCountEquals(0)
    }

    @Test
    fun `the report hides with Masquer`() {
        setContent(BackupUi(report = RestoreReport(ok = false, atMillis = 0L)))
        rule.onAllNodesWithText("La restauration a échoué", substring = true).assertCountEquals(1)
        rule.onNodeWithText("Masquer").performClick()
        assertEquals(1, hidden)
        rule.onAllNodesWithText("La restauration a échoué", substring = true).assertCountEquals(0)
    }

    @Test
    fun `nothing starts while a task runs`() {
        setContent(BackupUi(busy = BackupBusy.CREATING))
        rule.onAllNodesWithText("Création de la sauvegarde…").assertCountEquals(1)
        rule.onNodeWithText("Créer une sauvegarde…").assertIsNotEnabled()
        rule.onNodeWithText("Restaurer").assertIsNotEnabled()
    }
}
