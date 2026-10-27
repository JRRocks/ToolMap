package fr.toolmap.core.track

/** Collects [TrackIssue]s of one import without repeating the same one thousands of times on a big file. */
internal class IssueLog(
    private val max: Int = MAX_ISSUES,
) {
    private val seen = LinkedHashSet<TrackIssue>()
    private var dropped = 0

    fun add(
        kind: TrackIssue.Kind,
        detail: String = "",
    ) {
        val issue = TrackIssue(kind, detail.take(MAX_DETAIL))
        if (issue in seen) return
        if (seen.size < max) seen += issue else dropped++
    }

    fun toList(): List<TrackIssue> = seen.toList()

    /** Number of distinct issues that were not kept because the log was full. */
    val overflow: Int get() = dropped

    private companion object {
        const val MAX_ISSUES = 50
        const val MAX_DETAIL = 120
    }
}
