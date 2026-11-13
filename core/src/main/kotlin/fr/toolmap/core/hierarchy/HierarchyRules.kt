package fr.toolmap.core.hierarchy

import java.util.EnumSet

/**
 * The roles of the hierarchical groups (P39, `docs/specs/group-hierarchy.md` § 3). A member holds several at once:
 * every member is a [MEMBER], an administrator may also lead a group. Roles are never stored: they are read from the
 * state (the creator, the administrators, the leader and the deputy of the ensemble and of each group).
 */
enum class Role(
    val code: Int,
) {
    /** The one member who made the ensemble, or the one it handed it to. */
    CREATOR(1),
    ADMIN(2),
    ENSEMBLE_LEADER(3),
    ENSEMBLE_DEPUTY(4),
    GROUP_LEADER(5),
    GROUP_DEPUTY(6),

    /** Everyone admitted, waiting for a group or not. */
    MEMBER(7),
    ;

    /** The role whose rights this one has too: a deputy has the rights of its leader (a rule, not a cell). */
    val actsAs: Role?
        get() =
            when (this) {
                ENSEMBLE_DEPUTY -> ENSEMBLE_LEADER
                GROUP_DEPUTY -> GROUP_LEADER
                else -> null
            }

    companion object {
        fun of(code: Int): Role? = entries.firstOrNull { it.code == code }
    }
}

/** What a member may ask for: the lines of the [RightsTable]. Creating the ensemble is not one (no author yet). */
enum class Operation(
    val code: Int,
) {
    ADMIT_MEMBER(1),
    REMOVE_MEMBER(2),
    ADD_ADMIN(3),
    REMOVE_ADMIN(4),
    CREATE_GROUP(5),
    RENAME_GROUP(6),
    DELETE_GROUP(7),
    ASSIGN_MEMBER(8),
    UNASSIGN_MEMBER(9),

    /** Naming someone leader of a group, or leaving that post vacant. */
    NAME_GROUP_LEADER(10),
    NAME_GROUP_DEPUTY(11),
    NAME_ENSEMBLE_LEADER(12),
    NAME_ENSEMBLE_DEPUTY(13),
    TRANSFER_CREATOR(14),
    DISSOLVE(15),
    ;

    companion object {
        fun of(code: Int): Operation? = entries.firstOrNull { it.code == code }
    }
}

/** How far a right goes; a cell of the [RightsTable] holds any of them, none meaning « never ». */
enum class Reach(
    val bit: Int,
) {
    /** Only when the member concerned is the author: leaving, stepping down, choosing one's own group. */
    SELF(1),

    /** Only when the group concerned is the author's own group (the one it belongs to, and so the one it leads). */
    OWN_GROUP(2),

    /** Whoever and whichever group is concerned. */
    ANYONE(4),
    ;

    companion object {
        internal const val ALL_BITS = 7
    }
}

/**
 * Who may do what: one cell per [Operation] and [Role], data and not code, so that the answers of the user change the
 * table and not the engine. An author is allowed when one of the roles it holds has a cell that reaches the operation.
 * The table of an ensemble travels in its first operation: every phone of the ensemble applies the same one.
 */
