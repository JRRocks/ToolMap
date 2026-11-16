package fr.toolmap.operation

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import fr.toolmap.core.hierarchy.Post
import fr.toolmap.core.record.Fix
import fr.toolmap.data.hierarchy.OperationController
import fr.toolmap.data.hierarchy.OperationPhase
import fr.toolmap.data.hierarchy.OperationStorage
import fr.toolmap.operation.demo.DemoKeyVault
import fr.toolmap.record.LocationSource
import fr.toolmap.ui.Formats
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.ToolMapTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The screens of the « Opération » mode drawn from the place of each role of the demonstration (P39.8): each shows what
 * its maquette shows, and nothing of what the role may not do. The demonstration is the app's own (22 simulated phones,
 * a room in memory); only the drawing is checked here, the captures of the CI show it on an emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h2400dp")
class OperationScreensTest {
    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private val noGps =
        object : LocationSource {
            override fun fixes(
                minIntervalMillis: Long,
                minDistanceMeters: Float,
            ): Flow<Fix> = emptyFlow()
        }

    private fun session(): OperationSession {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val storage = OperationStorage(temp.newFolder("operations"), DemoKeyVault())
        val controller = OperationController(storage, null, { null })
        val session = OperationSession(context, controller, storage, noGps)
        session.startDemo()
        var waited = 0
        while (session.demo.value == null && waited < DEMO_WAIT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
            waited += POLL_MS.toInt()
        }
        assertNotNull(session.demo.value, "the demonstration is built")
        return session
    }

    private fun OperationSession.show(
        number: Int,
        nav: OperationNav = OperationNav(),
    ) {
        viewAs(number)
        this.nav.value = nav
        shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
    }

    private fun seen(text: String) =
        assertTrue(
            rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty(),
            "« $text » is shown",
        )

    private fun absent(text: String) =
        assertTrue(
            rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty(),
            "« $text » is not shown",
        )

    private fun groupOf(
        session: OperationSession,
        name: String,
    ): Long = requireNotNull(session.demoSeat(1)?.view?.value?.groups?.firstOrNull { it.name == name }).id

    @Test
    fun `each role sees its screens`() {
        val session = session()
        rule.setContent {
            ToolMapTheme {
                CompositionLocalProvider(LocalFormats provides Formats()) { OperationRoot(session, onBack = {}) }
            }
        }
        // The creator: its reminder, the administration, its group (`tour4/08-rappel-A`).
        session.show(1)
        seen("avant de changer de téléphone")
        seen("Transmettre")
        seen("Administrer")
        seen("2 en attente")
        seen("Inviter")
        seen("22 sur 30")
        seen("Mon groupe · Est")
        seen("Toute l'opération")
        seen("Ouest")
        seen("Sans chef")
        // An administrator (`tour3/08-inviter` A): no reminder.
        session.show(5)
        seen("Mon groupe · Nord")
        seen("Administrer")
        absent("avant de changer de téléphone")
        // A member (`tour3/08-inviter-B`): neither « Administrer » nor « Inviter ».
        session.show(7)
        seen("Partager ma position")
        seen("Membre 7 (vous) · Nord")
        absent("Administrer")
        absent("Inviter")
        // Who waits (`tour4/01-attente` A): its lock, no name but its own.
        session.show(21)
        seen("En attente d'un groupe")
        seen("envoyée aux administrateurs")
        seen("C'est le seul moyen de ne plus envoyer sa position.")
        absent("Membre 4")
        absent("Nord")
        // The administration (`tour2/04-admin-sommaire`, `03-attente-admin`, `04-admin-groupe`, `05-admins-createur`).
        session.show(5, OperationNav().open(OpPage.Admin))
        seen("Membre 21, Membre 22 : à placer dans un groupe")
        seen("Chef de l'opération")
        seen("Membre 2 · adjoint : Membre 3")
        seen("Chef : Membre 4 · adjoint : Membre 5")
        session.show(5, OperationNav().open(OpPage.Admin).open(OpPage.WaitingList))
        // « Arrivé il y a 3 minutes » on a phone in French (the JVM of the tests writes the age in English).
        seen("Arrivé ")
        seen("Placer")
        session.show(5, OperationNav().open(OpPage.GroupPage(groupOf(session, "Ouest"))))
        seen("Un groupe sans chef fonctionne")
        seen("Aucun chef nommé")
        // Renaming (P39.13): written at the bottom of the page of a group (N12 A), at the top of a member's sheet (N14 A).
        seen("Renommer le groupe")
        seen("Supprimer le groupe")
        session.show(1, OperationNav().open(OpPage.Admins))
        seen("Vous seul")
        seen("Retirer")
        session.show(5, OperationNav().open(OpPage.Admins))
        seen("Seul le créateur")
        // The sheet of a member: the creator's gestures are locked for an administrator (`tour3/07-remise-attente-fiche`).
        val m11 = requireNotNull(session.demo.value?.ids?.get(11))
        session.show(
            5,
            OperationNav().open(OpPage.GroupPage(groupOf(session, "Sud"))).copy(sheet = OpSheet.Member(m11)),
        )
        seen("Remettre en attente")
        seen("Réservé au créateur (Membre 1)")
        seen("Le nouveau nom vaut pour toute l'opération")
        // Its own name: a member asks (N11 B), an administrator renames itself at once (N2 A).
        session.show(7, OperationNav(sheet = OpSheet.MyName))
        seen("Changer mon nom")
        seen("Envoyer la demande")
        session.show(5, OperationNav(sheet = OpSheet.MyName))
        seen("Vous êtes administrateur")
        absent("Envoyer la demande")
        session.show(5, OperationNav(sheet = OpSheet.Nominate(groupOf(session, "Nord"), Post.DEPUTY)))
        seen("Adjoint du chef de groupe")
        seen("Déjà chef de ce groupe")
        // Leaving (`tour3/09-chef-quitte`, `tour3/05-createur-A`, `tour4/09-glissiere`).
        session.show(4, OperationNav(dialog = OpDialog.Quit))
        seen("Votre adjoint, Membre 5, devient chef automatiquement.")
        session.show(1, OperationNav(dialog = OpDialog.CreatorQuit))
        seen("Désigner un successeur")
        seen("20 membres et 2 personnes en attente")
        seen("Glisser pour dissoudre")
        session.show(1, OperationNav(dialog = OpDialog.CreatorQuit, slide = 0.5f))
        seen("jusqu'au bout")
        session.show(1, OperationNav(sheet = OpSheet.Successor))
        seen("Qui vous succède ?")
        seen("Transmettre à Membre 5 et quitter")
        session.show(1, OperationNav(sheet = OpSheet.Invite))
        seen("22 sur 30 · 20 placés, 2 en attente")
        assertTrue(session.view.value.phase == OperationPhase.PLACED)
    }

    private companion object {
        const val DEMO_WAIT_MS = 120_000
        const val POLL_MS = 100L
    }
}
