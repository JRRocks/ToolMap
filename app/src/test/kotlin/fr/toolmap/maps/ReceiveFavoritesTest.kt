package fr.toolmap.maps

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.toolmap.core.layers.FavoritesExport
import fr.toolmap.core.layers.FavoritesFileCodec
import fr.toolmap.core.layers.Workspace
import fr.toolmap.core.layers.WorkspaceLayer
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomSet
import fr.toolmap.data.settings.SettingsStore
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** « Ajouter N favoris ? » (P25.3): what each favorite of a `.toolmap` file brings, then what adding stores. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h2000dp")
class ReceiveFavoritesTest {
    @get:Rule
    val rule = createComposeRule()

    private val root: File = createTempDirectory("receive-favorites").toFile()

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

    private val builtins = listOf(map("ign-maps", "IGN Cartes"), map("osm", "OpenStreetMap"))

    /** The phone that opens the file: a favorite « Ville » already, OpenStreetMap hidden from the list. */
    private val repository = SourceRepository(File(root, "sources"), builtins)
    private val workspaces =
        WorkspaceStore(File(root, "workspaces")).apply {
            save(Workspace("ws-ville", "Ville", listOf(WorkspaceLayer("osm"))))
        }
    private val settings =
        SettingsStore(File(root, "settings.json")).apply {
            update { it.copy(maps = it.maps.copy(hiddenMaps = setOf("osm"))) }
        }
    private val vm = FavoritesImportViewModel(repository, workspaces, settings)

    /** The file another phone sent: « Ville » and « Sortie neige », whose added map lost its key. */
    private val text: String =
        run {
            val sender =
                SourceRepository(File(root, "sender"), builtins).apply {
                    save(map("SNOW", "SnowBase Map", mapOf("key" to "secret-123")))
                }
            val favorites =
                listOf(
                    Workspace("a", "Ville", listOf(WorkspaceLayer("osm"))),
                    Workspace("b", "Sortie neige", listOf(WorkspaceLayer("ign-maps"), WorkspaceLayer("SNOW"))),
                )
            FavoritesFileCodec.encode(
                FavoritesExport.build(favorites, sender::find, sender::isBuiltin, keepKeys = false, appVersion = "0"),
            )
        }

    @After
    fun tearDown() {
        vm.stopTurningLooper()
        root.deleteRecursively()
    }

    @Test
    fun `the sheet says what each favorite brings, and adding stores them without applying`() {
        vm.receive(text, "Ville et 1 autre.toolmap")
        val incoming = vm.incoming.value!!
        assertEquals(setOf(0, 1), incoming.checked)
        val resources = RuntimeEnvironment.getApplication().resources
        val byId = repository.sources.value.associateBy { it.id }
        val (ville, neige) = incoming.plan.items
        assertEquals(
            "Nom déjà pris : ajouté en « Ville (2) » · 1 carte remise dans la liste (OpenStreetMap)",
            statusOf(ville, byId, resources),
        )
        assertEquals(
            "1 carte ajoutée (SnowBase Map) · clé à saisir",
            statusOf(neige, byId + neige.added.associateBy { it.id }, resources),
        )
        vm.add()
        until { vm.keysToType.value.isNotEmpty() }
        assertNull(vm.incoming.value)
        assertEquals(setOf("Ville", "Ville (2)", "Sortie neige"), workspaces.workspaces.value.map { it.name }.toSet())
        assertEquals("", repository.find("SNOW")!!.params["key"])
        assertTrue("osm" !in settings.settings.value.maps.hiddenMaps)
        assertEquals(listOf("SNOW"), vm.keysToType.value.map { it.id })
        vm.setKey(mapOf("key" to "mine-456"))
        until { repository.find("SNOW")!!.params["key"] == "mine-456" }
        assertTrue(vm.keysToType.value.isEmpty())
    }

    @Test
    fun `the sheet counts the ticked favorites, and a broken file is refused`() {
        vm.receive(text, "Ville et 1 autre.toolmap")
        val byId = repository.sources.value.associateBy { it.id }
        rule.setContent {
            val incoming by vm.incoming.collectAsState()
            incoming?.let {
                ReceiveFavoritesContent(
                    incoming = it,
                    byId = byId,
                    thumbnails = MapThumbnails(OkHttpClient(), repository),
                    onToggle = vm::toggle,
                    onAdd = vm::add,
                    onDismiss = vm::dismiss,
                )
            }
        }
        rule.onNodeWithText("Ajouter 2 favoris ?").assertExists()
        rule.onNodeWithText("Ville et 1 autre.toolmap").assertExists()
        rule.onNodeWithText("Rien n'est appliqué : les favoris s'ajoutent à vos puces.").assertExists()
        rule.onNodeWithText("Ajouter 2 favoris").assertExists()
        rule.onNodeWithText("Ville").performClick()
        rule.onNodeWithText("Ajouter 1 favori").assertExists()
        rule.onNodeWithText("Sortie neige").performClick()
        // « favori » or « favoris »: the plural of 0 follows the language of the phone.
        rule.onNodeWithText("Ajouter 0 favori", substring = true).assertIsNotEnabled()
        rule.onNodeWithText("Annuler").performClick()
        assertNull(vm.incoming.value)
        vm.receive("""{"format":1,"app":"ToolMap","kind":"favorites","favorites":[]}""", "vide.toolmap")
        assertNull(vm.incoming.value)
    }

    /** Turns the main looper (where the view model resumes after its disk work) until [done]. */
    private fun until(done: () -> Boolean) {
        val end = System.currentTimeMillis() + TIMEOUT_MS
        while (!done()) {
            check(System.currentTimeMillis() < end) { "Timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L
    }
}
