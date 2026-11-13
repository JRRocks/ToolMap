package fr.toolmap.core.hierarchy

import java.util.EnumSet

/**
 * The two public keys of a phone, each a P-256 point of [P256.POINT_BYTES] bytes: [signing] proves what the phone
 * writes (its member identifier is the fingerprint of it), [agreement] is the one content keys are wrapped to
 * (`docs/specs/group-hierarchy.md` § 8). Two keys and not one, so that the signing key may stay in the Keystore of
 * an Android that cannot do a key agreement there.
 */
class MemberKeys(
    val signing: ByteArray,
    val agreement: ByteArray,
) {
    /** Whether both are points of the curve, in the one form every phone accepts. */
    val valid: Boolean by lazy { P256.isPoint(signing) && P256.isPoint(agreement) }

    override fun equals(other: Any?): Boolean =
        other is MemberKeys && signing.contentEquals(other.signing) && agreement.contentEquals(other.agreement)

    override fun hashCode(): Int = 31 * signing.contentHashCode() + agreement.contentHashCode()

    override fun toString(): String = "MemberKeys(${java.lang.Long.toUnsignedString(OpAuth.memberId(signing), 16)})"
}

/** A member of the ensemble: the stable identifier of its phone, the name shown, its keys, its group if it has one. */
data class Member(
    val id: Long,
    val name: String,
    val keys: MemberKeys,
    val group: Long? = null,
)

/**
 * A group, child of [parent]: the ensemble ([HierarchyState.ROOT]) or another group, as deep as the limits of the
 * ensemble allow. Its [leader] and its [deputy], when it has them, are two different members of this very group.
 */
data class Group(
    val id: Long,
    val parent: Long,
    val name: String,
    val leader: Long? = null,
    val deputy: Long? = null,
)

/**
 * The hierarchy at one moment: the ensemble, its groups, its members and who holds which post. Immutable; a change
 * goes through an [Op] and [HierarchyReducer]. Before the first operation the state is [empty]: nothing is [created].
 *
 * A « unit » is the ensemble itself ([ROOT]) or a group: both have a leader and a deputy, both are a place where
 * things are shared.
 */
