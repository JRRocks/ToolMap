package fr.toolmap.operation

import fr.toolmap.core.hierarchy.Post
import fr.toolmap.data.hierarchy.Ending
import fr.toolmap.data.hierarchy.OperationPhase
import fr.toolmap.data.hierarchy.OperationView

/** A page of the screen of the operation; Back goes to the one under it. */
sealed interface OpPage {
    /** The screen of the operation, or the one of the wait (`tour3/08-inviter` A, `tour4/01-attente` A). */
    data object Main : OpPage

    /** « Administrer » (`tour2/04-admin-sommaire`). */
    data object Admin : OpPage

    /** « En attente » (`tour2/03-attente-admin`). */
    data object WaitingList : OpPage

    /** The page of a group for an administrator (`tour2/04-admin-groupe`). */
    data class GroupPage(
        val group: Long,
    ) : OpPage

    /** The page of the role « Chef de l'opération » (`tour2/04-admin-chef`). */
    data object OperationChief : OpPage

    /** « Administrateurs » (`tour2/05-admins-createur`). */
    data object Admins : OpPage

    /** The list of another group (`tour3/12-noms-liste` A). */
    data class GroupList(
        val group: Long,
    ) : OpPage
}

/** A sheet over the page. */
sealed interface OpSheet {
    data object Invite : OpSheet

    data object Transfer : OpSheet

    data class Place(
        val member: Long,
    ) : OpSheet

    data class AddMembers(
        val group: Long,
    ) : OpSheet

    /** Who holds [post] of [unit] (a group, or the operation: [fr.toolmap.core.hierarchy.HierarchyState.ROOT]). */
    data class Nominate(
        val unit: Long,
        val post: Post,
    ) : OpSheet

    data object NameAdmin : OpSheet

    data class Member(
        val member: Long,
    ) : OpSheet

    /** « Nommer chef ou adjoint » of [member], in its group. */
    data class PostChoice(
        val member: Long,
    ) : OpSheet

    data object NewGroup : OpSheet

    data class EditGroup(
        val group: Long,
    ) : OpSheet

    data object Successor : OpSheet
}

/** A question in a window. */
sealed interface OpDialog {
    data class RemoveAdmin(
        val member: Long,
    ) : OpDialog

    data class RemoveMember(
        val member: Long,
    ) : OpDialog

    data class Unassign(
        val member: Long,
    ) : OpDialog

    data class DeleteGroup(
        val group: Long,
    ) : OpDialog

    data object Quit : OpDialog

    data object CreatorQuit : OpDialog
}

/**
 * Where the screens of the operation are: the pages (the last one shown), a sheet, a window. Kept by the session, not
 * by the composition: it outlives a turned screen, and the captures of the CI set it ([OperationScenes]).
 */
data class OperationNav(
    val pages: List<OpPage> = listOf(OpPage.Main),
    val sheet: OpSheet? = null,
    val dialog: OpDialog? = null,
    /** The ⋮ menu of the screen of the operation is open. */
    val menu: Boolean = false,
    /** Where the thumb of « Glisser pour dissoudre » is held, 0 to 1 (a capture); `null`: where the finger puts it. */
    val slide: Float? = null,
) {
    val page: OpPage get() = pages.last()

    fun open(next: OpPage): OperationNav = copy(pages = pages + next, sheet = null, dialog = null, menu = false)

    /** Back: the window, then the sheet, then the page; `null` when the screen itself closes. */
    fun back(): OperationNav? =
        when {
            dialog != null -> copy(dialog = null, slide = null)
            sheet != null -> copy(sheet = null)
            menu -> copy(menu = false)
            pages.size > 1 -> copy(pages = pages.dropLast(1))
            else -> null
        }
}

/** The form of « Nouvelle opération » (`tour4/10-creation` A). */
data class CreateForm(
    val name: String = "",
    /** The groups to make, by their names (« Nord » is written « Groupe Nord »). */
    val groups: List<String> = emptyList(),
    /** The index in [groups] of the creator's own group; `null`: « Aucun » (Q9). */
    val myGroup: Int? = null,
    /** The sheet « Nouveau groupe » is open (`-1`), or the one that renames the group of that index. */
    val editing: Int? = null,
    val busy: Boolean = false,
) {
    companion object {
        const val ADDING = -1
    }
}

/** « Rejoindre l'opération ? » (`tour3/04-entree-nom`): the invitation, the name of its operation, the name typed. */
data class JoinQuestion(
    val invitation: String,
    val operationName: String,
    /** What was typed; `null` while it is the name set in the app (`tour3/04-entree-nom`: « déjà rempli »). */
    val name: String? = null,
    val busy: Boolean = false,
)

