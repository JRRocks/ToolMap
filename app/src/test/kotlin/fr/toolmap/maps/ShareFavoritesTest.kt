package fr.toolmap.maps

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.toolmap.core.aqx.AqxParser
import fr.toolmap.core.layers.FavoritesFileCodec
import fr.toolmap.core.layers.Workspace
import fr.toolmap.core.layers.WorkspaceLayer
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomSet
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.workspaces.WorkspaceStore
import fr.toolmap.testing.stopTurningLooper
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** « Partager des favoris » (P25.2, variant C): the ticks make the file, the key is removed unless kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h2000dp")
class ShareFavoritesTest {
    @get:Rule
    val rule = createComposeRule()

    private val root: File = createTempDirectory("share-favorites").toFile()

    private fun map(
        id: String,
        name: String,
        params: Map<String, String> = emptyMap(),
    ) = TileSource(
        id = id,
        name = name,
        params = params,
        levels = listOf(LevelDef(ZoomSet.range(1, 18), listOf("https://t/$id/{\$z}/{\$x}/{\$y}.png"))),
    )

    private val repository =
        SourceRepository(
            File(root, "sources"),
            listOf(map("ign-maps", "IGN Cartes"), map("osm", "OpenStreetMap")),
        ).apply {
            save(map("SNOW", "SnowBase Map", mapOf("key" to "secret-123")))
        }
    private val workspaces =
        WorkspaceStore(File(root, "workspaces")).apply {
            save(Workspace("ws-snow", "Sortie neige", listOf(WorkspaceLayer("ign-maps"), WorkspaceLayer("SNOW"))))
            save(Workspace("ws-osm", "Ville", listOf(WorkspaceLayer("osm"))))
        }
    private val vm = FavoritesShareViewModel(RuntimeEnvironment.getApplication(), repository, workspaces)

    @After
    fun tearDown() {
        vm.stopTurningLooper()
        root.deleteRecursively()
    }

    @Test
    fun `the ticked favorites make one file with their added maps, keys removed by default`() {
        vm.open("ws-snow")
        settle()
        val file = vm.ui.value.file!!
        assertEquals("Sortie neige.toolmap", file.name)
        assertEquals(listOf("SNOW"), file.addedMaps.map { it.id })
        assertEquals(listOf("SNOW"), file.keyed.map { it.id })
        val read = FavoritesFileCodec.decode(file.text)
        assertEquals(listOf("Sortie neige"), read.favorites.map { it.name })
        assertEquals("", AqxParser.parse(read.aqx!!).file.sources.single().params["key"])
        vm.setKeepKeys(true)
        settle()
        assertTrue("secret-123" in vm.ui.value.file!!.text)
        vm.toggle("ws-osm")
        settle()
        assertEquals("Sortie neige et 1 autre.toolmap", vm.ui.value.file!!.name)
        vm.toggle("ws-snow")
        settle()
        vm.toggle("ws-osm")
        settle()
        assertNull(vm.ui.value.file)
        assertEquals(1, vm.ui.value.addedCount["ws-snow"])
        assertEquals(setOf("ws-snow"), vm.ui.value.withKey)
    }

    @Test
    fun `the screen says what the file holds, the key choice and its warning`() {
        vm.open("ws-snow")
        settle()
        val byId = repository.sources.value.associateBy { it.id }
        rule.setContent {
            val ui by vm.ui.collectAsState()
            ShareFavoritesPage(
                ui = ui,
                byId = byId,
                thumbnails = MapThumbnails(OkHttpClient(), repository),
                snackbar = SnackbarHostState(),
                onClose = {},
                onToggle = vm::toggle,
                onKeepKeys = vm::setKeepKeys,
                onShare = {},
            )
        }
        rule.onNodeWithText("Partager des favoris").assertExists()
        rule.onNodeWithText("1 coché sur 4").assertExists()
        rule.onNodeWithText("Sortie neige.toolmap").assertExists()
        rule.onNodeWithText("1 favori · 1 carte ajoutée", substring = true).assertExists()
        rule.onNodeWithText("2 cartes · 1 ajoutée · clé").assertExists()
        rule.onNodeWithText("Clé d'accès · 1 carte en utilise une (SnowBase Map)").assertExists()
        rule.onNodeWithText("Le destinataire saisira sa propre clé à l'ouverture.").assertExists()
        rule.onNodeWithText("Garder la clé").performClick()
        rule.onNodeWithText("Toute personne qui reçoit le fichier pourra utiliser votre clé.").assertExists()
        rule.onNodeWithText("Partager…").assertIsEnabled()
        rule.onNodeWithText("Sortie neige").performClick()
        rule.onNodeWithText("Cochez les favoris à partager").assertExists()
        rule.onNodeWithText("Partager…").assertIsNotEnabled()
        assertFalse(rule.onAllNodesWithTextExists("Clé d'accès", substring = true))
    }

    private fun settle() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextExists(
        text: String,
        substring: Boolean,
    ): Boolean =
        onAllNodes(androidx.compose.ui.test.hasText(text, substring = substring))
            .fetchSemanticsNodes()
            .isNotEmpty()
}