data class HierarchyState(
    val ensembleId: Long,
    val name: String,
    /** The one creator; `null` only before the ensemble exists. */
    val creator: Long?,
    /** The leader of the ensemble; `null` when the post is vacant. */
    val leader: Long?,
    /** The deputy of the leader of the ensemble; `null` when there is none. */
    val deputy: Long?,
    val rules: HierarchyRules,
    val members: Map<Long, Member>,
    val admins: Set<Long>,
    val groups: Map<Long, Group>,
    /** Groups that were deleted: their identifier never comes back. */
    val deletedGroups: Set<Long>,
    /** The creator ended the ensemble: nothing changes any more, nobody shares any more. */
    val dissolved: Boolean,
) {
    /** `false` before the operation that creates the ensemble. */
    val created: Boolean get() = creator != null

    /** The roles [member] holds; empty for someone who is not in the ensemble. */
    fun rolesOf(member: Long): Set<Role> {
        val roles = EnumSet.noneOf(Role::class.java)
        val known = members[member] ?: return roles
        roles.add(Role.MEMBER)
        if (creator == member) roles.add(Role.CREATOR)
        if (member in admins) roles.add(Role.ADMIN)
        if (leader == member) roles.add(Role.ENSEMBLE_LEADER)
        if (deputy == member) roles.add(Role.ENSEMBLE_DEPUTY)
        val group = known.group?.let { groups[it] }
        if (group?.leader == member) roles.add(Role.GROUP_LEADER)
        if (group?.deputy == member) roles.add(Role.GROUP_DEPUTY)
        return roles
    }

    /**
     * Whether [member] waits for a group: admitted, in no group, and holding none of the posts that give access
     * without one (creator, administrator, leader of the ensemble or its deputy). Someone who waits shares nothing,
     * reads nothing, and no content key is wrapped to its phone ([readersOf]).
     */
    fun isWaiting(member: Long): Boolean {
        val known = members[member] ?: return false
        return known.group == null && member != creator && member !in admins && member != leader && member != deputy
    }

    /** Whether [member] takes part in what is shared: in the ensemble, not waiting, ensemble not dissolved. */
    fun hasAccess(member: Long): Boolean = !dissolved && member in members && !isWaiting(member)

    /** The group of [member]; `null` when it has none or is not in the ensemble. */
    fun groupOf(member: Long): Group? = members[member]?.group?.let { groups[it] }

    /** The members of [group] itself; with `null`, the members without a group. Ordered by identifier. */
    fun membersOf(group: Long?): List<Member> = members.values.filter { it.group == group }.sortedWith(BY_MEMBER_ID)

    /** The members who wait for a group, ordered by identifier: what an administrator has to deal with. */
    fun waiting(): List<Member> = members.values.filter { isWaiting(it.id) }.sortedWith(BY_MEMBER_ID)

    /** Whether [unit] is a place of this ensemble: the ensemble itself or one of its groups. */
    fun isUnit(unit: Long): Boolean = unit == ROOT || unit in groups

    /** The unit just above [unit]; `null` for the ensemble and for what is not a unit. */
    fun parentOf(unit: Long): Long? = groups[unit]?.parent

    /** How many levels under the ensemble [unit] is: 0 for the ensemble, 1 for a group directly under it. */
    fun depthOf(unit: Long): Int = pathToRoot(unit).size - 1

    /** [unit], then the unit above it, and so on up to the ensemble. Empty for what is not a unit. */
    fun pathToRoot(unit: Long): List<Long> {
        if (!isUnit(unit)) return emptyList()
        val path = ArrayList<Long>()
        var at = unit
        // A state built by the reducer has no loop; the bound is for one decoded from bytes, before it is checked.
        while (at != ROOT && path.size <= HierarchyLimits.FORMAT_MAX_DEPTH) {
            path.add(at)
            at = groups[at]?.parent ?: return emptyList()
        }
        if (at != ROOT) return emptyList()
        path.add(ROOT)
        return path
    }

    /** Whether [unit] is [ancestor] or somewhere under it. */
    fun isWithin(
        unit: Long,
        ancestor: Long,
    ): Boolean = ancestor in pathToRoot(unit)

    /** The groups directly under [unit], ordered by identifier. */
    fun childrenOf(unit: Long): List<Group> = groups.values.filter { it.parent == unit }.sortedWith(BY_GROUP_ID)

    /** The leader of [unit] (the ensemble or a group), or `null`. */
    fun leaderOf(unit: Long): Long? = if (unit == ROOT) leader else groups[unit]?.leader

    /** The deputy of [unit] (the ensemble or a group), or `null`. */
    fun deputyOf(unit: Long): Long? = if (unit == ROOT) deputy else groups[unit]?.deputy

    /**
     * The units whose shares [member] reads, nearest first: its group, the units above it, the ensemble. Only the
     * ensemble for a creator, administrator or ensemble leader without a group; nothing for someone who waits.
     */
    fun unitsReadBy(member: Long): List<Long> {
        if (!hasAccess(member)) return emptyList()
        val group = members.getValue(member).group ?: return listOf(ROOT)
        return pathToRoot(group)
    }

    /**
     * Who reads what is shared in [unit], ordered by identifier: for a group, its members and the members of the
     * groups under it; for the ensemble, every member who [hasAccess]. These are the phones a content key of the
     * unit is wrapped to, and no other ([KeyBatch]): someone who waits is in none of these lists.
     */
    fun readersOf(unit: Long): List<Member> =
        when {
            dissolved || !isUnit(unit) -> emptyList()
            unit == ROOT -> members.values.filter { hasAccess(it.id) }
            else -> members.values.filter { it.group != null && isWithin(it.group, unit) }
        }.sortedWith(BY_MEMBER_ID)

    /**
     * Whether [author] may share something of [kind] into [unit] ([ShareTable]). Someone who waits shares nothing,
     * whatever the table says.
     */
    fun mayShare(
        author: Long,
        kind: ShareKind,
        unit: Long,
    ): Boolean {
        if (!hasAccess(author) || !isUnit(unit)) return false
        val own = members.getValue(author).group
        val above = own?.let(::parentOf)
        return rolesOf(author).any { role ->
            val mask = rules.shares.effectiveMask(kind, role)
            (mask and ShareReach.ENSEMBLE.bit != 0 && unit == ROOT) ||
                (mask and ShareReach.OWN_UNIT.bit != 0 && unit == own) ||
                (mask and ShareReach.PARENT_UNIT.bit != 0 && unit == above)
        }
    }

    /** The units [author] may share something of [kind] into, nearest first. */
    fun shareUnits(
        author: Long,
        kind: ShareKind,
    ): List<Long> = unitsReadBy(author).filter { mayShare(author, kind, it) }

    /**
     * Whether [viewer] may see the position of [target] on the map ([VisibilityTable]). Pure: the transport and the
     * map ask it, it never asks them. Someone who is not in the ensemble, or who waits for a group, sees nobody but
     * itself and is seen by nobody.
     */
    fun canSee(
        viewer: Long,
        target: Long,
    ): Boolean {
        val seer = members[viewer] ?: return false
        val seen = members[target] ?: return false
        if (viewer == target) return true
        if (!hasAccess(viewer) || !hasAccess(target)) return false
        var audiences = Audience.EVERYONE.bit
        if (seer.group != null && seer.group == seen.group) audiences = audiences or Audience.SAME_GROUP.bit
        val roles = rolesOf(target)
        if (Role.GROUP_LEADER in roles) audiences = audiences or Audience.GROUP_LEADERS.bit
        if (Role.ENSEMBLE_LEADER in roles) audiences = audiences or Audience.ENSEMBLE_LEADER.bit
        if (Role.ADMIN in roles) audiences = audiences or Audience.ADMINS.bit
        return rolesOf(viewer).any { rules.visibility.mask(it) and audiences != 0 }
    }

    /** The members whose position [viewer] may see, itself left out. Ordered by identifier. */
    fun visibleTo(viewer: Long): List<Member> =
        members.values.filter { it.id != viewer && canSee(viewer, it.id) }.sortedWith(BY_MEMBER_ID)

    /**
     * What is wrong with this state, or `null`: a state built by [HierarchyReducer] never has anything wrong; one
     * decoded from bytes might. In English, for the logs.
     */
    fun inconsistency(): String? {
        if (!created) {
            val blank = members.isEmpty() && groups.isEmpty() && deletedGroups.isEmpty() && admins.isEmpty()
            val bare = leader == null && deputy == null && !dissolved && name.isEmpty()
            return if (blank && bare) null else "content before the ensemble exists"
        }
        if (ensembleId == 0L) return "no ensemble identifier"
        if (!Names.valid(name)) return "bad ensemble name"
        if (!rules.limits.valid) return "limits the format cannot carry"
        if (members.size > rules.limits.maxMembers || groups.size > rules.limits.maxGroups) {
            return "more members or groups than the limits of the ensemble"
        }
        if (creator !in members) return "creator who is not a member"
        if (!members.keys.containsAll(admins)) return "administrator who is not a member"
        if (leader != null && leader !in members) return "ensemble leader who is not a member"
        if (deputy != null && deputy !in members) return "ensemble deputy who is not a member"
        if (leader != null && leader == deputy) return "ensemble leader who is its own deputy"
        for ((id, member) in members) {
            if (id == 0L || id != member.id || !Names.valid(member.name)) return "bad member"
            if (!member.keys.valid ||
                OpAuth.memberId(member.keys.signing) != id
            ) {
                return "member whose keys are not its own"
            }
            if (member.group != null && member.group !in groups) return "member in a group that does not exist"
        }
        for ((id, group) in groups) {
            if (id == ROOT || id != group.id || !Names.valid(group.name)) return "bad group"
            if (id in deletedGroups) return "deleted group still there"
            val depth = depthOf(id)
            if (depth < 1) return "group that hangs under nothing"
            if (depth > rules.limits.maxDepth) return "group deeper than the limits of the ensemble"
            if (group.leader != null && members[group.leader]?.group != id) return "group leader out of its group"
            if (group.deputy != null && members[group.deputy]?.group != id) return "group deputy out of its group"
            if (group.leader != null && group.leader == group.deputy) return "group leader who is its own deputy"
        }
        return null
    }

    companion object {
        /** The ensemble as a unit, and the parent of the groups of the first level. Never the identifier of a group. */
        const val ROOT = 0L

        private val BY_MEMBER_ID = Comparator<Member> { a, b -> java.lang.Long.compareUnsigned(a.id, b.id) }
        private val BY_GROUP_ID = Comparator<Group> { a, b -> java.lang.Long.compareUnsigned(a.id, b.id) }

        fun empty(ensembleId: Long) =
            HierarchyState(
                ensembleId = ensembleId,
                name = "",
                creator = null,
                leader = null,
                deputy = null,
                rules = HierarchyRules.DEFAULT,
                members = emptyMap(),
                admins = emptySet(),
                groups = emptyMap(),
                deletedGroups = emptySet(),
                dissolved = false,
            )
    }
}

/** The names of ensembles, groups and members: what every phone accepts. */
object Names {
    /** In UTF-8 bytes, so that a name always fits its field of the binary format. */
    const val MAX_BYTES = 64

    fun valid(name: String): Boolean =
        name.isNotBlank() && name.none { it.isISOControl() } && name.toByteArray(Charsets.UTF_8).size <= MAX_BYTES
}
