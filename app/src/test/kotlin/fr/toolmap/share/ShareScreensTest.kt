package fr.toolmap.share

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.toolmap.core.p2p.PeerState
import fr.toolmap.ui.Formats
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.ToolMapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The sharing screen in « Cordée » (P29.8): your line first, out of a group and in one; the others with a map button;
 * the line of the messages; the alert line under yours (P22) and the rest in the ⋮; the sheet of a member with its
 * confirmation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h2000dp")
class ShareScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = System.currentTimeMillis()
    private val calls = ArrayList<String>()

    private fun main(inGroup: Boolean) {
        rule.setContent {
            ToolMapTheme {
                CompositionLocalProvider(LocalFormats provides Formats()) {
                    MainPage(
                        ui = ShareSamples.ui(now, inGroup),
                        snackbar = SnackbarHostState(),
                        onBack = {},
                        onToggle = { calls += "toggle $it" },
                        onSos = { calls += "sos" },
                        onCancelSos = { calls += "cancel" },
                        onInvite = { calls += "invite" },
                        onScan = { calls += "scan" },
                        onPaste = { calls += "paste" },
                        onSettings = { calls += "settings" },
                        onHelp = { calls += "help" },
                        onLeave = { calls += "leave" },
                        onProfile = { calls += "profile" },
                        onMessages = { calls += "messages" },
                        onOpenMember = { calls += "member ${it.name}" },
                        onShowOnMap = { _, _ -> calls += "map" },
                        onAlertBack = {},
                    )
                }
            }
        }
    }

    @Test
    fun `out of a group, your line first, then the empty state, create and the two ways to join`() {
        main(inGroup = false)
        rule.onNodeWithText("Pixel 7").assertExists()
        rule.onNodeWithText("Aucun groupe").assertExists()
        rule.onNodeWithText("Ce que les autres voient").performClick()
        rule.onNodeWithText("Créer un groupe et inviter").performClick()
        rule.onNodeWithText("Rejoindre un groupe").assertExists()
        rule.onNodeWithText("Scanner un code").performClick()
        rule.onNodeWithText("Coller un lien").performClick()
        assertEquals(listOf("profile", "invite", "scan", "paste"), calls)
        rule.onAllNodesWithText("Envoyer une alerte au groupe").assertCountEquals(0)
    }

    @Test
    fun `out of a group the menu holds the settings and the help`() {
        main(inGroup = false)
        rule.onNodeWithContentDescription("Plus d'options du partage").performClick()
        rule.onNodeWithText("Réglages du partage").performClick()
        rule.onNodeWithContentDescription("Plus d'options du partage").performClick()
        rule.onNodeWithText("Aide").performClick()
        rule.onAllNodesWithText("Quitter le groupe").assertCountEquals(0)
        assertEquals(listOf("settings", "help"), calls)
    }

    @Test
    fun `in a group your line sends, the others have a map button, the messages count the unread`() {
        main(inGroup = true)
        rule.onNodeWithText("Partager ma position").assertExists()
        rule.onNodeWithText("Pixel 7 (vous) · En ligne").assertExists()
        rule.onNodeWithText("Partage actif · 1 connecté(s)").assertExists()
        rule.onNodeWithText("Économiseur de batterie activé").assertExists()
        rule.onNodeWithText("Serveur joignable").assertExists()
        rule.onNodeWithContentDescription("Voir Galaxy A54 sur la carte").performClick()
        rule.onNodeWithText("Galaxy A54").performClick()
        rule.onNodeWithText("Galaxy A54 : On attend au Pont du Furet").assertExists()
        rule.onNodeWithContentDescription("1 message non lu").assertExists()
        rule.onNodeWithText("Messages").performClick()
        rule.onNodeWithText("Inviter", useUnmergedTree = true).performClick()
        assertEquals(listOf("map", "member Galaxy A54", "messages", "invite"), calls)
    }

    @Test
    fun `in a group the alert line is under yours and scan, paste, settings, help and leave in the menu`() {
        main(inGroup = true)
        rule.onNodeWithText("Envoyer une alerte au groupe").performClick()
        rule.onNodeWithContentDescription("Plus d'options du partage").performClick()
        listOf("Scanner un code", "Coller un lien", "Réglages du partage", "Aide").forEach {
            rule.onNodeWithText(it).assertExists()
        }
        rule.onNodeWithText("Quitter le groupe").performClick()
        assertEquals(listOf("sos", "leave"), calls)
    }

    @Test
    fun `the sheet of a member gives four figures, three tiles and removes only after a confirmation`() {
        var removed = 0
        val member = ShareSamples.a54(now)
        rule.setContent {
            ToolMapTheme {
                CompositionLocalProvider(LocalFormats provides Formats()) {
                    MemberSheet(
                        member = member,
                        state = PeerState.of(member, now),
                        onShowOnMap = {},
                        onGoTo = {},
                        onSaveTrail = {},
                        onRemove = { removed++ },
                        onDismiss = {},
                    )
                }
            }
        }
        listOf("Altitude", "Vitesse", "Batterie", "Précision", "274 m", "±6 m").forEach {
            rule.onNodeWithText(it).assertExists()
        }
        listOf("Voir sur la carte", "Aller vers ce membre", "Enregistrer sa trace").forEach {
            rule.onNodeWithText(it).assertExists()
        }
        rule.onNodeWithContentDescription("Actions sur Galaxy A54").performClick()
        rule.onNodeWithText("Retirer du groupe").performClick()
        assertEquals(0, removed)
        rule.onNodeWithText("Retirer « Galaxy A54 » du groupe ?").assertExists()
        rule.onAllNodesWithText("Retirer du groupe")[0].performClick()
        assertEquals(1, removed)
    }

    @Test
    fun `your own sheet has neither Aller vers ce membre nor a menu`() {
        val self = ShareSamples.ui(now, inGroup = true).self!!
        rule.setContent {
            ToolMapTheme {
                CompositionLocalProvider(LocalFormats provides Formats()) {
                    MemberSheet(self, PeerState.of(self, now), {}, null, {}, {}, {})
                }
            }
        }
        rule.onNodeWithText("Pixel 7 (vous)").assertExists()
        rule.onAllNodesWithText("Aller vers ce membre").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Actions sur Pixel 7").assertCountEquals(0)
        rule.onNodeWithText("Enregistrer sa trace").assertIsNotEnabled()
    }
}
