package fr.toolmap.search

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import fr.toolmap.core.search.SearchSource
import fr.toolmap.data.search.GeocodeFailure
import fr.toolmap.data.search.GeocodeOutcome
import fr.toolmap.data.search.ProviderFailure
import fr.toolmap.ui.theme.ToolMapTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * « Recherche » since P29.10 (« Équerre »): the field and its cross, the state in cards under it (« Réessayer » when a
 * service did not answer), the results in groups with their distance on the second line and a ⋮, the credits on one
 * line at the foot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h1200dp")
class SearchScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var samples: SearchSamples

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        samples = SearchSamples()
    }

    @After
    fun tearDown() {
        samples.close()
        Dispatchers.resetMain()
    }

    private fun setContent() {
        rule.setContent { ToolMapTheme { SearchScreen(vm = samples.vm, onBack = {}, onShow = {}, onGoTo = {}) } }
    }

    private fun type(text: String) {
        rule.onNode(hasSetTextAction()).performTextInput(text)
        rule.waitUntil(TIMEOUT_MS) { samples.vm.ui.value.here.isNotEmpty() || text.isEmpty() }
        rule.waitForIdle()
    }

    @Test
    fun `the user's own items come as the text is typed, with their distance on the second line`() {
        setContent()
        rule.onAllNodesWithText("Lieu, coordonnées ou élément").assertCountEquals(1)
        type("Barraux")
        rule.onAllNodesWithText("Sur mon téléphone").assertCountEquals(1)
        rule.onAllNodesWithText("Waypoint · à ", substring = true).assertCountEquals(1)
        rule.onAllNodesWithText("Validez au clavier", substring = true).assertCountEquals(1)
    }

    @Test
    fun `the online results come in their group, the credits on one line at the foot`() {
        setContent()
        type("Barraux")
        samples.vm.submit()
        rule.waitUntil(TIMEOUT_MS) { samples.vm.ui.value.searched }
        rule.onAllNodesWithText("En ligne").assertCountEquals(1)
        rule.onAllNodesWithText("fort, blockhaus, casemate · 38530 Barraux", substring = true).assertCountEquals(1)
        rule.onAllNodesWithText("Village · Le Touvet", substring = true).assertCountEquals(1)
        val credits = "Recherche : © IGN Géoplateforme · © contributeurs OpenStreetMap (ODbL)"
        rule.onAllNodesWithText(credits).assertCountEquals(1)
    }

    @Test
    fun `the menu of a result has its three actions, two for an item of the phone`() {
        setContent()
        type("Barraux")
        rule.onAllNodesWithContentDescription("Actions")[0].performClick()
        rule.onAllNodesWithText("Voir sur la carte").assertCountEquals(1)
        rule.onAllNodesWithText("Aller vers").assertCountEquals(1)
        rule.onAllNodesWithText("Enregistrer comme waypoint").assertCountEquals(0)
    }

    @Test
    fun `a service that did not answer says so in a card, and the retry asks again`() {
        samples.geocoder.outcome =
            GeocodeOutcome(emptyList(), listOf(ProviderFailure(SearchSource.IGN, GeocodeFailure.NETWORK)))
        setContent()
        type("Barraux")
        samples.vm.submit()
        rule.waitUntil(TIMEOUT_MS) { samples.vm.ui.value.searched }
        rule.onAllNodesWithText("IGN Géoplateforme : pas de réponse", substring = true).assertCountEquals(1)
        rule.onNodeWithText("Réessayer").performClick()
        rule.waitUntil(TIMEOUT_MS) { samples.geocoder.asked == 2 }
        assertEquals(2, samples.geocoder.asked)
    }

    @Test
    fun `the cross clears the field`() {
        setContent()
        type("Barraux")
        rule.onNodeWithContentDescription("Effacer").performClick()
        rule.waitForIdle()
        assertEquals("", samples.vm.ui.value.query)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
