package fr.toolmap.maps

import android.app.Application
import fr.toolmap.core.download.ZoneRequest
import fr.toolmap.core.download.ZoneShape
import fr.toolmap.core.download.ZoomLimit
import fr.toolmap.core.layers.LayerStack
import fr.toolmap.core.layers.RangeLogic
import fr.toolmap.core.maps.AreaCorner
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomSet
import fr.toolmap.core.tiles.TilePlanner
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.data.download.ZoneDownloader
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.storage.StorageManager
import fr.toolmap.data.tiles.ForcedRefresh
import fr.toolmap.data.tiles.MbTilesStore
import fr.toolmap.data.tiles.OfflineMode
import fr.toolmap.data.tiles.StoredTile
import fr.toolmap.data.tiles.TileEntry
import fr.toolmap.download.ZoneEstimate
import fr.toolmap.download.ZoneEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MapsViewModelTest {
    private lateinit var root: File
    private lateinit var repository: SourceRepository
    private lateinit var store: MbTilesStore
    private lateinit var vm: MapsViewModel
    private lateinit var offline: OfflineMode
    private lateinit var settings: SettingsStore
    private val scheduler = TestCoroutineScheduler()
    private val queued = mutableListOf<ZoneRequest>()
    private val forcedRefresh = ForcedRefresh()

    private fun source(
        id: String,
        zooms: ZoomSet = ZoomSet.range(0, 12),
        server: String = "https://t/$id/{\$z}/{\$x}/{\$y}.png",
        isLayer: Boolean = false,
    ) = TileSource(id = id, name = id.uppercase(), isLayer = isLayer, levels = listOf(LevelDef(zooms, listOf(server))))

    private val osm = source("osm")
    private val topo = source("topo", ZoomSet.range(3, 8), isLayer = true)
    private val stored = source("stored", server = LevelDef.LOCAL_ONLY_SERVER)
    private val terrarium = source("terrarium", ZoomSet.range(0, 15)).copy(type = "dem")

    // Paris and its surroundings: a few tiles at the zooms used here.
    private val visible = ZoneShape.Rectangle(2.0, 48.5, 2.8, 49.0)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        root = createTempDirectory("maps-vm").toFile()
        build(listOf(osm, topo, stored))
    }

    /** A fresh view model whose repository ships the maps [builtins]. */
    private fun build(builtins: List<TileSource>) {
        if (::store.isInitialized) store.close()
        repository = SourceRepository(File(root, "sources"), builtins)
        store = MbTilesStore(File(root, "tiles"), repository)
        offline = OfflineMode()
        settings = SettingsStore(File(root, "settings.json"))
        val downloader = ZoneDownloader(OkHttpClient(), repository, store, offline)
        vm =
            MapsViewModel(
                context = RuntimeEnvironment.getApplication(),
                repository = repository,
                storage = StorageManager(store, repository),
                estimator = ZoneEstimator(downloader, repository, store),
                queue = { queued += it },
                offlineMode = offline,
                forcedRefresh = forcedRefresh,
                thumbnails = MapThumbnails(OkHttpClient(), repository),
                settingsStore = settings,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        store.close()
        root.deleteRecursively()
    }

    private fun stack(vararg ids: String) = ids.fold(LayerStack()) { s, id -> s.add(id) }

    /** Lets the debounce elapse and waits for the estimate computed on a background thread. */
    private fun awaitEstimate(): SaveAreaUi {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (System.currentTimeMillis() < deadline) {
            scheduler.advanceTimeBy(SETTLE_MS)
            scheduler.runCurrent()
            val ui = vm.area.value
            if (ui != null && !ui.estimating) return ui
            Thread.sleep(POLL_MS)
        }
        error("no estimate after $AWAIT_MS ms: ${vm.area.value}")
    }

    @Test
    fun `no panel is open at first`() {
        assertEquals(MapsPage.NONE, vm.page.value)
        assertNull(vm.area.value)
    }

    @Test
    fun `opening a page replaces the previous one`() {
        vm.open(MapsPage.ROOT)
        assertEquals(MapsPage.ROOT, vm.page.value)
        vm.open(MapsPage.CONDITIONS)
        assertEquals(MapsPage.CONDITIONS, vm.page.value)
        vm.close()
        assertEquals(MapsPage.NONE, vm.page.value)
    }

    @Test
    fun `closing the conditions comes back to the panel, the line of the layer still unfolded`() {
        vm.open(MapsPage.ROOT)
        vm.setExpanded("a")
        vm.openConditions("b")
        assertEquals(MapsPage.CONDITIONS, vm.page.value)
        assertEquals("b", vm.conditionsLayer.value)
        vm.closeConditions()
        assertEquals(MapsPage.ROOT, vm.page.value)
        assertEquals("b", vm.expandedLayer.value)
    }

    @Test
    fun `a map asked from the catalogue comes first and alone checked`() {
        vm.askSaveArea(only = "osm")
        vm.saveAreaStarted()
        vm.openSaveArea(visible, 8, stack("topo"))
        val rows = requireNotNull(vm.area.value).rows
        assertEquals(listOf("osm" to true, "topo" to false), rows.map { it.source.id to it.checked })
        // Asked once: the next selection offers the displayed maps again.
        vm.openSaveArea(visible, 8, stack("topo"))
        assertEquals(listOf("topo" to true), requireNotNull(vm.area.value).rows.map { it.source.id to it.checked })
    }

    @Test
    fun `a provided map hidden from the list leaves the catalogue until it is shown again`() {
        fun listed(): List<String> {
            scheduler.runCurrent()
            return vm.groups.value.flatMap { group -> group.sources.map { it.id } }
        }
        assertTrue("osm" in listed())
        vm.hideFromList("osm")
        assertTrue("osm" !in listed())
        assertEquals(setOf("osm"), vm.hidden.value)
        vm.setShowHidden(true)
        assertTrue("osm" in listed())
        vm.showInList("osm")
        scheduler.runCurrent()
        assertTrue(vm.hidden.value.isEmpty())
        // Nothing left hidden: « Afficher » turns itself off.
        assertFalse(vm.showHidden.value)
        assertEquals(emptySet(), settings.settings.value.maps.hiddenMaps)
    }

    @Test
    fun `the area offers the displayed maps that have a server, top first`() {
        vm.openSaveArea(visible, 8, stack("osm", "stored", "topo"))
        val ui = requireNotNull(vm.area.value)
        assertEquals(MapsPage.SAVE_AREA, vm.page.value)
        assertEquals(listOf("topo", "osm"), ui.rows.map { it.source.id })
        assertTrue(ui.rows.all { it.checked })
    }

    @Test
    fun `a hidden map starts unchecked`() {
        val hidden = stack("osm", "topo").let { it.setVisible(it.layers.last().id, false) }
        vm.openSaveArea(visible, 8, hidden)
        assertEquals(
            mapOf("topo" to false, "osm" to true),
            vm.area.value!!.rows.associate {
                it.source.id to it.checked
            },
        )
    }

    @Test
    fun `the maximum zoom starts a few levels below the screen and stays on the slider`() {
        vm.openSaveArea(visible, 8, stack("osm"))
        assertEquals(11, vm.area.value!!.maxZoom)
        assertEquals(12, vm.area.value!!.sliderMax)
        vm.openSaveArea(visible, 11, stack("osm"))
        assertEquals(12, vm.area.value!!.maxZoom)
    }

    @Test
    fun `the rectangle starts inside the upper part of the visible area`() {
        vm.openSaveArea(visible, 8, stack("osm"))
        val shape = vm.area.value!!.selection.toShape()
        assertTrue(shape.west > visible.west && shape.east < visible.east)
        assertTrue(shape.north < visible.north && shape.south > visible.north - (visible.north - visible.south) * 0.55)
    }

    @Test
    fun `the estimate counts the tiles of each checked map`() {
        vm.openSaveArea(visible, 8, stack("osm", "topo"))
        val ready = assertIs<ZoneEstimate.Ready>(awaitEstimate().estimate)
        assertEquals(setOf("osm", "topo"), ready.perSource.keys)
        assertTrue(ready.tiles > 0 && ready.bytes > 0)
        // The overlay only has zooms 3..8, so nothing above 8 is counted for it.
        assertTrue(ready.perSource.getValue("topo").perZoom.keys.all { it in 3..8 })
        assertEquals(0, ready.perSource.getValue("osm").perZoom.keys.min())
    }

    @Test
    fun `tiles already stored are counted apart and left out of what remains to download`() {
        vm.openSaveArea(visible, 8, stack("osm"))
        val before = assertIs<ZoneEstimate.Ready>(awaitEstimate().estimate)
        assertEquals(0L, before.stored)
        val area = vm.area.value!!.selection.toShape().toArea()
        val png = StoredTile(byteArrayOf(1, 2, 3), "image/png")
        store.putAll("osm", TilePlanner.tiles(area, 0..6).map { TileEntry(it.z, it.x, it.y, png) }.toList())
        val storedCount = TilePlanner.count(area, 0..6).values.sum()

        vm.setAreaMaxZoom(7)
        val partly = assertIs<ZoneEstimate.Ready>(awaitEstimate().estimate)
        assertEquals(storedCount, partly.stored)
        assertEquals(partly.tiles - storedCount, partly.missing)
        assertTrue(partly.missing > 0 && vm.area.value!!.canSave)

        vm.setAreaMaxZoom(6)
        val full = awaitEstimate()
        val ready = assertIs<ZoneEstimate.Ready>(full.estimate)
        assertEquals(ready.tiles, ready.stored)
        assertEquals(0L, ready.bytes)
        assertFalse(full.canSave)
    }

    @Test
    fun `lowering the zoom lowers the estimate`() {
        vm.openSaveArea(visible, 8, stack("osm"))
        val deep = assertIs<ZoneEstimate.Ready>(awaitEstimate().estimate).tiles
        vm.setAreaMaxZoom(6)
        assertTrue(vm.area.value!!.estimating)
        val shallow = assertIs<ZoneEstimate.Ready>(awaitEstimate().estimate).tiles
        assertTrue(shallow < deep, "$shallow < $deep")
    }

    @Test
    fun `dragging a corner moves it and recounts`() {
        vm.openSaveArea(visible, 8, stack("osm"))
        val before = requireNotNull(awaitEstimate())
        val shape = before.selection
        vm.dragCorner(AreaCorner.NORTH_EAST, shape.north + 0.4, shape.east + 0.6)
        val moved = requireNotNull(vm.area.value)
        assertEquals(shape.north + 0.4, moved.selection.north, 1e-9)
        assertEquals(shape.east + 0.6, moved.selection.east, 1e-9)
        assertEquals(shape.west, moved.selection.west, 0.0)
        assertTrue(moved.estimating)
        val after = assertIs<ZoneEstimate.Ready>(awaitEstimate().estimate)
        assertTrue(after.tiles > assertIs<ZoneEstimate.Ready>(before.estimate).tiles)
    }

    @Test
    fun `a drag that changes nothing does not restart the estimate`() {
        vm.openSaveArea(visible, 8, stack("osm"))
        val settled = awaitEstimate()
        val corner = settled.selection
        vm.dragCorner(AreaCorner.NORTH_WEST, corner.north, corner.west)
        vm.setAreaMaxZoom(settled.maxZoom)
        vm.setAreaChecked("osm", true)
        assertFalse(vm.area.value!!.estimating)
    }

    @Test
    fun `unchecking every map leaves nothing to save`() {
        vm.openSaveArea(visible, 8, stack("osm"))
        awaitEstimate()
        vm.setAreaChecked("osm", false)
        val ui = awaitEstimate()
        assertEquals(ZoneEstimate.Empty, ui.estimate)
        assertFalse(ui.canSave)
        assertEquals(SaveRefusal.NOTHING, vm.saveArea("zone"))
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `saving queues the checked maps from zoom 0 to the chosen maximum`() {
        vm.openSaveArea(visible, 8, stack("osm", "topo"))
        awaitEstimate()
        vm.setAreaChecked("topo", false)
        vm.setAreaMaxZoom(7)
        val ui = awaitEstimate()
        assertNull(vm.saveArea("Zone test").also { assertTrue(ui.canSave) })
        val request = queued.single()
        assertEquals("Zone test", request.name)
        assertEquals(listOf("osm"), request.sourceIds)
        assertEquals(0..7, request.zooms)
        assertEquals(ui.selection.toShape(), request.shape)
        assertNotNull(request.createdAtMillis.takeIf { it > 0 })
    }

    @Test
    fun `saving is refused in offline mode and while the figures are not ready`() {
        vm.openSaveArea(visible, 8, stack("osm"))
        assertEquals(SaveRefusal.NOTHING, vm.saveArea("early"))
        awaitEstimate()
        offline.set(true)
        assertEquals(SaveRefusal.OFFLINE, vm.saveArea("zone"))
        offline.set(false)
        assertNull(vm.saveArea("zone"))
        assertEquals(1, queued.size)
    }

    @Test
    fun `a band around a track counts and saves its tiles only, at the last width`() {
        // A line across Paris, 70 km long, from south-west to north-east.
        val line = listOf(TrackPoint(48.6, 1.9), TrackPoint(48.8, 2.3), TrackPoint(49.0, 2.7))
        settings.update { it.copy(maps = it.maps.copy(corridorRadius = 1_000)) }
        vm.openCorridor("Traversée", line, stack("osm"))
        val opened = vm.area.value!!
        assertEquals(MapsPage.SAVE_AREA, vm.page.value)
        val band = assertNotNull(opened.corridor)
        assertEquals(1_000, band.radiusMeters)
        assertEquals(minOf(16, opened.sliderMax), opened.maxZoom)
        vm.setAreaMaxZoom(12)
        val wide = assertIs<ZoneEstimate.Ready>(awaitEstimate().estimate)
        // Fewer tiles than the rectangle around the band.
        val box = TilePlanner.count(opened.selection.toShape().toArea(), 0..12).values.sum()
        assertTrue(wide.tiles in 1 until box, "${wide.tiles} tiles in the band, $box in its rectangle")

        vm.setCorridorRadius(100)
        val narrow = assertIs<ZoneEstimate.Ready>(awaitEstimate().estimate)
        assertTrue(narrow.tiles < wide.tiles)
        assertEquals(100, settings.settings.value.maps.corridorRadius, "kept for the next band")
        vm.setCorridorRadius(9_000)
        assertEquals(5_000, vm.area.value!!.corridor!!.radiusMeters)
        awaitEstimate()
        vm.setCorridorRadius(100)
        awaitEstimate()

        assertNull(vm.saveArea("Autour de « Traversée »"))
        val shape = assertIs<ZoneShape.Corridor>(queued.single().shape)
        assertEquals(100.0, shape.radiusMeters)
        assertEquals(line.map { ZoneShape.Point(it.lon, it.lat) }, shape.points)
    }

    @Test
    fun `leaving the area mode forgets the area`() {
        vm.openSaveArea(visible, 8, stack("osm"))
        vm.open(MapsPage.ROOT)
        assertNull(vm.area.value)
    }

    @Test
    fun `the slider reaches the deepest zoom of the offered maps`() {
        vm.openSaveArea(visible, 8, stack("topo"))
        assertEquals(8, vm.area.value!!.sliderMax)
        vm.setAreaMaxZoom(99)
        assertEquals(8, vm.area.value!!.maxZoom)
        vm.setAreaMaxZoom(-4)
        assertEquals(0, vm.area.value!!.maxZoom)
    }

    @Test
    fun `forcing an update is remembered for the session`() {
        assertTrue(vm.forced.value.isEmpty())
        vm.setForced("osm", true)
        assertEquals(setOf("osm"), vm.forced.value)
        vm.setForced("osm", false)
        assertTrue(vm.forced.value.isEmpty())
    }

    @Test
    fun `the catalogue groups the maps and drops empty groups`() {
        scheduler.runCurrent()
        val groups = vm.groups.value
        assertTrue(groups.isNotEmpty())
        assertTrue(groups.all { it.sources.isNotEmpty() })
        assertEquals(setOf("osm", "topo", "stored"), groups.flatMap { it.sources }.map { it.id }.toSet())
    }

    @Test
    fun `the catalogue is built on first read and is right at once`() {
        // P15.6: the flows are created when first asked for; their first value is the current catalogue, not an empty one.
        val groups = vm.groups.value
        assertEquals(setOf("osm", "topo", "stored"), groups.flatMap { it.sources }.map { it.id }.toSet())
        val offered = vm.onlineCatalog.flatMap { it.entries }.map { it.source.id }.toSet()
        assertTrue(offered.containsAll(vm.catalogAdded.value))
    }

    @Test
    fun `a map drawn in an elevation band brings an Altitudes row capped at zoom 13`() {
        build(listOf(osm, topo, stored, terrarium))
        vm.openSaveArea(visible, 8, stack("osm", "topo"))
        assertTrue(awaitEstimate().rows.none { it.isElevation }, "no band, no row")

        val banded = stack("osm", "topo").setElevationRange("l2", 1500, null, RangeLogic.AND)
        vm.openSaveArea(visible, 8, banded)
        val ui = awaitEstimate()
        val row = ui.rows.single { it.isElevation }
        assertEquals("terrarium", row.source.id)
        assertTrue(row.checked)
        assertEquals(listOf("TOPO"), row.forMaps)
        // From the lowest zoom of the banded map to the deepest one the masks read.
        assertEquals(3, row.minZoom)
        assertEquals(13, row.maxZoom)
        assertEquals(12, ui.sliderMax, "the elevations do not stretch the zoom slider")
        val ready = assertIs<ZoneEstimate.Ready>(ui.estimate)
        assertTrue(ready.perSource.getValue("terrarium").perZoom.keys.all { it in 3..ui.maxZoom })

        assertNull(vm.saveArea("Neige"))
        val request = queued.single()
        assertEquals(listOf("topo", "osm", "terrarium"), request.sourceIds)
        assertEquals(mapOf("terrarium" to ZoomLimit(3, 13)), request.zoomLimits)
    }

    @Test
    fun `the Altitudes row can be left out of the download`() {
        build(listOf(osm, topo, stored, terrarium))
        vm.openSaveArea(visible, 8, stack("osm").setElevationRange("l1", null, 1200, RangeLogic.OR))
        val row = awaitEstimate().rows.single { it.isElevation }
        assertEquals(0, row.minZoom)
        vm.setAreaChecked("terrarium", false)
        assertFalse(awaitEstimate().rows.single { it.isElevation }.checked)
        assertNull(vm.saveArea("Plaine"))
        assertEquals(listOf("osm"), queued.single().sourceIds)
    }

    @Test
    fun `the conditions panel opens on the chosen layer`() {
        vm.openConditions("l2")
        assertEquals(MapsPage.CONDITIONS, vm.page.value)
        assertEquals("l2", vm.conditionsLayer.value)
    }

    private companion object {
        const val AWAIT_MS = 20_000L
        const val SETTLE_MS = 300L
        const val POLL_MS = 20L
    }
}
