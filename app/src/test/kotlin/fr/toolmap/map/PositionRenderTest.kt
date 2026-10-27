package fr.toolmap.map

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import fr.toolmap.core.record.RecorderProfile
import fr.toolmap.core.record.TrackActivity
import fr.toolmap.record.RecordStartDialog
import fr.toolmap.ui.Formats
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.ToolMapTheme
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
 * PNG renders of « Positionnement » (P29.5, « Sentier »): at rest, while walking and recording, and the start
 * dialog, by day and by night, for the comparison with the validated preview. Skipped unless the folder is given:
 * `./gradlew :app:testDebugUnitTest --tests '*PositionRenderTest*' -Ptoolmap.renders=<folder>`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h760dp-xxhdpi")
class PositionRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private val rest =
        PositionMenuState(
            realTime = false,
            follow = null,
            recording = false,
            barometerAvailable = true,
            barometer = true,
        )
    private val walking =
        PositionMenuState(
            realTime = true,
            follow = FollowChoice.FOLLOW,
            recording = true,
            recordedMeters = 4820.0,
            recordedMillis = 72 * 60_000L,
            sharing = true,
            groupMembers = 3,
            barometerAvailable = true,
            barometer = true,
            accuracyMeters = 4.0,
            altitudeMeters = 297.0,
            altitudeFromBarometer = true,
            barometerCalibrated = true,
        )

    @Test
    fun restDay() = renderPanel(rest, night = false, "rest-day")

    @Test
    fun restNight() = renderPanel(rest, night = true, "rest-night")

    @Test
    fun walkingDay() = renderPanel(walking, night = false, "walking-day")

    @Test
    fun walkingNight() = renderPanel(walking, night = true, "walking-night")

    // A text field in a dialog never lets Robolectric go idle on a screen wider than 320 dp (an artefact of the test
    // environment): the dialog is drawn on a 320 dp screen.
    @Test
    @Config(qualifiers = "w320dp-h700dp-xxhdpi")
    fun startDay() = renderStart(night = false, "start-day")

    @Test
    @Config(qualifiers = "w320dp-h700dp-xxhdpi")
    fun startNight() = renderStart(night = true, "start-night")

    private fun folder(): File {
        val folder = System.getProperty("toolmap.renders").orEmpty()
        assumeTrue("Renders are made with -Ptoolmap.renders=<folder>", folder.isNotEmpty())
        return File(folder).apply { mkdirs() }
    }

    private fun renderPanel(
        state: PositionMenuState,
        night: Boolean,
        name: String,
    ) {
        val out = folder()
        rule.setContent {
            Themed(night) {
                OnMap {
                    PositionMenuPanel(
                        state = state,
                        onFollow = {},
                        onRealTime = {},
                        onRecorder = {},
                        onShare = {},
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

    private fun renderStart(
        night: Boolean,
        name: String,
    ) {
        val out = folder()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Themed(night) {
                OnMap {}
                RecordStartDialog(
                    initialProfile = RecorderProfile.WALK,
                    onStart = { _, _, _ -> },
                    onDismiss = {},
                    askActivity = true,
                    recentActivities =
                        listOf(TrackActivity.HIKE, TrackActivity.WALK, TrackActivity.TRAIL_RUN, TrackActivity.BICYCLE),
                    initialActivity = TrackActivity.HIKE,
                )
            }
        }
        rule.mainClock.advanceTimeBy(SETTLE_MS)
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
private const val SETTLE_MS = 1_000L

@Composable
private fun Themed(
    night: Boolean,
    content: @Composable () -> Unit,
) {
    ToolMapTheme(darkTheme = night) {
        CompositionLocalProvider(LocalFormats provides Formats(), content = content)
    }
}

/** A plain map colour, [panel] at the bottom over the bar, the position button lit. */
@Composable
private fun OnMap(panel: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MAP)) {
        Column(Modifier.align(Alignment.BottomCenter)) {
            Box(Modifier.padding(bottom = 10.dp)) { panel() }
            MapBottomBar(
                locationMode = LocationMode.FOLLOW,
                tracksOpen = false,
                positionOpen = true,
                onMenu = {},
                onMaps = {},
                onTracks = {},
                onPosition = {},
                onPositionLongPress = {},
            )
        }
    }
}
