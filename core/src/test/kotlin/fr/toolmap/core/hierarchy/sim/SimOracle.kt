package fr.toolmap.core.hierarchy.sim

import fr.toolmap.core.hierarchy.Channel
import fr.toolmap.core.hierarchy.HiddenName
import fr.toolmap.core.hierarchy.HierarchyState
import fr.toolmap.core.hierarchy.Lane
import fr.toolmap.core.hierarchy.NameLabel
import fr.toolmap.core.hierarchy.ShareKind

/**
 * A second reading of the tables of `docs/specs/group-hierarchy.md` § 4, written from the text of the specification
 * and the user's answers (§§ 2.1, 4.2 to 4.4, 10 quinquies to 10 sexies), not from the engine's code (P39.10): who is
 * waiting, who reads each channel, where each one may share each kind, where its alert goes, who sees whose position,
 * which names it may be told, how a name is written. The rules of the start only (the ones every operation is created
 * with), one level of groups. [differences] compares it with the engine in a state.
 */
object SimOracle {
    /** § 2.1: admitted, in no group, and none of the posts that give the access without a group. */
    fun waiting(
        s: HierarchyState,
        m: Long,
    ): Boolean {
        val member = s.members[m] ?: return false
        return member.group == null && m != s.creator && m !in s.admins && m != s.leader && m != s.deputy
    }

    fun access(
        s: HierarchyState,
        m: Long,
    ): Boolean = !s.dissolved && m in s.members && !waiting(s, m)

    /** The command of the whole operation (S1, S2, T1): its leader, its deputy, the administrators, the creator. */
    private fun commandOfAll(s: HierarchyState): Set<Long> =
        (listOfNotNull(s.leader, s.deputy, s.creator) + s.admins).filterTo(LinkedHashSet()) { it in s.members }

    private fun posts(
        s: HierarchyState,
        group: Long,
    ): Set<Long> = listOfNotNull(s.groups[group]?.leader, s.groups[group]?.deputy).toSet()

    private fun groupOf(
        s: HierarchyState,
        m: Long,
    ): Long? = s.members[m]?.group

    /** Every place to share in [s]: the operation, the leaders' lane, three per group, one per member who waits. */
    fun channels(s: HierarchyState): Set<Channel> {
        val all = LinkedHashSet<Channel>()
        all += Channel.ENSEMBLE
        all += Channel.LEADERS
        for (g in s.groups.keys) {
            all += Channel.unit(g)
            all += Channel.up(g)
            all += Channel.down(g)
        }
        for (m in s.members.keys) if (waiting(s, m)) all += Channel.waiting(m)
        return all
    }

    /** § 4.2, « Qui lit un canal ». */
    fun readers(
        s: HierarchyState,
        c: Channel,
    ): Set<Long> {
        if (s.dissolved) return emptySet()
        return when (c.lane) {
            Lane.UNIT -> {
                if (c.scope == HierarchyState.ROOT) {
                    s.members.keys.filterTo(HashSet()) { access(s, it) }
                } else if (c.scope in s.groups) {
                    // Q16: its members, nobody else (the administrators and the leader of the operation neither).
                    s.members.values.filter { it.group == c.scope }.mapTo(HashSet()) { it.id }
                } else {
                    emptySet()
                }
            }

            Lane.UP -> {
                if (c.scope !in s.groups) return emptySet()
                // Q3, S1: the leader of the group and its deputy, and the command of the operation; no reader
                // when the group has neither a leader nor a deputy (nobody would write there).
                val p = posts(s, c.scope)
                if (p.isEmpty()) emptySet() else p + commandOfAll(s)
            }

            Lane.DOWN -> {
                if (c.scope !in s.groups) return emptySet()
                // Q15: the group, and the command of the operation that writes to it; no reader for an empty group.
                val inside = s.members.values.filter { it.group == c.scope }.mapTo(HashSet()) { it.id }
                if (inside.isEmpty()) emptySet() else inside + commandOfAll(s)
            }

            Lane.LEADERS -> {
                // Q6, T5, T13: the leader and the deputy of every group, the command of the operation.
                s.groups.keys.flatMapTo(HashSet()) { posts(s, it) } + commandOfAll(s)
            }

            Lane.WAITING -> {
                // Q10: the one who waits, the administrators, the creator.
                if (!waiting(s, c.scope)) emptySet() else setOf(c.scope) + s.admins + listOfNotNull(s.creator)
            }
        }
    }

