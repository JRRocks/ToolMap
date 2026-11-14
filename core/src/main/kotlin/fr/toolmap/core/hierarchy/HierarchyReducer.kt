package fr.toolmap.core.hierarchy

/** Why an operation changed nothing. The same on every phone that holds the same operations. */
enum class Rejection {
    /** The ensemble does not exist yet at this point of the order. */
    NO_ENSEMBLE,

    /** A second creation of the ensemble. */
    ALREADY_CREATED,

    /** A creation that is not the first operation of its author, or not for this ensemble's identifier. */
    BAD_GENESIS,

    /** No valid signature of its author, or not with the key the author was admitted with. */
    UNAUTHENTICATED,

    /** Its clock does not pass the one of the operation its author wrote before it: nobody writes in its own past. */
    OUT_OF_ORDER,

    /** The creator ended the ensemble: nothing applies any more. */
    DISSOLVED,

    /** The author is not, or no longer, a member of the ensemble. */
    NOT_A_MEMBER,

    /** No role of the author gives it this right over this target. */
    FORBIDDEN,

    /** An identifier 0, a name or keys every phone refuses. */
    MALFORMED,
    NO_SUCH_MEMBER,
    ALREADY_MEMBER,

    /** Not an administrator: the role to take away is not held, or the one the ensemble is handed to has to be one. */
    NOT_AN_ADMIN,

    /** The creator does not leave and is not put out: it first hands the ensemble over, or dissolves it. */
    IS_CREATOR,
    NO_SUCH_GROUP,
    GROUP_EXISTS,

    /** The identifier of a deleted group never comes back. */
    GROUP_DELETED,

    /** A group deeper than the limits of the ensemble allow ([HierarchyLimits.maxDepth]). */
    TOO_DEEP,

    /** A group that still has groups under it is not deleted. */
    HAS_SUBGROUPS,

    /** The leader and the deputy of a group are members of that group. */
    NOT_IN_GROUP,

    /** Already so: nothing to change. */
    NO_CHANGE,

    /** The limits of the ensemble are reached ([HierarchyLimits.maxMembers], [HierarchyLimits.maxGroups]). */
    TOO_MANY,

    /** This phone can write no more operations (number, clock), or must first catch up with its own. */
    LOG_FULL,
}

/** The state after an operation, and why it changed nothing when it did not. */
class Reduced(
    val state: HierarchyState,
    val rejection: Rejection?,
)

/**
 * The rules of the hierarchy as one pure function: a state and an operation give a state. The rights of the author
 * are read in the state the operation applies to, not in the one its author saw.
 */
object HierarchyReducer {
    fun reduce(
        state: HierarchyState,
        op: Op,
    ): Reduced {
        val change = op.change
        if (op.ensembleId != state.ensembleId || op.isMalformed) return Reduced(state, Rejection.MALFORMED)
        // Every operation of an ensemble is signed by its author: nothing unsigned ever applies.
        if (!op.authentic) return Reduced(state, Rejection.UNAUTHENTICATED)
        val genesis = change is Change.CreateEnsemble && !state.created
        if (genesis && !isGenesisOf(state.ensembleId, op)) return Reduced(state, Rejection.BAD_GENESIS)
        // The key that signs is the very key the author came in with, not only one with the same fingerprint.
        val creation = (change as? Change.CreateEnsemble).takeIf { genesis }
        val registered = creation?.creatorKeys?.signing ?: state.members[op.author]?.keys?.signing
        if (registered != null && !registered.contentEquals(op.proof?.publicKey)) {
            return Reduced(state, Rejection.UNAUTHENTICATED)
        }
        return decide(state, op.author, change)
    }

    /** Whether [op] is the one first operation the ensemble [ensembleId] can have (any clock). */
    internal fun isGenesisOf(
        ensembleId: Long,
        op: Op,
    ): Boolean {
        val change = op.change
        return op.seq == 1 && change is Change.CreateEnsemble && OpAuth.ensembleId(op.author, change) == ensembleId
    }

