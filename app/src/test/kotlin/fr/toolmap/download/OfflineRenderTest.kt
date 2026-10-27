package fr.toolmap.download

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import fr.toolmap.core.download.ZoneShape
import fr.toolmap.data.storage.StorageManager
import fr.toolmap.data.tiles.MbTilesStore
import fr.toolmap.data.tiles.StoredTile
import fr.toolmap.data.tiles.TileKey
import fr.toolmap.maps.MapsPanelFixture
import fr.toolmap.maps.SaveAreaPanel
import fr.toolmap.storage.StorageScreen
import fr.toolmap.storage.StorageViewModel
import fr.toolmap.testing.stopTurningLooper
import fr.toolmap.ui.theme.ToolMapTheme
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.io.FileOutputStream

/**
 * PNG renders of « Téléchargements et stockage » (P29.7, « Poids »): the sheet of the zone (folded and unfolded), the
 * downloads and the storage, by day and by night, for the comparison with the validated preview where no emulator is
 * at hand. Skipped unless the folder is given:
 * `./gradlew :app:testDebugUnitTest --tests '*OfflineRenderTest*' -Ptoolmap.renders=<folder>`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h760dp-xxhdpi")
class OfflineRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private val closing = ArrayList<() -> Unit>()

    @After
    fun tearDown() = closing.forEach { it() }

    @Test
    fun zoneDay() = renderZone(night = false, "zone-day")

    @Test
    fun zoneNight() = renderZone(night = true, "zone-night")

    @Test
    fun zoneUnfoldedDay() = renderZone(night = false, "zone-unfolded-day", unfold = true)

    @Test
    fun downloadsDay() = renderDownloads(night = false, "downloads-day")

    @Test
    fun downloadsNight() = renderDownloads(night = true, "downloads-night")

    @Test
    fun storageDay() = renderStorage(night = false, "storage-day")

    @Test
    fun storageNight() = renderStorage(night = true, "storage-night")

    private fun folder(): File {
        val folder = System.getProperty("toolmap.renders").orEmpty()
        assumeTrue("Renders are made with -Ptoolmap.renders=<folder>", folder.isNotEmpty())
        return File(folder).apply { mkdirs() }
    }

    private fun renderZone(
        night: Boolean,
        name: String,
        unfold: Boolean = false,
    ) {
        val out = folder()
        val fixture = MapsPanelFixture(builtin = true)
        closing += { fixture.close() }
        ShadowStatFs.registerStats(RuntimeEnvironment.getApplication().filesDir, 1_250_000, 1_220_000, 1_220_000)
        // IGN Cartes and OpenTopoMap checked, the IGN slopes not: « 2 sur 3 », as in the preview.
        fixture.vm.addLayer("opentopomap")
        fixture.maps.openSaveArea(ZoneShape.Rectangle(5.90, 45.40, 6.00, 45.50), 13, fixture.vm.state.value.stack)
        fixture.maps.setAreaChecked("ign-pentes", false)
        rule.setContent {
            Themed(night) {
                Box(Modifier.fillMaxSize().background(MAP)) {
                    val area by fixture.maps.area.collectAsState()
                    area?.let { ui ->
                        SaveAreaPanel(
                            ui = ui,
                            currentTileZoom = 13.3,
                            maps = fixture.maps,
                            onSave = {},
                            onClose = {},
                            onHelp = {},
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
        val end = System.currentTimeMillis() + TIMEOUT_MS
        while (fixture.maps.area.value?.estimating != false || fixture.maps.storageFigures.value == null) {
            check(System.currentTimeMillis() < end) { "Timed out waiting for the estimate" }
            rule.waitForIdle()
            Thread.sleep(POLL_MS)
        }
        if (unfold) rule.onNodeWithText("Cartes").performClick()
        rule.waitForIdle()
        save(out, name)
    }

    private fun renderDownloads(
        night: Boolean,
        name: String,
    ) {
        val out = folder()
        val fixture = DownloadFixture()
        closing += { fixture.close() }
        fixture.fill()
        rule.setContent { Themed(night) { DownloadScreen(vm = fixture.vm, onBack = {}, onSelectZone = {}) } }
        rule.waitForIdle()
        save(out, name)
    }

    private fun renderStorage(
        night: Boolean,
        name: String,
    ) {
        val out = folder()
        val fixture = DownloadFixture()
        val store = MbTilesStore(File(fixture.root, "storage-tiles"), fixture.repository)
        val app = RuntimeEnvironment.getApplication()
        // 10,4 Go in all, 5,0 Go free, as in the preview.
        ShadowStatFs.registerStats(app.filesDir, 2_539_062, 1_220_703, 1_220_703)
        val png = ByteArray(TILE_BYTES) { it.toByte() }
        for ((id, tiles) in listOf("ign-maps" to 60, "ign-scan25" to 30, "opentopomap" to 20, "osm" to 8)) {
            for (x in 0 until tiles) store.put(TileKey(id, 14, x, x), StoredTile(png, "image/png", 1L))
        }
        val vm = StorageViewModel(app, StorageManager(store, fixture.repository), fixture.repository)
        closing += {
            vm.stopTurningLooper()
            store.close()
            fixture.close()
        }
        rule.setContent { Themed(night) { StorageScreen(vm = vm, onBack = {}) } }
        val end = System.currentTimeMillis() + TIMEOUT_MS
        while (vm.state.value.busy || vm.state.value.usages.isEmpty()) {
            check(System.currentTimeMillis() < end) { "Timed out waiting for the storage" }
            rule.waitForIdle()
            Thread.sleep(POLL_MS)
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
private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 50L
private const val TILE_BYTES = 20_000

@Composable
private fun Themed(
    night: Boolean,
    content: @Composable () -> Unit,
) {
    ToolMapTheme(darkTheme = night, content = content)
}
