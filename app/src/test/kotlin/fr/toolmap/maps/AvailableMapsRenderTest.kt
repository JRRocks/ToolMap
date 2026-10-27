package fr.toolmap.maps

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import fr.toolmap.core.source.ZoomBand
import fr.toolmap.data.storage.StorageManager
import fr.toolmap.data.tiles.MbTilesStore
import fr.toolmap.data.wizard.CapabilitiesFetcher
import fr.toolmap.data.wizard.TileProbe
import fr.toolmap.multizoom.MultiZoomScreen
import fr.toolmap.multizoom.MultiZoomViewModel
import fr.toolmap.testing.stopTurningLooper
import fr.toolmap.ui.Formats
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.ToolMapTheme
import fr.toolmap.wizard.SourceWizardScreen
import fr.toolmap.wizard.SourceWizardViewModel
import fr.toolmap.wizard.WizardKind
import org.junit.After
import org.junit.Assume.assumeTrue
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
 * PNG renders of « Cartes disponibles » (P29.6, « Bascule »): the list in its two segments, the search, the sheet
 * « Ajouter une carte », the online catalogue, the first step of the wizard and the multi-zoom editor, by day and by
 * night, for the comparison with the validated preview where no emulator is at hand. Skipped unless the folder is
 * given: `./gradlew :app:testDebugUnitTest --tests '*AvailableMapsRenderTest*' -Ptoolmap.renders=<folder>`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h760dp-xxhdpi")
class AvailableMapsRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private val fixture = MapsPanelFixture(builtin = true)
    private val closing = ArrayList<() -> Unit>()

    @After
    fun tearDown() {
        closing.forEach { it() }
        fixture.close()
    }

    @Test
    fun availableDay() = renderAvailable(night = false, "available-day")

    @Test
    fun availableNight() = renderAvailable(night = true, "available-night")

    @Test
    fun layersDay() = renderAvailable(night = false, "layers-day", segment = "Calques")

    @Test
    fun addSheetDay() = renderAvailable(night = false, "add-day", tap = "Ajouter une carte")

    @Test
    fun catalogueDay() = renderCatalogue(night = false, "catalogue-day")

    @Test
    fun catalogueNight() = renderCatalogue(night = true, "catalogue-night")

    @Test
    fun wizardDay() = renderWizard(night = false, "wizard-day")

    @Test
    fun wizardNight() = renderWizard(night = true, "wizard-night")

    @Test
    fun multiZoomDay() = renderMultiZoom(night = false, "multizoom-day")

    @Test
    fun multiZoomNight() = renderMultiZoom(night = true, "multizoom-night")

    private fun folder(): File {
        val folder = System.getProperty("toolmap.renders").orEmpty()
        assumeTrue("Renders are made with -Ptoolmap.renders=<folder>", folder.isNotEmpty())
        return File(folder).apply { mkdirs() }
    }

    private fun renderAvailable(
        night: Boolean,
        name: String,
        segment: String? = null,
        tap: String? = null,
    ) {
        val out = folder()
        rule.setContent {
            Themed(night) {
                AvailableMapsScreen(
                    maps = fixture.maps,
                    vm = fixture.vm,
                    actions = AvailableMapsActions(onBack = {}, onOpenWizard = {}, onImport = {}, onOpenStorage = {}),
                )
            }
        }
        segment?.let { rule.onNodeWithText(it).performClick() }
        tap?.let { rule.onNodeWithText(it, useUnmergedTree = true).performClick() }
        rule.waitForIdle()
        save(out, name)
    }

    private fun renderCatalogue(
        night: Boolean,
        name: String,
    ) {
        val out = folder()
        rule.setContent { Themed(night) { OnlineCatalogScreen(fixture.maps, onBack = {}) } }
        // The layers, « VTT (Waymarked Trails) » just added, as in the preview.
        rule.onNodeWithText("Calques", substring = true).performClick()
        rule.onAllNodesWithText("Ajouter")[2].performClick()
        rule.waitForIdle()
        save(out, name)
    }

    private fun renderWizard(
        night: Boolean,
        name: String,
    ) {
        val out = folder()
        val store = MbTilesStore(File(fixture.root, "wizard-tiles"), fixture.repository)
        val wizard =
            SourceWizardViewModel(
                RuntimeEnvironment.getApplication(),
                fixture.repository,
                StorageManager(store, fixture.repository),
                CapabilitiesFetcher(),
                TileProbe(),
            )
        closing += {
            wizard.stopTurningLooper()
            store.close()
        }
        wizard.start(null, null)
        wizard.chooseKind(WizardKind.XYZ)
        wizard.setUrls(
            "https://a.tile.opentopomap.org/{\$z}/{\$x}/{\$y}.png\nhttps://b.tile.opentopomap.org/{\$z}/{\$x}/{\$y}.png",
        )
        rule.setContent { Themed(night) { SourceWizardScreen(vm = wizard, onClose = {}) } }
        rule.waitForIdle()
        save(out, name)
    }

    private fun renderMultiZoom(
        night: Boolean,
        name: String,
    ) {
        val out = folder()
        val multiZoom = MultiZoomViewModel(fixture.repository)
        closing += { multiZoom.stopTurningLooper() }
        // The bands of the preview: OpenStreetMap, OpenTopoMap, IGN SCAN 25, nothing at 17, OpenStreetMap, IGN photos.
        multiZoom.startNew("Rando multi-cartes")
        val bands =
            listOf(
                ZoomBand(1, 8, "osm"),
                ZoomBand(9, 11, "opentopomap"),
                ZoomBand(12, 16, "ign-scan25"),
                ZoomBand(18, 18, "osm"),
                ZoomBand(19, 19, "ign-ortho"),
            )
        multiZoom.updateBand(0, bands.first())
        for (band in bands.drop(1)) {
            multiZoom.addBand()
            multiZoom.updateBand(multiZoom.editing.value!!.draft.bands.lastIndex, band)
        }
        rule.setContent {
            Themed(night) { MultiZoomScreen(vm = multiZoom, onBack = {}, onShow = {}, editorOnly = true) }
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

@Composable
private fun Themed(
    night: Boolean,
    content: @Composable () -> Unit,
) {
    ToolMapTheme(darkTheme = night) {
        CompositionLocalProvider(LocalFormats provides Formats(), content = content)
    }
}
