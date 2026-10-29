package fr.toolmap.maps

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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import fr.toolmap.map.LocationMode
import fr.toolmap.map.MapBottomBar
import fr.toolmap.ui.theme.ToolMapTheme
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
 * PNG renders of « Cartes et calques » (P29.3, « Strates »): the panel over the bar, a line unfolded, the compact
 * conditions, by day and by night, for the comparison with the validated preview where no emulator is at hand.
 * Skipped unless the folder is given: `./gradlew :app:testDebugUnitTest --tests '*MapsPanelRenderTest*'
 * -Ptoolmap.renders=<folder>`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h760dp-xxhdpi")
class MapsPanelRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private val fixture = MapsPanelFixture()

    @After
    fun tearDown() = fixture.close()

    @Test
    fun panelDay() = renderPanel(night = false, unfold = false, "panel-day")

    @Test
    fun panelNight() = renderPanel(night = true, unfold = false, "panel-night")

    @Test
    fun unfoldedDay() = renderPanel(night = false, unfold = true, "unfolded-day")

    /** P26.6: the colours open, « Teinte » at 120°. */
    @Test
    fun colorsDay() = renderPanel(night = false, unfold = true, "colors-day", colors = true)

    /** P26.6: « Teinte » held at 120°, the rest of the panel faded away. */
    @Test
    fun hueHeldDay() = renderPanel(night = false, unfold = true, "hue-held-day", colors = true, holdHue = true)

    @Test
    fun conditionsDay() = renderConditions(night = false, "conditions-day")

    @Test
    fun conditionsNight() = renderConditions(night = true, "conditions-night")

    private fun folder(): File {
        val folder = System.getProperty("toolmap.renders").orEmpty()
        assumeTrue("Renders are made with -Ptoolmap.renders=<folder>", folder.isNotEmpty())
        return File(folder).apply { mkdirs() }
    }

    private fun renderPanel(
        night: Boolean,
        unfold: Boolean,
        name: String,
        colors: Boolean = false,
        holdHue: Boolean = false,
    ) {
        val out = folder()
        rule.setContent {
            ToolMapTheme(darkTheme = night) {
                OnMap {
                    val state by fixture.vm.state.collectAsState()
                    MapsRootPanel(
                        state = state,
                        maps = fixture.maps,
                        vm = fixture.vm,
                        actions = MapsRootActions({}, {}, {}, {}),
                        currentZoom = 12.0,
                        maxHeight = 578.dp,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
            }
        }
        if (unfold) rule.onNodeWithText("IGN Cartes").performClick()
        if (colors) {
            rule.onNodeWithText("Couleurs").performClick()
            val id = fixture.vm.state.value.stack.layers.first { it.sourceId == "ign-maps" }.id
            rule.runOnIdle { fixture.vm.setHue(id, 120f) }
            rule.onNodeWithText("Teinte : 120°").performScrollTo()
        }
        if (holdHue) {
            // The finger rests where 120° is (300 of the 360 degrees from the start), as on the preview.
            val hue = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, HUE_PROGRESS)
            rule.onNode(hue).performTouchInput { down(percentOffset(HELD_AT, 0.5f)) }
        }
        rule.waitForIdle()
        save(out, name)
    }

    private fun renderConditions(
        night: Boolean,
        name: String,
    ) {
        val out = folder()
        val layer = fixture.vm.state.value.stack.topToBottom().first { it.sourceId == "osm" }
        rule.setContent {
            ToolMapTheme(darkTheme = night) {
                Box(Modifier.fillMaxSize().background(MAP)) {
                    Column(Modifier.align(Alignment.BottomCenter)) {
                        Bar()
                        DisplayConditionsPanel(
                            layer = layer,
                            name = "OpenStreetMap",
                            currentZoom = 12.0,
                            centerElevation = 290.0,
                            onZoomRange = { _, _ -> },
                            onElevationRange = { _, _, _ -> },
                            onClose = {},
                        )
                    }
                }
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
}

private val MAP = Color(0xFFE4EBCF)
private val HUE_PROGRESS = ProgressBarRangeInfo(120f, HUE_RANGE)
private const val HELD_AT = 300f / 360f

/** A plain map colour, the panel at the bottom over the bar, as on the map screen. */
@Composable
private fun OnMap(panel: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MAP)) {
        Column(Modifier.align(Alignment.BottomCenter)) {
            Box(Modifier.padding(bottom = 10.dp)) { panel() }
            Bar()
        }
    }
}

@Composable
private fun Bar() {
    MapBottomBar(
        locationMode = LocationMode.FREE,
        tracksOpen = false,
        positionOpen = false,
        onMenu = {},
        onMaps = {},
        onTracks = {},
        onPosition = {},
        onPositionLongPress = {},
        mapsOpen = true,
    )
}
