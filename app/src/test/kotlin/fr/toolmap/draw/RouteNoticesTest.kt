package fr.toolmap.draw

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.toolmap.core.route.RoutingError
import fr.toolmap.ui.theme.ToolMapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The messages of BRouter's errors (`docs/specs/routing.md` §3): one leg by hand, or the whole route by hand. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RouteNoticesTest {
    @get:Rule
    val rule = createComposeRule()

    private val resources get() = RuntimeEnvironment.getApplication().resources

    private fun text(notice: RouteNotice): String = RouteNoticeVisuals.of(resources, notice).message

    @Test
    fun `missing data, a missing app or profile all say there is no BRouter data here`() {
        val expected = "Pas de données BRouter ici : tronçon tracé à la main."
        listOf(RoutingError.NO_DATA, RoutingError.NOT_INSTALLED, RoutingError.UNKNOWN_PROFILE).forEach {
            assertEquals(expected, text(RouteNotice.LegByHand(it)))
        }
    }

    @Test
    fun `each error has its message, for one leg and for the whole route`() {
        assertEquals("Calcul trop long : tronçon tracé à la main.", text(RouteNotice.LegByHand(RoutingError.TIMEOUT)))
        assertEquals(
            "Dessin à la main activé : BRouter ne trouve pas de chemin ici.",
            text(RouteNotice.AllByHand(RoutingError.NO_PATH)),
        )
        val leg = RoutingError.entries.map { text(RouteNotice.LegByHand(it)) }.toSet()
        val all = RoutingError.entries.map { text(RouteNotice.AllByHand(it)) }.toSet()
        // NO_DATA, NOT_INSTALLED and UNKNOWN_PROFILE share one message: 4 messages each, none in common.
        assertEquals(4, leg.size)
        assertEquals(4, all.size)
        assertEquals(emptySet(), leg intersect all)
    }

    @Test
    fun `an unknown profile names it`() {
        assertEquals("Profil « hiking-beta » absent de BRouter.", text(RouteNotice.UnknownProfile("hiking-beta")))
    }

    @Test
    fun `the message of one leg offers to draw all by hand, both offer to open BRouter`() {
        var allByHand = 0
        var opened = 0
        var visuals by mutableStateOf(RouteNoticeVisuals.of(resources, RouteNotice.LegByHand(RoutingError.NO_DATA)))
        rule.setContent {
            ToolMapTheme {
                RouteNoticeSnackbar(visuals, onAllByHand = { allByHand++ }, onOpenBRouter = { opened++ })
            }
        }
        rule.onNodeWithText("Pas de données BRouter ici : tronçon tracé à la main.").assertExists()
        rule.onNodeWithText("Tout dessiner à la main").performClick()
        rule.onNodeWithText("Ouvrir BRouter").performClick()
        assertEquals(1, allByHand)
        assertEquals(1, opened)

        visuals = RouteNoticeVisuals.of(resources, RouteNotice.AllByHand(RoutingError.NO_DATA))
        rule.onAllNodesWithText("Tout dessiner à la main").assertCountEquals(0)
        rule.onNodeWithText("Ouvrir BRouter").assertExists()
    }
}
