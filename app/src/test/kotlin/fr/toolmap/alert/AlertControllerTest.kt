package fr.toolmap.alert

import android.app.Application
import fr.toolmap.R
import fr.toolmap.core.alert.AlertEvent
import fr.toolmap.core.alert.AlertState
import fr.toolmap.core.alert.FollowKind
import fr.toolmap.core.record.Fix
import fr.toolmap.core.settings.AlertSettings
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.core.track.Waypoint
import fr.toolmap.data.alert.AlertStateStore
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.data.tracks.ItemKind
import fr.toolmap.data.tracks.ItemRef
import fr.toolmap.data.tracks.TrackDatabase
import fr.toolmap.data.tracks.TrackRepository
import fr.toolmap.map.UiMessage
import fr.toolmap.record.LocationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
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
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AlertControllerTest {
    /** A GPS the test drives: fixes go out when the test says, and it knows whether anyone is listening. */
    private class FakeLocation : LocationSource {
        val shared = MutableSharedFlow<Fix>(extraBufferCapacity = 64)
        val listeners = AtomicInteger()
        var failure: Throwable? = null

        override fun fixes(
            minIntervalMillis: Long,
            minDistanceMeters: Float,
        ): Flow<Fix> =
            flow {
                listeners.incrementAndGet()
                failure?.let { throw it }
                emitAll(shared)
            }.onCompletion { listeners.decrementAndGet() }
    }

    private class FakeNotifier : AlertNotifier {
        val raised = ArrayList<AlertEvent>()

        override fun raise(event: AlertEvent) {
            raised += event
        }
    }

    private class FakeService : AlertServiceControl {
        val started = AtomicInteger()
        val stopped = AtomicInteger()

        override fun start() {
            started.incrementAndGet()
        }

        override fun stop() {
            stopped.incrementAndGet()
        }
    }

    private lateinit var db: TrackDatabase
    private lateinit var repo: TrackRepository
    private lateinit var settings: SettingsStore
    private lateinit var store: AlertStateStore
    private val location = FakeLocation()
    private val notifier = FakeNotifier()
    private val service = FakeService()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val messages = ArrayList<UiMessage>()
    private val dir: File = Files.createTempDirectory("alerts-app").toFile()
    private lateinit var controller: AlertController
    private var clockMillis = 1_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = TrackDatabase.inMemory(RuntimeEnvironment.getApplication())
        repo = TrackRepository(db)
        settings = SettingsStore(File(dir, "settings.json"))
        store = AlertStateStore(File(dir, "alerts.json"))
        controller = newController()
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
        db.close()
    }

    private fun newController(): AlertController =
        AlertController(repo, settings, store, location, notifier, service, scope) { clockMillis }.also { c ->
            scope.launch { c.messages.collect { messages += it } }
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

    // A flat patch of ground around 45°N 6°E.
    private fun lat(y: Double) = 45.0 + y / METERS_PER_DEGREE

    private fun lon(x: Double) = 6.0 + x / (METERS_PER_DEGREE * cos(Math.toRadians(45.0)))

    private fun fixAt(
        x: Double,
        y: Double,
    ): Fix {
        clockMillis += 1_000L
        return Fix(lat(y), lon(x), timeMillis = clockMillis, accuracyMeters = 5.0)
    }

    private fun send(
        x: Double,
        y: Double,
    ) {
        waitFor("a listener on the GPS") { location.listeners.get() > 0 }
        location.shared.tryEmit(fixAt(x, y))
    }

    private fun hut(radius: Int? = null): Long =
        runBlocking {
            repo.createWaypoint(Waypoint(lat(0.0), lon(1000.0), name = "Refuge")).also { id ->
                controller.watch(id, radius)
            }
        }.also { waitFor("the waypoint is resolved") { controller.ui.value.places.size == 1 } }

    private fun line(): Long =
        runBlocking {
            repo.createTrack(
                "Sentier",
                listOf((0..20).map { TrackPoint(lat(0.0), lon(it * 100.0)) }),
            )
        }.also { id -> waitFor("the track is in the library") { repo.library.first().tracks.any { it.id == id } } }

    @Test
    fun `nothing is watched at first so there is no service and no GPS`() {
        assertTrue(!controller.ui.value.active)
        assertEquals(0, service.started.get())
        assertEquals(0, location.listeners.get())
    }

    @Test
    fun `watching a waypoint starts the service and the GPS, and entering its radius raises the alert`() {
        hut(radius = 100)
        waitFor("service started") { service.started.get() >= 1 }
        send(0.0, 0.0)
        send(500.0, 0.0)
        assertTrue(notifier.raised.isEmpty())
        send(930.0, 0.0)
        waitFor("proximity alert") { notifier.raised.size == 1 }
        val event = assertIs<AlertEvent.Proximity>(notifier.raised.single())
        assertEquals("Refuge", event.place.name)
        val banner = assertNotNull(controller.ui.value.banner)
        assertEquals(event, banner.event)
        assertEquals(clockMillis, banner.atMillis)
        controller.dismissBanner()
        assertNull(controller.ui.value.banner)
    }

    @Test
    fun `the default radius of the settings applies to a waypoint without its own, and follows a change`() {
        settings.update { it.copy(alerts = AlertSettings(proximityRadiusMeters = 50)) }
        hut(radius = null)
        send(0.0, 0.0)
        send(800.0, 0.0)
        assertTrue(notifier.raised.isEmpty(), "300 m away is outside 50 m")
        settings.update { it.copy(alerts = AlertSettings(proximityRadiusMeters = 500)) }
        send(850.0, 0.0)
        waitFor("alert with the new default radius") { notifier.raised.size == 1 }
    }

    @Test
    fun `unwatching the last waypoint stops the GPS and the service`() {
        val id = hut()
        waitFor("listening") { location.listeners.get() == 1 }
        controller.unwatch(id)
        waitFor("not listening") { location.listeners.get() == 0 }
        waitFor("service stopped") { service.stopped.get() >= 1 }
        assertTrue(!controller.ui.value.active)
    }

    @Test
    fun `a deleted waypoint is no longer watched`() {
        val id = hut()
        waitFor("watched") { controller.ui.value.watches.size == 1 }
        runBlocking { repo.delete(ItemRef(ItemKind.WAYPOINT, id)) }
        waitFor("pruned") { store.state.value.watches.isEmpty() }
        waitFor("idle") { !controller.ui.value.active }
    }

    @Test
    fun `following a track shows its name and where the walker is on it`() {
        val id = line()
        controller.follow(ItemRef(ItemKind.TRACK, id))
        waitFor("follow loaded") { controller.ui.value.follow != null }
        val follow = assertNotNull(controller.ui.value.follow)
        assertEquals("Sentier", follow.name)
        assertEquals(FollowKind.TRACK, follow.kind)
        send(500.0, 10.0)
        waitFor("status") { controller.ui.value.follow?.status != null }
        val status = assertNotNull(controller.ui.value.follow?.status)
        assertEquals(500.0, status.alongMeters, 3.0)
        assertEquals(1500.0, status.remainingMeters, 3.0)
        assertEquals(10.0, status.offMeters, 1.0)
        assertTrue(controller.isFollowing(ItemRef(ItemKind.TRACK, id)))
        assertTrue(!controller.isFollowing(ItemRef(ItemKind.ROUTE, id)))
    }

    @Test
    fun `leaving the followed track raises the warning, coming back clears the banner`() {
        controller.follow(ItemRef(ItemKind.TRACK, line()))
        waitFor("follow loaded") { controller.ui.value.follow != null }
        send(500.0, 0.0)
        repeat(3) { send(500.0, 90.0) }
        waitFor("off track alert") { notifier.raised.any { it is AlertEvent.OffTrack } }
        assertIs<AlertEvent.OffTrack>(assertNotNull(controller.ui.value.banner).event)
        assertTrue(assertNotNull(controller.ui.value.follow?.status).offTrack)
        send(500.0, 5.0)
        waitFor("back on track") { notifier.raised.last() == AlertEvent.BackOnTrack }
        assertNull(controller.ui.value.banner)
    }

    @Test
    fun `reversing the follow counts progress from the other end`() {
        val id = line()
        controller.follow(ItemRef(ItemKind.TRACK, id))
        waitFor("follow loaded") { controller.ui.value.follow != null }
        controller.reverseFollow()
        waitFor("reversed") { controller.ui.value.follow?.reversed == true }
        send(1800.0, 0.0)
        waitFor("status") { controller.ui.value.follow?.status != null }
        assertEquals(200.0, assertNotNull(controller.ui.value.follow?.status).alongMeters, 3.0)
    }

    @Test
    fun `a route can be followed too`() {
        val id =
            runBlocking {
                repo.createRoute("Itinéraire", (0..5).map { Waypoint(lat(0.0), lon(it * 100.0)) })
            }
        controller.follow(ItemRef(ItemKind.ROUTE, id))
        waitFor("route followed") { controller.ui.value.follow?.kind == FollowKind.ROUTE }
        assertEquals("Itinéraire", controller.ui.value.follow?.name)
    }

    @Test
    fun `a line with nothing to follow is refused with a message`() {
        val id = runBlocking { repo.createTrack("Un point", listOf(listOf(TrackPoint(45.0, 6.0)))) }
        controller.follow(ItemRef(ItemKind.TRACK, id))
        waitFor("refused") { messages.any { it.resId == R.string.follow_unusable } }
        waitFor("not following") { store.state.value.follow == null }
        assertNull(controller.ui.value.follow)
    }

    @Test
    fun `a waypoint cannot be followed`() {
        val id = runBlocking { repo.createWaypoint(Waypoint(45.0, 6.0)) }
        controller.follow(ItemRef(ItemKind.WAYPOINT, id))
        assertTrue(store.state.value.isIdle)
    }

    @Test
    fun `stop following and stop all`() {
        val id = line()
        controller.follow(ItemRef(ItemKind.TRACK, id))
        controller.watch(runBlocking { repo.createWaypoint(Waypoint(45.0, 6.0)) }, null)
        waitFor("both") { controller.ui.value.follow != null && controller.ui.value.watches.size == 1 }
        controller.stopFollowing()
        waitFor("follow gone") { controller.ui.value.follow == null }
        assertTrue(controller.ui.value.active, "the watch is still there")
        controller.stopAll()
        waitFor("idle") { store.state.value == AlertState() }
        waitFor("GPS released") { location.listeners.get() == 0 }
    }

    @Test
    fun `what was watched comes back with a new controller after a restart`() {
        val id = hut(radius = 80)
        waitFor("saved") { store.state.value.watching(id) != null }
        val before = service.started.get()
        val restarted = newController()
        waitFor("service started again") { service.started.get() > before }
        waitFor("watch restored") { restarted.ui.value.watches.size == 1 }
        assertEquals(80, restarted.ui.value.watches.single().radiusMeters)
    }

    @Test
    fun `a GPS that cannot start is reported`() {
        location.failure = IllegalStateException("no GPS")
        hut()
        waitFor("gps failure") { controller.ui.value.gpsFailed }
        assertTrue(controller.ui.value.active, "what is watched stays, so that it resumes when the GPS is back")
    }

    private companion object {
        const val METERS_PER_DEGREE = 111_194.9266
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
    }
}
