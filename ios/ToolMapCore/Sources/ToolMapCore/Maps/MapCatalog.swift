// Port of core/src/main/kotlin/fr/toolmap/core/maps/MapCatalog.kt.

import Foundation

/// The families of the "Available maps" catalogue, in the order the screen lists them.
public enum GroupKind: String, CaseIterable, Sendable {
    /// Maps built on OpenStreetMap data (OpenStreetMap, OpenTopoMap...).
    case openStreetMap = "OPENSTREETMAP"
    /// The official IGN maps and layers served by the Géoplateforme.
    case ign = "IGN"
    /// Maps assembled from other maps by zoom range (ADR-031), built in or made by the user.
    case multiZoom = "MULTI_ZOOM"
    /// Maps the user added: an imported `.aqx` file or the "add a map" wizard.
    case imported = "IMPORTED"
    /// Packages of tiles with no server (an imported `.mbtiles`).
    case local = "LOCAL"
    case other = "OTHER"
}

/// One section of the catalogue.
public struct MapGroup: Equatable, Hashable, Sendable {
    public let kind: GroupKind
    public let sources: [TileSource]

    public init(kind: GroupKind, sources: [TileSource]) {
        self.kind = kind
        self.sources = sources
    }

    /// Credit line under the title ("© IGN · geopf.fr"): only for the groups that come from one provider, where the
    /// first map's copyright speaks for all of them.
    public var credit: String? {
        if kind != .ign, kind != .openStreetMap { return nil }
        let owner = sources.lazy.compactMap { $0.copyright.flatMap(MapCatalog.cleanCopyright) }.first
        let site = sources.lazy.compactMap { $0.dataSource.flatMap(MapCatalog.hostOf) }.first
        var parts: [String] = []
        if let owner { parts.append("© " + owner) }
        if let site { parts.append(site) }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}

/// Groups the maps of the catalogue the way AlpineQuest's "On-demand maps" screen does.
public enum MapCatalog {
    /// The sections to show: elevation data is left out (it is not a map to look at), empty sections are dropped, and
    /// inside a section the base maps come before the overlays, each in catalogue order.
    public static func groups(_ sources: [TileSource], isImported: (String) -> Bool) -> [MapGroup] {
        let visible = sources.filter { $0.type != "dem" }
        var result: [MapGroup] = []
        for kind in GroupKind.allCases {
            let members = visible.filter { kindOf($0, isImported: isImported) == kind }
            if members.isEmpty { continue }
            result.append(MapGroup(kind: kind, sources: members.filter { !$0.isLayer } + members.filter(\.isLayer)))
        }
        return result
    }

    /// The same sections without the provided maps the user hid from the list (F3), unless `showHidden`. A map the user
    /// added is never hidden: it is deleted instead.
    public static func groups(
        _ sources: [TileSource],
        hidden: Set<String>,
        showHidden: Bool,
        isImported: (String) -> Bool
    ) -> [MapGroup] {
        let shown = showHidden ? sources : sources.filter { !isHidden($0.id, hidden: hidden, isImported: isImported) }
        return groups(shown, isImported: isImported)
    }

    /// Whether `sourceId` is a provided map that the user hid from the list.
    public static func isHidden(_ sourceId: String, hidden: Set<String>, isImported: (String) -> Bool) -> Bool {
        hidden.contains(sourceId) && !isImported(sourceId)
    }

    /// The sections keeping only the maps whose name or section title holds every word of `query` (F5), case and
    /// accents ignored; sections left empty are dropped. A blank query keeps everything.
    public static func search(_ groups: [MapGroup], _ query: String, title: (GroupKind) -> String) -> [MapGroup] {
        let words = wordsOf(query)
        if words.isEmpty { return groups }
        var result: [MapGroup] = []
        for group in groups {
            let groupText = fold(title(group.kind))
            let kept = group.sources.filter { source in
                let text = fold(source.name + " " + groupText)
                return words.allSatisfy { contains(text, $0) }
            }
            if !kept.isEmpty { result.append(MapGroup(kind: group.kind, sources: kept)) }
        }
        return result
    }

    /// Whether `text` holds every word of `query`, whatever the case and the accents: the search of the online catalogue
    /// from « Cartes disponibles » (P29.6), as `search` does for the maps of the list.
    public static func matches(_ text: String, _ query: String) -> Bool {
        let words = wordsOf(query)
        let folded = fold(text)
        return !words.isEmpty && words.allSatisfy { contains(folded, $0) }
    }

    /// Kotlin `fold(query).split(' ').filter { it.isNotBlank() }`.
    private static func wordsOf(_ query: String) -> [String] {
        kotlinSplit(fold(query), " ").filter { !kotlinTrim($0).isEmpty }
    }

    /// Lower case without accents: « Énergie » and « energie » match.
    private static func fold(_ text: String) -> String {
        var out = String.UnicodeScalarView()
        for scalar in text.decomposedStringWithCanonicalMapping.unicodeScalars
            where scalar.properties.generalCategory != .nonspacingMark
        {
            out.append(scalar)
        }
        return String(out).lowercased()
    }

    /// Kotlin `CharSequence.contains(other)`: a plain search of the characters.
    private static func contains(_ text: String, _ part: String) -> Bool {
        text.range(of: part, options: .literal) != nil
    }

    public static func kindOf(_ source: TileSource, isImported: (String) -> Bool) -> GroupKind {
        if source.isLocalOnly { return .local }
        if source.isMultiZoom { return .multiZoom }
        if isImported(source.id) { return .imported }
        return providerOf(source) ?? .other
    }

    private static func providerOf(_ source: TileSource) -> GroupKind? {
        let text = [source.copyright, source.dataSource].compactMap { $0 }.joined(separator: " ").lowercased()
        // Kotlin `Regex("[^a-z]+").split(text)`: the runs of letters a to z.
        var letterRuns: [String] = []
        var run = ""
        for scalar in text.unicodeScalars {
            if ("a" ... "z").contains(scalar) {
                run.unicodeScalars.append(scalar)
            } else if !run.isEmpty {
                letterRuns.append(run)
                run = ""
            }
        }
        if !run.isEmpty { letterRuns.append(run) }
        if contains(text, "geopf.fr") || contains(text, "geoportail") || letterRuns.contains("ign") { return .ign }
        if contains(text, "openstreetmap") || contains(text, "opentopomap") { return .openStreetMap }
        return nil
    }

    /// A copyright without its "(c)" / "©" prefix, or nil when nothing is left.
    public static func cleanCopyright(_ text: String) -> String? {
        var s = kotlinTrim(text)
        for prefix in ["(c)", "(C)", "©"] where s.hasPrefix(prefix) {
            s = String(s.dropFirst(prefix.count))
        }
        s = kotlinTrim(s)
        return s.isEmpty ? nil : s
    }

    /// The host of a web address without `www.`, or nil when it is not an address.
    public static func hostOf(_ address: String) -> String? {
        guard let host = URLComponents(string: kotlinTrim(address))?.host else { return nil }
        let bare = host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
        return bare.isEmpty ? nil : bare
    }
}
