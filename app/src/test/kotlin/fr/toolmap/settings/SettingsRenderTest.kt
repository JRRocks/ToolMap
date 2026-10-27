package fr.toolmap.settings

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import fr.toolmap.core.settings.AppSettings
import fr.toolmap.core.settings.ThemeMode
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.ui.Formats
import fr.toolmap.ui.LocalAppSettings
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
 * Makes PNG renders of the settings screen, for the visual check of the settings (P11.8). Skipped unless the folder is
 * given: `./gradlew :app:testDebugUnitTest --tests '*SettingsRenderTest*' -Ptoolmap.renders=<folder>`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h915dp-xxhdpi")
class SettingsRenderTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun lightSettings() = render(ThemeMode.LIGHT, "settings-light")

    @Test
    fun darkSettings() = render(ThemeMode.DARK, "settings-dark")

    private fun render(
        theme: ThemeMode,
        prefix: String,
    ) {
        val folder = System.getProperty("toolmap.renders").orEmpty()
        assumeTrue("Renders are made with -Ptoolmap.renders=<folder>", folder.isNotEmpty())
        val out = File(folder).apply { mkdirs() }
        val file = File.createTempFile("settings", ".json").apply { delete() }
        val vm = SettingsViewModel(SettingsStore(file))
        vm.update { it.copy(theme = theme) }
        rule.setContent { SettingsPreview(vm) }
        rule.waitForIdle()

        var previous: Bitmap? = null
        var index = 1
        while (index <= MAX_PAGES) {
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            if (previous != null && bitmap.sameAs(previous)) break
            FileOutputStream(File(out, "$prefix-%02d.png".format(index))).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            previous = bitmap
            // A third of the screen per step, so that each capture overlaps the previous one.
            rule.onNode(hasScrollAction()).performTouchInput { swipeUp(startY = bottom * 0.8f, endY = bottom * 0.5f) }
            rule.waitForIdle()
            index++
        }
    }

    private companion object {
        /** A safety bound: the screen is about five phone pages long. */
        const val MAX_PAGES = 12
    }
}

@Composable
private fun SettingsPreview(vm: SettingsViewModel) {
    val settings: AppSettings by vm.settings.collectAsState()
    ToolMapTheme(
        darkTheme = settings.theme == ThemeMode.DARK,
        gloves = settings.gloves,
    ) {
        CompositionLocalProvider(
            LocalAppSettings provides settings,
            LocalFormats provides Formats(settings),
        ) {
            SettingsScreen(vm = vm, onBack = {})
        }
    }
}
