package fr.toolmap.measure

import android.app.Application
import fr.toolmap.R
import fr.toolmap.core.measure.MeasureMode
import fr.toolmap.data.tracks.ItemKind
import fr.toolmap.data.tracks.TrackDatabase
import fr.toolmap.data.tracks.TrackRepository
import fr.toolmap.map.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MeasureViewModelTest {
    private lateinit var db: TrackDatabase
    private lateinit var repo: TrackRepository
    private lateinit var vm: MeasureViewModel
    private val messages = ArrayList<UiMessage>()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = TrackDatabase.inMemory(RuntimeEnvironment.getApplication())
        repo = TrackRepository(db)
        vm = MeasureViewModel(repo)
        scope.launch { vm.messages.collect { messages += it } }
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
        db.close()
    }

    private fun waitFor(
        what: String,
        check: suspend () -> Boolean,
    ) = runBlocking {
        try {
            withTimeout(TIMEOUT_MS) { while (!check()) delay(POLL_MS) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    @Test
    fun `points added while closed are ignored and the tool opens empty`() {
        vm.addAt(45.0, 6.0)
        assertFalse(vm.ui.value.active)
        assertNull(vm.overlay.value)

        vm.open()
        assertTrue(vm.ui.value.active)
        assertEquals(0, vm.ui.value.result.pointCount)
        assertNotNull(vm.overlay.value, "an empty overlay is published so that the map clears the layers")
    }

    @Test
    fun `the figures follow the points and the mode decides what is drawn`() {
        vm.open()
        vm.addAt(45.0, 6.0)
        vm.addAt(45.001, 6.0)
        vm.addAt(45.001, 6.001)
        val result = vm.ui.value.result
        assertEquals(3, result.pointCount)
        assertTrue(result.lengthMeters in 180.0..190.0, "111 m north then about 78 m east: ${result.lengthMeters}")
        assertNotNull(result.areaSquareMeters)
        assertEquals("""{"type":"FeatureCollection","features":[]}""", vm.overlay.value!!.fillGeoJson)

        vm.setMode(MeasureMode.AREA)
        assertTrue(vm.overlay.value!!.fillGeoJson.contains("Polygon"))
        vm.setMode(MeasureMode.PATH)
        assertFalse(vm.overlay.value!!.fillGeoJson.contains("Polygon"))
    }

    @Test
    fun `undo redo and clear keep the figures in step`() {
        vm.open()
        vm.addAt(45.0, 6.0)
        vm.addAt(45.001, 6.0)
        assertTrue(vm.ui.value.canUndo)
        vm.undo()
        assertEquals(1, vm.ui.value.result.pointCount)
        assertEquals(0.0, vm.ui.value.result.lengthMeters)
        assertTrue(vm.ui.value.canRedo)
        vm.redo()
        assertEquals(2, vm.ui.value.result.pointCount)
        vm.clear()
        assertEquals(0, vm.ui.value.result.pointCount)
        assertTrue(vm.ui.value.active, "clearing keeps the tool open")
    }

    @Test
    fun `adding after an undo appends at the end and never inserts in the middle`() {
        vm.open()
        vm.addAt(45.0, 6.0)
        vm.addAt(45.001, 6.0)
        vm.addAt(45.002, 6.0)
        vm.undo()
        vm.addAt(45.003, 6.0)
        val points = vm.points()
        assertEquals(listOf(45.0, 45.001, 45.003), points.map { it.lat })
    }

    @Test
    fun `a line is kept as a route or as a track and the tool closes`() {
        vm.open()
        vm.addAt(45.0, 6.0)
        vm.addAt(45.001, 6.0)
        vm.save(ItemKind.ROUTE, "Plan du jour")
        waitFor("route stored") { repo.library.first().routes.size == 1 }
        assertEquals("Plan du jour", runBlocking { repo.library.first().routes.single().name })
        assertFalse(vm.ui.value.active)
        assertNull(vm.overlay.value)
        assertTrue(messages.any { it.resId == R.string.draw_saved })

        vm.open()
        vm.addAt(45.0, 6.0)
        vm.addAt(45.002, 6.0)
        vm.save(ItemKind.TRACK, "Passage")
        waitFor("track stored") { repo.library.first().tracks.size == 1 }
        val track = runBlocking { repo.library.first().tracks.single() }
        assertEquals("Passage", track.name)
        assertEquals(2, track.pointCount)
    }

    @Test
    fun `a single point cannot be kept`() {
        vm.open()
        vm.addAt(45.0, 6.0)
        vm.save(ItemKind.TRACK, "Seul")
        assertTrue(messages.any { it.resId == R.string.draw_too_short })
        assertTrue(vm.ui.value.active, "the tool stays open")
        assertTrue(runBlocking { repo.library.first().tracks.isEmpty() })
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
    }
}