    private fun commandsAll(
        s: HierarchyState,
        m: Long,
    ) = m == s.creator || m in s.admins || m == s.leader || m == s.deputy

    private fun commandsGroup(
        s: HierarchyState,
        m: Long,
    ): Long? {
        val g = groupOf(s, m) ?: return null
        return g.takeIf { m in posts(s, it) }
    }

    /** § 4.2, « Table de départ », the user's answers S2, Q15, S3, T2, T4, T13, T16: where [m] may share [kind]. */
    fun shareChannels(
        s: HierarchyState,
        m: Long,
        kind: ShareKind,
    ): Set<Channel> {
        if (s.dissolved || m !in s.members) return emptySet()
        val wanted = LinkedHashSet<Channel>()
        if (waiting(s, m)) {
            // Only the cells of who waits: its position and its name, to its own lane.
            if (kind == ShareKind.POSITION || kind == ShareKind.ROSTER) wanted += Channel.waiting(m)
        } else {
            val group = groupOf(s, m)
            val leadsGroup = commandsGroup(s, m)
            val all = commandsAll(s, m)
            when (kind) {
                ShareKind.POSITION -> {
                    wanted += Channel.ENSEMBLE
                }

                ShareKind.ROSTER -> {
                    wanted += Channel.ENSEMBLE
                    // 2026-11-16: the creator and the administrators tell who waits the names it may know (the
                    // operation's after a rename, its own), in its lane.
                    if (m == s.creator || m in s.admins) {
                        for (w in s.members.keys) if (waiting(s, w)) wanted += Channel.waiting(w)
                    }
                }

                ShareKind.SKETCH, ShareKind.WAYPOINT -> {
                    if (all) {
                        wanted += Channel.ENSEMBLE
                        for (g in s.groups.keys) wanted += Channel.down(g)
                    }
                    if (leadsGroup != null) wanted += Channel.up(leadsGroup)
                    if (group != null) wanted += Channel.unit(group)
                }

                ShareKind.MESSAGE -> {
                    if (all) {
                        wanted += Channel.ENSEMBLE
                        for (g in s.groups.keys) wanted += Channel.up(g)
                    }
                    if (leadsGroup != null) wanted += Channel.up(leadsGroup)
                    if (group != null) wanted += Channel.unit(group)
                }

                ShareKind.ALERT -> {
                    alertChannel(s, m)?.let(wanted::add)
                }
            }
        }
        // Whatever the table says, one writes only where one reads (the key is needed).
        return wanted.filterTo(LinkedHashSet()) { m in readers(s, it) }
    }

    /**
     * § 4.2, « L'alerte : une seule destination par personne » (Q6, T4, T13, T16): the leader of the operation or its
     * deputy, to everyone placed; else the creator, an administrator, the leader of a group or its deputy, to every
     * leader and not to its own group; else a member, to its group; who waits, nowhere.
     */
    fun alertChannel(
        s: HierarchyState,
        m: Long,
    ): Channel? {
        if (s.dissolved || m !in s.members || waiting(s, m)) return null
        val c =
            when {
                m == s.leader || m == s.deputy -> Channel.ENSEMBLE
                m == s.creator || m in s.admins || commandsGroup(s, m) != null -> Channel.LEADERS
                else -> groupOf(s, m)?.let { Channel.unit(it) }
            } ?: return null
        return c.takeIf { m in readers(s, it) }
    }

    /** § 4.3 with the table of the start (R2, Q10). */
    fun canSee(
        s: HierarchyState,
        viewer: Long,
        target: Long,
    ): Boolean {
        if (viewer !in s.members || target !in s.members) return false
        if (viewer == target) return true
        if (!access(s, viewer)) return false
        if (waiting(s, target)) return viewer in readers(s, Channel.waiting(target))
        return access(s, target)
    }

