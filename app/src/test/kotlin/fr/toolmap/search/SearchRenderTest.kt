package fr.toolmap.search

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import fr.toolmap.about.AboutScreen
import fr.toolmap.about.HelpSection
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * PNG renders of « Recherche » and « Aide et à propos » (P29.10, « Équerre »), by day and by night, for the comparison
 * with the validated preview where no emulator is at hand. Skipped unless the folder is given:
 * `./gradlew :app:testDebugUnitTest --tests '*SearchRenderTest*' -Ptoolmap.renders=<folder>`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h760dp-xxhdpi")
class SearchRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private val locale = Locale.getDefault()
    private var samples: SearchSamples? = null

    @Before
    fun setUp() {
        Locale.setDefault(Locale.FRANCE)
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        samples?.close()
        Dispatchers.resetMain()
        Locale.setDefault(locale)
    }

    @Test
    fun searchDay() = renderSearch(night = false, "search-day")

    @Test
    fun searchNight() = renderSearch(night = true, "search-night")

    @Test
    fun aboutDay() = renderAbout(night = false, "about-day")

    @Test
    fun aboutNight() = renderAbout(night = true, "about-night")

    private fun folder(): File {
        val folder = System.getProperty("toolmap.renders").orEmpty()
        assumeTrue("Renders are made with -Ptoolmap.renders=<folder>", folder.isNotEmpty())
        return File(folder).apply { mkdirs() }
    }

    private fun renderSearch(
        night: Boolean,
        name: String,
    ) {
        val out = folder()
        val s = SearchSamples().also { samples = it }
        rule.setContent {
            ToolMapTheme(darkTheme = night) { SearchScreen(vm = s.vm, onBack = {}, onShow = {}, onGoTo = {}) }
        }
        rule.onNode(hasSetTextAction()).performTextInput("Barraux")
        rule.waitUntil(TIMEOUT_MS) { s.vm.ui.value.here.isNotEmpty() }
        s.vm.submit()
        rule.waitUntil(TIMEOUT_MS) { s.vm.ui.value.searched }
        rule.waitForIdle()
        save(out, name)
    }

    private fun renderAbout(
        night: Boolean,
        name: String,
    ) {
        val out = folder()
        // Opened from the « ? » of « Cartes »: its block is lit, as in the preview.
        rule.mainClock.autoAdvance = false
        rule.setContent { ToolMapTheme(darkTheme = night) { AboutScreen(onBack = {}, section = HelpSection.MAPS) } }
        rule.mainClock.advanceTimeBy(FRAME_MS)
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
        const val TIMEOUT_MS = 5_000L
        const val FRAME_MS = 32L
    }
}