    /**
     * Why [author] could not make [change] now, or `null` when it could: for an interface that greys out what the
     * member may not do. The proof of the operation is not part of the question.
     */
    fun check(
        state: HierarchyState,
        author: Long,
        change: Change,
    ): Rejection? =
        if (author == 0L ||
            change.isMalformed()
        ) {
            Rejection.MALFORMED
        } else {
            decide(state, author, change).rejection
        }

    private fun decide(
        state: HierarchyState,
        author: Long,
        change: Change,
    ): Reduced {
        fun no(reason: Rejection) = Reduced(state, reason)

        fun yes(next: HierarchyState) = Reduced(next, null)
        if (change is Change.CreateEnsemble) {
            if (state.created) return no(Rejection.ALREADY_CREATED)
            return yes(
                state.copy(
                    name = change.name,
                    creator = author,
                    rules = change.rules,
                    members = mapOf(author to Member(author, change.creatorName, change.creatorKeys)),
                ),
            )
        }
        if (!state.created) return no(Rejection.NO_ENSEMBLE)
        if (state.dissolved) return no(Rejection.DISSOLVED)
        if (author !in state.members) return no(Rejection.NOT_A_MEMBER)
        if (!allowed(state, author, change)) return no(Rejection.FORBIDDEN)
        return when (change) {
            is Change.CreateEnsemble -> {
                no(Rejection.ALREADY_CREATED)
            }

            is Change.AdmitMember -> {
                when {
                    change.member in state.members -> {
                        no(Rejection.ALREADY_MEMBER)
                    }

                    state.members.size >= state.rules.limits.maxMembers -> {
                        no(Rejection.TOO_MANY)
                    }

                    else -> {
                        val member = Member(change.member, change.name, change.keys)
                        yes(state.copy(members = state.members + (member.id to member)))
                    }
                }
            }

            is Change.RemoveMember -> {
                val member = state.members[change.member] ?: return no(Rejection.NO_SUCH_MEMBER)
                // There is always one creator: it hands the ensemble over before it goes, or it dissolves it. Its
                // plain leaving is refused rather than read as a dissolution: a handing over that lost against the
                // leaving of the successor would otherwise end the ensemble for everyone by surprise.
                if (member.id == state.creator) return no(Rejection.IS_CREATOR)
                val (leader, deputy) = postsWithout(state, state.leader, state.deputy, member.id)
                yes(
                    state.copy(
                        leader = leader,
                        deputy = deputy,
                        members = state.members - member.id,
                        admins = state.admins - member.id,
                        groups = groupsWithout(state, member),
                    ),
                )
            }

            is Change.AddAdmin -> {
                when (change.member) {
                    !in state.members -> no(Rejection.NO_SUCH_MEMBER)
                    in state.admins -> no(Rejection.NO_CHANGE)
                    else -> yes(state.copy(admins = state.admins + change.member))
                }
            }

            is Change.RemoveAdmin -> {
                if (change.member !in state.admins) return no(Rejection.NOT_AN_ADMIN)
                yes(state.copy(admins = state.admins - change.member))
            }

            is Change.CreateGroup -> {
                when {
                    change.group in state.groups -> no(Rejection.GROUP_EXISTS)

                    change.group in state.deletedGroups -> no(Rejection.GROUP_DELETED)

                    !state.isUnit(change.parent) -> no(Rejection.NO_SUCH_GROUP)

                    // As many levels as the limits of this ensemble say: one today, the rules hold for more.
                    state.depthOf(change.parent) >= state.rules.limits.maxDepth -> no(Rejection.TOO_DEEP)

                    state.groups.size >= state.rules.limits.maxGroups -> no(Rejection.TOO_MANY)

                    else -> yes(state.copy(groups = state.groups + (change.group to newGroup(change))))
                }
            }

            is Change.RenameGroup -> {
                val group = state.groups[change.group] ?: return no(Rejection.NO_SUCH_GROUP)
                if (group.name == change.name) return no(Rejection.NO_CHANGE)
                yes(state.copy(groups = state.groups + (group.id to group.copy(name = change.name))))
            }

            is Change.DeleteGroup -> {
                if (change.group !in state.groups) return no(Rejection.NO_SUCH_GROUP)
                if (state.groups.values.any { it.parent == change.group }) return no(Rejection.HAS_SUBGROUPS)
                yes(
                    state.copy(
                        members =
                            state.members.mapValues { (_, m) ->
                                m.takeIf { it.group != change.group }
                                    ?: m.copy(group = null)
                            },
                        groups = state.groups - change.group,
                        deletedGroups = state.deletedGroups + change.group,
                    ),
                )
            }

            is Change.AssignMember -> {
                val member = state.members[change.member] ?: return no(Rejection.NO_SUCH_MEMBER)
                if (change.group !in state.groups) return no(Rejection.NO_SUCH_GROUP)
                if (member.group == change.group) return no(Rejection.NO_CHANGE)
                // One group at most: the new one takes the place of the old one.
                yes(
                    state.copy(
                        members = state.members + (member.id to member.copy(group = change.group)),
                        groups = groupsWithout(state, member),
                    ),
                )
            }

            is Change.UnassignMember -> {
                val member = state.members[change.member] ?: return no(Rejection.NO_SUCH_MEMBER)
                if (member.group == null) return no(Rejection.NO_CHANGE)
                yes(
                    state.copy(
                        members = state.members + (member.id to member.copy(group = null)),
                        groups = groupsWithout(state, member),
                    ),
                )
            }

            is Change.SetPost -> {
                val group = if (change.unit == HierarchyState.ROOT) null else state.groups[change.unit]
                if (change.unit != HierarchyState.ROOT && group == null) return no(Rejection.NO_SUCH_GROUP)
                val member = state.members[change.member] ?: return no(Rejection.NO_SUCH_MEMBER)
                if (group != null && member.group != group.id) return no(Rejection.NOT_IN_GROUP)
                val leader = state.leaderOf(change.unit)
                val deputy = state.deputyOf(change.unit)
                val holder = if (change.post == Post.LEADER) leader else deputy
                if (holder == member.id) return no(Rejection.NO_CHANGE)
                // One post of a unit at most: a deputy named leader leaves its post of deputy, and the reverse.
                val newLeader = if (change.post == Post.LEADER) member.id else leader.takeIf { it != member.id }
                val newDeputy = if (change.post == Post.DEPUTY) member.id else deputy.takeIf { it != member.id }
                yes(withPosts(state, group, newLeader, newDeputy))
            }

            is Change.ClearPost -> {
                val group = if (change.unit == HierarchyState.ROOT) null else state.groups[change.unit]
                if (change.unit != HierarchyState.ROOT && group == null) return no(Rejection.NO_SUCH_GROUP)
                val leader = state.leaderOf(change.unit)
                val deputy = state.deputyOf(change.unit)
                if ((if (change.post == Post.LEADER) leader else deputy) == null) return no(Rejection.NO_CHANGE)
                // A vacant post blocks nothing: the unit goes on without it.
                yes(
                    withPosts(
                        state,
                        group,
                        leader.takeIf { change.post != Post.LEADER },
                        deputy.takeIf { change.post != Post.DEPUTY },
                    ),
                )
            }

            is Change.TransferCreator -> {
                when (change.member) {
                    !in state.members -> no(Rejection.NO_SUCH_MEMBER)

                    state.creator -> no(Rejection.NO_CHANGE)

                    // The successor is an administrator (the user's answer Q13); it does not have to accept.
                    !in state.admins -> no(Rejection.NOT_AN_ADMIN)

                    else -> yes(state.copy(creator = change.member))
                }
            }

            Change.Dissolve -> {
                yes(state.copy(dissolved = true))
            }
        }
    }