    /**
     * § 8.8 and T14: every name with the access; the operation's and its own while waiting; none otherwise. The
     * names of a member (2026-11-16): its name, the one it asked for while nobody answered, the last one answered.
     */
    fun namesFor(
        s: HierarchyState,
        m: Long,
    ): Set<HiddenName> {
        fun own(x: Long) = setOfNotNull(s.members[x]?.name, s.nameRequests[x], s.nameAnswers[x]?.name)
        if (access(s, m)) {
            val all = LinkedHashSet<HiddenName>()
            s.name?.let(all::add)
            s.groups.values.mapTo(all) { it.name }
            for (x in s.members.keys) all.addAll(own(x))
            return all
        }
        if (!s.dissolved && waiting(s, m)) return setOfNotNull(s.name) + own(m)
        return emptySet()
    }

    /** § 4.4, Q17, T11, T16: in full for oneself, one's group, and whoever holds a post; initials otherwise. */
    fun label(
        s: HierarchyState,
        viewer: Long,
        target: Long,
    ): NameLabel {
        val seer = s.members[viewer] ?: return NameLabel.INITIALS
        val seen = s.members[target] ?: return NameLabel.INITIALS
        val sameGroup = seer.group != null && seer.group == seen.group
        val post =
            target == s.leader || target == s.deputy ||
                (seen.group != null && target in posts(s, seen.group))
        return if (viewer == target || sameGroup || post) NameLabel.FULL else NameLabel.INITIALS
    }

    /** Where the engine and this reading disagree in [s]; empty when they agree on everything. */
    fun differences(s: HierarchyState): List<String> {
        val out = ArrayList<String>()
        val ids = s.members.keys.sortedWith { a, b -> java.lang.Long.compareUnsigned(a, b) }
        val engineChannels = s.channels().toSet()
        val oracleChannels = channels(s)
        if (engineChannels != oracleChannels) out += "channels: engine $engineChannels, oracle $oracleChannels"
        for (c in engineChannels + oracleChannels) {
            val engine = s.readersOf(c).mapTo(HashSet()) { it.id }
            val oracle = readers(s, c)
            if (engine != oracle) out += "readers of $c: engine ${hex(engine)}, oracle ${hex(oracle)}"
        }
        for (m in ids) {
            if (s.isWaiting(m) != waiting(s, m)) out += "waiting ${hex(m)}: engine ${s.isWaiting(m)}"
            if (s.hasAccess(m) != access(s, m)) out += "access ${hex(m)}: engine ${s.hasAccess(m)}"
            val read = s.channelsReadBy(m).toSet()
            val oracleRead = oracleChannels.filterTo(HashSet()) { m in readers(s, it) }
            if (read != oracleRead) out += "channels read by ${hex(m)}: engine $read, oracle $oracleRead"
            for (kind in ShareKind.entries) {
                val engine = s.shareChannels(m, kind).toSet()
                val oracle = shareChannels(s, m, kind)
                if (engine != oracle) out += "${hex(m)} shares $kind: engine $engine, oracle $oracle"
            }
            if (s.alertChannel(m) != alertChannel(s, m)) {
                out += "alert of ${hex(m)}: engine ${s.alertChannel(m)}, oracle ${alertChannel(s, m)}"
            }
            if (s.namesFor(m) != namesFor(s, m)) out += "names for ${hex(m)} differ"
            for (t in ids) {
                if (s.canSee(m, t) != canSee(s, m, t)) out += "${hex(m)} sees ${hex(t)}: engine ${s.canSee(m, t)}"
                if (s.nameLabel(m, t) !=
                    label(s, m, t)
                ) {
                    out += "label of ${hex(t)} for ${hex(m)}: engine ${s.nameLabel(m, t)}"
                }
            }
        }
        return out
    }

    fun hex(id: Long): String = java.lang.Long.toHexString(id).padStart(16, '0').take(6)

    fun hex(ids: Collection<Long>): String = ids.map(::hex).sorted().toString()
}
