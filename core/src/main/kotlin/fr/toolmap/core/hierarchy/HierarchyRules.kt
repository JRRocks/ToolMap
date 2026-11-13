package fr.toolmap.core.hierarchy

import java.util.EnumSet

/**
 * The roles of the hierarchical groups (P39, `docs/specs/group-hierarchy.md`). A member holds several at once: every
 * member is a [MEMBER], an administrator may also lead a group. Roles are never stored: they are read from the state
 * (the administrators, the leader of the ensemble, the leader of each group).
 */
enum class Role(
    val code: Int,
) {
    ADMIN(1),
    ENSEMBLE_LEADER(2),
    GROUP_LEADER(3),
    MEMBER(4),
    ;

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
    SET_GROUP_LEADER(10),
    CLEAR_GROUP_LEADER(11),
    SET_ENSEMBLE_LEADER(12),
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

    internal fun mask(
        operation: Operation,
        role: Role,
    ): Int = masks[index(operation, role)]

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

        /**
         * The careful start: only the administrators change roles, groups and assignments; a member may leave by
         * itself. The leaders have a title and no right until the user says otherwise.
         */
        val DEFAULT: RightsTable =
            Operation.entries
                .fold(NONE) { table, op -> table.with(op, Role.ADMIN, Reach.ANYONE) }
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

    /** Every member of the ensemble. */
    EVERYONE(16),
    ;

    companion object {
        internal const val ALL_BITS = 31
    }
}

/**
 * Who sees the position of whom: for each [Role] of the viewer, the [Audience]s it sees. A viewer sees a target when
 * one of the roles it holds sees one of the audiences the target is in. Everyone always sees oneself.
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

        /** The start: everyone sees everyone. */
        val DEFAULT: VisibilityTable = Role.entries.fold(NONE) { table, role -> table.with(role, Audience.EVERYONE) }
    }
}

/**
 * The rules of one ensemble, fixed by its first operation.
 *
 * @property signedOps when `true`, an operation without a valid proof of its author ([OpAuth]) is refused by every
 * phone. Off until the user and P39.3 decide where the keys of the members live.
 */
data class HierarchyRules(
    val rights: RightsTable = RightsTable.DEFAULT,
    val visibility: VisibilityTable = VisibilityTable.DEFAULT,
    val signedOps: Boolean = false,
) {
    companion object {
        val DEFAULT = HierarchyRules()
    }
}
