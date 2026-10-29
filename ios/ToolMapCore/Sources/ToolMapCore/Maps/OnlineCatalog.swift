// Port of core/src/main/kotlin/fr/toolmap/core/maps/OnlineCatalog.kt.

import Foundation

/// The providers of the online catalogue, in the order the screen lists them (P16.4).
public enum CatalogProvider: String, CaseIterable, Sendable {
    case openStreetMap = "OPENSTREETMAP"
    case ign = "IGN"
    case swisstopo = "SWISSTOPO"
    case bkg = "BKG"
    case usgs = "USGS"
    case ordnanceSurvey = "ORDNANCE_SURVEY"

    /// Prefix of the ids of the provider's maps in `online-catalog.aqx`.
    public var prefix: String {
        switch self {
        case .openStreetMap: return "cat-osm-"
        case .ign: return "cat-ign-"
        case .swisstopo: return "cat-swisstopo-"
        case .bkg: return "cat-bkg-"
        case .usgs: return "cat-usgs-"
        case .ordnanceSurvey: return "cat-os-"
        }
    }
}

/// A map of the online catalogue: `keyParams` are the API keys the user must give when adding it.
public struct CatalogEntry: Equatable, Hashable, Sendable {
    public let provider: CatalogProvider
    public let source: TileSource
    public let keyParams: [String]

    public init(provider: CatalogProvider, source: TileSource, keyParams: [String]) {
        self.provider = provider
        self.source = source
        self.keyParams = keyParams
    }
}

/// One section of the online catalogue.
public struct CatalogGroup: Equatable, Hashable, Sendable {
    public let provider: CatalogProvider
    public let entries: [CatalogEntry]

    public init(provider: CatalogProvider, entries: [CatalogEntry]) {
        self.provider = provider
        self.entries = entries
    }
}

/// The maps that can be added in one gesture from « Ajouter une carte › Catalogue en ligne » (AlpineQuest's catalogue
/// of remote maps). They ship with the app in `online-catalog.aqx` (AQX syntax, so that adding one is storing a source
/// like an import), and only list sources whose terms of use allow direct access to the tiles: no Bing, no Google. A
/// parameter left empty in the file is an API key, asked when the map is added.
public enum OnlineCatalog {
    /// The package resource (a copy of the Android resource `/online-catalog.aqx`).
    public static let resource = "online-catalog"

    /// Throws when the resource is missing or is not a valid AQX file (a packaging bug).
    public static func load() throws -> [CatalogGroup] {
        guard let url = Bundle.module.url(forResource: resource, withExtension: "aqx") else {
            throw ToolMapError.invalidArgument("Missing resource \(resource).aqx")
        }
        let data = try Data(contentsOf: url)
        let sources = try AqxParser.parse(String(decoding: data, as: UTF8.self)).file.sources
        return groups(sources)
    }

    /// The sources by provider, in file order; a source of no known provider is left out.
    public static func groups(_ sources: [TileSource]) -> [CatalogGroup] {
        var result: [CatalogGroup] = []
        for provider in CatalogProvider.allCases {
            let entries = sources
                .filter { $0.id.hasPrefix(provider.prefix) }
                .map { CatalogEntry(provider: provider, source: $0, keyParams: keyParams($0)) }
            if !entries.isEmpty { result.append(CatalogGroup(provider: provider, entries: entries)) }
        }
        return result
    }

    /// The parameters of `source` that no value fills: API keys to ask for, sorted by name (a Swift dictionary has no
    /// file order).
    public static func keyParams(_ source: TileSource) -> [String] {
        source.params.filter { kotlinTrim($0.value).isEmpty }.keys.sorted()
    }

    /// `source` with the keys the user gave filled in.
    public static func withKeys(_ source: TileSource, _ keys: [String: String]) -> TileSource {
        var params = source.params
        for (name, value) in keys {
            params[name] = kotlinTrim(value)
        }
        return source.withFields { $0.params = params }
    }
}