class RightsTable private constructor(
    private val masks: IntArray,
) {
    fun reach(
        operation: Operation,
        role: Role,
    ): Set<Reach> {
        val mask = mask(operation, role)
        return Reach.entries.filterTo(EnumSet.noneOf(Reach::class.java)) { mask and it.bit != 0 }
    }

    /** The cell as written. */
    internal fun mask(
        operation: Operation,
        role: Role,
    ): Int = masks[index(operation, role)]

    /** What [role] may really do: its own cell, and the one of the leader it is the deputy of. */
    internal fun effectiveMask(
        operation: Operation,
        role: Role,
    ): Int = mask(operation, role) or (role.actsAs?.let { mask(operation, it) } ?: 0)

    /** A copy where [role] has exactly [reach] over [operation] (nothing: never). */
    fun with(
        operation: Operation,
        role: Role,
        vararg reach: Reach,
    ): RightsTable = withMask(operation, role, reach.fold(0) { mask, r -> mask or r.bit })

    internal fun withMask(
        operation: Operation,
        role: Role,
        mask: Int,
    ): RightsTable {
        require(mask and Reach.ALL_BITS.inv() == 0) { "unknown reach" }
        return RightsTable(masks.copyOf().also { it[index(operation, role)] = mask })
    }

    override fun equals(other: Any?): Boolean = other is RightsTable && masks.contentEquals(other.masks)

    override fun hashCode(): Int = masks.contentHashCode()

    override fun toString(): String =
        Operation.entries.joinToString(prefix = "RightsTable(", postfix = ")") { op ->
            "$op=" + Role.entries.filter { mask(op, it) != 0 }.joinToString("/") { "$it${reach(op, it)}" }
        }

    companion object {
        private fun index(
            operation: Operation,
            role: Role,
        ) = operation.ordinal * Role.entries.size + role.ordinal

        /** Nobody may do anything. */
        val NONE = RightsTable(IntArray(Operation.entries.size * Role.entries.size))

        /** What the administrators decide: who comes in, the groups, who is in which, who holds which post. */
        private val ADMINISTRATION =
            listOf(
                Operation.ADMIT_MEMBER,
                Operation.CREATE_GROUP,
                Operation.RENAME_GROUP,
                Operation.DELETE_GROUP,
                Operation.ASSIGN_MEMBER,
                Operation.UNASSIGN_MEMBER,
                Operation.NAME_GROUP_LEADER,
                Operation.NAME_GROUP_DEPUTY,
                Operation.NAME_ENSEMBLE_LEADER,
                Operation.NAME_ENSEMBLE_DEPUTY,
            )

        /** What only the creator decides: the administrators, who is put out, its own succession, the end. */
        private val CREATION =
            listOf(
                Operation.REMOVE_MEMBER,
                Operation.ADD_ADMIN,
                Operation.REMOVE_ADMIN,
                Operation.TRANSFER_CREATOR,
                Operation.DISSOLVE,
            )

        /**
         * The answers of the user (2026-11-13, spec § 10 bis and § 10 ter): the creator names and removes the
         * administrators and is the only one who puts a member out; the administrators (and the creator) admit,
         * make the groups, assign and name to every post; a member may leave by itself and nothing else. The
         * leaders have no right over the hierarchy: what they have more is what they may share ([ShareTable]).
         */
        val DEFAULT: RightsTable =
            (ADMINISTRATION + CREATION)
                .fold(NONE) { table, op -> table.with(op, Role.CREATOR, Reach.ANYONE) }
                .let { ADMINISTRATION.fold(it) { table, op -> table.with(op, Role.ADMIN, Reach.ANYONE) } }
                .with(Operation.REMOVE_MEMBER, Role.MEMBER, Reach.SELF)
    }
}

/** Whom a viewer sees, as the columns of the [VisibilityTable]; a target may be in several. */
enum class Audience(
    val bit: Int,
) {
    /** The members of the viewer's own group. */
    SAME_GROUP(1),

    /** The leaders of the groups. */
    GROUP_LEADERS(2),

    /** The leader of the ensemble. */
    ENSEMBLE_LEADER(4),

    /** The administrators. */
    ADMINS(8),

    /** Every member of the ensemble who has a group or a post that gives access. */
    EVERYONE(16),
    ;

    companion object {
        internal const val ALL_BITS = 31
    }
}

/**
 * Who sees the position of whom on the map: for each [Role] of the viewer, the [Audience]s it sees. A viewer sees a
 * target when one of the roles it holds sees one of the audiences the target is in. Everyone always sees oneself.
 * Someone who waits for a group sees nobody and is seen by nobody, whatever the table ([HierarchyState.hasAccess]).
 *
 * A table narrower than « everyone » is a filter of the display only: positions are sealed with the key of the
 * whole ensemble (spec § 8).
 */
