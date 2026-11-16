package fr.toolmap.operation

import android.util.Log
import fr.toolmap.core.hierarchy.Post
import kotlinx.coroutines.delay
import java.io.PrintWriter

/**
 * The screens of the « Opération » mode for the captures of the CI (P39.8, `tools/emulator/operation-screens.sh`),
 * asked by `adb shell dumpsys activity fr.toolmap/.MainActivity operation-screens <command>` (debug build type only;
 * only `adb` and the system may make an activity dump):
 *
 * - `demo`: builds the demonstration; « operation-screens demo ready » in logcat when done;
 * - `list`: the names of the scenes, in the order to take them (the last ones change the demonstration);
 * - `show <name>`: shows that scene; « operation-screens shown <name> » in logcat when it is on the screen.
 *
 * A scene is named after the maquette it is to be compared with (`tour4-10-creation-A` for
 * `docs/previews/p39-groupes/tour4/img/10-creation.png`, variant A).
 */
object OperationScenes {
    const val DUMP_ARGUMENT = "operation-screens"

    private class Scene(
        val name: String,
        /** It changes the demonstration (somebody leaves, a group goes): taken after the others. */
        val changes: Boolean = false,
        val show: suspend OperationSession.() -> Boolean,
    )

    private val GROUP_ORDER = listOf("Nord", "Sud", "Est", "Ouest")

    private fun OperationSession.group(name: String): Long? {
        val v = view.value
        return v.groups.firstOrNull { it.name == name }?.id
    }

    private fun OperationSession.member(number: Int): Long? = demo.value?.ids?.get(number)

    /** Shows the operation from the phone of Membre [number], at [nav]. */
    private fun OperationSession.at(
        number: Int,
        nav: (OperationSession) -> OperationNav = { OperationNav() },
    ): Boolean {
        if (demoSeat(number) == null) return false
        viewAs(number)
        this.nav.value = nav(this)
        request(OperationRequest.SHARE)
        return true
    }

    private fun OperationSession.entry(): Boolean {
        viewReal()
        request(OperationRequest.SHARE)
        return true
    }

    private fun page(vararg pages: OpPage) = OperationNav(pages = listOf(OpPage.Main) + pages)

    private fun sudPage(s: OperationSession) = page(OpPage.Admin, OpPage.GroupPage(s.groupOf("Sud") ?: 0L))

