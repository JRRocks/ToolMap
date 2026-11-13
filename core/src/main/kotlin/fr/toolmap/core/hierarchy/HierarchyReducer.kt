package fr.toolmap.core.hierarchy

/** Why an operation changed nothing. The same on every phone that holds the same operations. */
enum class Rejection {
    /** The ensemble does not exist yet at this point of the order. */
    NO_ENSEMBLE,

    /** A second creation of the ensemble. */
    ALREADY_CREATED,

    /** A creation that is not the first operation of its author, or not for this ensemble's identifier. */
    BAD_GENESIS,

    /** The ensemble asks for signed operations and this one has no valid proof of its author. */
    UNAUTHENTICATED,

    /** The order puts it after a later operation of the same author: the clock of that author went backwards. */
    OUT_OF_ORDER,

    /** The author is not, or no longer, a member of the ensemble. */
    NOT_A_MEMBER,

    /** No role of the author gives it this right over this target. */
    FORBIDDEN,

    /** An identifier 0 or a name every phone refuses. */
    MALFORMED,
    NO_SUCH_MEMBER,
    ALREADY_MEMBER,
    NOT_AN_ADMIN,

    /** The ensemble always keeps one administrator. */
    LAST_ADMIN,
    NO_SUCH_GROUP,
    GROUP_EXISTS,

    /** The identifier of a deleted group never comes back. */
    GROUP_DELETED,

    /** A group under another group: the model allows it, this version does not. */
    UNSUPPORTED_DEPTH,

    /** A group leader is a member of its group. */
    NOT_IN_GROUP,

    /** Already so: nothing to change. */
    NO_CHANGE,

    /** [HierarchyState.MAX_MEMBERS] or [HierarchyState.MAX_GROUPS] reached. */
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
        val rules = if (change is Change.CreateEnsemble && !state.created) change.rules else state.rules
        if (change is Change.CreateEnsemble && !state.created) {
            if (!isGenesisOf(state.ensembleId, op)) return Reduced(state, Rejection.BAD_GENESIS)
        }
        if (rules.signedOps && !op.authentic) return Reduced(state, Rejection.UNAUTHENTICATED)
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
                    leader = author,
                    rules = change.rules,
                    members = mapOf(author to Member(author, change.creatorName)),
                    admins = setOf(author),
                ),
            )
        }
        if (!state.created) return no(Rejection.NO_ENSEMBLE)
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

                    state.members.size >= HierarchyState.MAX_MEMBERS -> {
                        no(Rejection.TOO_MANY)
                    }

                    else -> {
                        yes(
                            state.copy(
                                members =
                                    state.members + (change.member to Member(change.member, change.name)),
                            ),
                        )
                    }
                }
            }

            is Change.RemoveMember -> {
                val member = state.members[change.member] ?: return no(Rejection.NO_SUCH_MEMBER)
                if (member.id in state.admins && state.admins.size == 1) return no(Rejection.LAST_ADMIN)
                yes(
                    state.copy(
                        leader = state.leader.takeIf { it != member.id },
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
                when {
                    change.member !in state.admins -> no(Rejection.NOT_AN_ADMIN)
                    state.admins.size == 1 -> no(Rejection.LAST_ADMIN)
                    else -> yes(state.copy(admins = state.admins - change.member))
                }
            }

            is Change.CreateGroup -> {
                when {
                    // Under the ensemble only: HierarchyState.MAX_DEPTH levels. A deeper tree starts by relaxing this.
                    change.parent != HierarchyState.ROOT -> no(Rejection.UNSUPPORTED_DEPTH)

                    change.group in state.groups -> no(Rejection.GROUP_EXISTS)

                    change.group in state.deletedGroups -> no(Rejection.GROUP_DELETED)

                    state.groups.size >= HierarchyState.MAX_GROUPS -> no(Rejection.TOO_MANY)

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
                yes(
                    state.copy(
                        members =
                            state.members.mapValues { (_, m) ->
                                if (m.group ==
                                    change.group
                                ) {
                                    m.copy(group = null)
                                } else {
                                    m
                                }
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

            is Change.SetGroupLeader -> {
                val group = state.groups[change.group] ?: return no(Rejection.NO_SUCH_GROUP)
                val member = state.members[change.member] ?: return no(Rejection.NO_SUCH_MEMBER)
                when {
                    member.group != group.id -> no(Rejection.NOT_IN_GROUP)
                    group.leader == member.id -> no(Rejection.NO_CHANGE)
                    else -> yes(state.copy(groups = state.groups + (group.id to group.copy(leader = member.id))))
                }
            }

            is Change.ClearGroupLeader -> {
                val group = state.groups[change.group] ?: return no(Rejection.NO_SUCH_GROUP)
                if (group.leader == null) return no(Rejection.NO_CHANGE)
                yes(state.copy(groups = state.groups + (group.id to group.copy(leader = null))))
            }

            is Change.SetEnsembleLeader -> {
                when (change.member) {
                    !in state.members -> no(Rejection.NO_SUCH_MEMBER)
                    state.leader -> no(Rejection.NO_CHANGE)
                    else -> yes(state.copy(leader = change.member))
                }
            }
        }
    }

    private fun newGroup(change: Change.CreateGroup) = Group(change.group, change.parent, change.name)

    /** The groups once [member] has left its own: a group it led has no leader any more. */
    private fun groupsWithout(
        state: HierarchyState,
        member: Member,
    ): Map<Long, Group> {
        val group = state.groupOf(member.id)?.takeIf { it.leader == member.id } ?: return state.groups
        return state.groups + (group.id to group.copy(leader = null))
    }

    /** Whether one of the roles of [author] reaches [change] ([RightsTable]). */
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
            val mask = state.rules.rights.mask(operation, role)
            mask and Reach.ANYONE.bit != 0 ||
                (mask and Reach.SELF.bit != 0 && member == author) ||
                (mask and Reach.OWN_GROUP.bit != 0 && own != null && group == own)
        }
    }

    /** The member a change is about ([Reach.SELF]); for a group that loses its leader, that leader. */
    private fun memberConcerned(
        state: HierarchyState,
        change: Change,
    ): Long? =
        when (change) {
            is Change.AdmitMember -> change.member
            is Change.RemoveMember -> change.member
            is Change.AddAdmin -> change.member
            is Change.RemoveAdmin -> change.member
            is Change.AssignMember -> change.member
            is Change.UnassignMember -> change.member
            is Change.SetGroupLeader -> change.member
            is Change.SetEnsembleLeader -> change.member
            is Change.ClearGroupLeader -> state.groups[change.group]?.leader
            is Change.CreateEnsemble, is Change.CreateGroup, is Change.RenameGroup, is Change.DeleteGroup -> null
        }

    /** The group a change is about ([Reach.OWN_GROUP]): the one named, or the one the member is taken out of. */
    private fun groupConcerned(
        state: HierarchyState,
        change: Change,
    ): Long? =
        when (change) {
            is Change.RenameGroup -> change.group
            is Change.DeleteGroup -> change.group
            is Change.AssignMember -> change.group
            is Change.SetGroupLeader -> change.group
            is Change.ClearGroupLeader -> change.group
            is Change.UnassignMember -> state.members[change.member]?.group
            is Change.RemoveMember -> state.members[change.member]?.group
            is Change.CreateEnsemble, is Change.AdmitMember, is Change.AddAdmin, is Change.RemoveAdmin -> null
            is Change.CreateGroup, is Change.SetEnsembleLeader -> null
        }
}
