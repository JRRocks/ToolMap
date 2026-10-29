import Foundation

// Port of core/src/main/kotlin/fr/toolmap/core/settings/SettingsSearch.kt. Places in a text are counted in UTF-16 code
// units, as Kotlin counts the characters of a String (`String.Index(utf16Offset:in:)` turns them into indices).

/// The search of « Réglages » (P29.9, « Loupe »): a setting is found when every word typed is in its title, its
/// explanation or the names of its options, whatever the case and the accents; the final « s » or « x » of a word typed
/// is not needed, so that « gants » finds « Mode gants » and « repère » finds « Repères et traces ».
public enum SettingsSearch {
    /// The shortest word whose final « s » or « x » is taken for a plural: « gps » keeps its « s ».
    private static let pluralMinLength = 4
    private static let ascii: UInt16 = 128

    /// The words typed, folded, each without its plural mark; empty when nothing is typed.
    public static func terms(_ query: String) -> [String] {
        var out: [String] = []
        for token in tokens(query) {
            let units = Array(token.utf16)
            let last = units.last
            let term: String
            if units.count >= pluralMinLength, last == 0x73 || last == 0x78 { // 's', 'x'
                term = String(decoding: units.dropLast(), as: UTF16.self)
            } else {
                term = token
            }
            if !out.contains(term) { out.append(term) }
        }
        return out
    }

    /// Whether every one of `terms` is in one of `texts`; never with no term.
    public static func matches(_ terms: [String], _ texts: [String]) -> Bool {
        if terms.isEmpty { return false }
        let folded = Array(texts.map { fold($0) }.joined(separator: "\n").utf16)
        return terms.allSatisfy { indexOf(folded, Array($0.utf16), from: 0) != nil }
    }

    /// Where `terms` are in `text`, as ranges of its UTF-16 code units for the bold: sorted, merged when they touch.
    public static func highlights(_ text: String, _ terms: [String]) -> [ClosedRange<Int>] {
        let folded = Array(fold(text).utf16)
        var found: [ClosedRange<Int>] = []
        for term in terms where !term.isEmpty {
            let needle = Array(term.utf16)
            var start = indexOf(folded, needle, from: 0)
            while let at = start {
                found.append(at ... at + needle.count - 1)
                start = indexOf(folded, needle, from: at + 1)
            }
        }
        found.sort { $0.lowerBound < $1.lowerBound }
        var merged: [ClosedRange<Int>] = []
        for range in found {
            if let last = merged.last, range.lowerBound <= last.upperBound + 1 {
                merged[merged.count - 1] = last.lowerBound ... max(last.upperBound, range.upperBound)
            } else {
                merged.append(range)
            }
        }
        return merged
    }

    /// `text` in lower case without its accents (« É » is « e »), one UTF-16 code unit for one, so that a place in the
    /// folded text is the same place in `text`.
    public static func fold(_ text: String) -> String {
        var out: [UInt16] = []
        out.reserveCapacity(text.utf16.count)
        for unit in text.utf16 {
            out.append(unit < ascii ? lowercased(unit) : foldUnit(unit))
        }
        return String(decoding: out, as: UTF16.self)
    }

    /// Java `Normalizer.normalize(c, NFD)[0].lowercaseChar()` on one UTF-16 code unit; half of a surrogate pair is kept.
    private static func foldUnit(_ unit: UInt16) -> UInt16 {
        guard let scalar = Unicode.Scalar(unit) else { return unit }
        let first = String(Character(scalar)).decomposedStringWithCanonicalMapping.utf16.first ?? unit
        return lowercased(first)
    }

    /// Java `Character.toLowerCase(char)`: the lower case of one UTF-16 code unit, itself when it has none that fits.
    private static func lowercased(_ unit: UInt16) -> UInt16 {
        guard let scalar = Unicode.Scalar(unit) else { return unit }
        guard let lower = scalar.properties.lowercaseMapping.utf16.first, !UTF16.isLeadSurrogate(lower) else {
            return unit
        }
        return lower
    }

    /// Where `needle` starts in `haystack` from `from` on (Java `String.indexOf`, code unit by code unit).
    private static func indexOf(_ haystack: [UInt16], _ needle: [UInt16], from: Int) -> Int? {
        let start = max(from, 0)
        if needle.isEmpty { return start <= haystack.count ? start : nil }
        if needle.count > haystack.count { return nil }
        var i = start
        while i + needle.count <= haystack.count {
            var j = 0
            while j < needle.count, haystack[i + j] == needle[j] {
                j += 1
            }
            if j == needle.count { return i }
            i += 1
        }
        return nil
    }

    /// The words of `query` as `SearchText.tokens` (search/LocalSearch.kt) gives them: lower case, decomposed (NFD),
    /// without the combining marks, split on spaces. Kept here so that the settings search does not wait for the port
    /// of the search package; `SearchText.tokens` can replace it once ported.
    private static func tokens(_ query: String) -> [String] {
        var folded = String.UnicodeScalarView()
        var afterSpace = false
        for scalar in query.lowercased().decomposedStringWithCanonicalMapping.unicodeScalars {
            switch scalar.properties.generalCategory {
            case .nonspacingMark, .spacingMark, .enclosingMark:
                continue
            default:
                break
            }
            if KotlinText.isRegexSpace(scalar) {
                if !afterSpace { folded.append(" ") }
                afterSpace = true
            } else {
                folded.append(scalar)
                afterSpace = false
            }
        }
        return kotlinSplit(kotlinTrim(String(folded)), " ").filter { !$0.isEmpty }
    }
}
