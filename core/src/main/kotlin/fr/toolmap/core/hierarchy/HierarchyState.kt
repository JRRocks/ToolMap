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

/**
 * A member of the ensemble: the stable identifier of its phone, its name as the operations carry it (hidden: a phone
 * that waits for a group learns no name, [NameBook]), its keys, its group if it has one.
 */
data class Member(
    val id: Long,
    val name: HiddenName,
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
    val name: HiddenName,
    val leader: Long? = null,
    val deputy: Long? = null,
)

/**
 * The hierarchy at one moment: the ensemble, its groups, its members and who holds which post. Immutable; a change
 * goes through an [Op] and [HierarchyReducer]. Before the first operation the state is [empty]: nothing is [created].
 *
 * A « unit » is the ensemble itself ([ROOT]) or a group: both have a leader and a deputy. Things are shared in
 * [Channel]s: the unit itself, and the lanes that go up from a group, down to it, between the leaders, and from
 * someone who waits to the administrators.
 */
data class HierarchyState(
    val ensembleId: Long,
    /** The name of the ensemble, hidden like every name; `null` only before the ensemble exists. */
    val name: HiddenName?,
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
        if (isWaiting(member)) roles.add(Role.WAITING)
        return roles
    }

    /**
     * Whether [member] waits for a group: admitted, in no group, and holding none of the posts that give access
     * without one (creator, administrator, leader of the ensemble or its deputy). Someone who waits reads nothing of
     * what the others share and learns no name; the one thing its phone sends is what the cells of [Role.WAITING]
     * say (its position, to the administrators only), in a lane of its own ([Lane.WAITING]).
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
     * Those who command [unit]: its leader and its deputy; for the ensemble, the administrators and the creator too
     * (the user's answers S1 and S2: « le chef de l'opération, son adjoint et les administrateurs »).
     */
    fun commandOf(unit: Long): Set<Long> {
        val command = LinkedHashSet<Long>()
        leaderOf(unit)?.let(command::add)
        deputyOf(unit)?.let(command::add)
        if (unit == ROOT) {
            creator?.let(command::add)
            command.addAll(admins)
        }
        return command
    }

    /**
     * The units whose own channel [member] reads, nearest first: its group, the units above it, the ensemble. Only
     * the ensemble for a creator, administrator or ensemble leader without a group; nothing for someone who waits.
     */
    fun unitsReadBy(member: Long): List<Long> {
        if (!hasAccess(member)) return emptyList()
        val group = members.getValue(member).group ?: return listOf(ROOT)
        return pathToRoot(group)
    }

    /** Whether [channel] is a place of this ensemble now (it may still have no reader). */
    fun isChannel(channel: Channel): Boolean =
        when (channel.lane) {
            Lane.UNIT -> isUnit(channel.scope)
            Lane.UP, Lane.DOWN -> channel.scope in groups
            Lane.LEADERS -> channel.scope == ROOT
            Lane.WAITING -> isWaiting(channel.scope)
        }

    /**
     * Who reads what is shared in [channel], ordered by identifier. These are the phones a content key of the
     * channel is wrapped to, and no other ([KeyBatch]): the closing is done by the keys, not by the display. One
     * line per lane, the readings of the user's answers:
     *
     * - [Lane.UNIT] of the ensemble: every member who [hasAccess]; of a group: its members and the members of the
     *   groups under it, and nobody else, administrators and leader of the ensemble included (Q16) ;
     * - [Lane.UP] of a group: its leader, its deputy, and those who command the unit above (S1) ;
     * - [Lane.DOWN] of a group: its members, and those who command the unit above, who write there (Q15) ;
     * - [Lane.LEADERS]: the leader and the deputy of every unit, the administrators and the creator (Q6) ;
     * - [Lane.WAITING] of a member who waits: that member, the administrators and the creator (Q10).
     *
     * A lane nobody would write in has no reader, so no key is made for it: the lane up of a group without leader
     * nor deputy, the lane down of a group without member, the lane of the leaders while no group has one.
     */
    fun readersOf(channel: Channel): List<Member> {
        if (dissolved || !isChannel(channel)) return emptyList()
        val scope = channel.scope
        val ids: Collection<Long> =
            when (channel.lane) {
                Lane.UNIT -> {
                    return unitReaders(scope)
                }

                Lane.UP -> {
                    groupPosts(scope).ifEmpty { return emptyList() } + commandOf(parentOf(scope) ?: ROOT)
                }

                Lane.DOWN -> {
                    val inside = unitReaders(scope).map { it.id }.ifEmpty { return emptyList() }
                    inside + commandOf(parentOf(scope) ?: ROOT)
                }

                Lane.LEADERS -> {
                    groups.keys.flatMap(::groupPosts).ifEmpty { return emptyList() } + commandOf(ROOT)
                }

                Lane.WAITING -> {
                    listOf(scope) + admins + listOfNotNull(creator)
                }
            }
        return ids.distinct().mapNotNull { members[it] }.sortedWith(BY_MEMBER_ID)
    }

    /** The readers of the own channel of [unit]. */
    fun readersOf(unit: Long): List<Member> = readersOf(Channel.unit(unit))

    private fun unitReaders(unit: Long): List<Member> =
        if (unit == ROOT) {
            members.values.filter { hasAccess(it.id) }
        } else {
            members.values.filter { it.group != null && isWithin(it.group, unit) }
        }.sortedWith(BY_MEMBER_ID)

    /** The leader and the deputy of [group], when it has them. */
    private fun groupPosts(group: Long): List<Long> = listOfNotNull(groups[group]?.leader, groups[group]?.deputy)

    /**
     * Every channel of the ensemble, whether someone reads it or not: the ensemble, the lane of the leaders, then
     * for each group (by identifier) its own channel and its lanes up and down, then the lane of each member who
     * waits (by identifier).
     */
    fun channels(): List<Channel> {
        val all = ArrayList<Channel>()
        all.add(Channel.ENSEMBLE)
        all.add(Channel.LEADERS)
        for (group in groups.keys.sortedWith(HierarchyWire.UNSIGNED)) {
            all.add(Channel.unit(group))
            all.add(Channel.up(group))
            all.add(Channel.down(group))
        }
        for (member in waiting()) all.add(Channel.waiting(member.id))
        return all
    }

    /** The channels [member] reads, in the order of [channels]. Empty for who is not in the ensemble. */
    fun channelsReadBy(member: Long): List<Channel> =
        if (member in
            members
        ) {
            channels().filter { channel -> readersOf(channel).any { it.id == member } }
        } else {
            emptyList()
        }

    /**
     * Whether [author] may share something of [kind] into [channel] ([ShareTable]). Whatever the table says: only
     * in a channel the author reads (it needs its key), and someone who waits only has the cells of [Role.WAITING].
     */
    fun mayShare(
        author: Long,
        kind: ShareKind,
        channel: Channel,
    ): Boolean {
        val known = members[author] ?: return false
        if (readersOf(channel).none { it.id == author }) return false
        val own = known.group
        val roles = if (isWaiting(author)) setOf(Role.WAITING) else rolesOf(author)
        val scope = channel.scope
        return roles.any { role ->
            val mask = rules.shares.effectiveMask(kind, role)

            fun has(reach: ShareReach) = mask and reach.bit != 0
            // The unit this role commands: the groups just under it are the ones it sends down to, and answers.
            val commanded = if (role.commandsEnsemble) ROOT else own.takeIf { role.commandsGroup }
            val below = commanded != null && parentOf(scope) == commanded
            when (channel.lane) {
                Lane.UNIT -> (has(ShareReach.ENSEMBLE) && scope == ROOT) || (has(ShareReach.OWN_UNIT) && scope == own)
                Lane.UP -> (has(ShareReach.UP) && scope == own) || (has(ShareReach.UP_BELOW) && below)
                Lane.DOWN -> has(ShareReach.GROUP_BELOW) && below
                Lane.LEADERS -> has(ShareReach.LEADERS)
                Lane.WAITING -> has(ShareReach.WAITING) && scope == author
            }
        }
    }

    /** Whether [author] may share something of [kind] into the own channel of [unit]. */
    fun mayShare(
        author: Long,
        kind: ShareKind,
        unit: Long,
    ): Boolean = mayShare(author, kind, Channel.unit(unit))

    /** The channels [author] may share something of [kind] into, in the order of [channels]. */
    fun shareChannels(
        author: Long,
        kind: ShareKind,
    ): List<Channel> = channelsReadBy(author).filter { mayShare(author, kind, it) }

    /**
     * For each channel, the readers it has now that it did not have in [before]: who just arrived there (placed in
     * a group, named to a post, let back in). What is still shown in the channel is to be sent to them again, and
     * the names to who arrives in the whole ensemble ([SharedObjects.resend], [NameBook.cardsFor]). Channels
     * without an arrival are left out.
     */
    fun arrivals(before: HierarchyState): Map<Channel, List<Long>> {
        val arrived = LinkedHashMap<Channel, List<Long>>()
        for (channel in channels()) {
            val known = before.readersOf(channel).mapTo(HashSet()) { it.id }
            val new = readersOf(channel).map { it.id }.filter { it !in known }
            if (new.isNotEmpty()) arrived[channel] = new
        }
        return arrived
    }

    /**
     * The readers of [channel] who may share [kind] there, in the order they should send again what is still shown
     * to someone who arrives or comes back ([SharedObjects]): like [keyIssuers], the order only avoids that all do
     * it at once.
     */
    fun resenders(
        kind: ShareKind,
        channel: Channel,
    ): List<Long> = keyIssuers(channel).filter { mayShare(it, kind, channel) }

    /**
     * Whether [viewer] may see the position of [target] on the map ([VisibilityTable]). Pure: the transport and the
     * map ask it, it never asks them. Someone who is not in the ensemble, or who waits for a group, sees nobody but
     * itself; someone who waits is seen only by those its position is sealed for (the administrators).
     */
    fun canSee(
        viewer: Long,
        target: Long,
    ): Boolean {
        val seer = members[viewer] ?: return false
        val seen = members[target] ?: return false
        if (viewer == target) return true
        if (!hasAccess(viewer)) return false
        if (isWaiting(target)) {
            return !dissolved && mayShare(target, ShareKind.POSITION, Channel.waiting(target)) &&
                readersOf(Channel.waiting(target)).any { it.id == viewer }
        }
        if (!hasAccess(target)) return false
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
     * How the name of [target] is written on the map of [viewer] (the user's answer Q17): in full for oneself, for
     * the members of one's own group and for whoever holds a post of leader or deputy; as initials for the others.
     * A rule of the display, not of the keys: everyone who has access knows every name ([NameBook]), and reads it in
     * full by touching the marker.
     */
    fun nameLabel(
        viewer: Long,
        target: Long,
    ): NameLabel {
        val seer = members[viewer] ?: return NameLabel.INITIALS
        val seen = members[target] ?: return NameLabel.INITIALS
        val sameGroup = seer.group != null && seer.group == seen.group
        val holdsPost =
            rolesOf(target).any {
                it.commandsGroup || it == Role.ENSEMBLE_LEADER ||
                    it == Role.ENSEMBLE_DEPUTY
            }
        return if (viewer == target || sameGroup || holdsPost) NameLabel.FULL else NameLabel.INITIALS
    }

    /** Every hidden name this state carries: the ensemble, its groups, its members. */
    fun hiddenNames(): Set<HiddenName> {
        val all = LinkedHashSet<HiddenName>()
        name?.let(all::add)
        groups.values.mapTo(all) { it.name }
        members.values.mapTo(all) { it.name }
        return all
    }

    /**
     * What is wrong with this state, or `null`: a state built by [HierarchyReducer] never has anything wrong; one
     * decoded from bytes might. In English, for the logs.
     */
    fun inconsistency(): String? {
        if (!created) {
            val blank = members.isEmpty() && groups.isEmpty() && deletedGroups.isEmpty() && admins.isEmpty()
            val bare = leader == null && deputy == null && !dissolved && name == null
            return if (blank && bare) null else "content before the ensemble exists"
        }
        if (ensembleId == 0L) return "no ensemble identifier"
        if (name?.valid != true) return "bad ensemble name"
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
            if (id == 0L || id != member.id || !member.name.valid) return "bad member"
            if (!member.keys.valid ||
                OpAuth.memberId(member.keys.signing) != id
            ) {
                return "member whose keys are not its own"
            }
            if (member.group != null && member.group !in groups) return "member in a group that does not exist"
        }
        for ((id, group) in groups) {
            if (id == ROOT || id != group.id || !group.name.valid) return "bad group"
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
                name = null,
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

/** How a name is written on the map ([HierarchyState.nameLabel]). */
enum class NameLabel {
    FULL,
    INITIALS,
}
