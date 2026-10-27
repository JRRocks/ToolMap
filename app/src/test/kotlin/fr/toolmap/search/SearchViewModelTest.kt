package fr.toolmap.search

import android.app.Application
import fr.toolmap.R
import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.search.LibraryKind
import fr.toolmap.core.search.SearchResult
import fr.toolmap.core.search.SearchSource
import fr.toolmap.core.track.Waypoint
import fr.toolmap.data.search.GeocodeFailure
import fr.toolmap.data.search.GeocodeOutcome
import fr.toolmap.data.search.Geocoder
import fr.toolmap.data.search.ProviderFailure
import fr.toolmap.data.tiles.OfflineMode
import fr.toolmap.data.tracks.TrackDatabase
import fr.toolmap.data.tracks.TrackRepository
import fr.toolmap.map.UiMessage
import fr.toolmap.testing.stopAndJoin
import kotlinx.coroutines.CompletableDeferred
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SearchViewModelTest {
    /** A geocoder that answers what the test says, when the test says, and remembers what it was asked. */
    private class FakeGeocoder : Geocoder {
        val asked = ArrayList<Pair<String, LonLat?>>()
        var gate: CompletableDeferred<Unit>? = null
        var outcome = GeocodeOutcome(emptyList(), emptyList())

        override suspend fun search(
            query: String,
            near: LonLat?,
            languageTag: String,
            limit: Int,
        ): GeocodeOutcome {
            asked += query to near
            gate?.await()
            return outcome
        }
    }

    private lateinit var db: TrackDatabase
    private lateinit var repo: TrackRepository
    private val geocoder = FakeGeocoder()
    private val offline = OfflineMode()
    private lateinit var vm: SearchViewModel

    // Filled from the thread the view model emits on while the test reads it: a list safe to read meanwhile.
    private val messages = CopyOnWriteArrayList<UiMessage>()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = TrackDatabase.inMemory(RuntimeEnvironment.getApplication())
        repo = TrackRepository(db)
        vm = SearchViewModel(repo, geocoder, offline)
        scope.launch { vm.messages.collect { messages += it } }
    }

    @After
    fun tearDown() {
        scope.cancel()
        // The view model keeps reading the library: stop it before closing the database.
        vm.stopAndJoin()
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

    private fun seedLibrary() =
        runBlocking {
            repo.createWaypoint(Waypoint(45.0, 6.0, name = "Refuge de l'Écrin", desc = "Gardé en été"))
            repo.createWaypoint(Waypoint(45.1, 6.1, name = "Col du Lautaret"))
            repo.createTrack(
                "Traversée des Écrins",
                listOf(
                    listOf(
                        fr.toolmap.core.track.TrackPoint(45.0, 6.0),
                        fr.toolmap.core.track.TrackPoint(45.2, 6.2),
                    ),
                ),
            )
        }

    private val found =
        SearchResult("Grand Veymont", "Isère", 44.87, 5.53, SearchSource.OSM, "peak")

    @Test
    fun `my own items are found as the text changes, without any network`() {
        seedLibrary()
        waitFor("library read") {
            vm.onQueryChange("ecrin")
            vm.ui.value.here.size == 2
        }
        val kinds = vm.ui.value.here.map { it.libraryKind }
        assertTrue(LibraryKind.WAYPOINT in kinds && LibraryKind.TRACK in kinds)
        val track = vm.ui.value.here.first { it.libraryKind == LibraryKind.TRACK }
        assertNotNull(track.bounds, "a track is framed by its extent")
        assertEquals(45.1, track.lat, 1e-6, "and shown at the middle of it")
        assertEquals(0, geocoder.asked.size, "typing never reaches the geocoders")

        vm.onQueryChange("lautaret")
        assertEquals(listOf("Col du Lautaret"), vm.ui.value.here.map { it.title })
        vm.onQueryChange("")
        assertTrue(vm.ui.value.here.isEmpty())
    }

    @Test
    fun `a position typed in the box comes first`() {
        seedLibrary()
        vm.onQueryChange("45.05, 6.05")
        val first = vm.ui.value.here.first()
        assertEquals(SearchSource.COORDINATES, first.source)
        assertEquals(45.05, first.lat, 1e-9)
        assertEquals(6.05, first.lon, 1e-9)
    }

    @Test
    fun `submitting asks the geocoders with the map centre and shows their answer`() {
        geocoder.outcome =
            GeocodeOutcome(listOf(found), listOf(ProviderFailure(SearchSource.IGN, GeocodeFailure.NETWORK)))
        vm.open(44.8, 5.5)
        vm.onQueryChange("Grand Veymont")
        vm.submit()
        waitFor("answer shown") { vm.ui.value.searched }
        assertEquals(listOf<Pair<String, LonLat?>>("Grand Veymont" to LonLat(5.5, 44.8)), geocoder.asked.toList())
        assertEquals(listOf(found), vm.ui.value.online)
        assertEquals(1, vm.ui.value.failures.size, "a failing service is shown next to the answer of the other")
        assertFalse(vm.ui.value.searching)
    }

    @Test
    fun `in offline mode nothing is sent and the screen says so`() {
        offline.set(true)
        vm.onQueryChange("Grand Veymont")
        vm.submit()
        assertEquals(0, geocoder.asked.size)
        assertTrue(vm.ui.value.skippedOffline)
        assertFalse(vm.ui.value.searching)

        offline.set(false)
        vm.submit()
        waitFor("sent online") { geocoder.asked.size == 1 }
        assertFalse(vm.ui.value.skippedOffline)
    }

    @Test
    fun `an empty text is not submitted`() {
        vm.onQueryChange("   ")
        vm.submit()
        assertEquals(0, geocoder.asked.size)
        assertFalse(vm.ui.value.searching)
    }

    @Test
    fun `changing the text drops the answer of the previous one, even a late one`() {
        val gate = CompletableDeferred<Unit>()
        geocoder.gate = gate
        geocoder.outcome = GeocodeOutcome(listOf(found), emptyList())
        vm.onQueryChange("Grand Veymont")
        vm.submit()
        assertTrue(vm.ui.value.searching)
        vm.onQueryChange("Mont Blanc")
        assertFalse(vm.ui.value.searching, "editing cancels the search in flight")
        gate.complete(Unit)
        runBlocking { delay(100) }
        assertTrue(vm.ui.value.online.isEmpty(), "the answer for the old text never shows under the new one")
        assertFalse(vm.ui.value.searched)
    }

    @Test
    fun `a found place is kept as a waypoint with its name and where it is`() {
        vm.saveAsWaypoint(found)
        waitFor("waypoint stored") { runBlocking { repo.library.first().waypoints.size } == 1 }
        val waypoint = runBlocking { repo.library.first().waypoints.single() }
        assertEquals("Grand Veymont", waypoint.name)
        assertEquals("Isère", waypoint.desc)
        assertEquals(44.87, waypoint.lat, 1e-9)
        assertTrue(messages.any { it.resId == R.string.waypoint_saved })
        assertNull(vm.ui.value.online.firstOrNull())
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
    }
}
