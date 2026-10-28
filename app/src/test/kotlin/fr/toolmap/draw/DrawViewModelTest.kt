package fr.toolmap.draw

import android.app.Application
import fr.toolmap.R
import fr.toolmap.core.track.PathStyle
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.core.track.Waypoint
import fr.toolmap.data.elevation.ElevationCompleter
import fr.toolmap.data.elevation.ElevationTiles
import fr.toolmap.data.routing.BRouterEngine
import fr.toolmap.data.settings.SettingsStore
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
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DrawViewModelTest {
    private lateinit var db: TrackDatabase
    private lateinit var repo: TrackRepository
    private lateinit var vm: DrawViewModel
    private lateinit var settings: SettingsStore

    // Filled from the thread the view model emits on while the test reads it: a list safe to read meanwhile.
    private val messages = CopyOnWriteArrayList<UiMessage>()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    /** The terrain tile served for every position; `null`: offline, no tile. */
    @Volatile private var tilePng: ByteArray? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = TrackDatabase.inMemory(RuntimeEnvironment.getApplication())
        repo = TrackRepository(db)
        val app = RuntimeEnvironment.getApplication()
        // BRouter is not installed under Robolectric: routes are drawn by hand, as before P19.
        val terrain = ElevationCompleter(ElevationTiles { _, _, _ -> tilePng })
        settings = SettingsStore(File(app.filesDir, "settings.json"))
        vm = DrawViewModel(repo, BRouterEngine(app), settings, terrain)
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

    private fun walk(count: Int) = List(count) { TrackPoint(45.0 + it * 0.001, 6.0) }

    /** Reach of a tap, in meters: far smaller than the 111 m between the test points. */
    private val reach = 20.0

    @Test
    fun `taps on the bare map add points and a tap on a vertex selects it`() {
        vm.startNewTrack()
        assertTrue(vm.ui.value.active)
        vm.onMapTap(45.0, 6.0, reach)
        vm.onMapTap(45.001, 6.0, reach)
        vm.onMapTap(45.002, 6.0, reach)
        assertEquals(3, vm.ui.value.pointCount)
        assertEquals(2, vm.ui.value.selected, "an added point is selected")
        assertTrue(vm.ui.value.lengthMeters in 220.0..224.0)

        vm.onMapTap(45.001, 6.00001, reach)
        assertEquals(1, vm.ui.value.selected)
        vm.onMapTap(45.001, 6.00001, reach)
        assertNull(vm.ui.value.selected, "a second tap deselects")
        assertNotNull(vm.overlay.value)
        assertTrue(vm.overlay.value!!.lineGeoJson.contains("LineString"))
    }

    @Test
    fun `undo and redo follow every change and the handles move with the crosshair`() {
        vm.startNewTrack()
        vm.addAt(45.0, 6.0)
        vm.addAt(45.001, 6.0)
        vm.addAt(45.002, 6.0)
        assertTrue(vm.ui.value.canUndo)
        vm.moveSelectedTo(45.0025, 6.001)
        assertEquals(3, vm.ui.value.pointCount)
        vm.deleteSelected()
        assertEquals(2, vm.ui.value.pointCount)
        vm.undo()
        assertEquals(3, vm.ui.value.pointCount)
        assertTrue(vm.ui.value.canRedo)
        vm.undo()
        vm.redo()
        vm.redo()
        assertEquals(2, vm.ui.value.pointCount, "the deletion is replayed")
        assertFalse(vm.ui.value.canRedo)
        assertTrue(vm.ui.value.dirty)
    }

    @Test
    fun `a new track is saved with its name at the library root and the editor closes`() {
        vm.startNewTrack()
        vm.addAt(45.0, 6.0)
        vm.addAt(45.001, 6.0)
        vm.save("Boucle du lac")
        waitFor("track stored") { repo.library.first().tracks.size == 1 }
        val track = runBlocking { repo.library.first().tracks.single() }
        assertEquals("Boucle du lac", track.name)
        assertNull(track.folderId)
        assertEquals(2, track.pointCount)
        assertFalse(vm.ui.value.active)
        assertNull(vm.overlay.value)
        assertTrue(messages.any { it.resId == R.string.draw_saved })
    }

    @Test
    fun `saving a single point is refused and keeps the editor open`() {
        vm.startNewRoute()
        vm.addAt(45.0, 6.0)
        assertFalse(vm.canSave())
        vm.save("trop court")
        assertTrue(messages.any { it.resId == R.string.draw_too_short })
        assertTrue(vm.ui.value.active)
        assertTrue(runBlocking { repo.library.first().routes.isEmpty() })
    }

    @Test
    fun `a new route stores its points as waypoints`() {
        vm.startNewRoute()
        assertTrue(vm.ui.value.isRoute)
        vm.addAt(45.0, 6.0)
        vm.addAt(45.01, 6.0)
        vm.save("Itinéraire")
        waitFor("route stored") { repo.library.first().routes.size == 1 }
        val route = runBlocking { repo.library.first().routes.single() }
        assertEquals(2, route.pointCount)
        assertEquals(1112.0, route.distanceMeters, 5.0)
    }

    @Test
    fun `editing a track with one segment saves over it and keeps the name and style`() {
        val id = runBlocking { repo.createTrack("Aller", listOf(walk(4)), PathStyle(colorArgb = 0xFF336699.toInt())) }
        vm.startEditTrack(id)
        waitFor("editor open") { vm.ui.value.active }
        assertEquals(4, vm.ui.value.pointCount)
        vm.onMapTap(45.001, 6.0, reach)
        vm.deleteSelected()
        vm.save(null)
        waitFor("saved") { runBlocking { repo.loadTrack(id)!!.segments.sumOf { it.size } } == 3 }
        val track = runBlocking { repo.loadTrack(id)!! }
        assertEquals("Aller", track.name)
        assertEquals(0xFF336699.toInt(), track.style!!.colorArgb)
    }

    @Test
    fun `a track with several segments asks which one to edit and leaves the others alone`() {
        val id = runBlocking { repo.createTrack("Deux", listOf(walk(3), walk(5).map { it.copy(lon = 7.0) })) }
        vm.startEditTrack(id)
        waitFor("chooser") { vm.ui.value.chooseSegment != null }
        assertEquals(listOf(3, 5), vm.ui.value.chooseSegment)
        // Taps are ignored until a segment is chosen.
        vm.onMapTap(45.0, 6.0, reach)
        assertEquals(0, vm.ui.value.pointCount)
        vm.chooseSegment(1)
        assertEquals(5, vm.ui.value.pointCount)
        vm.reverse()
        vm.save(null)
        waitFor("saved") { !vm.ui.value.active }
        val segments = runBlocking { repo.loadTrack(id)!!.segments }
        assertEquals(listOf(3, 5), segments.map { it.size })
        assertEquals(45.0, segments[0].first().lat, 1e-6)
        assertEquals(45.004, segments[1].first().lat, 1e-6)
    }

    @Test
    fun `a route edit keeps the names of the points that did not move`() {
        val id =
            runBlocking {
                repo.createRoute("R", listOf(Waypoint(45.0, 6.0, name = "Départ"), Waypoint(45.01, 6.0, name = "Col")))
            }
        vm.startEditRoute(id)
        waitFor("editor open") { vm.ui.value.active }
        vm.addAt(45.02, 6.0)
        vm.save(null)
        waitFor("saved") { !vm.ui.value.active }
        val route = runBlocking { repo.loadRoute(id)!! }
        assertEquals(listOf("Départ", "Col", null), route.points.map { it.name })
    }

    @Test
    fun `splitting a track makes two tracks and closes the editor`() {
        val id = runBlocking { repo.createTrack("Grande", listOf(walk(6))) }
        vm.startEditTrack(id)
        waitFor("editor open") { vm.ui.value.active }
        // The split is refused without a selected vertex.
        vm.splitAtSelected()
        assertTrue(messages.any { it.resId == R.string.draw_split_invalid })
        vm.onMapTap(45.003, 6.0, reach)
        assertEquals(3, vm.ui.value.selected)
        vm.splitAtSelected()
        waitFor("two tracks") { repo.library.first().tracks.size == 2 }
        val tracks = runBlocking { repo.library.first().tracks }
        assertEquals(listOf("Grande", "Grande (2)"), tracks.map { it.name.orEmpty() }.sorted())
        // The cut vertex belongs to both halves: 4 + 3 points.
        assertEquals(listOf(4, 3), listOf("Grande", "Grande (2)").map { n -> tracks.first { it.name == n }.pointCount })
        assertFalse(vm.ui.value.active)
    }

    @Test
    fun `quitting drops the work without touching the library`() {
        vm.startNewTrack()
        vm.addAt(45.0, 6.0)
        vm.addAt(45.001, 6.0)
        vm.cancel()
        assertFalse(vm.ui.value.active)
        assertTrue(runBlocking { repo.library.first().isEmpty })
    }

    @Test
    fun `a zone pulled at its handle keeps its centre, takes the size for the next zone and is undone at once`() {
        vm.startNewRoute()
        vm.addAt(45.0, 6.0)
        vm.addAt(45.01, 6.0)
        waitFor("two stops") { vm.ui.value.route?.plan?.stops?.size == 2 }
        vm.startZone()
        vm.followZone(45.005, 6.0)
        vm.placeZone(45.005, 6.0)
        val placed = vm.ui.value.route!!.plan.zones.single()
        assertEquals(250, placed.radiusMeters)

        vm.hoverZone(0)
        assertTrue("\"hover\"" in vm.overlay.value!!.zonesGeoJson!!, "the zone under the crosshair has a solid edge")
        vm.previewZoneSize(0, 400)
        assertTrue("\"st\":\"g\"" in vm.overlay.value!!.lineGeoJson, "the route pales while the handle is pulled")
        assertEquals(250, vm.ui.value.route!!.plan.zones.single().radiusMeters, "nothing changes before it is let go")

        vm.resizeZone(0, 400)
        val resized = vm.ui.value.route!!.plan.zones.single()
        assertEquals(400, resized.radiusMeters)
        assertEquals(placed.lat, resized.lat)
        assertEquals(placed.lon, resized.lon)
        assertEquals(400, settings.settings.value.placemarks.routingAvoidRadius)
        assertFalse("\"st\":\"g\"" in vm.overlay.value!!.lineGeoJson)

        vm.undo()
        assertEquals(250, vm.ui.value.route!!.plan.zones.single().radiusMeters)
        vm.hoverZone(null)
        assertFalse("\"hover\"" in vm.overlay.value!!.zonesGeoJson!!)
    }

    @Test
    fun `a route drawn by hand stores its stops with the terrain elevation`() {
        tilePng = flatTile(812.0)
        vm.startNewRoute()
        vm.addAt(45.0, 6.0)
        vm.addAt(45.01, 6.0)
        waitFor("elevations") { vm.ui.value.route?.summary?.hasElevation == true }
        vm.save("Itinéraire")
        waitFor("route stored") { repo.library.first().routes.size == 1 }
        val id = runBlocking { repo.library.first().routes.single().id }
        val points = runBlocking { repo.loadRoute(id)!!.points }
        assertEquals(2, points.size, "a route wholly by hand keeps its stops only")
        points.forEach { assertEquals(812.0, it.ele!!, 1.0) }
    }

    @Test
    fun `a stored route takes the terrain elevations without counting as changed`() {
        tilePng = flatTile(1500.0)
        val id = runBlocking { repo.createRoute("R", listOf(Waypoint(45.0, 6.0), Waypoint(45.01, 6.0))) }
        vm.startEditRoute(id)
        waitFor("elevations") { vm.ui.value.route?.plan?.geometry?.all { it.ele != null } == true }
        assertFalse(vm.ui.value.dirty)
        assertFalse(vm.ui.value.canUndo)
    }

    @Test
    fun `offline, a route drawn by hand has no elevation`() {
        vm.startNewRoute()
        vm.addAt(45.0, 6.0)
        vm.addAt(45.01, 6.0)
        vm.save("Itinéraire")
        waitFor("route stored") { repo.library.first().routes.size == 1 }
        val id = runBlocking { repo.library.first().routes.single().id }
        assertTrue(runBlocking { repo.loadRoute(id)!!.points.all { it.ele == null } })
    }

    /** A 256 px Terrarium tile at [meters] everywhere. */
    private fun flatTile(meters: Double): ByteArray {
        val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
        val v = Math.round((meters + 32768.0) * 256).toInt()
        val rgb = ((v shr 16) and 0xFF shl 16) or ((v shr 8) and 0xFF shl 8) or (v and 0xFF)
        for (y in 0 until 256) for (x in 0 until 256) image.setRGB(x, y, rgb)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
    }
}
