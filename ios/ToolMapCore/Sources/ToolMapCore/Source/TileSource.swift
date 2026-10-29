// Port of core/src/main/kotlin/fr/toolmap/core/source/TileSource.kt.

/// Kotlin `IntRange`: `first...last`, empty when `first > last` (a Swift `ClosedRange` cannot be empty).
public struct IntRange: Equatable, Hashable, Sendable, CustomStringConvertible {
    public let first: Int
    public let last: Int

    public init(_ first: Int, _ last: Int) {
        self.first = first
        self.last = last
    }

    public var isEmpty: Bool { first > last }

    public func contains(_ value: Int) -> Bool { value >= first && value <= last }

    public var description: String { "\(first)..\(last)" }
}

/// Inclusive RGB ranges that are made transparent (AQX `<clear-color r="240-255" g=".." b=".."/>`).
public struct ClearColor: Equatable, Hashable, Sendable {
    public let r: IntRange
    public let g: IntRange
    public let b: IntRange

    public init(r: IntRange, g: IntRange, b: IntRange) {
        self.r = r
        self.g = g
        self.b = b
    }
}

/// An extra HTTP header of a level (Kotlin `Pair<String, String>`).
public struct HttpHeader: Equatable, Hashable, Sendable {
    public let name: String
    public let value: String

    public init(_ name: String, _ value: String) {
        self.name = name
        self.value = value
    }
}

/// One block of zoom levels of a `TileSource` with its own servers and HTTP settings
/// (AQX `<level>` / `<zoom-levels>`).
public struct LevelDef: Equatable, Hashable, Sendable {
    /// `<server>` value of a local-only level: not a URL, never requested.
    public static let localOnlyServer = "offline://stored-tiles"
    public static let localOnlyPrefix = "offline:"
    public static let defaultMaxThreads = 3
    public static let defaultTileSize = 256
    public static let crsWGS84 = "EPSG:4326"
    public static let crsWebMercator = "EPSG:3857"

    public let zooms: ZoomSet
    /// URL templates; several entries mean round-robin load balancing.
    public let servers: [String]
    public let referer: String?
    public let userAgent: String?
    /// Extra HTTP headers, in file order.
    public let headers: [HttpHeader]
    public let cookie: String?
    /// Cache validity as written in the file (`0D`, `2W`, `3M`, `1Y`); nil means never expires.
    public let updateDelay: String?
    public let maxThreads: Int
    /// Accept any HTTPS certificate. Dangerous: surfaced to the user as a warning.
    public let insecure: Bool
    public let tileSize: Int
    public let clearColor: ClearColor?
    public let keyUrl: String?
    /// CRS used by the `{$bbox}` placeholder.
    public let bboxCrs: String

    public init(
        zooms: ZoomSet,
        servers: [String],
        referer: String? = nil,
        userAgent: String? = nil,
        headers: [HttpHeader] = [],
        cookie: String? = nil,
        updateDelay: String? = nil,
        maxThreads: Int = LevelDef.defaultMaxThreads,
        insecure: Bool = false,
        tileSize: Int = LevelDef.defaultTileSize,
        clearColor: ClearColor? = nil,
        keyUrl: String? = nil,
        bboxCrs: String = LevelDef.crsWGS84
    ) {
        self.zooms = zooms
        self.servers = servers
        self.referer = referer
        self.userAgent = userAgent
        self.headers = headers
        self.cookie = cookie
        self.updateDelay = updateDelay
        self.maxThreads = maxThreads
        self.insecure = insecure
        self.tileSize = tileSize
        self.clearColor = clearColor
        self.keyUrl = keyUrl
        self.bboxCrs = bboxCrs
    }

    /// True for a level that has no server: its tiles only exist in local storage (an imported `.mbtiles`).
    public var isLocalOnly: Bool {
        !servers.isEmpty && servers.allSatisfy { $0.hasPrefix(LevelDef.localOnlyPrefix) }
    }

    /// The map this level shows instead of calling a server (multi-zoom maps, ADR-031), or nil.
    public var mapRef: MapRef? {
        servers.count == 1 ? MapRef.parse(servers[0]) : nil
    }

    /// True for an ordinary level whose tiles come from a server.
    public var hasServer: Bool { !isLocalOnly && mapRef == nil }
}

/// A map source: a base map or an overlay, defined by one or more `LevelDef`s.
public struct TileSource: Equatable, Hashable, Sendable {
    /// Unique id; also the storage key and the path segment of the fake tile host.
    public let id: String
    public let name: String
    /// roads, topo, satellite, hybrid, nautical, aeronautical, historical, hillshade, contours, dem... (lower case).
    public let type: String?
    /// True when the source is meant to be drawn over another map.
    public let isLayer: Bool
    /// Initial opacity, 0...100.
    public let opacity: Int
    public let copyright: String?
    public let dataSource: String?
    public let region: String?
    public let outline: [LonLat]
    public let previewLocation: LonLatZoom?
    public let defaultLocation: LonLat?
    /// Values of `{$param:key}` placeholders.
    public let params: [String: String]
    public let levels: [LevelDef]
    /// Non-nil when the source was read but cannot be displayed (e.g. non Web Mercator projection).
    public let unsupportedReason: String?

    public init(
        id: String,
        name: String,
        type: String? = nil,
        isLayer: Bool = false,
        opacity: Int = 100,
        copyright: String? = nil,
        dataSource: String? = nil,
        region: String? = nil,
        outline: [LonLat] = [],
        previewLocation: LonLatZoom? = nil,
        defaultLocation: LonLat? = nil,
        params: [String: String] = [:],
        levels: [LevelDef] = [],
        unsupportedReason: String? = nil
    ) {
        self.id = id
        self.name = name
        self.type = type
        self.isLayer = isLayer
        self.opacity = opacity
        self.copyright = copyright
        self.dataSource = dataSource
        self.region = region
        self.outline = outline
        self.previewLocation = previewLocation
        self.defaultLocation = defaultLocation
        self.params = params
        self.levels = levels
        self.unsupportedReason = unsupportedReason
    }

    public var isSupported: Bool { unsupportedReason == nil && !levels.isEmpty }

    /// True for a map that only shows tiles stored on the device (imported package): nothing to download.
    public var isLocalOnly: Bool { !levels.isEmpty && levels.allSatisfy(\.isLocalOnly) }

    /// True for a map assembled from other maps by zoom range (at least one level is a `MapRef`).
    public var isMultiZoom: Bool { levels.contains { $0.mapRef != nil } }

    /// Lowest zoom served by any level (nil without levels, where Kotlin throws).
    public var minZoom: Int? { levels.map(\.zooms.min).min() }

    /// Highest zoom served by any level (nil without levels, where Kotlin throws).
    public var maxZoom: Int? { levels.map(\.zooms.max).max() }

    /// The level that serves `zoom`, or nil when the source has no tile at this zoom.
    public func levelFor(_ zoom: Int) -> LevelDef? {
        levels.first { $0.zooms.contains(zoom) }
    }
}
