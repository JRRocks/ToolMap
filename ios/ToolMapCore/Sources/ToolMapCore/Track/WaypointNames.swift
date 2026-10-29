// Port of core/src/main/kotlin/fr/toolmap/core/track/WaypointNames.kt.

import Foundation

/// Names offered to a new waypoint: "Waypoint 12" after "Waypoint 11", so that a quick save still tells points apart.
public enum WaypointNames {
    /// The number following the highest "`prefix` N" among `names` (case ignored, other names skipped); 1 when there
    /// is none. Gaps are not refilled: a deleted "Waypoint 3" is not offered again while "Waypoint 4" exists.
    public static func nextNumber(_ names: [String?], _ prefix: String) -> Int {
        // Kotlin `Regex(Regex.escape(prefix.trim()) + "\\s+(\\d+)", IGNORE_CASE)` (Java's `\s` and `\d` are ASCII).
        let pattern = NSRegularExpression.escapedPattern(for: kotlinTrim(prefix)) + #"[ \t\n\u000B\f\r]+([0-9]+)"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else { return 1 }
        var highest = 0
        for case let name? in names {
            let text = kotlinTrim(name)
            let whole = NSRange(text.startIndex ..< text.endIndex, in: text)
            guard let match = regex.firstMatch(in: text, options: [.anchored], range: whole),
                  match.range == whole,
                  let digits = Range(match.range(at: 1), in: text),
                  let number = kotlinInt(String(text[digits]))
            else { continue }
            highest = Swift.max(highest, number)
        }
        return highest + 1
    }
}
