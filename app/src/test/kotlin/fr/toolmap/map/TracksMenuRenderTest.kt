package fr.toolmap.map

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
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
 * Makes a PNG render of the tracks and waypoints panel above the bar, the flag highlighted, for the visual check against
 * the placemark panel of AlpineQuest (P14.3). Skipped unless the folder is given:
 * `./gradlew :app:testDebugUnitTest --tests '*TracksMenuRenderTest*' -Ptoolmap.renders=<folder>`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h915dp-xxhdpi")
class TracksMenuRenderTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun trackPanelAboveTheBar() {
        val folder = System.getProperty("toolmap.renders").orEmpty()
        assumeTrue("Renders are made with -Ptoolmap.renders=<folder>", folder.isNotEmpty())
        rule.setContent {
            ToolMapTheme {
                CompositionLocalProvider(LocalFormats provides Formats()) {
                    // A neutral ground in place of the map, which the render does not draw.
                    Box(Modifier.fillMaxSize().background(Color(0xFFE6E1D3)).padding(12.dp)) {
                        Column(
                            Modifier.align(Alignment.BottomStart),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TracksMenuPanel(
                                state = TracksMenuState(drawAllowed = true, waypointAllowed = true),
                                onAction = {},
                                onExplorer = {},
                                onHelp = {},
                                maxHeight = 600.dp,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            MapBottomBar(
                                locationMode = LocationMode.FOLLOW,
                                tracksOpen = true,
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
            }
        }
        rule.waitForIdle()
        val out = File(folder).apply { mkdirs() }
        FileOutputStream(File(out, "flag-panel.png")).use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