    private val SCENES: List<Scene> =
        listOf(
            // Entry and creation.
            Scene("tour2-01-entree-A") { entry() },
            Scene("tour4-10-creation-A") {
                viewReal()
                createForm.value = CreateForm(name = "Exercice Vallon", groups = GROUP_ORDER.take(3), myGroup = 2)
                request(OperationRequest.CREATE)
                true
            },
            Scene("tour4-10-creation-A-nouveau-groupe") {
                viewReal()
                createForm.value =
                    CreateForm(
                        name = "Exercice Vallon",
                        groups = GROUP_ORDER.take(3),
                        myGroup = 2,
                        editing = CreateForm.ADDING,
                    )
                request(OperationRequest.CREATE)
                true
            },
            // The screen of the operation.
            Scene("tour4-08-rappel-A") { at(1) },
            Scene("tour4-08-rappel-A-feuille") { at(1) { OperationNav(sheet = OpSheet.Transfer) } },
            Scene("tour3-08-inviter-A") { at(5) },
            Scene("tour3-08-inviter-invitation") {
                at(5) { OperationNav(sheet = OpSheet.Invite) }.also { loadInvitationLater() }
            },
            Scene("tour3-08-inviter-B-membre") { at(7) },
            Scene("tour3-12-noms-liste-A") { at(7) { s -> page(OpPage.GroupList(s.groupOf("Sud") ?: 0L)) } },
            Scene("tour4-08-rappel-B-menu") { at(1) { OperationNav(menu = true) } },
            // Arrival and wait.
            Scene("tour3-04-entree-nom") { joinScene(name = null) },
            Scene("tour3-04-entree-nom-etats") { joinScene(name = "") },
            Scene("tour2-04-admin-limites-31e") {
                entry()
                full.value = "Exercice Vallon"
                true
            },
            Scene("tour4-01-attente-A") { at(21) },
            // Administration.
            Scene("tour2-04-admin-sommaire") { at(5) { page(OpPage.Admin) } },
            Scene("tour2-03-attente-admin") { at(5) { page(OpPage.Admin, OpPage.WaitingList) } },
            Scene("tour2-03-attente-admin-placer") {
                at(5) { s -> page(OpPage.Admin, OpPage.WaitingList).copy(sheet = s.member(21)?.let(OpSheet::Place)) }
            },
            Scene("tour2-04-admin-groupe") {
                at(5) { s -> page(OpPage.Admin, OpPage.GroupPage(s.groupOf("Nord") ?: 0L)) }
            },
            Scene("tour2-04-admin-groupe-ajouter") {
                at(5) { s ->
                    val nord = s.groupOf("Nord") ?: 0L
                    page(OpPage.Admin, OpPage.GroupPage(nord)).copy(sheet = OpSheet.AddMembers(nord))
                }
            },
            Scene("tour2-04-admin-chef") { at(5) { page(OpPage.Admin, OpPage.OperationChief) } },
            Scene("tour2-04-admin-chef-adjoint") {
                at(5) { s ->
                    val nord = s.groupOf("Nord") ?: 0L
                    page(OpPage.Admin, OpPage.GroupPage(nord)).copy(sheet = OpSheet.Nominate(nord, Post.DEPUTY))
                }
            },
            Scene("tour2-04-admin-groupes-nouveau") { at(5) { page(OpPage.Admin).copy(sheet = OpSheet.NewGroup) } },
            Scene("tour2-04-admin-groupes-modifier") {
                at(5) { s ->
                    val ouest = s.groupOf("Ouest") ?: 0L
                    page(OpPage.Admin, OpPage.GroupPage(ouest)).copy(sheet = OpSheet.EditGroup(ouest))
                }
            },
            Scene("tour2-04-admin-sans-chef") {
                at(5) { s ->
                    page(
                        OpPage.Admin,
                        OpPage.GroupPage(s.groupOf("Ouest") ?: 0L),
                    )
                }
            },
            Scene("tour2-05-admins-createur") { at(1) { page(OpPage.Admin, OpPage.Admins) } },
            Scene("tour2-05-admins-createur-nommer") {
                at(1) { page(OpPage.Admin, OpPage.Admins).copy(sheet = OpSheet.NameAdmin) }
            },
            Scene("tour2-05-admins-retirer") {
                at(1) { s -> page(OpPage.Admin, OpPage.Admins).copy(dialog = s.member(5)?.let(OpDialog::RemoveAdmin)) }
            },
            Scene("tour2-05-admins-retirer-administrateur") { at(5) { page(OpPage.Admin, OpPage.Admins) } },
            Scene("tour2-05-retirer-membre") {
                at(1) { s ->
                    page(
                        OpPage.Admin,
                        OpPage.GroupPage(s.groupOf("Sud") ?: 0L),
                    ).copy(sheet = s.member(11)?.let(OpSheet::Member))
                }
            },
            Scene("tour3-07-remise-attente-fiche") {
                at(5) { s ->
                    page(
                        OpPage.Admin,
                        OpPage.GroupPage(s.groupOf("Sud") ?: 0L),
                    ).copy(sheet = s.member(11)?.let(OpSheet::Member))
                }
            },
            Scene("tour3-07-remise-attente-adjoint") {
                at(5) { s ->
                    page(
                        OpPage.Admin,
                        OpPage.GroupPage(s.groupOf("Sud") ?: 0L),
                    ).copy(sheet = s.member(10)?.let(OpSheet::Member))
                }
            },
            Scene("tour3-07-remise-attente-confirmation") {
                at(5) { s ->
                    page(
                        OpPage.Admin,
                        OpPage.GroupPage(s.groupOf("Sud") ?: 0L),
                    ).copy(dialog = s.member(11)?.let(OpDialog::Unassign))
                }
            },
            Scene("tour3-07-remise-attente-adjoint-confirmation") {
                at(5) { s ->
                    page(
                        OpPage.Admin,
                        OpPage.GroupPage(s.groupOf("Sud") ?: 0L),
                    ).copy(dialog = s.member(10)?.let(OpDialog::Unassign))
                }
            },
            Scene("tour2-05-retirer-confirmation") {
                at(1) { s ->
                    page(
                        OpPage.Admin,
                        OpPage.GroupPage(s.groupOf("Sud") ?: 0L),
                    ).copy(dialog = s.member(11)?.let(OpDialog::RemoveMember))
                }
            },
            Scene("tour3-06-supprimer-groupe") {
                at(5) { s ->
                    val ouest = s.groupOf("Ouest") ?: 0L
                    page(OpPage.Admin, OpPage.GroupPage(ouest)).copy(dialog = OpDialog.DeleteGroup(ouest))
                }
            },
            // Renaming (P39.13, `tour5`).
            Scene("tour5-02-membre-A") { at(5) { s -> sudPage(s).copy(sheet = s.member(11)?.let(OpSheet::Member)) } },
            Scene("tour5-02-membre-feuille") {
                at(5) { s -> sudPage(s).copy(sheet = s.member(11)?.let(OpSheet::RenameMember)) }
            },
            Scene("tour5-02-groupe-A") {
                at(5) { s -> page(OpPage.Admin, OpPage.GroupPage(s.groupOf("Ouest") ?: 0L)) }
            },
            Scene("tour5-02-groupe-B-feuille") {
                at(5) { s ->
                    val ouest = s.groupOf("Ouest") ?: 0L
                    page(OpPage.Admin, OpPage.GroupPage(ouest)).copy(sheet = OpSheet.EditGroup(ouest))
                }
            },
            Scene("tour5-02-demande-B") { at(7) },
            Scene("tour5-02-demande-feuille") { at(7) { OperationNav(sheet = OpSheet.MyName) } },
            Scene("tour5-02-demande-B-admin") { at(5) { OperationNav(sheet = OpSheet.MyName) } },
            Scene("tour5-02-operation-A") { at(1) { OperationNav(menu = true) } },
            Scene("tour5-02-operation-feuille") { at(1) { OperationNav(sheet = OpSheet.RenameOperation) } },
            Scene("tour5-04-attente-nom") { at(21) { OperationNav(sheet = OpSheet.MyName) } },
            // Leaving.
            Scene("tour4-11-quitter-texte-membre") { at(7) { OperationNav(dialog = OpDialog.Quit) } },
            Scene("tour3-09-chef-quitte") { at(4) { OperationNav(dialog = OpDialog.Quit) } },
            Scene("tour3-09-chef-operation-quitte") { at(2) { OperationNav(dialog = OpDialog.Quit) } },
            Scene("tour3-05-createur-A") { at(1) { OperationNav(dialog = OpDialog.CreatorQuit) } },
            Scene("tour4-09-glissiere") { at(1) { OperationNav(dialog = OpDialog.CreatorQuit, slide = SLIDING) } },
            Scene("tour3-05-createur-A-au-bout") { at(1) { OperationNav(dialog = OpDialog.CreatorQuit, slide = 1f) } },
            Scene("tour3-05-successeur") { at(1) { OperationNav(sheet = OpSheet.Successor) } },
            // What changes the demonstration, in this order.
            Scene("tour3-06-supprimer-groupe-apres", changes = true) {
                forgetNotices(5, 18)
                val ouest = demoSeat(5)?.view?.value?.groups?.firstOrNull { it.name == "Ouest" }?.id
                if (ouest != null) demoSeat(5)?.deleteGroup(ouest)
                waitFor { noticesOf(18).any { it is Notice.GroupDeleted } } && at(5) { page(OpPage.Admin) }
            },
            Scene("tour3-06-supprimer-groupe-apres-membre", changes = true) { at(18) },
            Scene("tour3-07-remise-attente-confirmation-membre", changes = true) {
                forgetNotices(11)
                member(11)?.let { demoSeat(5)?.unassign(it) }
                waitFor { noticesOf(11).any { it is Notice.Unassigned } } && at(11)
            },
            Scene("tour3-09-promotion-adjoint", changes = true) {
                forgetNotices(5, 7)
                demoSeat(4)?.leave()
                val told =
                    waitFor {
                        noticesOf(5).any { it is Notice.GroupChief } &&
                            noticesOf(7).any { it is Notice.NewGroupChief }
                    }
                told && at(5)
            },
            Scene("tour4-05-promotion-groupe", changes = true) { at(7) },
            Scene("tour3-09-promotion-operation", changes = true) {
                forgetNotices(3, 7)
                demoSeat(2)?.leave()
                val told =
                    waitFor {
                        noticesOf(3).any { it is Notice.OperationChief } &&
                            noticesOf(7).any { it is Notice.NewOperationChief }
                    }
                told && at(3)
            },
            Scene("tour4-05-promotion-operation", changes = true) { at(7) },
            Scene("tour2-05-retirer-confirmation-retire", changes = true) {
                forgetNotices(13)
                member(13)?.let { demoSeat(1)?.removeMember(it) }
                val told = waitFor { noticesOf(13).any { it is Notice.Removed } }
                viewAs(13)
                request(OperationRequest.SHARE)
                told
            },
            Scene("tour2-08-successeur", changes = true) {
                forgetNotices(5, 7)
                member(5)?.let { demoSeat(1)?.handOverAndLeave(it) }
                val told =
                    waitFor {
                        noticesOf(5).any { it is Notice.Creator } &&
                            noticesOf(7).any { it is Notice.NewCreator }
                    }
                told && at(5)
            },
            Scene("tour2-08-successeur-autres", changes = true) { at(7) },
            Scene("tour5-02-attente-A", changes = true) {
                val seven = member(7)
                demoSeat(7)?.requestName("Guide 7")
                val seen = {
                    demoSeat(5)?.view?.value?.nameRequests.orEmpty().any { it.member == seven && it.requested != null }
                }
                waitFor(seen) && at(7)
            },
            Scene("tour5-02-admin-A", changes = true) { at(5) },
            Scene("tour5-02-admin-A-administrer", changes = true) { at(5) { page(OpPage.Admin) } },
            Scene("tour5-02-admin-valider-A", changes = true) { at(5) { page(OpPage.Admin, OpPage.NameRequests) } },
            Scene("tour5-04-attente-nom-demande", changes = true) {
                demoSeat(21)?.requestName("Pierre D.")
                waitFor { demoSeat(21)?.view?.value?.nameRequests?.isNotEmpty() == true } && at(21)
            },
            Scene("tour5-02-attente-accepte", changes = true) {
                forgetNotices(7)
                member(7)?.let { demoSeat(5)?.answerName(it, accept = true) }
                waitFor { noticesOf(7).any { it is Notice.NameAccepted } } && at(7)
            },
            Scene("tour2-08-dissolution-autres", changes = true) {
                forgetNotices(7)
                demoSeat(5)?.dissolve()
                val told = waitFor { noticesOf(7).any { it is Notice.Dissolved } }
                viewAs(7)
                request(OperationRequest.SHARE)
                told
            },
        )

