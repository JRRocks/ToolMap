package fr.toolmap.backup

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.toolmap.core.backup.BackupArea
import fr.toolmap.core.backup.BackupEntry
import fr.toolmap.core.backup.BackupManifest
import fr.toolmap.core.backup.RestoreReport
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.maps.MapThumbnails
import fr.toolmap.ui.theme.ToolMapTheme
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals

/**
 * « Sauvegarde et restauration » since P29.9 (« Loupe ») and P33 (« Poids »): « Sauvegarder » and « Restaurer » on a
 * segmented button, one main action each; « Sauvegarder » weighs the file, lists the areas to tick (the three lightest
 * on one line) and the offline maps, unticked; a restore waiting opens the screen on « Restaurer », its card first; the
 * report hides with « Masquer »; nothing can be started while a task runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h1600dp")
class BackupPageTest {
    @get:Rule
    val rule = createComposeRule()

    private val thumbnails =
        MapThumbnails(OkHttpClient(), SourceRepository(File(createTempDirectory("sources").toFile(), "s"), emptyList()))

    private var created = 0
    private var chosen = 0
    private var hidden = 0

    private val weights =
        mapOf(
            BackupArea.DATABASE to 2_100_000L,
            BackupArea.WORKSPACES to 8_000L,
            BackupArea.SOURCES to 41_000L,
            BackupArea.SETTINGS to 12_000L,
            BackupArea.ALERTS to 3_000L,
            BackupArea.PRINT to 2_000L,
            BackupArea.STATE to 2_000L,
        )
    private val maps =
        listOf(
            OfflineMapFile("ign.mbtiles", "IGN SCAN 25 Touristique", "ign", 212_400_000L),
            OfflineMapFile("osm.mbtiles", "OpenStreetMap", "osm", 61_000_000L),
        )

    private fun setContent(start: BackupUi) {
        rule.setContent {
            var ui by remember { mutableStateOf(start) }
            ToolMapTheme {
                BackupPage(
                    ui = ui,
                    thumbnails = thumbnails,
                    snackbar = remember { SnackbarHostState() },
                    onBack = {},
                    onToggleAreas = { ui = ui.copy(areas = ui.areas.flip(it)) },
                    onToggleTiles = { ui = ui.copy(tiles = ui.tiles.flip(setOf(it))) },
                    onCreate = { created++ },
                    onChoose = { chosen++ },
                    onRestart = {},
                    onCancelPending = { ui = ui.copy(pending = null) },
                    onHideReport = {
                        hidden++
                        ui = ui.copy(report = null)
                    },
                )
            }
        }
    }

    @Test
    fun `the screen opens on Sauvegarder, the weight, the areas, the offline maps unticked, then the button`() {
        setContent(BackupUi(areaBytes = weights, offlineMaps = maps))
        rule.onAllNodesWithText("fichier .zip").assertCountEquals(1)
        rule.onAllNodesWithText("Données 2", substring = true).assertCountEquals(1)
        rule.onAllNodesWithText("Traces et waypoints").assertCountEquals(1)
        rule.onAllNodesWithText("Réglages").assertCountEquals(1)
        // The three lightest share one line.
        rule.onAllNodesWithText("3 autres rubriques").assertCountEquals(1)
        rule.onAllNodesWithText("Alertes, mises en page, position et calques").assertCountEquals(1)
        rule.onAllNodesWithText("Alertes").assertCountEquals(0)
        rule.onNodeWithText("IGN SCAN 25 Touristique").assertIsOff()
        rule.onAllNodesWithText("Sans les cartes hors ligne : 2", substring = true).assertCountEquals(1)
        rule.onAllNodesWithText("Choisir une sauvegarde…").assertCountEquals(0)
        rule.onNodeWithText("Créer une sauvegarde…").performClick()
        assertEquals(1, created)
    }

    @Test
    fun `a ticked offline map weighs on the gauge, not on the bar at the foot`() {
        setContent(BackupUi(areaBytes = weights, offlineMaps = maps))
        rule.onNodeWithText("IGN SCAN 25 Touristique").performClick()
        rule.onNodeWithText("IGN SCAN 25 Touristique").assertIsOn()
        rule.onAllNodesWithText("Cartes hors ligne 212", substring = true).assertCountEquals(1)
        rule.onAllNodesWithText("Sans les cartes hors ligne : 2", substring = true).assertCountEquals(1)
    }

    @Test
    fun `the line of the lightest areas unfolds, and its box ticks them together`() {
        setContent(BackupUi(areaBytes = weights, offlineMaps = maps))
        rule.onNodeWithText("3 autres rubriques").performClick()
        rule.onAllNodesWithText("Alertes").assertCountEquals(1)
        rule.onNodeWithText("Mises en page").performClick()
        rule.onNodeWithText("Mises en page").assertIsOff()
        // Nothing ticked: nothing to save.
        setOf("Traces et waypoints", "Favoris", "Cartes ajoutées", "Réglages", "Alertes", "Position et calques")
            .forEach { rule.onNodeWithText(it).performClick() }
        rule.onNodeWithText("Créer une sauvegarde…").assertIsNotEnabled()
    }

    @Test
    fun `Restaurer shows its own action only`() {
        setContent(BackupUi())
        rule.onNodeWithText("Restaurer").performClick()
        rule.onAllNodesWithText("Créer une sauvegarde…").assertCountEquals(0)
        rule.onNodeWithText("Choisir une sauvegarde…").performClick()
        assertEquals(1, chosen)
    }

    @Test
    fun `a restore waiting opens the screen on Restaurer, its card first`() {
        setContent(BackupUi(pending = BackupManifest(createdAtMillis = 1_760_000_000_000L)))
        rule.onAllNodesWithText("Restauration prête").assertCountEquals(1)
        rule.onAllNodesWithText("Redémarrer maintenant").assertCountEquals(1)
        val top = { text: String -> rule.onNodeWithText(text, substring = true).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(true, top("Restauration prête") < top("Choisissez un fichier"))
        rule.onNodeWithText("Annuler").performClick()
        rule.onAllNodesWithText("Restauration prête").assertCountEquals(0)
    }

    @Test
    fun `the report hides with Masquer`() {
        setContent(BackupUi(report = RestoreReport(ok = false, atMillis = 0L)))
        rule.onAllNodesWithText("La restauration a échoué", substring = true).assertCountEquals(1)
        rule.onNodeWithText("Masquer").performClick()
        assertEquals(1, hidden)
        rule.onAllNodesWithText("La restauration a échoué", substring = true).assertCountEquals(0)
    }

    @Test
    fun `nothing starts while a task runs`() {
        setContent(BackupUi(busy = BackupBusy.CREATING))
        rule.onAllNodesWithText("Création de la sauvegarde…").assertCountEquals(1)
        rule.onNodeWithText("Créer une sauvegarde…").assertIsNotEnabled()
        rule.onNodeWithText("Restaurer").assertIsNotEnabled()
    }

    @Test
    fun `the restore dialog groups the areas in four lines with their weight, and the total of the ticked ones`() {
        val manifest =
            BackupManifest(
                appVersion = "0.4.80",
                createdAtMillis = 1_760_000_000_000L,
                areas = (DATA_AREAS + BackupArea.TILES).map { it.id },
                entries =
                    listOf(
                        BackupEntry("tracks.db", 2_100_000L, "0".repeat(64)),
                        BackupEntry("workspaces/a.json", 8_000L, "0".repeat(64)),
                        BackupEntry("sources/b.aqx", 41_000L, "0".repeat(64)),
                        BackupEntry("settings.json", 12_000L, "0".repeat(64)),
                        BackupEntry("tiles/ign.mbtiles", 212_400_000L, "0".repeat(64)),
                        BackupEntry("tiles/osm.mbtiles", 48_300_000L, "0".repeat(64)),
                    ),
            )
        var confirmed = 0
        rule.setContent {
            var chosen by remember { mutableStateOf(DATA_AREAS + BackupArea.TILES) }
            ToolMapTheme {
                RestoreDialog(
                    manifest = manifest,
                    chosen = chosen,
                    onToggle = { chosen = chosen.flip(it) },
                    onConfirm = { confirmed++ },
                    onDismiss = {},
                )
            }
        }
        rule.onAllNodesWithText("les cartes hors ligne s'ajoutent", substring = true).assertCountEquals(1)
        rule.onAllNodesWithText("Favoris et cartes ajoutées").assertCountEquals(1)
        rule.onAllNodesWithText("49 Ko").assertCountEquals(1)
        rule.onAllNodesWithText("Cartes hors ligne · 2").assertCountEquals(1)
        rule.onNodeWithText("Réglages et alertes").performClick()
        rule.onNodeWithText("Réglages et alertes").assertIsOff()
        rule.onAllNodesWithText("À restaurer : 262", substring = true).assertCountEquals(1)
        listOf("Traces et waypoints", "Favoris et cartes ajoutées", "Cartes hors ligne · 2")
            .forEach { rule.onNodeWithText(it).performClick() }
        rule.onNodeWithText("Préparer la restauration").assertIsNotEnabled()
        rule.onNodeWithText("Traces et waypoints").performClick()
        rule.onNodeWithText("Préparer la restauration").performClick()
        assertEquals(1, confirmed)
    }
}
