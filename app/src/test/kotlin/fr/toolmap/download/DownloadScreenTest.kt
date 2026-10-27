package fr.toolmap.download

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.toolmap.core.download.ZoneStatus
import fr.toolmap.ui.theme.ToolMapTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.NumberFormat
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * « Téléchargements » since P29.7 (« Poids »): the job going first in large, « En pause » with the failures, « Terminés »,
 * the counts in French with the maps and zooms, « Nouvelle zone », « Retirer » with « Annuler ».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h2000dp")
class DownloadScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val fixture = DownloadFixture()
    private var zoneAsked = 0

    @After
    fun tearDown() = fixture.close()

    private fun setContent() {
        fixture.fill()
        rule.setContent {
            ToolMapTheme { DownloadScreen(vm = fixture.vm, onBack = {}, onSelectZone = { zoneAsked++ }) }
        }
    }

    private fun french(value: Long) = NumberFormat.getIntegerInstance(Locale.FRENCH).format(value)

    @Test
    fun `the job going comes first, in large, with its maps and zooms`() {
        setContent()
        rule.onNodeWithText("Téléchargements").assertExists()
        rule.onNodeWithText("51 %").assertExists()
        rule.onNodeWithText("IGN Cartes, OpenTopoMap · zooms jusqu'à 16").assertExists()
        rule.onNodeWithText("En cours · 228 / 446 tuiles", substring = true).assertExists()
        val top = { text: String -> rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(true, top("Zone du 27 oct. 2026 à 14:05") < top("En pause"))
        assertEquals(true, top("En pause") < top("Terminés"))
    }

    @Test
    fun `paused and failed jobs are under En pause, the counts in French, no error count at zero`() {
        setContent()
        rule.onNodeWithText("${french(310)} / ${french(1_450)} tuiles", substring = true).assertExists()
        rule.onNodeWithText("IGN SCAN 25 Touristique", substring = true).assertExists()
        rule.onNodeWithText("Carte SD absente", substring = true).assertExists()
        rule.onNodeWithText("${french(2_380)} tuiles", substring = true).assertExists()
        rule.onNodeWithText("le 25 oct.", substring = true).assertExists()
        rule.onAllNodesWithText("erreur", substring = true).assertCountEquals(0)
    }

    @Test
    fun `Nouvelle zone opens the selection`() {
        setContent()
        rule.onNodeWithText("Nouvelle zone", useUnmergedTree = true).performClick()
        assertEquals(1, zoneAsked)
    }

    @Test
    fun `Retirer forgets the job and Annuler brings it back, queued again`() {
        setContent()
        rule.onNodeWithText("Retirer").performClick()
        rule.waitForIdle()
        assertNull(fixture.jobs.get("going"))
        rule.onNodeWithText("Les tuiles restent sur le téléphone", substring = true).assertExists()
        rule.onNodeWithText("Annuler").performClick()
        rule.waitForIdle()
        assertEquals(ZoneStatus.QUEUED, fixture.jobs.get("going")?.status)
    }
}