/** What happened to this phone while it was in an operation, said once (P39.8). */
sealed interface Notice {
    /** On the screen of the sharing (`tour2/05-retirer-confirmation`). */
    data class Removed(
        val operation: String,
    ) : Notice

    /** On the screen of the sharing (`tour2/08-dissolution`). */
    data class Dissolved(
        val operation: String,
    ) : Notice

    /** A banner on the screen of the wait (`tour3/06-supprimer-groupe-apres`). */
    data class GroupDeleted(
        val group: String,
    ) : Notice

    /** A window on the screen of the wait (`tour3/07-remise-attente-confirmation`). */
    data class Unassigned(
        val group: String,
    ) : Notice

    /** `tour2/08-successeur`; [left]: the former creator left the operation (it may also have stayed, U8 A). */
    data class Creator(
        val former: String,
        val left: Boolean,
    ) : Notice

    /** `tour3/09-promotion-adjoint`. */
    data class GroupChief(
        val group: String,
        val former: String,
    ) : Notice

    /** `tour3/09-promotion-operation`. */
    data class OperationChief(
        val former: String,
    ) : Notice

    /** A message at the bottom of the screen of the others (`tour2/08-successeur`, `tour4/05-promotion-*`). */
    data class NewCreator(
        val former: String,
        val creator: String,
    ) : Notice

    data class NewGroupChief(
        val former: String,
        val chief: String,
        val group: String,
    ) : Notice

    data class NewOperationChief(
        val former: String,
        val chief: String,
    ) : Notice
}

/**
 * What this phone is told when its operation changes from [before] to [after] (two views a moment apart, so that a
 * hand-over and the departure that follows it are read together). [byItself]: this phone asked for the change
 * (it left, dissolved): it is not told what it did.
 */
object Notices {
    fun between(
        before: OperationView,
        after: OperationView,
        byItself: Boolean = false,
    ): List<Notice> {
        val was = before.hierarchy ?: return emptyList()
        val me = before.self ?: return emptyList()
        val wasIn = before.phase == OperationPhase.WAITING || before.phase == OperationPhase.PLACED
        val name = before.operationName.orEmpty()
        if (after.phase == OperationPhase.ENDED) {
            if (!wasIn || byItself) return emptyList()
            return when (after.ending) {
                Ending.DISSOLVED -> listOf(Notice.Dissolved(name))
                Ending.OUT -> listOf(Notice.Removed(name))
                Ending.WIPED, null -> emptyList()
            }
        }
        val now = after.hierarchy ?: return emptyList()

        fun named(
            view: OperationView,
            id: Long,
        ): String =
            view.members.firstOrNull { it.id == id }?.name ?: before.members.firstOrNull { it.id == id }?.name ?: "…"

        fun groupNamed(id: Long): String =
            before.groups.firstOrNull { it.id == id }?.name ?: after.groups.firstOrNull { it.id == id }?.name ?: "…"
        val out = ArrayList<Notice>()
        // Back to waiting: its group was deleted, or an administrator took it out of it (Q7, Q12).
        val myGroup = was.members[me]?.group
        if (before.phase == OperationPhase.PLACED && after.phase == OperationPhase.WAITING && myGroup != null) {
            out +=
                if (myGroup !in
                    now.groups
                ) {
                    Notice.GroupDeleted(groupNamed(myGroup))
                } else {
                    Notice.Unassigned(groupNamed(myGroup))
                }
        }
        // The creator handed the operation over (Q13, U8 A).
        val formerCreator = was.creator
        val creator = now.creator
        if (formerCreator != null && creator != null && creator != formerCreator) {
            val left = formerCreator !in now.members
            if (creator == me) {
                out += Notice.Creator(named(before, formerCreator), left)
            } else if (formerCreator != me && left && after.phase == OperationPhase.PLACED) {
                out += Notice.NewCreator(named(before, formerCreator), named(after, creator))
            }
        }
        // A chief left and its deputy became chief (Q18, S4): the deputy is told why, the others get a message.
        val formerLeader = was.leader
        val leader = now.leader
        if (formerLeader != null && formerLeader !in now.members && leader != null && leader == was.deputy) {
            if (leader == me) {
                out += Notice.OperationChief(named(before, formerLeader))
            } else if (after.phase == OperationPhase.PLACED) {
                out += Notice.NewOperationChief(named(before, formerLeader), named(after, leader))
            }
        }
        for ((id, group) in was.groups) {
            val next = now.groups[id] ?: continue
            val gone = group.leader
            val chief = next.leader
            if (gone == null || gone in now.members || chief == null || chief != group.deputy) continue
            if (chief == me) {
                out += Notice.GroupChief(groupNamed(id), named(before, gone))
            } else if (now.members[me]?.group == id) {
                out += Notice.NewGroupChief(named(before, gone), named(after, chief), groupNamed(id))
            }
        }
        return out
    }
}