class VisibilityTable private constructor(
    private val masks: IntArray,
) {
    fun sees(role: Role): Set<Audience> {
        val mask = mask(role)
        return Audience.entries.filterTo(EnumSet.noneOf(Audience::class.java)) { mask and it.bit != 0 }
    }

    internal fun mask(role: Role): Int = masks[role.ordinal]

    /** A copy where [role] sees exactly [audiences] (nothing: only itself). */
    fun with(
        role: Role,
        vararg audiences: Audience,
    ): VisibilityTable = withMask(role, audiences.fold(0) { mask, a -> mask or a.bit })

    internal fun withMask(
        role: Role,
        mask: Int,
    ): VisibilityTable {
        require(mask and Audience.ALL_BITS.inv() == 0) { "unknown audience" }
        return VisibilityTable(masks.copyOf().also { it[role.ordinal] = mask })
    }

    override fun equals(other: Any?): Boolean = other is VisibilityTable && masks.contentEquals(other.masks)

    override fun hashCode(): Int = masks.contentHashCode()

    override fun toString(): String =
        Role.entries.joinToString(prefix = "VisibilityTable(", postfix = ")") { "$it=${sees(it)}" }

    companion object {
        /** Everyone sees only oneself. */
        val NONE = VisibilityTable(IntArray(Role.entries.size))

        /** The answer of the user: everyone sees everyone. */
        val DEFAULT: VisibilityTable = Role.entries.fold(NONE) { table, role -> table.with(role, Audience.EVERYONE) }
    }
}

/** What a member shares with others: the lines of the [ShareTable]. */
enum class ShareKind(
    val code: Int,
) {
    POSITION(1),
    SKETCH(2),
    WAYPOINT(3),
    MESSAGE(4),
    ;

    internal val bit: Int get() = 1 shl (code - 1)

    companion object {
        internal const val ALL_BITS = 15

        fun of(code: Int): ShareKind? = entries.firstOrNull { it.code == code }
    }
}

/** Into which unit (the ensemble, or a group) a share may go; a cell of the [ShareTable] holds any of them. */
enum class ShareReach(
    val bit: Int,
) {
    /** The author's own group. */
    OWN_UNIT(1),

    /** The unit just above the author's own group, and no higher: the ensemble for a group under the ensemble. */
    PARENT_UNIT(2),

    /** The whole ensemble, wherever the author is. */
    ENSEMBLE(4),
    ;

    companion object {
        internal const val ALL_BITS = 7
    }
}

/**
 * Who may share what, and how far up: one cell per [ShareKind] and [Role], data like the [RightsTable]. What is
 * shared in a unit is read by everyone in that unit and under it ([HierarchyState.readersOf]); nobody shares into a
 * unit it is not in, nor downwards into a group that is not its own.
 */
