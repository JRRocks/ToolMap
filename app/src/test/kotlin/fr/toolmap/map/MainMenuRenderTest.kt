package fr.toolmap.map

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
 * PNG renders of the main menu (P29.2, « Portée ») over the bar, folded and unfolded, and of the exit dialog during a
 * recording, by day and by night: the check against the validated preview where no emulator is at hand. Skipped
 * unless the folder is given: `./gradlew :app:testDebugUnitTest --tests '*MainMenuRenderTest*' -Ptoolmap.renders=<folder>`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h760dp-xxhdpi")
class MainMenuRenderTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun menuDay() = render(night = false, unfold = false, "menu-day")

    @Test
    fun menuNight() = render(night = true, unfold = false, "menu-night")

    @Test
    fun plusDay() = render(night = false, unfold = true, "plus-day")

    @Test
    fun exitDay() = renderExit(night = false, "exit-day")

    @Test
    fun exitNight() = renderExit(night = true, "exit-night")

    private fun folder(): File {
        val folder = System.getProperty("toolmap.renders").orEmpty()
        assumeTrue("Renders are made with -Ptoolmap.renders=<folder>", folder.isNotEmpty())
        return File(folder).apply { mkdirs() }
    }

    private fun render(
        night: Boolean,
        unfold: Boolean,
        name: String,
    ) {
        val out = folder()
        rule.setContent {
            ToolMapTheme(darkTheme = night) {
                OnMap {
                    MainMenuPanel(
                        state =
                            MainMenuState(
                                showCoordinates = true,
                                measureAllowed = true,
                                printAllowed = true,
                                diagnostics = true,
                            ),
                        onAction = {},
                        onCoordinates = {},
                        maxHeight = 520.dp,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }
        if (unfold) rule.onNodeWithText("Plus").performClick()
        rule.waitForIdle()
        save(out, name)
    }

    private fun renderExit(
        night: Boolean,
        name: String,
    ) {
        val out = folder()
        rule.setContent {
            ToolMapTheme(darkTheme = night) {
                OnMap {}
                ExitDialog(
                    running =
                        listOf(
                            RunningService(
                                RunningService.Kind.RECORDING,
                                "Enregistrement de trace",
                                "1 h 12 · 4,8 km",
                                "Arrêter l'enregistrement",
                            ),
                        ),
                    onExit = {},
                    onDismiss = {},
                )
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

/** A plain map colour, the panel at the bottom over the bar, as on the map screen. */
@Composable
private fun OnMap(panel: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xFFE4EBCF))) {
        Column(Modifier.align(Alignment.BottomCenter)) {
            Box(Modifier.padding(bottom = 10.dp)) { panel() }
            MapBottomBar(
                locationMode = LocationMode.FREE,
                tracksOpen = false,
                positionOpen = false,
                onMenu = {},
                onMaps = {},
                onTracks = {},
                onPosition = {},
                onPositionLongPress = {},
                menuOpen = true,
            )
        }
    }
}
