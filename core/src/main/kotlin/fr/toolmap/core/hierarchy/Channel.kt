package fr.toolmap.core.hierarchy

/**
 * The kinds of place where things are shared (`docs/specs/group-hierarchy.md` § 4.2). Each lane has its own readers
 * ([HierarchyState.readersOf]) and so its own content keys: who is not a reader of a lane cannot open what is shared
 * there, whatever its application shows.
 */
enum class Lane(
    val code: Int,
) {
    /** A unit itself: the whole ensemble, or one group (read by that group and by nobody else). */
    UNIT(1),

    /** Up from a group: its leader and its deputy, with those who command the unit above. */
    UP(2),

    /** Down to one precise group: its members, with those who command the unit above, who write there. */
    DOWN(3),

    /** Between the leaders: the leader and the deputy of every unit, the administrators and the creator. */
    LEADERS(4),

    /** From one member who waits for a group to the administrators. */
    WAITING(5),
    ;

    companion object {
        fun of(code: Int): Lane? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One place where things are shared: a [lane] and what it is the lane of ([scope]): a unit ([HierarchyState.ROOT]
 * for the ensemble) for [Lane.UNIT], a group for [Lane.UP] and [Lane.DOWN], [HierarchyState.ROOT] for
 * [Lane.LEADERS], the member who waits for [Lane.WAITING].
 */
data class Channel(
    val lane: Lane,
    val scope: Long,
) {
    override fun toString(): String = "$lane(${java.lang.Long.toUnsignedString(scope, 16)})"

    companion object {
        /** The whole ensemble: everyone who has a group or a post that gives access. */
        val ENSEMBLE = Channel(Lane.UNIT, HierarchyState.ROOT)

        val LEADERS = Channel(Lane.LEADERS, HierarchyState.ROOT)

        /** The own channel of [unit]: the ensemble or a group. */
        fun unit(unit: Long) = Channel(Lane.UNIT, unit)

        fun up(group: Long) = Channel(Lane.UP, group)

        fun down(group: Long) = Channel(Lane.DOWN, group)

        fun waiting(member: Long) = Channel(Lane.WAITING, member)
    }
}