class ShareTable private constructor(
    private val masks: IntArray,
) {
    fun reach(
        kind: ShareKind,
        role: Role,
    ): Set<ShareReach> {
        val mask = mask(kind, role)
        return ShareReach.entries.filterTo(EnumSet.noneOf(ShareReach::class.java)) { mask and it.bit != 0 }
    }

    internal fun mask(
        kind: ShareKind,
        role: Role,
    ): Int = masks[index(kind, role)]

    /** What [role] may really share: its own cell, and the one of the leader it is the deputy of. */
    internal fun effectiveMask(
        kind: ShareKind,
        role: Role,
    ): Int = mask(kind, role) or (role.actsAs?.let { mask(kind, it) } ?: 0)

    /** A copy where [role] shares [kind] exactly as far as [reach] (nothing: never). */
    fun with(
        kind: ShareKind,
        role: Role,
        vararg reach: ShareReach,
    ): ShareTable = withMask(kind, role, reach.fold(0) { mask, r -> mask or r.bit })

    internal fun withMask(
        kind: ShareKind,
        role: Role,
        mask: Int,
    ): ShareTable {
        require(mask and ShareReach.ALL_BITS.inv() == 0) { "unknown share reach" }
        return ShareTable(masks.copyOf().also { it[index(kind, role)] = mask })
    }

    override fun equals(other: Any?): Boolean = other is ShareTable && masks.contentEquals(other.masks)

    override fun hashCode(): Int = masks.contentHashCode()

    override fun toString(): String =
        ShareKind.entries.joinToString(prefix = "ShareTable(", postfix = ")") { kind ->
            "$kind=" + Role.entries.filter { mask(kind, it) != 0 }.joinToString("/") { "$it${reach(kind, it)}" }
        }

    companion object {
        private fun index(
            kind: ShareKind,
            role: Role,
        ) = kind.ordinal * Role.entries.size + role.ordinal

        /** Nobody shares anything. */
        val NONE = ShareTable(IntArray(ShareKind.entries.size * Role.entries.size))

        /** The ones who speak for the whole ensemble: its leader (so its deputy), the administrators, the creator. */
        private val ENSEMBLE_VOICES = listOf(Role.CREATOR, Role.ADMIN, Role.ENSEMBLE_LEADER)

        /**
         * The answers of the user (2026-11-13): positions go to the whole ensemble; a member shares sketches,
         * waypoints and messages inside its own group; the leader of a group (so its deputy) may also share
         * sketches and waypoints one level up, never higher; messages to the whole ensemble are for its leader, its
         * deputy and the administrators.
         */
        val DEFAULT: ShareTable =
            ShareKind.entries
                .fold(NONE) { table, kind ->
                    ENSEMBLE_VOICES.fold(table) { t, role -> t.with(kind, role, ShareReach.ENSEMBLE) }
                }.with(ShareKind.POSITION, Role.MEMBER, ShareReach.ENSEMBLE)
                .with(ShareKind.SKETCH, Role.MEMBER, ShareReach.OWN_UNIT)
                .with(ShareKind.WAYPOINT, Role.MEMBER, ShareReach.OWN_UNIT)
                .with(ShareKind.MESSAGE, Role.MEMBER, ShareReach.OWN_UNIT)
                .with(ShareKind.SKETCH, Role.GROUP_LEADER, ShareReach.PARENT_UNIT)
                .with(ShareKind.WAYPOINT, Role.GROUP_LEADER, ShareReach.PARENT_UNIT)
    }
}

/**
 * How big an ensemble may grow: data fixed by its first operation, so that a later version starts bigger ensembles
 * without another engine. [maxDepth] is the number of levels of groups under the ensemble: 1 today (ensemble ▸
 * groups), 2 for groups inside groups.
 */
data class HierarchyLimits(
    val maxMembers: Int = 30,
    val maxGroups: Int = 6,
    val maxDepth: Int = 1,
) {
    /** Whether the binary format can carry these limits. */
    val valid: Boolean
        get() =
            maxMembers in 1..FORMAT_MAX_MEMBERS && maxGroups in 0..FORMAT_MAX_GROUPS &&
                maxDepth in 1..FORMAT_MAX_DEPTH

    companion object {
        const val FORMAT_MAX_MEMBERS = 4096
        const val FORMAT_MAX_GROUPS = 1024
        const val FORMAT_MAX_DEPTH = 8

        /** The start asked by the user: 30 members, 6 groups, one level of groups. */
        val DEFAULT = HierarchyLimits()
    }
}

/**
 * The rules of one ensemble, fixed by its first operation and part of its identifier. Every operation of an ensemble
 * is signed by its author ([OpAuth]): that is not a rule one can turn off.
 *
 * @property signedShares the kinds of share that carry the signature of their author, so that nobody who holds the
 * key of a unit can share in the name of another member. All of them at the start (the ensemble is the mode where
 * security is required); an ensemble may leave positions out, which are many, to save 64 bytes and a signature on
 * each (spec § 12, question 1).
 */
data class HierarchyRules(
    val rights: RightsTable = RightsTable.DEFAULT,
    val visibility: VisibilityTable = VisibilityTable.DEFAULT,
    val shares: ShareTable = ShareTable.DEFAULT,
    val limits: HierarchyLimits = HierarchyLimits.DEFAULT,
    val signedShares: Set<ShareKind> = SIGNED_BY_DEFAULT,
) {
    companion object {
        val SIGNED_BY_DEFAULT: Set<ShareKind> = ShareKind.entries.toSet()
        val DEFAULT = HierarchyRules()
    }
}
