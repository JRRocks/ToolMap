// Port of core/src/main/kotlin/fr/toolmap/core/search/LocalSearch.kt (LibraryItem, SearchText, LocalSearch,
// SearchMerge; CoordinateQuery is in CoordinateQuery.swift).

import Foundation

/// What the search sees of one of the user's own tracks, routes or waypoints.
public struct LibraryItem: Equatable, Hashable, Sendable {
    public let kind: LibraryKind
    public let id: Int64
    public let name: String?
    /// Comment and description, searched too.
    public let note: String?
    /// A point to show: the waypoint itself, the middle of a line's extent.
    public let lat: Double
    public let lon: Double
    public let bounds: Bounds?

    public init(
        kind: LibraryKind,
        id: Int64,
        name: String?,
        note: String?,
        lat: Double,
        lon: Double,
        bounds: Bounds? = nil
    ) {
        self.kind = kind
        self.id = id
        self.name = name
        self.note = note
        self.lat = lat
        self.lon = lon
        self.bounds = bounds
    }
}

/// Case- and accent-insensitive comparison of what the user typed with what is stored.
public enum SearchText {
    /// `Écrins` and `ecrins` fold to the same text.
    public static func fold(_ text: String) -> String {
        // Kotlin: lowercase(Locale.ROOT), NFD, `\p{M}+` removed, `\s+` made one space, trimmed.
        var out = String.UnicodeScalarView()
        var inSpace = false
        for scalar in text.lowercased().decomposedStringWithCanonicalMapping.unicodeScalars {
            if isMark(scalar) { continue }
            if KotlinText.isRegexSpace(scalar) {
                if !inSpace { out.append(" ") }
                inSpace = true
            } else {
                out.append(scalar)
                inSpace = false
            }
        }
        return kotlinTrim(String(out))
    }

    public static func tokens(_ query: String) -> [String] {
        kotlinSplit(fold(query), " ").filter { !$0.isEmpty }
    }

    /// The words of a folded text: `l'ecrin` is `l` and `ecrin`, so that "ecrin" finds a name where it starts a word.
    public static func words(_ folded: String) -> [String] {
        // Kotlin `split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }`.
        var result: [String] = []
        var current = String.UnicodeScalarView()
        for scalar in folded.unicodeScalars {
            if isLetterOrNumber(scalar) {
                current.append(scalar)
            } else if !current.isEmpty {
                result.append(String(current))
                current = String.UnicodeScalarView()
            }
        }
        if !current.isEmpty { result.append(String(current)) }
        return result
    }

    private static func isMark(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .nonspacingMark, .spacingMark, .enclosingMark: return true
        default: return false
        }
    }

    private static func isLetterOrNumber(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter,
             .decimalNumber, .letterNumber, .otherNumber:
            return true
        default:
            return false
        }
    }
}

/// Search in the user's own library (feature P9.1): offline, instant, nothing leaves the phone.
public enum LocalSearch {
    public static let defaultLimit = 30
    private static let noteExcerpt = 80
    private static let exact = 0
    private static let prefix = 1
    private static let wordPrefix = 2
    private static let inName = 3
    private static let inNote = 4

    private struct Ranked {
        let score: Int
        let foldedName: String
        let item: LibraryItem
        let name: String
        let order: Int
    }

    /// Items whose name or note contain every word typed, the closest names first.
    public static func search(_ query: String, _ items: [LibraryItem], limit: Int = defaultLimit) -> [SearchResult] {
        let tokens = SearchText.tokens(query)
        guard let firstToken = tokens.first else { return [] }
        let whole = tokens.joined(separator: " ")
        var ranked: [Ranked] = []
        for (order, item) in items.enumerated() {
            guard let name = item.name, !kotlinTrim(name).isEmpty else { continue }
            let foldedName = SearchText.fold(name)
            let foldedNote = SearchText.fold(item.note ?? "")
            let nameHasAll = tokens.allSatisfy { contains(foldedName, $0) }
            let eitherHasAll = tokens.allSatisfy { contains(foldedName, $0) || contains(foldedNote, $0) }
            if !eitherHasAll { continue }
            let score: Int
            if foldedName == whole {
                score = exact
            } else if startsWith(foldedName, whole) {
                score = prefix
            } else if nameHasAll, SearchText.words(foldedName).contains(where: { startsWith($0, firstToken) }) {
                score = wordPrefix
            } else if nameHasAll {
                score = inName
            } else {
                score = inNote
            }
            ranked.append(Ranked(score: score, foldedName: foldedName, item: item, name: name, order: order))
        }
        ranked.sort { a, b in
            if a.score != b.score { return a.score < b.score }
            if a.foldedName != b.foldedName { return a.foldedName.utf16.lexicographicallyPrecedes(b.foldedName.utf16) }
            if a.item.id != b.item.id { return a.item.id < b.item.id }
            return a.order < b.order
        }
        return ranked.prefix(Swift.max(limit, 0)).map { entry in
            SearchResult(
                title: entry.name,
                subtitle: String(firstLine(entry.item.note ?? "").prefix(noteExcerpt)),
                lat: entry.item.lat,
                lon: entry.item.lon,
                source: .library,
                bounds: entry.item.bounds,
                libraryKind: entry.item.kind,
                libraryId: entry.item.id
            )
        }
    }

    /// Kotlin `lineSequence().firstOrNull().orEmpty()`.
    private static func firstLine(_ text: String) -> String {
        let scalars = text.unicodeScalars
        let end = scalars.firstIndex { $0 == "\n" || $0 == "\r" } ?? scalars.endIndex
        return String(String.UnicodeScalarView(scalars[..<end]))
    }

    /// Kotlin `contains`: a plain search of the characters.
    private static func contains(_ text: String, _ part: String) -> Bool {
        text.range(of: part, options: .literal) != nil
    }

    /// Kotlin `startsWith`: a plain comparison of the characters.
    private static func startsWith(_ text: String, _ start: String) -> Bool {
        text.utf16.starts(with: start.utf16)
    }
}

/// Puts the answers of several sources in one list without showing the same place twice.
public enum SearchMerge {
    private static let samePlaceMeters = 300.0
    private static let articles: Set<String> = ["le", "la", "les", "l", "un", "une", "du", "de", "des", "d", "the"]

    /// `groups` in order of preference: a later result is dropped when an earlier one has the same name nearby.
    public static func merge(_ groups: [[SearchResult]], limit: Int) -> [SearchResult] {
        var kept: [SearchResult] = []
        for group in groups {
            for result in group {
                if kept.count >= limit { return kept }
                if !kept.contains(where: { sameAs($0, result) }) { kept.append(result) }
            }
        }
        return kept
    }

    private static func sameAs(_ a: SearchResult, _ b: SearchResult) -> Bool {
        key(a.title) == key(b.title) && Geodesy.distance(a.lat, a.lon, b.lat, b.lon) <= samePlaceMeters
    }

    private static func key(_ title: String) -> String {
        SearchText.words(SearchText.fold(title)).filter { !articles.contains($0) }.joined(separator: " ")
    }
}
