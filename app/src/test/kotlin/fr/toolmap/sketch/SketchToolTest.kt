package fr.toolmap.sketch

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.sketch.Sketch
import fr.toolmap.core.sketch.SketchPath
import fr.toolmap.core.sketch.SketchScale
import fr.toolmap.core.sketch.SketchSmoothing
import fr.toolmap.core.sketch.SketchStroke
import fr.toolmap.core.sketch.SketchStyle
import fr.toolmap.core.sketch.XY
import fr.toolmap.data.tracks.SavedSketch
import fr.toolmap.data.tracks.SketchRepository
import fr.toolmap.data.tracks.TrackDatabase
import fr.toolmap.map.MapController
import fr.toolmap.ui.theme.ToolMapTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sketch tool of P28: the pen, the eraser, undo, « Enregistrer », the layer of the map and the two panels. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h900dp")
class SketchToolTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var db: TrackDatabase
    private lateinit var repo: SketchRepository
    private val origin = XY.of(LonLat(5.95, 45.52))
    private val zoom = 15.0
    private val mpp = SketchScale.metersPerPixel(zoom)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = TrackDatabase.inMemory(RuntimeEnvironment.getApplication())
        repo = SketchRepository(db)
    }

    private val vms = mutableListOf<SketchViewModel>()

    private fun newVm() = SketchViewModel(repo).also { vms += it }

    @After
    fun tearDown() {
        // The list of the sketches is read until the view model ends: it must end before the database closes. Cancelling
        // is not enough: a query already running on Room's thread goes on, so wait for it.
        runBlocking { vms.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        Dispatchers.resetMain()
        db.close()
    }

    /** A straight line of [n] points, one pixel apart at [zoom], from [dy] pixels under the origin. */
    private fun line(
        n: Int = 101,
        dy: Double = 0.0,
    ) = (0 until n).map { origin + XY(it * mpp, dy * mpp) }

    @Test
    fun penUndoRedoAndSave() =
        runBlocking {
            val vm = newVm()
            vm.startNew("Croquis du 3 nov.")
            vm.addStroke(line(), zoom)
            vm.addStroke(line(dy = 40.0), zoom)
            assertEquals(2, vm.ui.value.strokes.size)
            // 6 px at zoom 15 in map meters, and a straight line smoothed into a single curve.
            assertEquals(6 * mpp, vm.ui.value.strokes[0].raw.style.widthMeters, 1e-9)
            assertEquals(1, vm.ui.value.strokes[0].smooth.curves.size)
            vm.undo()
            assertEquals(1, vm.ui.value.strokes.size)
            assertTrue(vm.ui.value.canRedo)
            vm.redo()
            assertEquals(2, vm.ui.value.strokes.size)
            val saved = vm.saveNow()
            assertFalse(vm.ui.value.active)
            val all = repo.sketches.first()
            assertEquals(listOf(saved), all.map { it.id })
            assertEquals("Croquis du 3 nov.", all[0].sketch.name)
            assertEquals(2, all[0].sketch.strokes.size)
        }

    @Test
    fun eraserCutsAStrokeAndAnEmptyGestureLeavesNoStep() {
        val vm = newVm()
        vm.startNew("Croquis")
        vm.addStroke(line(), zoom)
        // Erasing beside the stroke changes nothing, and adds nothing to undo past the stroke itself.
        vm.beginErase()
        vm.eraseAt(origin + XY(50 * mpp, 200 * mpp), 16 * mpp)
        vm.endErase()
        vm.undo()
        assertEquals(0, vm.ui.value.strokes.size)
        vm.redo()
        // Erasing its middle leaves two pieces, put back together by one undo.
        vm.beginErase()
        vm.eraseAt(origin + XY(50 * mpp, 0.0), 16 * mpp)
        vm.endErase()
        assertEquals(2, vm.ui.value.strokes.size)
        vm.undo()
        assertEquals(1, vm.ui.value.strokes.size)
    }

    @Test
    fun reopenedSketchLeftEmptyIsDeleted() =
        runBlocking {
            val vm = newVm()
            vm.startNew("Croquis")
            vm.addStroke(line(), zoom)
            val id = vm.saveNow()!!
            vm.open(id)
            withTimeout(5_000) { vm.ui.first { it.active } }
            assertEquals(1, vm.ui.value.strokes.size)
            assertEquals(id, vm.ui.value.editingId)
            vm.beginErase()
            for (i in 0..100 step 5) vm.eraseAt(origin + XY(i * mpp, 0.0), 16 * mpp)
            vm.endErase()
            assertEquals(0, vm.ui.value.strokes.size)
            val after = vm.saveNow()
            assertNull(after)
            assertTrue(repo.sketches.first().isEmpty())
        }

    private suspend fun SketchViewModel.saveNow(): Long? {
        val done = CompletableDeferred<Long?>()
        save { done.complete(it) }
        return withTimeout(5_000) { done.await() }
    }

    private fun saved(
        id: Long,
        widthMeters: Double,
        visible: Boolean = true,
        dot: Boolean = false,
    ): SavedSketch {
        val path = if (dot) SketchPath(origin) else SketchPath.parse("M ${origin.x} ${origin.y} l 100 0")
        val stroke = SketchStroke(SketchStyle(0xFFE53935.toInt(), widthMeters, 1.0), path)
        return SavedSketch(id, visible, 0, 0, Sketch("Croquis $id", listOf(stroke)))
    }

    @Test
    fun mapLayerLeavesOutHiddenEditedAndTooThinSketches() {
        val sketches =
            listOf(
                saved(1, widthMeters = 6 * mpp),
                saved(2, widthMeters = 6 * mpp, visible = false),
                saved(3, widthMeters = 6 * mpp, dot = true),
                saved(4, widthMeters = 6 * mpp),
            )
        val json = JSONObject(SketchLayers.geoJson(sketches, zoom, skipId = 4))
        val features = json.getJSONArray("features")
        assertEquals(2, features.length())
        val line = features.getJSONObject(0)
        assertEquals("LineString", line.getJSONObject("geometry").getString("type"))
        assertEquals("#E53935", line.getJSONObject("properties").getString("color"))
        // 6 px at zoom 15: w0 × 2^15.
        assertEquals(6.0, line.getJSONObject("properties").getDouble("w0") * Math.pow(2.0, zoom), 1e-6)
        assertEquals("Point", features.getJSONObject(1).getJSONObject("geometry").getString("type"))
        // Three levels out, 6 px become 0.75 px: hidden.
        assertEquals(0, JSONObject(SketchLayers.geoJson(sketches, zoom - 3, null)).getJSONArray("features").length())
    }

    @Test
    fun spaceListsSketchesAndCreatesOne() {
        var created = false
        rule.setContent {
            ToolMapTheme {
                SketchSpacePanel(
                    rows = listOf(SketchRow(saved(7, 6 * mpp), hiddenHere = true, visibleFromZoom = 13)),
                    onBack = {},
                    onHelp = {},
                    onNew = { created = true },
                    onOpen = {},
                    onShow = { _, _ -> },
                    onRename = { _, _ -> },
                    onDelete = {},
                    maxHeight = 700.dp,
                )
            }
        }
        rule.onNodeWithText("Croquis 7").assertExists()
        rule.onNodeWithText("1 trait", substring = true).assertExists()
        rule.onNodeWithText("visible dès le zoom 13", substring = true).assertExists()
        rule.onNodeWithText("1 croquis · aucun affiché à ce zoom").assertExists()
        rule.onNodeWithText("Nouveau croquis").performClick()
        assertTrue(created)
    }

    @Test
    fun toolShowsBandAndTwoTiers() {
        val vm = newVm()
        vm.startNew("Croquis du 3 nov.")
        val controller = MapController(RuntimeEnvironment.getApplication())
        rule.setContent {
            ToolMapTheme {
                val ui by vm.ui.collectAsState()
                SketchTool(ui = ui, vm = vm, controller = controller, onSaved = {})
            }
        }
        rule.onNodeWithText("Croquis du 3 nov.").assertExists()
        rule.onNodeWithText("aucun trait").assertExists()
        rule.onNodeWithText("Enregistrer").assertExists()
        rule.onNodeWithText("6 px").assertExists()
        rule.onNodeWithText("Lissage moyen").assertExists()
        rule.onNodeWithText("Main").assertExists()
        rule.onNodeWithText("Stylo").performClick()
        assertEquals(SketchMode.PEN, vm.ui.value.mode)
        rule.onNodeWithContentDescription("bleu").performClick()
        assertEquals(4, vm.ui.value.colorIndex)
        rule.onNodeWithContentDescription("bleu").assertIsSelected()
        rule.onNodeWithText("Lissage moyen").performClick()
        rule.onNodeWithText("Lissage fort").performClick()
        assertEquals(SketchSmoothing.HIGH, vm.ui.value.smoothing)
        // The eraser has its own tier (variant A of p28-gomme.html): its size, the pen's settings hidden under it.
        rule.onNodeWithText("Gomme").performClick()
        rule.onNodeWithText("Taille de la gomme").assertIsDisplayed()
        rule.onNodeWithText("32 px").assertIsDisplayed()
        rule.onAllNodesWithText("Lissage fort").assertCountEquals(0)
        rule.onNodeWithContentDescription("Taille de la gomme").performSemanticsAction(SemanticsActions.SetProgress) {
            it(64f)
        }
        assertEquals(64, vm.ui.value.eraserPx)
        rule.onNodeWithText("Stylo").performClick()
        rule.onNodeWithText("Lissage fort").assertIsDisplayed()
    }
}
