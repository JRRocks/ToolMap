package fr.toolmap.print

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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.print.PrintArea
import fr.toolmap.core.print.PrintPlanner
import fr.toolmap.core.print.PrintTemplate
import fr.toolmap.core.source.BuiltinSources
import fr.toolmap.ui.theme.ToolMapTheme
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
 * PNG renders of the print settings with the georeferenced PDF (P37.2, variant B of
 * `docs/previews/p37-pdf-georef.html`): « Qualité et marges » with its switch and the main page with its summary, by
 * day and by night, for the comparison with the validated preview where no emulator is at hand. Skipped unless the
 * folder is given: `./gradlew :app:testDebugUnitTest --tests '*PrintRenderTest*' -Ptoolmap.renders=<folder>`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h760dp-xxhdpi")
class PrintRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private val sources = BuiltinSources.load().associateBy { it.id }
    private val stack = listOf(LayerConfig("l1", "ign-maps"))

    // The data set of the preview: one A4 landscape page at 1:25 000, 300 dpi, UTM grid, « Tour du Lac Blanc ».
    private val template = PrintTemplate(title = "Tour du Lac Blanc", geoReferenced = true)

    private val locale = Locale.getDefault()

    @Before
    fun setUp() = Locale.setDefault(Locale.FRANCE)

    @After
    fun tearDown() = Locale.setDefault(locale)

    @Test
    fun qualityDay() = render(PrintSheetPage.QUALITY, night = false, "p37-quality-day")

    @Test
    fun qualityNight() = render(PrintSheetPage.QUALITY, night = true, "p37-quality-night")

    @Test
    fun mainDay() = render(PrintSheetPage.MAIN, night = false, "p37-main-day")

    @Test
    fun mainNight() = render(PrintSheetPage.MAIN, night = true, "p37-main-night")

    @Test
    fun qualityOffDay() =
        render(PrintSheetPage.QUALITY, night = false, "p37-quality-off-day", template.copy(geoReferenced = false))

    private fun render(
        page: PrintSheetPage,
        night: Boolean,
        name: String,
        template: PrintTemplate = this.template,
    ) {
        val folder = System.getProperty("toolmap.renders").orEmpty()
        assumeTrue("Renders are made with -Ptoolmap.renders=<folder>", folder.isNotEmpty())
        val out = File(folder).apply { mkdirs() }
        val plan = PrintPlanner.plan(template, PrintArea(45.0, 6.0), stack, sources::get)
        val ui =
            PrintUi(stage = PrintStage.CONFIRM, template = template, plan = plan, missingTiles = 0, sheetPage = page)
        rule.setContent {
            ToolMapTheme(darkTheme = night) {
                // The panel as on the map: at the foot of the screen, above the bottom bar, on a grey standing for the map.
                Box(Modifier.fillMaxSize().background(Color(MAP_GREY)).padding(bottom = 84.dp)) {
                    PrintConfirmSheet(
                        ui = ui,
                        actions = NO_ACTIONS,
                        maxHeight = 506.dp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp),
                    )
                }
            }
        }
        // The main page as in the preview: scrolled to its last line, the summary of « Qualité et marges ».
        if (page == PrintSheetPage.MAIN) rule.onNodeWithText("Qualité et marges").performScrollTo()
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        FileOutputStream(File(out, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val MAP_GREY = 0xFFB9C2B4

        val NO_ACTIONS =
            PrintActions(
                onUpdate = {},
                onRotate = {},
                onChooseZone = {},
                onNext = {},
                onBack = {},
                onPage = {},
                onClose = {},
                onCancel = {},
                onDownload = {},
                onExport = {},
                onCancelJob = {},
            )
    }
}