    private fun newGroup(change: Change.CreateGroup) = Group(change.group, change.parent, change.name)

    /** [state] with [leader] and [deputy] at the posts of [group], or of the ensemble when [group] is `null`. */
    private fun withPosts(
        state: HierarchyState,
        group: Group?,
        leader: Long?,
        deputy: Long?,
    ): HierarchyState =
        if (group == null) {
            state.copy(leader = leader, deputy = deputy)
        } else {
            state.copy(groups = state.groups + (group.id to group.copy(leader = leader, deputy = deputy)))
        }

    /** The groups once [member] has left its own: it no longer holds a post there ([postsWithout]). */
    private fun groupsWithout(
        state: HierarchyState,
        member: Member,
    ): Map<Long, Group> {
        val group = state.groupOf(member.id) ?: return state.groups
        if (group.leader != member.id && group.deputy != member.id) return state.groups
        val (leader, deputy) = postsWithout(state, group.leader, group.deputy, member.id)
        return state.groups + (group.id to group.copy(leader = leader, deputy = deputy))
    }

    /**
     * The leader and the deputy of a unit once [gone] has left it. When the leader goes, its deputy becomes the
     * leader and the post of deputy is vacant (the user's answers Q18 and S4, [HierarchyRules.deputySucceeds]);
     * without a deputy the post of leader stays vacant. Part of the operation that makes the leader go, so the same
     * on every phone whatever the order the operations arrived in.
     */
    private fun postsWithout(
        state: HierarchyState,
        leader: Long?,
        deputy: Long?,
        gone: Long,
    ): Pair<Long?, Long?> =
        when (gone) {
            leader -> if (state.rules.deputySucceeds) deputy to null else null to deputy
            deputy -> leader to null
            else -> leader to deputy
        }

