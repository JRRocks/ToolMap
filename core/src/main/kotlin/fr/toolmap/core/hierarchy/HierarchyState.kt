package fr.toolmap.core.hierarchy

import java.util.EnumSet

/** A member of the ensemble: the stable identifier of its phone, the name shown, and its group if it has one. */
data class Member(
    val id: Long,
    val name: String,
    val group: Long? = null,
)

/**
 * A group, child of [parent]. Today every group hangs under the ensemble ([HierarchyState.ROOT]); the field is there
 * so that a level more (groups inside a group) is a rule to relax, not a format to change.
 */
data class Group(
    val id: Long,
    val parent: Long,
    val name: String,
    val leader: Long? = null,
)

/**
 * The hierarchy at one moment: the ensemble, its groups, its members and who holds which post. Immutable; a change
 * goes through an [Op] and [HierarchyReducer]. Before the first operation the state is [empty]: nothing is [created].
 */
data class HierarchyState(
    val ensembleId: Long,
    val name: String,
    /** The leader of the ensemble; `null` when the post is vacant (its holder left or was removed). */
    val leader: Long?,
    val rules: HierarchyRules,
    val members: Map<Long, Member>,
    val admins: Set<Long>,
    val groups: Map<Long, Group>,
    /** Groups that were deleted: their identifier never comes back. */
    val deletedGroups: Set<Long>,
) {
    /** `false` before the operation that creates the ensemble; then there is always at least one administrator. */
    val created: Boolean get() = admins.isNotEmpty()

    /** The roles [member] holds; empty for someone who is not in the ensemble. */
    fun rolesOf(member: Long): Set<Role> {
        val roles = EnumSet.noneOf(Role::class.java)
        val known = members[member] ?: return roles
        roles.add(Role.MEMBER)
        if (member in admins) roles.add(Role.ADMIN)
        if (leader == member) roles.add(Role.ENSEMBLE_LEADER)
        if (known.group?.let { groups[it]?.leader } == member) roles.add(Role.GROUP_LEADER)
        return roles
    }

    /** The group of [member]; `null` when it has none or is not in the ensemble. */
    fun groupOf(member: Long): Group? = members[member]?.group?.let { groups[it] }

    /** The members of [group]; with `null`, the members without a group. Ordered by identifier. */
    fun membersOf(group: Long?): List<Member> = members.values.filter { it.group == group }.sortedWith(BY_MEMBER_ID)

    /**
     * Whether [viewer] may see the position of [target] ([VisibilityTable]). Pure: the transport and the map ask it,
     * it never asks them. Someone who is not in the ensemble sees nobody and is seen by nobody.
     */
    fun canSee(
        viewer: Long,
        target: Long,
    ): Boolean {
        val seer = members[viewer] ?: return false
        val seen = members[target] ?: return false
        if (viewer == target) return true
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
            val blank = members.isEmpty() && groups.isEmpty() && deletedGroups.isEmpty() && leader == null
            return if (blank && name.isEmpty()) null else "content before the ensemble exists"
        }
        if (ensembleId == 0L) return "no ensemble identifier"
        if (!Names.valid(name)) return "bad ensemble name"
        if (members.size > MAX_MEMBERS || groups.size > MAX_GROUPS) return "too many members or groups"
        if (!members.keys.containsAll(admins)) return "administrator who is not a member"
        if (leader != null && leader !in members) return "ensemble leader who is not a member"
        for ((id, member) in members) {
            if (id == 0L || id != member.id || !Names.valid(member.name)) return "bad member"
            if (member.group != null && member.group !in groups) return "member in a group that does not exist"
        }
        for ((id, group) in groups) {
            if (id == ROOT || id != group.id || !Names.valid(group.name)) return "bad group"
            if (group.parent != ROOT) return "group deeper than the format allows"
            if (id in deletedGroups) return "deleted group still there"
            if (group.leader != null && members[group.leader]?.group != id) return "group leader out of its group"
        }
        return null
    }

    companion object {
        /** The parent of the groups of the first level: the ensemble itself. Never the identifier of a group. */
        const val ROOT = 0L

        /** How many levels of groups under the ensemble. One today; the model does not forbid more. */
        const val MAX_DEPTH = 1
        const val MAX_MEMBERS = 256
        const val MAX_GROUPS = 64

        private val BY_MEMBER_ID = Comparator<Member> { a, b -> java.lang.Long.compareUnsigned(a.id, b.id) }

        fun empty(ensembleId: Long) =
            HierarchyState(
                ensembleId = ensembleId,
                name = "",
                leader = null,
                rules = HierarchyRules.DEFAULT,
                members = emptyMap(),
                admins = emptySet(),
                groups = emptyMap(),
                deletedGroups = emptySet(),
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
