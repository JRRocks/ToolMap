package fr.toolmap.operation

import fr.toolmap.core.hierarchy.Change
import fr.toolmap.core.hierarchy.HierarchyReducer
import fr.toolmap.core.hierarchy.HierarchyState
import fr.toolmap.core.hierarchy.Names
import fr.toolmap.core.hierarchy.Post
import fr.toolmap.core.hierarchy.Rejection
import fr.toolmap.core.p2p.PeerState
import fr.toolmap.core.p2p.ServerState
import fr.toolmap.data.hierarchy.MemberView
import fr.toolmap.data.hierarchy.OperationPhase
import fr.toolmap.data.hierarchy.OperationView

/** A role as the lists write it (Q8 A: one word per role, the highest first). */
enum class RoleWord {
    OP_CHIEF,
    OP_DEPUTY,
    GROUP_CHIEF,
    GROUP_DEPUTY,
    CREATOR,
    ADMIN,
}

/** A mark on a token (V4 A): filled for a chief, hollow for its deputy. */
enum class Mark { FULL, HOLLOW }

/**
 * A token of an operation (V4 A): ring and text in the colour of the group ([palette], an index of [GROUP_COLORS]),
 * grey and dashed while waiting; a crown for the chief of the operation and a star for a group chief.
 */
data class TokenLook(
    val text: String,
    val palette: Int?,
    val waiting: Boolean,
    val crown: Mark? = null,
    val star: Mark? = null,
)

/** What a gesture of a sheet is for this phone: offered, shown grey with a lock (reserved to the creator), or absent. */
enum class Gesture { ALLOWED, LOCKED, HIDDEN }

/** The posts of a member, as the sheet of a member offers them (`tour3/07-remise-attente-fiche`, `-adjoint`). */
enum class PostOffer {
    /** A member of a group with no post there: « Nommer chef ou adjoint ». */
    CHOOSE,

    /** The deputy of its group: « Nommer chef du … » and « Retirer le poste d'adjoint ». */
    DEPUTY,

    /** The chief of its group, or no group: nothing to offer (not drawn). */
    NONE,
}

/** What the sheet of [member] offers to this phone (`tour2/05-retirer-membre`, `tour3/07-remise-attente-*`). */
data class MemberGestures(
    val member: Long,
    val changeGroup: Boolean,
    val post: PostOffer,
    val unassign: Boolean,
    val nameAdmin: Gesture,
    val remove: Gesture,
)

/**
 * Everything the screens of the « Opération » mode read, worked out from one [OperationView] (P39.8): who is who, in
 * which order, with which colour and token, and what this phone may do. Pure: the JVM tests build views of every role.
 *
 * [lastSeen] is when this phone last saw each member present (the view only says who is present now): the lists say
 * « Synchronisation perdue » for a short silence and « Hors ligne » after that, as « Entre amis » does.
 */
