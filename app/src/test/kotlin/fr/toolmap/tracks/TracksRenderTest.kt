package fr.toolmap.tracks

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import fr.toolmap.alert.AlertUi
import fr.toolmap.core.track.Waypoint
import fr.toolmap.data.tracks.ItemKind
import fr.toolmap.data.tracks.ItemRef
import fr.toolmap.map.LocationMode
import fr.toolmap.map.MapBottomBar
import fr.toolmap.map.TracksMenuPanel
import fr.toolmap.map.TracksMenuState
import fr.toolmap.ui.Formats
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.ToolMapTheme
import fr.toolmap.waypoints.WaypointDialog
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * PNG renders of « Traces et waypoints » (P29.4): the panel, the list and the detail in « Topo », the sheet and the
 * message of « Waypoint ici » in « Repère », and the waypoint dialog, by day and by night, for the comparison with the
 * validated preview where no emulator is at hand. Skipped unless the folder is given:
 * `./gradlew :app:testDebugUnitTest --tests '*TracksRenderTest*' -Ptoolmap.renders=<folder>`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h760dp-xxhdpi")
class TracksRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private val fixture = TracksFixture()

    @After
    fun tearDown() = fixture.close()

    @Test
    fun panelDay() = renderPanel(night = false, "panel-day")

    @Test
    fun panelNight() = renderPanel(night = true, "panel-night")

    @Test
    fun listDay() = renderList(night = false, "list-day")

    @Test
    fun listNight() = renderList(night = true, "list-night")

    @Test
    fun emptyDay() = renderList(night = false, "empty-day", empty = true)

    @Test
    fun detailDay() = renderDetail(night = false, "detail-day")

    @Test
    fun detailNight() = renderDetail(night = true, "detail-night")

    @Test
    fun sheetDay() = renderSheet(night = false, "sheet-day")

    @Test
    fun sheetNight() = renderSheet(night = true, "sheet-night")

    @Test
    fun trackSheetDay() = renderSheet(night = false, "sheet-track-day", track = true)

    @Test
    fun quickWaypointDay() = renderQuick(night = false, "quick-day")

    // A text field in a dialog never lets Robolectric go idle on a screen wider than 320 dp (an artefact of the test
    // environment: the app shows it on any width); the dialog is drawn on a 320 dp screen.
    @Test
    @Config(qualifiers = "w320dp-h700dp-xxhdpi")
    fun dialogDay() = renderDialog(night = false, "dialog-day")

    @Test
    @Config(qualifiers = "w320dp-h700dp-xxhdpi")
    fun dialogNight() = renderDialog(night = true, "dialog-night")

    private fun folder(): File {
        val folder = System.getProperty("toolmap.renders").orEmpty()
        assumeTrue("Renders are made with -Ptoolmap.renders=<folder>", folder.isNotEmpty())
        return File(folder).apply { mkdirs() }
    }

    private fun renderPanel(
        night: Boolean,
        name: String,
    ) {
        val out = folder()
        rule.setContent {
            Themed(night) {
                OnMap(tracksOpen = true) {
                    TracksMenuPanel(
                        state = TracksMenuState(true, true, tracks = 3, routes = 1, waypoints = 5, allVisible = true),
                        onAction = {},
                        onExplorer = {},
                        onHelp = {},
                        maxHeight = 578.dp,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
            }
        }
        rule.waitForIdle()
        save(out, name)
    }

    private fun renderList(
        night: Boolean,
        name: String,
        empty: Boolean = false,
    ) {
        val out = folder()
        fixture.fill(empty)
        fixture.await(rule::waitForIdle) {
            fixture.vm.library.value.let { if (empty) it?.isEmpty == true else fixture.isFilled(it) }
        }
        // The folder open, as in the preview.
        fixture.vm.library.value?.folders?.firstOrNull()?.let { fixture.vm.expandFolder(it.id) }
        rule.setContent {
            Themed(night) {
                TracksScreen(vm = fixture.vm, onBack = {}, onImport = {}, onOpenDetail = {}, onShowOnMap = {})
            }
        }
        rule.waitForIdle()
        save(out, name)
    }

    private fun renderDetail(
        night: Boolean,
        name: String,
    ) {
        val out = folder()
        fixture.fill()
        fixture.await(rule::waitForIdle) { fixture.vm.library.value?.isEmpty == false }
        rule.setContent {
            Themed(night) {
                TrackDetailScreen(
                    vm = fixture.vm,
                    trackId = fixture.fbt,
                    onBack = {},
                    onShowOnMap = {},
                    onDownloadAround = { _, _ -> },
                    onPrintAlong = {},
                    onEdit = {},
                    onFollow = {},
                )
            }
        }
        fixture.await(rule::waitForIdle) { fixture.vm.detail.value?.trackId == fixture.fbt }
        // The cursor at 3,20 km, as in the preview.
        fixture.vm.setCursor(3_200.0)
        rule.waitForIdle()
        save(out, name)
    }

    private fun renderSheet(
        night: Boolean,
        name: String,
        track: Boolean = false,
    ) {
        val out = folder()
        fixture.fill()
        fixture.await(rule::waitForIdle) { fixture.isFilled(fixture.vm.library.value) }
        val library = fixture.vm.library.value!!
        val ref = if (track) ItemRef(ItemKind.TRACK, fixture.fbt) else ItemRef(ItemKind.WAYPOINT, fixture.tetes)
        rule.setContent {
            Themed(night) {
                OnMap(tracksOpen = false) {}
                TappedItemSheet(
                    ref = ref,
                    library = library,
                    // 3,90 km north-west of « Les Trois Têtes »: the reading of the preview.
                    from = 45.5191 to 5.9620,
                    onDetails = {},
                    onHide = {},
                    onEdit = {},
                    onGoTo = {},
                    onShare = { _, _ -> },
                    onExport = { _, _ -> },
                    onDelete = {},
                    alerts = AlertUi(),
                    onFollow = {},
                    onStopFollowing = {},
                    onWatch = {},
                    onDismiss = {},
                    profileOf = { fixture.vm.profileOf(it) },
                )
            }
        }
        rule.waitForIdle()
        save(out, name)
    }

    private fun renderQuick(
        night: Boolean,
        name: String,
    ) {
        val out = folder()
        rule.setContent {
            Themed(night) {
                OnMap(tracksOpen = false) {
                    // As the snackbar of the map screen draws its action (SnackbarHost's default).
                    Snackbar(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        action = {
                            TextButton(
                                onClick = {},
                                colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
                            ) { Text("Modifier") }
                        },
                    ) { Text("« Waypoint 1 » enregistré · 442 m") }
                }
            }
        }
        rule.waitForIdle()
        save(out, name)
    }

    private fun renderDialog(
        night: Boolean,
        name: String,
    ) {
        val out = folder()
        // The caret of the name blinks for ever: the clock is moved by hand, or the rule would never be idle.
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Themed(night) {
                OnMap(tracksOpen = false) {}
                WaypointDialog(
                    initial = null,
                    start = 45.4553 to 5.9790,
                    defaultName = "Waypoint 1",
                    center = { 45.4553 to 5.9790 },
                    terrainElevation = { _, _ -> 442.0 },
                    onSave = { _: Waypoint -> },
                    onSaveAndGo = {},
                    onDismiss = {},
                )
            }
        }
        rule.mainClock.advanceTimeBy(DIALOG_SETTLE_MS)
        save(out, name)
    }

    private fun save(
        out: File,
        name: String,
    ) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        FileOutputStream(File(out, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

private val MAP = Color(0xFFE4EBCF)
private const val DIALOG_SETTLE_MS = 1_000L

@Composable
private fun Themed(
    night: Boolean,
    content: @Composable () -> Unit,
) {
    ToolMapTheme(darkTheme = night) {
        CompositionLocalProvider(LocalFormats provides Formats(), content = content)
    }
}

/** A plain map colour, [panel] at the bottom over the bar, as on the map screen. */
@Composable
private fun OnMap(
    tracksOpen: Boolean,
    panel: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(MAP)) {
        Column(Modifier.align(Alignment.BottomCenter)) {
            Box(Modifier.padding(bottom = 10.dp)) { panel() }
            MapBottomBar(
                locationMode = LocationMode.FREE,
                tracksOpen = tracksOpen,
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
