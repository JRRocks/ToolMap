package fr.toolmap.multizoom

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomBand
import fr.toolmap.core.source.ZoomSet
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.testing.stopTurningLooper
import fr.toolmap.ui.theme.ToolMapTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals

/**
 * The multi-zoom editor since P29.6 (« Bascule »): « Ce qui s'affiche », one line per band and one per zooms without a
 * band with « Combler », a sheet per band with « Supprimer la tranche ».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h1600dp")
class MultiZoomScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val root: File = createTempDirectory("multizoom-screen").toFile()

    private fun source(
        id: String,
        name: String,
    ) = TileSource(
        id = id,
        name = name,
        levels = listOf(LevelDef(ZoomSet.range(0, 19), listOf("https://t/$id/{\$z}/{\$x}/{\$y}.png"))),
    )

    private val repository =
        SourceRepository(File(root, "sources"), listOf(source("ign", "IGN Cartes"), source("osm", "OpenStreetMap")))
    private val vm = MultiZoomViewModel(repository)

    @After
    fun tearDown() {
        vm.stopTurningLooper()
        root.deleteRecursively()
    }

    private fun editor() {
        vm.startNew("Rando")
        vm.setRange(0, 1, 8)
        vm.addBand()
        vm.setRange(1, 12, 16)
        vm.setMap(1, "osm")
        rule.setContent { ToolMapTheme { MultiZoomScreen(vm = vm, onBack = {}, onShow = {}, editorOnly = true) } }
    }

    @Test
    fun `each band is a line, the zooms without one have Combler`() {
        editor()
        rule.onNodeWithText("Ce qui s'affiche").assertExists()
        rule.onNodeWithText("Zooms 1 à 8").assertExists()
        rule.onNodeWithText("Zooms 12 à 16").assertExists()
        rule.onNodeWithText("Zooms 9 à 11").assertExists()
        rule.onNodeWithText("Aucune tranche : rempli par la tranche voisine").assertExists()
    }

    @Test
    fun `Combler makes the band with the map below and opens its sheet, which removes it`() {
        editor()
        rule.onNodeWithText("Combler").performClick()
        rule.waitForIdle()
        assertEquals(ZoomBand(9, 11, "ign"), vm.ui.value.editor?.draft?.bands?.last())
        rule.onAllNodesWithText("Combler").assertCountEquals(0)
        rule.onNodeWithText("Supprimer la tranche").performClick()
        rule.waitForIdle()
        assertEquals(2, vm.ui.value.editor?.draft?.bands?.size)
        rule.onNodeWithText("Combler").assertExists()
    }
}
