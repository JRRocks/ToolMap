package fr.toolmap.core.track

/** Names offered to a new waypoint: "Waypoint 12" after "Waypoint 11", so that a quick save still tells points apart. */
object WaypointNames {
    /**
     * The number following the highest "[prefix] N" among [names] (case ignored, other names skipped); 1 when there is
     * none. Gaps are not refilled: a deleted "Waypoint 3" is not offered again while "Waypoint 4" exists.
     */
    fun nextNumber(
        names: Iterable<String?>,
        prefix: String,
    ): Int {
        val pattern = Regex(Regex.escape(prefix.trim()) + "\\s+(\\d+)", RegexOption.IGNORE_CASE)
        val highest =
            names
                .mapNotNull { name -> name?.trim()?.let(pattern::matchEntire)?.groupValues?.get(1)?.toIntOrNull() }
                .maxOrNull() ?: 0
        return highest + 1
    }
}