    private const val SLIDING = 0.45f
    private const val SETTLE_MS = 900L
    private const val WAIT_MS = 30_000L
    private const val POLL_MS = 100L

    private suspend fun OperationSession.joinScene(name: String?): Boolean {
        val link = demoSeat(1)?.invitationLink() ?: return false
        entry()
        if (!onCode(link)) return false
        join.value = join.value?.copy(name = name)
        return true
    }

    private fun OperationSession.loadInvitationLater() = sheet(OpSheet.Invite)

    private fun OperationSession.groupOf(name: String): Long? =
        demoSeat(1)?.view?.value?.groups?.firstOrNull { it.name == name }?.id ?: group(name)

    private suspend fun waitFor(condition: () -> Boolean): Boolean {
        var waited = 0L
        while (!condition()) {
            if (waited >= WAIT_MS) return false
            delay(POLL_MS)
            waited += POLL_MS
        }
        return true
    }

    /** The commands of `dumpsys … operation-screens`; `true` when [args] were for it. */
    fun dump(
        session: OperationSession,
        args: Array<out String>,
        writer: PrintWriter,
    ): Boolean {
        val at = args.indexOf(DUMP_ARGUMENT)
        if (at < 0) return false
        when (args.getOrNull(at + 1)) {
            "demo" -> {
                session.startDemo()
                writer.println("$DUMP_ARGUMENT demo started")
            }

            "list" -> {
                for (scene in SCENES) {
                    writer.println(
                        "$DUMP_ARGUMENT scene ${scene.name}${if (scene.changes) " changes" else ""}",
                    )
                }
            }

            "show" -> {
                val name = args.getOrNull(at + 2)
                val scene = SCENES.firstOrNull { it.name == name }
                if (scene == null) {
                    writer.println("$DUMP_ARGUMENT unknown $name")
                } else {
                    writer.println("$DUMP_ARGUMENT show ${scene.name} started")
                    session.run {
                        session.closeWindows()
                        val shown = scene.show(session)
                        delay(SETTLE_MS)
                        val phase = session.view.value.phase
                        Log.i(
                            OperationSession.LOG_TAG,
                            "$DUMP_ARGUMENT ${if (shown) "shown" else "failed"} ${scene.name} phase=$phase",
                        )
                    }
                }
            }

            else -> {
                writer.println("$DUMP_ARGUMENT commands: demo, list, show <scene>")
            }
        }
        return true
    }

    /** For the tests: the names of the scenes, those that change the demonstration last. */
    internal val names: List<String> get() = SCENES.map { it.name }

    internal val changing: Set<String> get() = SCENES.filter { it.changes }.mapTo(HashSet()) { it.name }
}
