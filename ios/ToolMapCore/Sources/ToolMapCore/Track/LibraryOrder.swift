// Port of core/src/main/kotlin/fr/toolmap/core/track/LibraryOrder.kt.

import Foundation

/// The order of the explorer of the tracks and waypoints (F7): the most recent first, by name, or the longest first.
public enum LibrarySort: String, Codable, CaseIterable, Sendable {
    case date = "DATE"
    case name = "NAME"
    case distance = "DISTANCE"
}

/// The kinds of item the explorer shows (F7).
public enum LibraryFilter: String, Codable, CaseIterable, Sendable {
    case all = "ALL"
    case tracks = "TRACKS"
    case routes = "ROUTES"
    case waypoints = "WAYPOINTS"
}

/// Also the `LibraryKind` of search/SearchModels.kt, which has the same three values.
public enum LibraryKind: String, Codable, CaseIterable, Sendable {
    case track = "TRACK"
    case route = "ROUTE"
    case waypoint = "WAYPOINT"
}

/// What the explorer sorts and searches one item by.
public struct LibraryKey: Equatable, Hashable, Sendable {
    public let kind: LibraryKind
    public let name: String?
    /// When the item was recorded, or else imported.
    public let timeMillis: Int64
    /// nil for a waypoint.
    public let distanceMeters: Double?

    public init(kind: LibraryKind, name: String?, timeMillis: Int64, distanceMeters: Double?) {
        self.kind = kind
        self.name = name
        self.timeMillis = timeMillis
        self.distanceMeters = distanceMeters
    }
}

/// Sort, filter and search of the explorer (F7); the folders are not sorted here, they stay on top.
public enum LibraryOrder {
    public static func accepts(_ filter: LibraryFilter, _ kind: LibraryKind) -> Bool {
        switch filter {
        case .all: true
        case .tracks: kind == .track
        case .routes: kind == .route
        case .waypoints: kind == .waypoint
        }
    }

    /// Kotlin's `Comparator`: negative when `a` comes first. Recent first; or by name, case and accents ignored,
    /// unnamed items last; or longest first, waypoints last. Ties keep the most recent first.
    public static func compare(
        _ a: LibraryKey,
        _ b: LibraryKey,
        _ sort: LibrarySort,
        locale: Locale = Locale(identifier: "fr")
    ) -> Int {
        switch sort {
        case .date:
            return recent(a, b)
        case .name:
            let blankA = isBlank(a.name)
            let blankB = isBlank(b.name)
            if blankA != blankB { return blankA ? 1 : -1 }
            let nameA = kotlinTrim(a.name ?? "")
            let nameB = kotlinTrim(b.name ?? "")
            // `Collator` of strength PRIMARY: case and accents are not differences.
            switch nameA.compare(nameB, options: [.caseInsensitive, .diacriticInsensitive], range: nil, locale: locale) {
            case .orderedAscending: return -1
            case .orderedDescending: return 1
            case .orderedSame: return recent(a, b)
            }
        case .distance:
            let noneA = a.distanceMeters == nil
            let noneB = b.distanceMeters == nil
            if noneA != noneB { return noneA ? 1 : -1 }
            let da = a.distanceMeters ?? 0.0
            let db = b.distanceMeters ?? 0.0
            if da != db { return da > db ? -1 : 1 }
            return recent(a, b)
        }
    }

    /// `areInIncreasingOrder` for `sorted(by:)`.
    public static func comparator(
        _ sort: LibrarySort,
        locale: Locale = Locale(identifier: "fr")
    ) -> @Sendable (LibraryKey, LibraryKey) -> Bool {
        { a, b in compare(a, b, sort, locale: locale) < 0 }
    }

    /// Kotlin `sortedWith(comparator(sort))`: a stable sort, equal keys keep their order.
    public static func sorted(
        _ keys: [LibraryKey],
        by sort: LibrarySort,
        locale: Locale = Locale(identifier: "fr")
    ) -> [LibraryKey] {
        keys.enumerated().sorted { x, y in
            let c = compare(x.element, y.element, sort, locale: locale)
            return c != 0 ? c < 0 : x.offset < y.offset
        }.map(\.element)
    }

    /// True when `name` holds every word of `query`, case and accents ignored; a blank query matches everything.
    public static func matches(_ name: String?, _ query: String) -> Bool {
        let words = kotlinSplit(fold(query), " ").filter { !isBlank($0) }
        if words.isEmpty { return true }
        let text = fold(name ?? "")
        return words.allSatisfy { text.contains($0) }
    }

    private static func recent(_ a: LibraryKey, _ b: LibraryKey) -> Int {
        a.timeMillis == b.timeMillis ? 0 : (a.timeMillis > b.timeMillis ? -1 : 1)
    }

    /// Kotlin `isNullOrBlank()`.
    private static func isBlank(_ text: String?) -> Bool {
        guard let text else { return true }
        return text.unicodeScalars.allSatisfy { KotlinText.isWhitespace($0) }
    }

    /// Lower case without accents: « Écrins » and « ecrins » match.
    private static func fold(_ text: String) -> String {
        var kept = String.UnicodeScalarView()
        for scalar in text.decomposedStringWithCanonicalMapping.unicodeScalars
            where scalar.properties.generalCategory != .nonspacingMark {
            kept.append(scalar)
        }
        return String(kept).lowercased()
    }
}
