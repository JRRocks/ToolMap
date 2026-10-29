// Port of core/src/main/kotlin/fr/toolmap/core/track/IssueLog.kt.

/// Collects `TrackIssue`s of one import without repeating the same one thousands of times on a big file.
final class IssueLog {
    static let maxIssues = 50
    static let maxDetail = 120

    private let capacity: Int
    private var seen: Set<TrackIssue> = []
    private var ordered: [TrackIssue] = []
    private var dropped = 0

    init(max: Int = 50) {
        capacity = max
    }

    func add(_ kind: TrackIssue.Kind, _ detail: String = "") {
        // Kotlin `take(120)`: the first 120 UTF-16 units.
        let utf16 = detail.utf16
        let kept = utf16.count > IssueLog.maxDetail
            ? String(decoding: Array(utf16.prefix(IssueLog.maxDetail)), as: UTF16.self)
            : detail
        let issue = TrackIssue(kind, kept)
        if seen.contains(issue) { return }
        if seen.count < capacity {
            seen.insert(issue)
            ordered.append(issue)
        } else {
            dropped += 1
        }
    }

    func toList() -> [TrackIssue] { ordered }

    /// Number of distinct issues that were not kept because the log was full.
    var overflow: Int { dropped }
}
