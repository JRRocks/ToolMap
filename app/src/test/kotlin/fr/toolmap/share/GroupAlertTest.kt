package fr.toolmap.share

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import fr.toolmap.R
import fr.toolmap.core.p2p.PeerFix
import fr.toolmap.ui.Formats
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.ToolMapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The alert of the group as validated on 2026-10-28 (`docs/previews/p22-alerte.html`, B for the sheet that sends it,
 * C for the rest): no « SOS » left anywhere (P22.1), the sheet held to send, your alert and its confirmed cancel, the
 * dialogs and the banners of an alert received and lifted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h2000dp")
class GroupAlertTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = System.currentTimeMillis()
    private val calls = ArrayList<String>()

    private fun content(block: @Composable () -> Unit) {
        rule.setContent {
            ToolMapTheme {
                CompositionLocalProvider(LocalFormats provides Formats()) { block() }
            }
        }
    }

    private fun main(ui: ShareUi) =
        content {
            MainPage(
                ui = ui,
                snackbar = SnackbarHostState(),
                onBack = {},
                onToggle = {},
                onSos = { calls += "open" },
                onCancelSos = { calls += "cancel" },
                onInvite = {},
                onScan = {},
                onPaste = {},
                onSettings = {},
                onHelp = {},
                onLeave = {},
                onProfile = {},
                onMessages = {},
                onOpenMember = {},
                onShowOnMap = { _, _ -> },
                onAlertBack = {},
            )
        }

    private val alert =
        SosAlert(
            memberId = 3L,
            name = "Galaxy S23",
            fix = PeerFix(45.92384, 6.87112, timeSec = now / 1000 - 20),
            text = "Cheville tordue",
            receivedAtMs = now,
            acknowledged = false,
        )

    @Test
    fun `no text of the app says SOS any more`() {
        val res = ApplicationProvider.getApplicationContext<Application>().resources
        val word = Regex("""\bSOS\b""")
        val said =
            R.string::class.java.fields
                .map { it.name to res.getString(it.getInt(null)) }
                .filter { (_, text) -> word.containsMatchIn(text) }
        assertEquals(emptyList(), said)
        assertTrue(res.getStringArray(R.array.group_alert_directions).none { word.containsMatchIn(it) })
    }

    @Test
    fun `in a group the line under yours opens the sheet, and no SOS shows`() {
        main(ShareSamples.ui(now, inGroup = true))
        rule.onNodeWithText("Envoyer une alerte au groupe").performClick()
        assertEquals(listOf("open"), calls)
        rule.onAllNodesWithText("SOS", substring = true).assertCountEquals(0)
    }

    @Test
    fun `your alert shows how long it repeats and is cancelled only after the confirmation`() {
        main(ShareSamples.ui(now, inGroup = true).copy(ownSos = true, ownSosAtMs = now - 36_000))
        rule.onNodeWithText("Alerte en cours").assertExists()
        rule.onNodeWithText("Répétée encore", substring = true).assertExists()
        rule.onAllNodesWithText("Envoyer une alerte au groupe").assertCountEquals(0)
        rule.onNodeWithText("Annuler l'alerte").performClick()
        rule.onNodeWithText("Annuler l'alerte ?").assertExists()
        rule.onNodeWithText("Le groupe verra « Alerte levée par Pixel 7 ».").assertExists()
        rule.onNodeWithText("Garder l'alerte").performClick()
        assertTrue(calls.isEmpty())
        rule.onNodeWithText("Annuler l'alerte").performClick()
        rule.onAllNodesWithText("Annuler l'alerte").onLast().performClick()
        assertEquals(listOf("cancel"), calls)
        rule.onAllNodesWithText("SOS", substring = true).assertCountEquals(0)
    }

    @Test
    fun `after its two minutes your alert says when it was sent`() {
        val since = now - 5 * 60_000L
        main(ShareSamples.ui(now, inGroup = true).copy(ownSos = true, ownSosAtMs = since))
        val time =
            DateTimeFormatter.ofPattern(
                "HH:mm",
            ).format(Instant.ofEpochMilli(since).atZone(ZoneId.systemDefault()))
        rule.onNodeWithText("Envoyée à $time").assertExists()
    }

    @Test
    fun `the sheet sends the message when held, not on a tap`() {
        val sent = ArrayList<String>()
        content { AlertSendSheet(onSend = { sent += it }, onClose = { calls += "close" }) }
        rule.onNodeWithText("Avec votre dernière position · répétée 2 min · annulable").assertExists()
        rule.onNode(hasSetTextAction()).performTextInput("Cheville tordue")
        rule.onNodeWithText("Maintenez pour envoyer").performClick()
        assertTrue(sent.isEmpty())
        // TalkBack's double tap, and the hold once full, call the same send.
        rule.onNodeWithText("Maintenez pour envoyer").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("Cheville tordue"), sent)
        rule.onNodeWithText("Annuler").performClick()
        assertEquals(listOf("close"), calls)
    }

    @Test
    fun `an alert received gives its message, position, age, distance and direction`() {
        content {
            GroupAlertDialog(
                alert = alert,
                from = PeerFix(45.92080, 6.86800, timeSec = now / 1000),
                onAcknowledge = { calls += "seen" },
                onShowOnMap = { calls += "map" },
            )
        }
        rule.onNodeWithText("Alerte de Galaxy S23").assertExists()
        rule.onNodeWithText("Cheville tordue").assertExists()
        rule.onNodeWithText("au nord-est", substring = true).assertExists()
        rule.onNodeWithText("Dernière position", substring = true).assertExists()
        rule.onNodeWithText("Voir sur la carte").performClick()
        assertEquals(listOf("seen", "map"), calls)
    }

    @Test
    fun `an alert lifted before it was seen says so in its dialog`() {
        val at = now - 3 * 60_000L
        content {
            AlertLiftedDialog(
                SosLifted(3L, "Galaxy S23", now, alertAtMs = at, dialog = true),
                onOk = { calls += "ok" },
            )
        }
        val time = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
        rule.onNodeWithText("Alerte levée").assertExists()
        rule.onNodeWithText("Galaxy S23 a annulé son alerte de $time.").assertExists()
        rule.onNodeWithText("OK").performClick()
        assertEquals(listOf("ok"), calls)
    }

    @Test
    fun `the map shows the alert in red and its lift in white`() {
        val ui =
            ShareSamples.ui(now, inGroup = true).copy(
                sos = listOf(alert),
                sosLifted = listOf(SosLifted(4L, "Pixel 6a", now)),
            )
        content { ShareHud(ui = ui, onOpen = {}, onShowSos = { calls += "show ${it.name}" }, sosOnly = true) }
        rule.onNodeWithText("Alerte de Galaxy S23").performClick()
        rule.onNodeWithText("Alerte levée par Pixel 6a").assertExists()
        assertEquals(listOf("show Galaxy S23"), calls)
    }

    @Test
    fun `a bearing falls in its eighth of the compass`() {
        assertEquals(0, octant(0.0))
        assertEquals(0, octant(359.0))
        assertEquals(1, octant(40.0))
        assertEquals(2, octant(90.0))
        assertEquals(4, octant(180.0))
        assertEquals(7, octant(320.0))
    }
}
