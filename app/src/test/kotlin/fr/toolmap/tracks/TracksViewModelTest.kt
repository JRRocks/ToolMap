package fr.toolmap.tracks

import android.app.Application
import fr.toolmap.R
import fr.toolmap.core.track.GpxReader
import fr.toolmap.core.track.LibraryFilter
import fr.toolmap.core.track.LibrarySort
import fr.toolmap.core.track.Track
import fr.toolmap.core.track.TrackDocument
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.core.track.Waypoint
import fr.toolmap.data.elevation.ElevationCompleter
import fr.toolmap.data.elevation.ElevationTiles
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.data.tracks.ItemKind
import fr.toolmap.data.tracks.ItemRef
import fr.toolmap.data.tracks.TrackDatabase
import fr.toolmap.data.tracks.TrackDisplay
import fr.toolmap.data.tracks.TrackRepository
import fr.toolmap.data.tracks.TrackSelection
import fr.toolmap.map.UiMessage
import fr.toolmap.testing.stopAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TracksViewModelTest {
    private lateinit var db: TrackDatabase
    private lateinit var vm: TracksViewModel
    private lateinit var repository: TrackRepository
    private lateinit var dir: File

    // Filled from the thread the view model emits on while the test reads it: a list safe to read meanwhile.
    private val messages = CopyOnWriteArrayList<UiMessage>()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private var collector: Job? = null

    private fun fixture(name: String): ByteArray {
        val root = requireNotNull(System.getProperty("fixtures.dir")) { "fixtures.dir not set by Gradle" }
        return File(root, name).readBytes()
    }

    /** A flat 500 m Terrarium tile. */
    private val flatTile: ByteArray by lazy {
        val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
        val v = Math.round((500.0 + 32768.0) * 256).toInt()
        val rgb = ((v shr 16) and 0xFF shl 16) or ((v shr 8) and 0xFF shl 8) or (v and 0xFF)
        for (y in 0 until 256) for (x in 0 until 256) image.setRGB(x, y, rgb)
        ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = TrackDatabase.inMemory(RuntimeEnvironment.getApplication())
        repository = TrackRepository(db)
        dir = createTempDirectory("tracks-vm").toFile()
        vm = newViewModel()
        collector = scope.launch { vm.messages.collect { messages += it } }
    }

    @After
    fun tearDown() {
        collector?.cancel()
        scope.cancel()
        // The view model keeps reading the database (library, map data): stop it before closing the database.
        vm.stopAndJoin()
        Dispatchers.resetMain()
        db.close()
        dir.deleteRecursively()
    }

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

    private fun newViewModel() =
        TracksViewModel(
            repository,
            TrackDisplay(repository),
            ElevationCompleter(ElevationTiles { _, _, _ -> flatTile }),
            SettingsStore(File(dir, "settings.json")),
        )

    @Test
    fun `the sort and the filter of the explorer are kept for the next opening`() {
        assertEquals(LibrarySort.DATE, vm.libraryView.value.sort)
        assertEquals(LibraryFilter.ALL, vm.libraryView.value.filter)
        vm.setLibrarySort(LibrarySort.DISTANCE)
        vm.setLibraryFilter(LibraryFilter.WAYPOINTS)
        eventually("sort and filter") {
            vm.libraryView.value.sort == LibrarySort.DISTANCE && vm.libraryView.value.filter == LibraryFilter.WAYPOINTS
        }
        // A new view model (the app opened again) reads them back from the settings file.
        val again = newViewModel()
        assertEquals(LibrarySort.DISTANCE, again.libraryView.value.sort)
        assertEquals(LibraryFilter.WAYPOINTS, again.libraryView.value.filter)
        again.stopAndJoin()
    }

    @Test
    fun `open folders and the scroll position are kept while the app runs, not to the next launch`() {
        assertEquals(emptySet(), vm.expandedFolders.value)
        vm.toggleFolder(3)
        vm.toggleFolder(5)
        vm.expandFolder(3)
        vm.libraryScroll = 12 to 40
        assertEquals(setOf(3L, 5L), vm.expandedFolders.value)
        assertEquals(12 to 40, vm.libraryScroll)
        vm.toggleFolder(5)
        assertEquals(setOf(3L), vm.expandedFolders.value)
        // A new view model (the app launched again) starts with every folder closed, at the top of the list.
        val again = newViewModel()
        assertEquals(emptySet(), again.expandedFolders.value)
        assertEquals(0 to 0, again.libraryScroll)
        again.stopAndJoin()
    }

    @Test
    fun `importing a GPX file fills the library and the map layer`() {
        vm.import(fixture("gpx/Mojstrovka.gpx"), "Mojstrovka.gpx")
        eventually("library") { vm.library.value?.tracks?.size == 1 }
        eventually("map data") { vm.mapData.value?.lineCount == 1 }
        assertTrue(messages.any { it.resId == R.string.tracks_imported })
        assertTrue(vm.mapData.value!!.linesGeoJson.contains("\"LineString\""))
    }

    @Test
    fun `a file that is not a track file is refused with a message and changes nothing`() {
        vm.import("<html>not a track</html>".toByteArray(), "page.gpx")
        eventually("message") { messages.any { it.resId == R.string.tracks_import_failed } }
        eventually("library read") { vm.library.value != null }
        assertTrue(vm.library.value!!.isEmpty)
    }

    @Test
    fun `selecting a track computes its statistics and profile`() {
        vm.import(fixture("gpx/Mojstrovka.gpx"), "Mojstrovka.gpx")
        eventually("library") { vm.library.value?.tracks?.size == 1 }
        val id = vm.library.value!!.tracks.single().id
        vm.select(id)
        eventually("detail") { vm.detail.value?.trackId == id }
        val detail = vm.detail.value!!
        assertTrue(detail.stats.distance2dMeters > 1000)
        assertNotNull(detail.profile)
        assertEquals(0, detail.missingElevation)

        vm.setCursor(500.0)
        assertNotNull(vm.cursor.value)
        vm.setCursor(null)
        assertNull(vm.cursor.value)
    }

    @Test
    fun `missing elevations are completed from terrain tiles and the screen figures follow`() {
        val line = List(6) { i -> TrackPoint(45.0 + i * 0.0003, 6.0 + i * 0.0003) }
        val repo = TrackRepository(db)
        runBlocking {
            repo.import(TrackDocument(tracks = listOf(Track(name = "bare", segments = listOf(line)))), "bare.gpx")
        }
        eventually("library") { vm.library.value?.tracks?.size == 1 }
        val id = vm.library.value!!.tracks.single().id
        vm.select(id)
        eventually("detail") { vm.detail.value?.trackId == id }
        assertEquals(6, vm.detail.value!!.missingElevation)
        assertNull(vm.detail.value!!.profile)

        vm.completeElevation(id)
        eventually("completion") { vm.detail.value?.missingElevation == 0 && vm.detail.value?.trackId == id }
        assertTrue(messages.any { it.resId == R.string.tracks_elevation_done })
        assertNotNull(vm.detail.value!!.profile)
        assertEquals(500.0, vm.detail.value!!.profile!!.minElevation!!, 0.5)
    }

    @Test
    fun `hiding a track removes it from the map layer and visibility survives`() {
        vm.import(fixture("gpx/Mojstrovka.gpx"), "Mojstrovka.gpx")
        eventually("map data") { vm.mapData.value?.lineCount == 1 }
        val id = vm.library.value!!.tracks.single().id
        vm.setVisible(ItemRef(ItemKind.TRACK, id), false)
        eventually("hidden") { vm.mapData.value?.lineCount == 0 }
        vm.setVisible(ItemRef(ItemKind.TRACK, id), true)
        eventually("shown") { vm.mapData.value?.lineCount == 1 }
    }

    @Test
    fun `exporting to the cache writes a GPX file that reads back`() {
        vm.import(fixture("gpx/korita-zbevnica.gpx"), "korita.gpx")
        // The library flow combines four independent table flows, so it can show the new tracks a moment before the
        // new folders: wait for the whole import, not for its first visible part.
        eventually("library") {
            vm.library.value?.let {
                it.tracks.size == KORITA_TRACKS && it.folders.isNotEmpty() && it.waypoints.size == 2
            } ==
                true
        }
        val lib = vm.library.value!!
        val file =
            runBlocking {
                vm.exportToCache(
                    dir,
                    TrackSharing.fileName("Korita / Zbevnica"),
                    TrackSelection(
                        folderIds =
                            lib.folders.map {
                                it.id
                            },
                    ),
                )
            }
        assertNotNull(file)
        assertEquals("Korita_Zbevnica.gpx", file.name)
        val again = GpxReader.read(file.readBytes())
        assertEquals(KORITA_TRACKS, again.tracks.size)
        assertEquals(2, again.waypoints.size)
        assertEquals(KORITA_POINTS, again.tracks.sumOf { it.pointCount })
    }

    @Test
    fun `the corridor path of a track is thinned but keeps both ends`() {
        vm.import(fixture("gpx/korita-zbevnica.gpx"), "korita.gpx")
        eventually("library") { vm.library.value?.tracks?.size == KORITA_TRACKS }
        val biggest = vm.library.value!!.tracks.maxBy { it.pointCount }
        val path = runBlocking { vm.corridorPath(biggest.id) }
        assertTrue(path.size in 2 until biggest.pointCount, "thinned: ${path.size} of ${biggest.pointCount}")
    }

    private fun lineTrack(
        name: String,
        count: Int,
        lon: Double = 6.0,
    ): Long =
        runBlocking {
            repository.createTrack(name, listOf(List(count) { TrackPoint(45.0 + it * 0.0001, lon, 1000.0 + it) }))
        }

    @Test
    fun `reversing from the detail screen flips the track and says so`() {
        val id = lineTrack("Aller", 5)
        vm.reverse(id)
        eventually("reversed") { runBlocking { repository.loadTrack(id)!!.segments[0].first().lat } > 45.0003 }
        eventually("message") { messages.any { it.resId == R.string.tracks_reversed } }
    }

    @Test
    fun `simplifying reports the points before and after, or that nothing can go`() {
        val id = lineTrack("Droite", 50)
        vm.simplify(id, 5.0)
        eventually("message") { messages.any { it.resId == R.string.draw_simplified } }
        val msg = messages.first { it.resId == R.string.draw_simplified }
        assertEquals(listOf(50, 2), msg.args)
        vm.simplify(id, 5.0)
        eventually("nothing") { messages.any { it.resId == R.string.draw_simplify_nothing } }
        vm.simplify(999, 5.0)
        eventually("missing") { messages.any { it.resId == R.string.draw_nothing_to_edit } }
    }

    @Test
    fun `merging builds a third track, and fewer than two tracks is refused`() {
        val a = lineTrack("A", 4)
        val b = lineTrack("B", 3, lon = 7.0)
        vm.merge(listOf(a, b), "A + B")
        eventually("merged") { runBlocking { repository.library.first().tracks.size } == 3 }
        eventually("message") { messages.any { it.resId == R.string.tracks_merged } }
        vm.merge(listOf(a), "seule")
        eventually("refused") { messages.any { it.resId == R.string.tracks_merge_failed } }
        assertEquals(3, runBlocking { repository.library.first().tracks.size })
    }

    @Test
    fun `waypoints are created and edited from the map`() {
        vm.saveWaypoint(null, Waypoint(45.5, 6.5, ele = 1500.0, name = "Col", sym = "Summit"))
        eventually("created") { runBlocking { repository.library.first().waypoints.size } == 1 }
        val id = runBlocking { repository.library.first().waypoints.single().id }
        assertEquals("Col", runBlocking { vm.waypoint(id)!!.name })
        vm.saveWaypoint(id, Waypoint(45.6, 6.6, name = "Refuge", sym = "Lodge"))
        eventually("updated") { runBlocking { vm.waypoint(id)!!.name } == "Refuge" }
        assertEquals(1, runBlocking { repository.library.first().waypoints.size })
        assertNull(runBlocking { vm.waypoint(9999) })
    }

    @Test
    fun `a description is stored and can be cleared`() {
        val id = lineTrack("Décrite", 3)
        vm.describe(ItemRef(ItemKind.TRACK, id), "Belle sortie")
        eventually("described") { runBlocking { repository.loadTrack(id)!!.desc } == "Belle sortie" }
        vm.describe(ItemRef(ItemKind.TRACK, id), "")
        eventually("cleared") { runBlocking { repository.loadTrack(id)!!.desc } == null }
    }

    @Test
    fun `file names are safe`() {
        assertEquals("trace.gpx", TrackSharing.fileName(null))
        assertEquals("trace.gpx", TrackSharing.fileName("///"))
        assertEquals("Col_du_Galibier.gpx", TrackSharing.fileName("Col du Galibier"))
        assertEquals("Été_2026.gpx", TrackSharing.fileName("Été 2026"))
    }

    private companion object {
        const val KORITA_TRACKS = 3
        const val KORITA_POINTS = 871
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 25L
    }
}
