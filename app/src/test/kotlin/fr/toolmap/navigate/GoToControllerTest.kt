package fr.toolmap.navigate

import android.app.Application
import fr.toolmap.core.geo.NavTarget
import fr.toolmap.core.record.Fix
import fr.toolmap.core.route.AvoidZone
import fr.toolmap.core.route.LegResult
import fr.toolmap.core.route.RouteStop
import fr.toolmap.core.route.RoutingEngine
import fr.toolmap.core.route.RoutingError
import fr.toolmap.core.route.RoutingProfiles
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.record.LocationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * « Aller vers un point » by BRouter (P30.3): the way asked from the position, again at the interval and at once off
 * the way, the straight line without data, the start sheet and its choices, the service and the vibration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GoToControllerTest {
    private class FakeLocation : LocationSource {
        val shared = MutableSharedFlow<Fix>(extraBufferCapacity = 64)

        override fun fixes(
            minIntervalMillis: Long,
            minDistanceMeters: Float,
        ): Flow<Fix> = shared
    }

    /** BRouter: a way due north from the position to the target, a point every 0.001°; or [error]. */
    private class FakeEngine : RoutingEngine {
        val asked = ArrayList<Pair<RouteStop, String>>()
        var error: RoutingError? = null

        override suspend fun route(
            from: RouteStop,
            to: RouteStop,
            profile: String,
            zones: List<AvoidZone>,
        ): LegResult {
            asked += from to profile
            error?.let { return LegResult.Failed(it, "test") }
            val steps = ((to.lat - from.lat) / STEP).toInt().coerceAtLeast(1)
            val points = List(steps + 1) { TrackPoint(from.lat + (to.lat - from.lat) * it / steps, to.lon) }
            return LegResult.Ok(listOf(TrackPoint(from.lat, from.lon)) + points, timeMillis = 600_000L)
        }
    }

    private class FakePlatform : GoToPlatform {
        var running = false
        var vibrations = 0

        override fun startService() {
            running = true
        }

        override fun stopService() {
            running = false
        }

        override fun vibrate() {
            vibrations++
        }
    }

    private val dir: File = Files.createTempDirectory("goto").toFile()
    private val settings = SettingsStore(File(dir, "settings.json"))
    private val location = FakeLocation()
    private val engine = FakeEngine()
    private val platform = FakePlatform()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var now = 1_000_000L
    private val controller = GoToController(location, engine, settings, platform, scope) { now }
    private val refuge = NavTarget(45.01, 6.0, "Refuge du Pré", "Alpinehut")

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun at(
        lat: Double,
        lon: Double,
    ) {
        location.shared.tryEmit(Fix(lat, lon, timeMillis = now))
    }

    @Test
    fun `the way is asked from the position, then again at the interval, and what remains of it is measured`() {
        controller.start(refuge)
        assertTrue(platform.running, "the screen-off service runs")
        at(45.0, 6.0)
        assertEquals(1, engine.asked.size)
        assertEquals(RoutingProfiles.HIKING, engine.asked.single().second)
        val ui = controller.ui.value
        val progress = assertNotNull(ui.progress)
        assertEquals(1_112.0, progress.remainingMeters, 15.0)
        assertTrue(assertNotNull(ui.remaining).size >= 2)
        assertNotNull(ui.nextAtMillis)

        now += 60_000L
        at(45.002, 6.0)
        assertEquals(1, engine.asked.size, "not before the interval")
        assertEquals(890.0, assertNotNull(controller.ui.value.progress).remainingMeters, 15.0)
        now += 61_000L
        at(45.003, 6.0)
        assertEquals(2, engine.asked.size, "two minutes later")
        assertEquals(45.003, engine.asked.last().first.lat, 1e-9)

        controller.stop()
        assertNull(controller.ui.value.target)
        assertFalse(platform.running)
    }

    @Test
    fun `off the way it vibrates once, asks again at once and says so in red`() {
        controller.start(refuge)
        at(45.0, 6.0)
        now += 20_000L
        // About 79 m east of the way.
        at(45.004, 6.001)
        assertEquals(1, platform.vibrations)
        assertEquals(2, engine.asked.size, "a new way from where the walker is")
        val ui = controller.ui.value
        assertEquals(79.0, assertNotNull(ui.rerouteMeters), 3.0)
        assertNotNull(ui.reroutedAtMillis)
        assertTrue(ui.rerouting)
        assertFalse(assertNotNull(ui.progress).offRoute, "on the new way")
        now += GoToUi.REROUTED_SHOWN_MILLIS + 1_000L
        at(45.005, 6.001)
        assertFalse(controller.ui.value.rerouting, "the red pill goes after a moment")
    }

    @Test
    fun `without data the straight line stays, and BRouter is asked again at the next interval`() {
        engine.error = RoutingError.NO_DATA
        controller.start(refuge)
        at(45.0, 6.0)
        val ui = controller.ui.value
        assertEquals(RoutingError.NO_DATA, ui.failure)
        assertNull(ui.way)
        assertEquals(1_112.0, assertNotNull(ui.guidance).distanceMeters, 15.0)
        now += 30_000L
        at(45.001, 6.0)
        assertEquals(1, engine.asked.size)
        engine.error = null
        now += 100_000L
        at(45.002, 6.0)
        assertEquals(2, engine.asked.size)
        assertNull(controller.ui.value.failure)
        assertNotNull(controller.ui.value.way)
    }

    @Test
    fun `in a straight line BRouter is never asked`() {
        settings.update { it.copy(placemarks = it.placemarks.copy(goToRouted = false)) }
        controller.start(refuge)
        at(45.0, 6.0)
        assertTrue(engine.asked.isEmpty())
        assertNotNull(controller.ui.value.guidance)
    }

    @Test
    fun `the start sheet computes the way for its figures, its choices are kept on Partir`() {
        controller.preview(refuge)
        assertTrue(controller.ui.value.previewing)
        assertFalse(platform.running, "nothing runs with the screen off before Partir")
        at(45.0, 6.0)
        assertEquals(1, engine.asked.size)
        controller.choose(true, RoutingProfiles.MTB, 5)
        assertEquals(RoutingProfiles.MTB, engine.asked.last().second, "the way follows the profile")
        assertEquals(300_000L, controller.ui.value.intervalMillis)
        controller.go()
        assertFalse(controller.ui.value.previewing)
        assertTrue(platform.running)
        val p = settings.settings.value.placemarks
        assertEquals(RoutingProfiles.MTB, p.goToProfile)
        assertEquals(5, p.goToIntervalMinutes)
    }

    @Test
    fun `a start sheet closed without Partir gives back the navigation it stood in front of`() {
        val hut = NavTarget(45.02, 6.0, "Cabane")
        controller.start(hut)
        at(45.0, 6.0)
        controller.preview(refuge)
        assertEquals(refuge, controller.ui.value.target)
        controller.cancelPreview()
        assertEquals(hut, controller.ui.value.target)
        assertFalse(controller.ui.value.previewing)
        assertTrue(platform.running)

        controller.stop()
        controller.preview(refuge)
        controller.cancelPreview()
        assertNull(controller.ui.value.target, "nothing to give back")
    }

    @Test
    fun `once arrived BRouter is not asked any more`() {
        controller.start(refuge)
        at(45.0, 6.0)
        now += 200_000L
        at(45.01, 6.0)
        assertTrue(controller.ui.value.arrived)
        assertEquals(1, engine.asked.size)
    }

    private companion object {
        const val STEP = 0.001
    }
}
