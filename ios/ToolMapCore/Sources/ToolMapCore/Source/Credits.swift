// Port of core/src/main/kotlin/fr/toolmap/core/source/Credits.kt.

import Foundation

/// The credits of the maps shown, on one short line (variant E of `docs/previews/p19-reticule.html`): each copyright
/// is cut at its separators, the « (c) », « Style : », « contributors » and licences in brackets are dropped,
/// OpenStreetMap becomes « OSM », and each name is kept once, in order: `© OSM · SRTM · OpenTopoMap · IGN`.
public enum Credits {
    /// Kotlin `[,|·;]`.
    private static let separators: Set<Character> = [",", "|", "·", ";"]
    /// Kotlin `\([^)]*\)`.
    private static let brackets = try! NSRegularExpression(pattern: "\\([^)]*\\)")
    /// Kotlin `(?i)©|\bstyle\s*:|\bcontributors\b|\bcopyright\b` (`\s` of the JVM written out).
    private static let noise = try! NSRegularExpression(
        pattern: "(?i)©|\\bstyle[ \\t\\n\\x0B\\f\\r]*:|\\bcontributors\\b|\\bcopyright\\b"
    )
    /// Kotlin `\s+` (JVM whitespace).
    private static let whitespace = try! NSRegularExpression(pattern: "[ \\t\\n\\x0B\\f\\r]+")

    public static func short(_ copyrights: [String]) -> String {
        var names: [String] = []
        var seen = Set<String>()
        for copyright in copyrights {
            let parts = copyright.split(omittingEmptySubsequences: false, whereSeparator: { separators.contains($0) })
            for part in parts {
                let name = clean(String(part))
                if name.isEmpty { continue }
                if seen.insert(name.lowercased()).inserted {
                    names.append(name)
                }
            }
        }
        return names.isEmpty ? "" : "© " + names.joined(separator: " · ")
    }

    private static func clean(_ part: String) -> String {
        var s = replace(brackets, part, " ")
        s = replace(noise, s, " ")
        s = kotlinTrim(replace(whitespace, s, " "))
        return equalsIgnoringCase(s, "OpenStreetMap") ? "OSM" : s
    }

    private static func replace(_ regex: NSRegularExpression, _ text: String, _ template: String) -> String {
        let range = NSRange(text.startIndex..., in: text)
        return regex.stringByReplacingMatches(in: text, options: [], range: range, withTemplate: template)
    }
}
