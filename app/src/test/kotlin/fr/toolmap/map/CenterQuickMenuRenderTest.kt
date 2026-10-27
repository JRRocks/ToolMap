package fr.toolmap.map

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
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
 * Makes a PNG render of the quick menu of the centre point (P14.5), for the visual check against the menu of a point in
 * AlpineQuest. Skipped unless the folder is given:
 * `./gradlew :app:testDebugUnitTest --tests '*CenterQuickMenuRenderTest*' -Ptoolmap.renders=<folder>`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h915dp-xxhdpi")
class CenterQuickMenuRenderTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun centreMenu() {
        val folder = System.getProperty("toolmap.renders").orEmpty()
        assumeTrue("Renders are made with -Ptoolmap.renders=<folder>", folder.isNotEmpty())
        rule.setContent {
            ToolMapTheme {
                // A neutral ground in place of the map, which the render does not draw.
                Box(
                    Modifier.fillMaxSize().background(Color(0xFFE6E1D3)).padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CenterQuickMenu(
                        lat = 45.832,
                        lon = 6.865,
                        terrainElevation = { _, _ -> 282.0 },
                        onTarget = {},
                        onWaypoint = {},
                        onMeasure = {},
                        onCopy = {},
                        onShare = {},
                        onOpenWith = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        val out = File(folder).apply { mkdirs() }
        FileOutputStream(File(out, "centre-menu.png")).use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