class OperationFacts(
    val view: OperationView,
    val nowMs: Long = System.currentTimeMillis(),
    private val lastSeen: Map<Long, Long> = emptyMap(),
    /** The name this phone asked to come in with, while the operations do not hold it yet. */
    private val selfName: String? = null,
) {
    val state: HierarchyState? = view.hierarchy
    val self: Long? = view.self
    private val byId: Map<Long, MemberView> = view.members.associateBy { it.id }

    /** The groups in the order they were made: the engine keeps that order, the same on every phone. */
    val groupOrder: List<Long> = state?.groups?.keys?.toList().orEmpty()

    /** The colour of each group: the first of [GROUP_COLORS] that no older group has (no colour in the engine). */
    val palette: Map<Long, Int> =
        buildMap {
            val taken = HashSet<Int>()
            for (g in groupOrder) {
                val free = GROUP_COLORS.indices.firstOrNull { it !in taken } ?: (size % GROUP_COLORS.size)
                taken += free
                put(g, free)
            }
        }

    val operationName: String? get() = view.operationName

    fun member(id: Long): MemberView? = byId[id]

    /** The name of [id], `null` while this phone does not know it. */
    fun name(id: Long): String? = byId[id]?.name ?: selfName?.takeIf { id == self }

    /** The name of a group as this phone knows it (« Nord »), written alone in lists and menus (N0, 2026-11-16). */
    fun groupName(id: Long): String? = view.groups.firstOrNull { it.id == id }?.name

    fun groupOf(member: Long): Long? = state?.members?.get(member)?.group

    val myGroup: Long? get() = self?.let(::groupOf)

    fun isCreator(id: Long): Boolean = state?.creator == id

    fun isAdmin(id: Long): Boolean = state?.admins?.contains(id) == true

    /** This phone places the arrivals, makes the groups and names the chiefs (the creator acts as an administrator). */
    val administers: Boolean get() = self?.let { isCreator(it) || isAdmin(it) } == true

    val waiting: Boolean get() = view.phase == OperationPhase.WAITING || view.phase == OperationPhase.ASKING

    /** The members waiting for a group, in the order they came. */
    val waitingMembers: List<Long>
        get() = state?.members?.keys?.filter { state.isWaiting(it) }.orEmpty()

    val placedCount: Int get() = state?.members?.keys?.count { !state.isWaiting(it) } ?: 0

    val total: Int get() = state?.members?.size ?: 0

    val memberLimit: Int get() = state?.rules?.limits?.maxMembers ?: DEFAULT_MEMBER_LIMIT

    val groupLimit: Int get() = state?.rules?.limits?.maxGroups ?: DEFAULT_GROUP_LIMIT

    val full: Boolean get() = total >= memberLimit

    val groupsFull: Boolean get() = groupOrder.size >= groupLimit

    /** The members of [group]: its chief, its deputy, then the others in the order they came. */
    fun membersOf(group: Long): List<Long> {
        val s = state ?: return emptyList()
        val g = s.groups[group] ?: return emptyList()
        val all = s.members.values.filter { it.group == group }.map { it.id }
        return listOfNotNull(g.leader?.takeIf { it in all }, g.deputy?.takeIf { it in all }) +
            all.filter { it != g.leader && it != g.deputy }
    }

    fun leaderOf(unit: Long): Long? = state?.leaderOf(unit)

    fun deputyOf(unit: Long): Long? = state?.deputyOf(unit)

    fun isOnline(id: Long): Boolean = byId[id]?.online == true

    fun onlineIn(group: Long): Int = membersOf(group).count(::isOnline)

    /** This phone reaches its coordination server, so it can vouch for who is there (as « Entre amis »). */
    val serverUp: Boolean get() = view.server != ServerState.UNREACHABLE && view.server != ServerState.REFUSED

    fun peerState(id: Long): PeerState {
        val seen = lastSeen[id]
        return when {
            id == self -> if (serverUp) PeerState.ONLINE else PeerState.OFFLINE
            !serverUp -> PeerState.LOST_SYNC
            isOnline(id) -> PeerState.ONLINE
            seen != null && nowMs - seen <= PeerState.LOST_SYNC_WINDOW_MS -> PeerState.LOST_SYNC
            else -> PeerState.OFFLINE
        }
    }

    /** When [id] was last seen present, for « il y a 4 minutes »; `null` when online or never seen. */
    fun silentSince(id: Long): Long? = if (id == self || isOnline(id)) null else lastSeen[id]

    /** The roles of [id], highest first (Q8 A); the deputy of a group is « Adjoint », of the operation in full. */
    fun roleWords(id: Long): List<RoleWord> {
        val s = state ?: return emptyList()
        val group = groupOf(id)?.let { s.groups[it] }
        return buildList {
            if (s.leader == id) add(RoleWord.OP_CHIEF)
            if (s.deputy == id) add(RoleWord.OP_DEPUTY)
            if (group?.leader == id) add(RoleWord.GROUP_CHIEF)
            if (group?.deputy == id) add(RoleWord.GROUP_DEPUTY)
            if (s.creator == id) {
                add(RoleWord.CREATOR)
            } else if (id in s.admins) {
                add(RoleWord.ADMIN)
            }
        }
    }

    /** The one role the line of a member writes (its highest post; creator and administrator are signs, not words). */
    fun postWord(id: Long): RoleWord? = roleWords(id).firstOrNull { it != RoleWord.CREATOR && it != RoleWord.ADMIN }

    fun token(id: Long): TokenLook {
        val s = state
        val group = groupOf(id)
        val waitingHere = s?.isWaiting(id) == true
        val crown =
            when (id) {
                s?.leader -> Mark.FULL
                s?.deputy -> Mark.HOLLOW
                else -> null
            }
        val g = group?.let { s?.groups?.get(it) }
        val star =
            when (id) {
                g?.leader -> Mark.FULL
                g?.deputy -> Mark.HOLLOW
                else -> null
            }
        return TokenLook(badgeText(name(id)), group?.let { palette[it] }, waitingHere, crown, star)
    }

    /** Why [change] would be refused for this phone now; `null` when it would go through (to grey a button). */
    fun check(change: Change): Rejection? {
        val s = state ?: return Rejection.NO_ENSEMBLE
        val me = self ?: return Rejection.NOT_A_MEMBER
        return HierarchyReducer.check(s, me, change)
    }

    fun may(change: Change): Boolean = check(change) == null

    private fun gesture(change: Change): Gesture =
        when (check(change)) {
            null -> Gesture.ALLOWED
            Rejection.FORBIDDEN -> Gesture.LOCKED
            else -> Gesture.HIDDEN
        }

    /** The admin tiles « Administrer » and « Inviter » show to the creator and the administrators only (Q5, T9 A). */
    val showsAdminTiles: Boolean get() = administers && view.phase == OperationPhase.PLACED

    /** The reminder to the creator (T10 B, U8 A): its own phone, until « Compris ». */
    val isCreatorHere: Boolean get() = self != null && isCreator(self)

    /** The administrators as the screens list them: the creator first (it acts as one), then the others. */
    val adminList: List<Long>
        get() {
            val s = state ?: return emptyList()
            return listOfNotNull(s.creator) + s.members.keys.filter { it in s.admins && it != s.creator }
        }

    /** Who may succeed the creator, or receive its role (Q13): an administrator, not itself. */
    val successors: List<Long> get() =
        state?.let { s ->
            s.members.keys.filter { it in s.admins && it != self }
        }.orEmpty()

    /** Who may be named administrator (`tour2/05-admins-createur`): the members placed in a group. */
    val adminCandidates: List<Long>
        get() =
            state?.let { s ->
                s.members.values.filter { it.group != null && it.id != s.creator }.map { it.id }
            }.orEmpty()

    /** Who may be chief of the operation or its deputy: whoever has the access, waiting members excluded. */
    val operationPostCandidates: List<Long>
        get() = state?.let { s -> s.members.keys.filter { !s.isWaiting(it) } }.orEmpty()

    /** What the sheet of [member] offers this phone. */
    fun gestures(member: Long): MemberGestures {
        val s = state
        val group = groupOf(member)
        val g = group?.let { s?.groups?.get(it) }
        val otherGroup = groupOrder.firstOrNull { it != group }
        val post =
            when {
                g == null -> PostOffer.NONE
                g.leader == member -> PostOffer.NONE
                g.deputy == member -> PostOffer.DEPUTY
                else -> PostOffer.CHOOSE
            }
        val postAllowed =
            when (post) {
                PostOffer.NONE -> false
                PostOffer.CHOOSE, PostOffer.DEPUTY -> group != null && may(Change.SetPost(group, Post.LEADER, member))
            }
        return MemberGestures(
            member = member,
            changeGroup = otherGroup != null && may(Change.AssignMember(member, otherGroup)),
            post = if (postAllowed) post else PostOffer.NONE,
            unassign = group != null && may(Change.UnassignMember(member)),
            nameAdmin = if (isAdmin(member) || isCreator(member)) Gesture.HIDDEN else gesture(Change.AddAdmin(member)),
            remove = if (member == self) Gesture.HIDDEN else gesture(Change.RemoveMember(member)),
        )
    }

    companion object {
        /** The eight colours of the groups (`tour2/04-admin-groupes`), in the order they are given. */
        val GROUP_COLORS: List<Long> =
            listOf(0xFF1976D2, 0xFFE06C00, 0xFF2E7D32, 0xFF7B1FA2, 0xFFC2185B, 0xFF5D4037, 0xFF00838F, 0xFFAFB42B)

        /** Grey of the members waiting for a group. */
        const val WAITING_COLOR: Long = 0xFF6B7B82

        const val DEFAULT_MEMBER_LIMIT = 30
        const val DEFAULT_GROUP_LIMIT = 6

        /**
         * The text of a token: the initials of the name ([Names.initials]); a name that ends in a number (« Membre 21 »,
         * a call sign) shows that number, as the maquettes draw it.
         */
        fun badgeText(name: String?): String {
            if (name == null) return "…"
            val last = name.trim().split(' ').lastOrNull { it.isNotEmpty() }
            if (last != null && last.length <= MAX_NUMBER_DIGITS && last.all(Char::isDigit)) return last
            return Names.initials(name).ifEmpty { "…" }
        }

        private const val MAX_NUMBER_DIGITS = 3
    }
}