    /** Whether one of the roles of [author] reaches [change] ([RightsTable]); a deputy has its leader's rights. */
    private fun allowed(
        state: HierarchyState,
        author: Long,
        change: Change,
    ): Boolean {
        val operation = change.operation ?: return false
        val own = state.members[author]?.group
        val member = memberConcerned(state, change)
        val group = groupConcerned(state, change)
        return state.rolesOf(author).any { role ->
            val mask = state.rules.rights.effectiveMask(operation, role)
            mask and Reach.ANYONE.bit != 0 ||
                (mask and Reach.SELF.bit != 0 && member == author) ||
                (mask and Reach.OWN_GROUP.bit != 0 && own != null && group == own)
        }
    }

    /** The member a change is about ([Reach.SELF]); for a post left vacant, the one who held it. */
    private fun memberConcerned(
        state: HierarchyState,
        change: Change,
    ): Long? =
        when (change) {
            is Change.AdmitMember -> {
                change.member
            }

            is Change.RemoveMember -> {
                change.member
            }

            is Change.AddAdmin -> {
                change.member
            }

            is Change.RemoveAdmin -> {
                change.member
            }

            is Change.AssignMember -> {
                change.member
            }

            is Change.UnassignMember -> {
                change.member
            }

            is Change.SetPost -> {
                change.member
            }

            is Change.TransferCreator -> {
                change.member
            }

            is Change.ClearPost -> {
                if (change.post == Post.LEADER) state.leaderOf(change.unit) else state.deputyOf(change.unit)
            }

            is Change.CreateEnsemble, is Change.CreateGroup, is Change.RenameGroup, is Change.DeleteGroup -> {
                null
            }

            Change.Dissolve -> {
                null
            }
        }

    /** The group a change is about ([Reach.OWN_GROUP]): the one named, or the one the member is taken out of. */
    private fun groupConcerned(
        state: HierarchyState,
        change: Change,
    ): Long? =
        when (change) {
            is Change.RenameGroup -> {
                change.group
            }

            is Change.DeleteGroup -> {
                change.group
            }

            is Change.AssignMember -> {
                change.group
            }

            is Change.SetPost -> {
                change.unit.takeIf { it != HierarchyState.ROOT }
            }

            is Change.ClearPost -> {
                change.unit.takeIf { it != HierarchyState.ROOT }
            }

            is Change.UnassignMember -> {
                state.members[change.member]?.group
            }

            is Change.RemoveMember -> {
                state.members[change.member]?.group
            }

            is Change.CreateEnsemble, is Change.AdmitMember, is Change.AddAdmin, is Change.RemoveAdmin -> {
                null
            }

            is Change.CreateGroup, is Change.TransferCreator, Change.Dissolve -> {
                null
            }
        }
}
