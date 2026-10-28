package fr.toolmap.draw

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import fr.toolmap.data.elevation.ElevationCompleter
import fr.toolmap.data.elevation.ElevationTiles
import fr.toolmap.data.routing.BRouterEngine
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.data.tracks.TrackDatabase
import fr.toolmap.data.tracks.TrackRepository
import fr.toolmap.measure.MeasureBar
import fr.toolmap.measure.MeasureViewModel
import fr.toolmap.print.PrintFrameActions
import fr.toolmap.tracks.ConfirmDialog
import fr.toolmap.ui.theme.ToolMapTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The tools on the map since P29.10 (« Équerre »): panels of the bar with the petrol band of the tool, ✕ to leave,
 * the actions at the crosshair as tiles, undo and redo as icons, a single filled button; the print frame's panel
 * without ✕; a deletion that names what it deletes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h900dp")
class ToolPanelsTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var db: TrackDatabase
    private lateinit var repo: TrackRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = TrackDatabase.inMemory(RuntimeEnvironment.getApplication())
        repo = TrackRepository(db)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun drawVm(): DrawViewModel {
        val app = RuntimeEnvironment.getApplication()
        val terrain = ElevationCompleter(ElevationTiles { _, _, _ -> null })
        return DrawViewModel(repo, BRouterEngine(app), SettingsStore(File(app.filesDir, "settings.json")), terrain)
    }

    @Test
    fun `a new track has its band, its tiles, icons for undo and redo, and Enregistrer`() {
        val vm = drawVm()
        vm.startNewTrack()
        rule.setContent {
            val ui by vm.ui.collectAsState()
            ToolMapTheme { DrawBar(ui = ui, vm = vm, center = { 45.45 to 5.95 }, maxHeight = 600.dp) }
        }
        rule.onAllNodesWithText("Nouvelle trace").assertCountEquals(1)
        rule.onAllNodesWithText("Points : 0 · 0 m").assertCountEquals(1)
        rule.onNodeWithContentDescription("Annuler la dernière action").assertIsNotEnabled()
        rule.onNodeWithText("Enregistrer").assertIsNotEnabled()
        rule.onNodeWithText("Ajouter ici").performClick()
        rule.onNodeWithText("Ajouter ici").performClick()
        rule.waitForIdle()
        assertEquals(2, vm.ui.value.pointCount)
        // The point just added is selected: the band says so.
        rule.onAllNodesWithText("Point sélectionné : n° 2", substring = true).assertCountEquals(1)
        rule.onNodeWithText("Main levée").performClick()
        rule.waitForIdle()
        assertTrue(vm.ui.value.freehand)
        rule.onNodeWithText("Main levée").assertIsSelected()
        rule.onNodeWithContentDescription("Annuler la dernière action").performClick()
        rule.waitForIdle()
        assertEquals(1, vm.ui.value.pointCount)
        rule.onAllNodesWithText("Annuler").assertCountEquals(0)
    }

    @Test
    fun `the cross asks before losing the points, and leaves at once without changes`() {
        val vm = drawVm()
        vm.startNewTrack()
        rule.setContent {
            val ui by vm.ui.collectAsState()
            ToolMapTheme { DrawBar(ui = ui, vm = vm, center = { 45.45 to 5.95 }, maxHeight = 600.dp) }
        }
        rule.onNodeWithText("Ajouter ici").performClick()
        rule.onNodeWithContentDescription("Quitter").performClick()
        rule.onAllNodesWithText("Abandonner les modifications ?").assertCountEquals(1)
        rule.onNodeWithText("Abandonner").performClick()
        rule.waitForIdle()
        assertFalse(vm.ui.value.active)
    }

    @Test
    fun `a route drawn by hand is named Itinéraire and offers the ways of BRouter`() {
        val vm = drawVm()
        vm.startNewRoute()
        rule.setContent {
            val ui by vm.ui.collectAsState()
            ToolMapTheme { DrawBar(ui = ui, vm = vm, center = { 45.45 to 5.95 }, maxHeight = 600.dp) }
        }
        rule.onAllNodesWithText("Itinéraire").assertCountEquals(1)
        rule.onAllNodesWithText("Suivre les chemins (BRouter)").assertCountEquals(1)
    }

    @Test
    fun `the measure has its band with the points and the length, Distance or Surface, and Garder`() {
        val vm = MeasureViewModel(repo)
        vm.open()
        vm.addAt(45.45, 5.95)
        vm.addAt(45.46, 5.95)
        rule.setContent {
            val ui by vm.ui.collectAsState()
            ToolMapTheme { MeasureBar(ui = ui, vm = vm, center = { 45.47 to 5.96 }, maxHeight = 600.dp) }
        }
        rule.onAllNodesWithText("Mesure").assertCountEquals(1)
        rule.onAllNodesWithText("2 points · ", substring = true).assertCountEquals(1)
        rule.onAllNodesWithText("Distance").assertCountEquals(1)
        rule.onNodeWithText("Surface").performClick()
        rule.onNodeWithText("Ajouter ici").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Télécharger cette zone…").assertCountEquals(1)
        rule.onNodeWithText("Garder…").performClick()
        rule.onAllNodesWithText("Comme itinéraire").assertCountEquals(1)
    }

    @Test
    fun `the panel of the print frame has no cross, Annuler gives up and Suivant goes on`() {
        var cancelled = 0
        var next = 0
        rule.setContent {
            ToolMapTheme {
                PrintFrameActions(onCancel = { cancelled++ }, onNext = { next++ }, maxHeight = 600.dp)
            }
        }
        rule.onAllNodesWithText("Imprimer à l'échelle").assertCountEquals(1)
        rule.onAllNodesWithText("Glissez le cadre", substring = true).assertCountEquals(1)
        rule.onNodeWithText("Annuler").performClick()
        rule.onNodeWithText("Suivant").performClick()
        assertEquals(1, cancelled)
        assertEquals(1, next)
    }

    @Test
    fun `a deletion names what it deletes`() {
        rule.setContent {
            ToolMapTheme {
                ConfirmDialog(
                    title = "Supprimer « Fort Barraux » ?",
                    message = "Cette action est définitive.",
                    confirmLabel = "Supprimer",
                    onConfirm = {},
                    onDismiss = {},
                    destructive = true,
                )
            }
        }
        rule.onAllNodesWithText("Supprimer « Fort Barraux » ?").assertCountEquals(1)
        rule.onAllNodesWithText("Supprimer").assertCountEquals(1)
        rule.onAllNodesWithText("Annuler").assertCountEquals(1)
    }
}
