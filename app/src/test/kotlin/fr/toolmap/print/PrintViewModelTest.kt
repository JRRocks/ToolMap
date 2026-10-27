package fr.toolmap.print

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import fr.toolmap.core.geo.CoordinateSystem
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.print.AtlasMode
import fr.toolmap.core.print.GridKind
import fr.toolmap.core.print.PrintIssue
import fr.toolmap.data.download.ZoneDownloader
import fr.toolmap.data.print.PrintTemplateStore
import fr.toolmap.data.print.PrintTileFetcher
import fr.toolmap.data.print.TileFetch
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.tiles.MbTilesStore
import fr.toolmap.data.tiles.OfflineMode
import fr.toolmap.data.tracks.TrackDatabase
import fr.toolmap.data.tracks.TrackDisplay
import fr.toolmap.data.tracks.TrackRepository
import fr.toolmap.testing.stopAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class PrintViewModelTest {
    private lateinit var dir: File
    private lateinit var db: TrackDatabase
    private lateinit var tiles: MbTilesStore
    private lateinit var vm: PrintViewModel
    private lateinit var scheduler: TestCoroutineScheduler
    private val stack = listOf(LayerConfig("l1", "ign-maps"))

    /** Set by a test to hold the export inside its first tile read. */
    @Volatile
    private var gate: CountDownLatch? = null

    @Volatile
    private var unavailable: (Int) -> Boolean = { false }

    private val tilePng: ByteArray by lazy {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(200, 220, 200))
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Before
    fun setUp() {
        val dispatcher = UnconfinedTestDispatcher()
        scheduler = dispatcher.scheduler
        Dispatchers.setMain(dispatcher)
        val app = RuntimeEnvironment.getApplication()
        dir = createTempDirectory("print-vm").toFile()
        db = TrackDatabase.inMemory(app)
        val sources = SourceRepository(File(dir, "sources"))
        tiles = MbTilesStore(File(dir, "tiles"), sources)
        val downloader = ZoneDownloader(OkHttpClient(), sources, tiles, OfflineMode())
        val fetcher =
            PrintTileFetcher { _, tile ->
                gate?.await(GATE_SECONDS, TimeUnit.SECONDS)
                if (unavailable(tile.x)) TileFetch.Unavailable else TileFetch.Found(tilePng)
            }
        vm =
            PrintViewModel(
                app,
                PrintTemplateStore(File(dir, "print")),
                downloader,
                TrackDisplay(TrackRepository(db)),
                sources,
                fetcher,
            )
    }

    @After
    fun tearDown() {
        // Background work of the view model must not outlive the test: it would still be using the main dispatcher
        // while the next test class installs its own. Cancelling is not enough: wait until it has finished.
        vm.stopAndJoin()
        Dispatchers.resetMain()
        tiles.close()
        db.close()
        dir.deleteRecursively()
    }

    private fun waitFor(
        what: String,
        check: () -> Boolean,
    ) = runBlocking {
        try {
            withTimeout(TIMEOUT_MS) {
                while (true) {
                    // The plan is debounced on the (virtual-time) main dispatcher, then computed on real threads.
                    scheduler.advanceTimeBy(DEBOUNCE_STEP_MS)
                    scheduler.runCurrent()
                    if (check()) break
                    delay(POLL_MS)
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    private fun settled(scale: Double? = null) =
        waitFor("a settled plan") {
            val ui = vm.ui.value
            ui.plan != null && !ui.planning && ui.missingTiles != null &&
                (scale == null || ui.plan?.template?.scale == scale)
        }

    private fun open() {
        vm.open(45.0, 6.0, stack)
        vm.chooseZone()
        settled()
    }

    @Test
    fun `the two questions come first, then the frame centred on the map with the tiles to download`() {
        vm.open(45.0, 6.0, stack)
        assertEquals(PrintStage.SETUP, vm.ui.value.stage)
        val fits = vm.ui.value.fitRequest
        vm.chooseZone()
        assertEquals(PrintStage.FRAME, vm.ui.value.stage)
        assertEquals(fits + 1, vm.ui.value.fitRequest)
        settled()
        val ui = vm.ui.value
        val plan = assertNotNull(ui.plan)
        assertTrue(plan.canExport, plan.issues.toString())
        assertEquals(1, plan.mapPages.size)
        assertEquals(45.0, plan.mapPages.single().frame.centerLat, 1e-9)
        // Nothing stored yet: every tile of the page is still to download.
        assertEquals(plan.totalTiles.toInt(), ui.missingTiles)
    }

    @Test
    fun `a new scale or paper keeps the centre, fits the zoom again, and the print stays one page`() {
        open()
        val fits = vm.ui.value.fitRequest
        vm.update { it.copy(scale = 50_000.0) }
        settled(50_000.0)
        assertEquals(fits + 1, vm.ui.value.fitRequest)
        assertEquals(50_000.0, vm.ui.value.plan?.mapPages?.single()?.frame?.scale)
        assertEquals(45.0, vm.ui.value.plan!!.mapPages.single().frame.centerLat, 1e-9)

        vm.rotate()
        assertEquals(fits + 2, vm.ui.value.fitRequest)
        // The title changes nothing on the map: no new fit.
        vm.update { it.copy(title = "Belledonne") }
        assertEquals(fits + 2, vm.ui.value.fitRequest)

        vm.update { it.copy(atlas = AtlasMode.GRID) }
        settled()
        assertEquals(AtlasMode.SINGLE, vm.ui.value.template.atlas)
        assertEquals(1, vm.ui.value.plan!!.mapPages.size)
    }

    @Test
    fun `dragging the frame gives a new plan centred on it`() {
        open()
        vm.moveFrame(44.5, 6.5)
        waitFor("the moved plan") { vm.ui.value.plan?.mapPages?.firstOrNull()?.frame?.centerLat == 44.5 }
        assertEquals(6.5, vm.ui.value.centerLon)
    }

    @Test
    fun `Suivant shows the confirmation and Retour brings the frame back`() {
        open()
        vm.confirm()
        assertEquals(PrintStage.CONFIRM, vm.ui.value.stage)
        vm.toggleMoreOptions()
        assertTrue(vm.ui.value.moreOptions)
        vm.backToFrame()
        assertEquals(PrintStage.FRAME, vm.ui.value.stage)
        vm.close()
        assertNull(vm.ui.value.stage)
        assertTrue(!vm.ui.value.active)
    }

    @Test
    fun `the first print takes the grid of the coordinate format, the next ones the last settings`() {
        vm.open(45.0, 6.0, stack, CoordinateSystem.MGRS)
        assertEquals(GridKind.MGRS, vm.ui.value.template.grid)
        vm.update { it.copy(scale = 100_000.0, grid = GridKind.LAMBERT93, title = "Sortie") }
        vm.close()
        vm.open(45.0, 6.0, stack, CoordinateSystem.DD)
        assertEquals(100_000.0, vm.ui.value.template.scale)
        assertEquals(GridKind.LAMBERT93, vm.ui.value.template.grid)
        // The title is made again for each print: the map and the day.
        assertTrue(vm.ui.value.template.title != "Sortie")
        assertEquals(GridKind.WGS84_DECIMAL, PrintViewModel.gridFor(CoordinateSystem.DD))
        assertEquals(GridKind.WGS84, PrintViewModel.gridFor(CoordinateSystem.DMS))
        assertEquals(GridKind.UTM, PrintViewModel.gridFor(CoordinateSystem.UTM))
    }

    @Test
    fun `the PDF is exported to the chosen file with the missing tiles counted`() {
        open()
        unavailable = { it % 5 == 0 }
        val file = File(dir, "out.pdf")
        vm.export(Uri.fromFile(file))
        waitFor("the export outcome") { vm.ui.value.outcome != null }
        val outcome = assertIs<PrintOutcome.Exported>(vm.ui.value.outcome)
        assertEquals(1, outcome.pages)
        assertEquals(file.length(), outcome.bytes)
        assertTrue(outcome.missingTiles > 0)
        assertTrue(file.readBytes().take(8).toByteArray().decodeToString().startsWith("%PDF-1.4"))
        assertNull(vm.ui.value.job)
        vm.dismissOutcome()
        assertNull(vm.ui.value.outcome)
    }

    @Test
    fun `an export that cannot start leaves no outcome stuck and no file`() {
        open()
        vm.update { it.copy(scale = 1.0) }
        waitFor("the refused plan") { PrintIssue.SCALE_RANGE in (vm.ui.value.plan?.issues ?: emptyList()) }
        val file = File(dir, "never.pdf")
        vm.export(Uri.fromFile(file))
        assertNull(vm.ui.value.job)
        assertNull(vm.ui.value.outcome)
        assertTrue(!file.exists())
    }

    @Test
    fun `cancelling an export in progress removes the half written file`() {
        open()
        val latch = CountDownLatch(1)
        gate = latch
        val file = File(dir, "cancelled.pdf")
        vm.export(Uri.fromFile(file))
        waitFor("the export to start") { vm.ui.value.job is PrintJob.Exporting && file.exists() }
        vm.cancelJob()
        latch.countDown()
        waitFor("the job to end") { vm.ui.value.job == null }
        // The cancellation is noticed between two strips: the file must be gone and no outcome shown.
        waitFor("the file to be removed") { !file.exists() }
        assertNull(vm.ui.value.outcome)
    }

    @Test
    fun `the declination at Grenoble is a few degrees east`() {
        val d = PrintDeclination.at(45.19, 5.72)
        assertTrue(d in 0.5..5.0, "declination $d")
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 20L
        const val DEBOUNCE_STEP_MS = 300L
        const val GATE_SECONDS = 20L
    }
}
