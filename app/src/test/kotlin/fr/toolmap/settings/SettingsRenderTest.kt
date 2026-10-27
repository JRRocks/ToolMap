package fr.toolmap.settings

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import fr.toolmap.backup.BackupPage
import fr.toolmap.backup.BackupUi
import fr.toolmap.core.backup.BackupEntry
import fr.toolmap.core.backup.BackupManifest
import fr.toolmap.core.geo.CoordinateSystem
import fr.toolmap.core.print.GridKind
import fr.toolmap.core.settings.AppSettings
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.ui.Formats
import fr.toolmap.ui.LocalAppSettings
import fr.toolmap.ui.LocalFormats
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
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * PNG renders of « Réglages » and « Sauvegarde et restauration » (P29.9, « Loupe »): the search « cap », the page of
 * « Affichage » and the two halves of the backup, by day and by night, for the comparison with the validated preview
 * where no emulator is at hand. Skipped unless the folder is given:
 * `./gradlew :app:testDebugUnitTest --tests '*SettingsRenderTest*' -Ptoolmap.renders=<folder>`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h760dp-xxhdpi")
class SettingsRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private val locale = Locale.getDefault()

    // The data set of the preview: MGRS, the MGRS grid, the screen kept on, the rest by default.
    private val vm =
        SettingsViewModel(SettingsStore(File.createTempFile("settings", ".json").apply { delete() })).apply {
            update {
                it.copy(coordinates = CoordinateSystem.MGRS, screenGrid = GridKind.MGRS, keepScreenOn = true)
            }
        }

    @Before
    fun setUp() = Locale.setDefault(Locale.FRANCE)

    @After
    fun tearDown() = Locale.setDefault(locale)

    @Test
    fun searchDay() = renderSearch(night = false, "search-day")

    @Test
    fun searchNight() = renderSearch(night = true, "search-night")

    @Test
    fun displayDay() = renderDisplay(night = false, "display-day")

    @Test
    fun displayNight() = renderDisplay(night = true, "display-night")

    @Test
    fun backupDay() = renderBackup(night = false, "backup-day", pending = false)

    @Test
    fun backupNight() = renderBackup(night = true, "backup-night", pending = false)

    @Test
    fun restorePendingDay() = renderBackup(night = false, "restore-pending-day", pending = true)

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
        rule.setContent { Settings(vm, night) }
        rule.onNode(hasSetTextAction()).performTextInput("cap")
        rule.waitForIdle()
        save(out, name)
    }

    private fun renderDisplay(
        night: Boolean,
        name: String,
    ) {
        val out = folder()
        vm.category = SettingsCategory.DISPLAY
        rule.setContent { Settings(vm, night) }
        rule.waitForIdle()
        save(out, name)
    }

    private fun renderBackup(
        night: Boolean,
        name: String,
        pending: Boolean,
    ) {
        val out = folder()
        val created = LocalDateTime.parse("2026-10-12T18:40").atZone(ZoneId.of("Europe/Paris")).toInstant()
        val manifest =
            BackupManifest(
                appVersion = "0.4.80",
                createdAtMillis = created.toEpochMilli(),
                entries = List(FILES) { BackupEntry("files/f$it", SIZE / FILES, "0".repeat(64)) },
            )
        val ui = BackupUi(tilesBytes = TILES_BYTES, pending = if (pending) manifest else null)
        rule.setContent {
            ToolMapTheme(darkTheme = night) {
                BackupPage(
                    ui = ui,
                    snackbar = remember { SnackbarHostState() },
                    onBack = {},
                    onIncludeTiles = {},
                    onCreate = {},
                    onChoose = {},
                    onRestart = {},
                    onCancelPending = {},
                    onHideReport = {},
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

    private companion object {
        const val TILES_BYTES = 18_900_000L
        const val FILES = 214
        const val SIZE = 4_200_000L
    }
}

@Composable
private fun Settings(
    vm: SettingsViewModel,
    night: Boolean,
) {
    val settings: AppSettings by vm.settings.collectAsState()
    ToolMapTheme(darkTheme = night, gloves = settings.gloves) {
        CompositionLocalProvider(
            LocalAppSettings provides settings,
            LocalFormats provides Formats(settings),
        ) {
            SettingsScreen(vm = vm, onBack = {}, onOpenBackup = {}, onOpenMultiZoom = {})
        }
    }
}
