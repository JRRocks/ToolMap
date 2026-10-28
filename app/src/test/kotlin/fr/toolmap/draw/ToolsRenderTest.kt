package fr.toolmap.draw

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import fr.toolmap.data.elevation.ElevationCompleter
import fr.toolmap.data.elevation.ElevationTiles
import fr.toolmap.data.routing.BRouterEngine
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.data.tracks.TrackDatabase
import fr.toolmap.data.tracks.TrackRepository
import fr.toolmap.map.LocationMode
import fr.toolmap.map.MapBottomBar
import fr.toolmap.measure.MeasureBar
import fr.toolmap.measure.MeasureViewModel
import fr.toolmap.print.PrintFrameActions
import fr.toolmap.print.PrintFrameBar
import fr.toolmap.print.PrintUi
import fr.toolmap.ui.theme.ToolMapTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * PNG renders of the tools on the map (P29.10, « Équerre »): the route that follows the ways with its figures in the
 * pill, the print frame's pill and panel and the measure, by day and by night, for the comparison with the validated
 * preview where no emulator is at hand. Skipped unless the folder is given:
 * `./gradlew :app:testDebugUnitTest --tests '*ToolsRenderTest*' -Ptoolmap.renders=<folder>`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h760dp-xxhdpi")
class ToolsRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var db: TrackDatabase
    private lateinit var repo: TrackRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = TrackDatabase.inMemory(RuntimeEnvironment.getApplication())
        repo = TrackRepository(db)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun routeDay() = renderRoute(night = false, "route-day")

    @Test
    fun routeNight() = renderRoute(night = true, "route-night")

    @Test
    fun frameDay() = renderFrame(night = false, "frame-day")

    @Test
    fun frameNight() = renderFrame(night = true, "frame-night")

    @Test
    fun measureDay() = renderMeasure(night = false, "measure-day")

    private fun folder(): File {
        val folder = System.getProperty("toolmap.renders").orEmpty()
        assumeTrue("Renders are made with -Ptoolmap.renders=<folder>", folder.isNotEmpty())
        return File(folder).apply { mkdirs() }
    }

    private fun renderRoute(
        night: Boolean,
        name: String,
    ) {
        val out = folder()
        val app = RuntimeEnvironment.getApplication()
        val terrain = ElevationCompleter(ElevationTiles { _, _, _ -> null })
        val vm = DrawViewModel(repo, BRouterEngine(app), SettingsStore(File(app.filesDir, "settings.json")), terrain)
        vm.startNewRoute()
        // Five stops, by hand: BRouter is not installed under Robolectric.
        for (i in 0 until STOPS) {
            vm.addAt(45.44 + i * 0.004, 5.93 + i * 0.006, 1.0)
            // Each stop is added once the previous one is in the plan.
            val end = System.currentTimeMillis() + TIMEOUT_MS
            while ((vm.ui.value.route?.plan?.stops?.size ?: 0) <= i) {
                check(System.currentTimeMillis() < end) { "Timed out waiting for stop ${i + 1}" }
                Thread.sleep(POLL_MS)
            }
        }
        val hooks =
            RouteHooks(
                insertLegAt = { 1 },
                stopUnder = { null },
                reachMeters = { 10.0 },
                position = { null },
                onCenter = { _, _ -> },
                onInstall = {},
            )
        rule.setContent {
            val drawn by vm.ui.collectAsState()
            // As if BRouter followed the ways: the panel of the computed routes.
            val ui = drawn.copy(route = drawn.route?.copy(routing = true, installed = true))
            ToolMapTheme(darkTheme = night) {
                OnMap(top = { ui.route?.let { RouteStatsCard(route = it) } }) {
                    DrawBar(ui = ui, vm = vm, center = { 45.45 to 5.95 }, maxHeight = 500.dp, hooks = hooks)
                }
            }
        }
        rule.waitForIdle()
        save(out, name)
    }

    private fun renderFrame(
        night: Boolean,
        name: String,
    ) {
        val out = folder()
        rule.setContent {
            ToolMapTheme(darkTheme = night) {
                Box(Modifier.fillMaxSize().background(MAP)) {
                    PrintFrameBar(
                        ui = PrintUi(),
                        onUpdate = {},
                        onRotate = {},
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                    )
                    PrintFrameActions(
                        onCancel = {},
                        onNext = {},
                        maxHeight = 500.dp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp).padding(bottom = 6.dp),
                    )
                }
            }
        }
        rule.waitForIdle()
        save(out, name)
    }

    private fun renderMeasure(
        night: Boolean,
        name: String,
    ) {
        val out = folder()
        val vm = MeasureViewModel(repo)
        vm.open()
        vm.addAt(45.45, 5.95)
        vm.addAt(45.46, 5.96)
        vm.addAt(45.452, 5.975)
        rule.setContent {
            val ui by vm.ui.collectAsState()
            ToolMapTheme(darkTheme = night) {
                OnMap { MeasureBar(ui = ui, vm = vm, center = { 45.45 to 5.95 }, maxHeight = 500.dp) }
            }
        }
        rule.waitForIdle()
        save(out, name)
    }

    private fun save(
        out: File,
        name: String,
    ) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        FileOutputStream(File(out, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val STOPS = 5
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
    }
}

private val MAP = Color(0xFFE4EBCF)

/** A plain map colour, a pill at the top, the tool panel 10 dp above the bar, as on the map screen. */
@Composable
private fun OnMap(
    top: @Composable () -> Unit = {},
    panel: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(MAP)) {
        Box(Modifier.align(Alignment.TopCenter).padding(12.dp)) { top() }
        Column(Modifier.align(Alignment.BottomCenter)) {
            Box(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) { panel() }
            MapBottomBar(
                locationMode = LocationMode.FREE,
                tracksOpen = false,
                positionOpen = false,
                onMenu = {},
                onMaps = {},
                onTracks = {},
                onPosition = {},
                onPositionLongPress = {},
            )
        }
    }
}
