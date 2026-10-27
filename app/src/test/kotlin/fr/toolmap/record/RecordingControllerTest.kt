package fr.toolmap.record

import android.app.Application
import fr.toolmap.core.record.Fix
import fr.toolmap.core.record.RecorderProfile
import fr.toolmap.core.record.RecorderState
import fr.toolmap.core.settings.RecorderSettings
import fr.toolmap.data.tracks.ItemKind
import fr.toolmap.data.tracks.ItemRef
import fr.toolmap.data.tracks.RecordingSessionStore
import fr.toolmap.data.tracks.TrackDatabase
import fr.toolmap.data.tracks.TrackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A GPS the test drives by hand; [failing] makes collecting it throw, like a phone without a receiver. */
private class FakeLocation : LocationSource {
    val flow = MutableSharedFlow<Fix>(extraBufferCapacity = 256)
    var failing = false
    private val subscribed = AtomicInteger()

    /**
     * Receivers actually subscribed to [flow]. Counted once the subscription is registered, not before: the flow
     * has no replay, so a fix emitted in between would be lost and a test waiting for it would time out.
     */
    val collectors: Int get() = subscribed.get()

    override fun fixes(
        minIntervalMillis: Long,
        minDistanceMeters: Float,
    ): Flow<Fix> =
        flow {
            if (failing) error("no GPS")
            var counted = false
            try {
                flow
                    .onSubscription {
                        counted = true
                        subscribed.incrementAndGet()
                    }.collect { emit(it) }
            } finally {
                if (counted) subscribed.decrementAndGet()
            }
        }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RecordingControllerTest {
    private lateinit var db: TrackDatabase
    private lateinit var repo: TrackRepository
    private lateinit var dir: File
    private lateinit var store: RecordingSessionStore
    private lateinit var gps: FakeLocation
    private lateinit var scope: CoroutineScope
    private var now = 1_000_000L

    @Before
    fun setUp() {
        db = TrackDatabase.inMemory(RuntimeEnvironment.getApplication())
        repo = TrackRepository(db)
        dir = createTempDirectory("recording").toFile()
        store = RecordingSessionStore(File(dir, "session.json"))
        gps = FakeLocation()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        dir.deleteRecursively()
    }

    private fun controller(
        flushEveryPoints: Int = 3,
        options: () -> RecorderSettings = { RecorderSettings() },
    ) = RecordingController(
        repository = repo,
        store = store,
        location = gps,
        clock = { now },
        scope = scope,
        flushEveryPoints = flushEveryPoints,
        flushEveryMillis = 60_000,
        options = options,
    )

    /** A walker going north, one fix every 4 s, 5.6 m apart (above the walking thresholds). */
    private fun fix(step: Int) =
        Fix(45.0 + step * 0.00005, 6.0, 1000.0, 1_000_000L + step * 4_000L, accuracyMeters = 5.0)

    private fun eventually(
        what: String,
        check: () -> Boolean,
    ) = runBlocking {
        try {
            withTimeout(TIMEOUT_MS) { while (!check()) delay(POLL_MS) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    private fun feed(
        c: RecordingController,
        from: Int,
        count: Int,
    ) {
        eventually("receiver") { gps.collectors > 0 }
        for (k in from until from + count) {
            now = 1_000_000L + k * 4_000L
            gps.flow.tryEmit(fix(k))
        }
        eventually("points counted") { c.state.value.live.pointCount == from + count }
    }

    private fun stored(id: Long) = runBlocking { repo.loadTrack(id)!!.segments }

    @Test
    fun `points are written in batches and the rest on stop`() {
        val c = controller(flushEveryPoints = 3)
        val id = runBlocking { c.start("  Ma sortie ", RecorderProfile.WALK) }
        assertEquals(RecorderState.RECORDING, c.state.value.phase)
        feed(c, 0, 7)
        // 7 points, batches of 3: 6 are in the database, 1 is waiting.
        eventually("two batches") { stored(id).sumOf { it.size } == 6 }
        assertEquals(1, stored(id).size)
        val finished = runBlocking { c.stop() }
        assertEquals(id, finished)
        assertEquals(7, stored(id).sumOf { it.size })
        assertEquals(RecorderState.IDLE, c.state.value.phase)
        assertEquals(id, c.state.value.finishedTrackId)
        assertNull(store.read(), "the session file goes with the recording")
        assertEquals("Ma sortie", runBlocking { repo.loadTrack(id)!!.name })
        c.clearFinished()
        assertNull(c.state.value.finishedTrackId)
    }

    @Test
    fun `a pause cuts the track into two segments and writes what was waiting`() {
        val c = controller(flushEveryPoints = 50)
        val id = runBlocking { c.start("Pause", RecorderProfile.WALK) }
        feed(c, 0, 3)
        runBlocking { c.pause() }
        assertEquals(RecorderState.PAUSED, c.state.value.phase)
        assertEquals(3, stored(id).sumOf { it.size }, "pausing flushes")
        assertTrue(store.read()!!.paused)
        eventually("receiver stopped") { gps.collectors == 0 }
        // Fixes while paused are not even looked at.
        gps.flow.tryEmit(fix(5))
        runBlocking { delay(100) }
        assertEquals(3, c.state.value.live.pointCount)

        now += 60_000
        runBlocking { c.resume() }
        eventually("receiver back") { gps.collectors > 0 }
        for (k in 20 until 23) {
            now = 1_000_000L + k * 4_000L
            gps.flow.tryEmit(fix(k))
        }
        // The resume publishes a count of 3 before any new fix is handled: wait for the last fix itself. The default
        // setting starts the statistics again, so the live count is the new segment's; the track keeps all six.
        eventually("the last fix after the pause") { c.state.value.live.lastFix?.timeMillis == fix(22).timeMillis }
        assertEquals(3, c.state.value.live.pointCount)
        runBlocking { c.stop() }
        assertEquals(listOf(3, 3), stored(id).map { it.size })
    }

    @Test
    fun `Reprendre after a stop records the same track again, in a new segment, with its figures`() {
        val c = controller(flushEveryPoints = 50, options = { RecorderSettings(resetStatsOnSegment = false) })
        val id = runBlocking { c.start("Sortie", RecorderProfile.WALK) }
        feed(c, 0, 3)
        runBlocking { c.stop() }
        eventually("receiver stopped") { gps.collectors == 0 }
        assertTrue(!runBlocking { c.reopen(id + 1) }, "only the track that just ended opens again")
        assertTrue(runBlocking { c.reopen(id) })
        assertEquals(RecorderState.RECORDING, c.state.value.phase)
        assertEquals(id, store.read()!!.trackId, "the session file is back, for a crash")
        eventually("receiver back") { gps.collectors > 0 }
        for (k in 20 until 23) {
            now = 1_000_000L + k * 4_000L
            gps.flow.tryEmit(fix(k))
        }
        eventually("the last fix after Reprendre") { c.state.value.live.lastFix?.timeMillis == fix(22).timeMillis }
        assertEquals(6, c.state.value.live.pointCount, "the figures go on from those of the stopped recording")
        runBlocking { c.stop() }
        assertEquals(listOf(3, 3), stored(id).map { it.size })
        // A new recording forgets the one before: « Reprendre » of an old message does nothing.
        val other = runBlocking { c.start("Autre", RecorderProfile.WALK) }
        runBlocking { c.stop() }
        assertTrue(!runBlocking { c.reopen(id) })
        assertTrue(other != id)
    }

    @Test
    fun `without the reset the live statistics keep counting the whole track after a pause`() {
        val c = controller(flushEveryPoints = 50, options = { RecorderSettings(resetStatsOnSegment = false) })
        runBlocking { c.start("Sans remise à zéro", RecorderProfile.WALK) }
        feed(c, 0, 3)
        runBlocking { c.pause() }
        eventually("receiver stopped") { gps.collectors == 0 }
        now += 60_000
        runBlocking { c.resume() }
        eventually("receiver back") { gps.collectors > 0 }
        for (k in 20 until 23) {
            now = 1_000_000L + k * 4_000L
            gps.flow.tryEmit(fix(k))
        }
        eventually("points after the pause") { c.state.value.live.pointCount == 6 }
        runBlocking { c.stop() }
    }

    @Test
    fun `a killed process is recovered from the session file and the next fix opens a new segment`() {
        val first = controller(flushEveryPoints = 2)
        val id = runBlocking { first.start("Crash", RecorderProfile.WALK) }
        feed(first, 0, 5)
        eventually("four points stored") { stored(id).sumOf { it.size } >= 4 }
        // The process dies: no stop, no flush; the first controller is simply abandoned.
        scope.cancel()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        gps = FakeLocation()

        val second = controller(flushEveryPoints = 2)
        runBlocking { second.checkForRecovery() }
        val session = assertNotNull(second.state.value.recoverable)
        assertEquals(id, session.trackId)
        assertFailsWith<IllegalStateException> { runBlocking { second.start("autre", RecorderProfile.WALK) } }

        now += 120_000
        runBlocking { second.resumeRecovered() }
        assertEquals(RecorderState.RECORDING, second.state.value.phase)
        assertNull(second.state.value.recoverable)
        val before = stored(id).sumOf { it.size }
        eventually("receiver") { gps.collectors > 0 }
        for (k in 40 until 44) {
            now = 1_000_000L + k * 4_000L
            gps.flow.tryEmit(fix(k))
        }
        eventually("new points") { stored(id).sumOf { it.size } >= before + 4 }
        runBlocking { second.stop() }
        val segments = stored(id)
        assertEquals(2, segments.size, "the gap is not drawn as a line")
        assertEquals(before + 4, segments.sumOf { it.size })
    }

    @Test
    fun `keeping a recovered recording closes it without recording further`() {
        val first = controller(flushEveryPoints = 1)
        val id = runBlocking { first.start("Garder", RecorderProfile.WALK) }
        feed(first, 0, 3)
        eventually("stored") { stored(id).sumOf { it.size } == 3 }
        scope.cancel()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val second = controller()
        runBlocking {
            second.checkForRecovery()
            assertNotNull(second.state.value.recoverable)
            second.finishRecovered()
        }
        assertEquals(id, second.state.value.finishedTrackId)
        assertNull(second.state.value.recoverable)
        assertNull(store.read())
        assertEquals(RecorderState.IDLE, second.state.value.phase)
        assertNotNull(runBlocking { repo.loadTrack(id) })
    }

    @Test
    fun `a session file that points at a deleted track is dropped`() {
        val c = controller()
        val id = runBlocking { c.start("Perdue", RecorderProfile.WALK) }
        scope.cancel()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        runBlocking { repo.delete(ItemRef(ItemKind.TRACK, id)) }
        val second = controller()
        runBlocking { second.checkForRecovery() }
        assertNull(second.state.value.recoverable)
        assertNull(store.read())
    }

    @Test
    fun `a missing GPS is reported and the recording stays open`() {
        gps.failing = true
        val c = controller()
        runBlocking { c.start("Sans GPS", RecorderProfile.WALK) }
        eventually("gps flag") { c.state.value.gpsUnavailable }
        assertEquals(RecorderState.RECORDING, c.state.value.phase)
        runBlocking { c.stop() }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
    }
}
