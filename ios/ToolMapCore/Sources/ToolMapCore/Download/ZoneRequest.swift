// Port of core/src/main/kotlin/fr/toolmap/core/download/ZoneRequest.kt (ZoneShape, ZoomLimit, ZoneRequest, ZoneStatus,
// ZoneProgress, ZoneJob, ZoneJobCodec).

import Foundation

/// Serializable form of a `DownloadArea`, stored with the download job so it can resume after a restart.
///
/// Kotlin `sealed interface ZoneShape` with the classes `Rectangle`, `Polygon` and `Corridor`; here one case per class,
/// each holding a value of the nested struct of the same name. JSON: kotlinx.serialization's polymorphic form, the
/// fields of the class plus `"type": "fr.toolmap.core.download.ZoneShape.Rectangle"` (or `Polygon`, `Corridor`).
public enum ZoneShape: Equatable, Hashable, Sendable, Codable {
    case rectangle(Rectangle)
    case polygon(Polygon)
    case corridor(Corridor)

    public struct Rectangle: Equatable, Hashable, Sendable, Codable {
        public let west: Double
        public let south: Double
        public let east: Double
        public let north: Double

        public init(west: Double, south: Double, east: Double, north: Double) {
            self.west = west
            self.south = south
            self.east = east
            self.north = north
        }
    }

    public struct Polygon: Equatable, Hashable, Sendable, Codable {
        public let points: [Point]

        public init(points: [Point]) {
            self.points = points
        }
    }

    public struct Corridor: Equatable, Hashable, Sendable, Codable {
        public let points: [Point]
        public let radiusMeters: Double

        public init(points: [Point], radiusMeters: Double) {
            self.points = points
            self.radiusMeters = radiusMeters
        }
    }

    public struct Point: Equatable, Hashable, Sendable, Codable {
        public let lon: Double
        public let lat: Double

        public init(lon: Double, lat: Double) {
            self.lon = lon
            self.lat = lat
        }
    }

    /// The kotlinx.serialization names of the three classes, written in the `type` key.
    public static let rectangleType = "fr.toolmap.core.download.ZoneShape.Rectangle"
    public static let polygonType = "fr.toolmap.core.download.ZoneShape.Polygon"
    public static let corridorType = "fr.toolmap.core.download.ZoneShape.Corridor"

    public func toArea() -> DownloadArea {
        switch self {
        case let .rectangle(r):
            return .rectangle(bounds: Bounds(minX: r.west, minY: r.south, maxX: r.east, maxY: r.north))
        case let .polygon(p):
            return .polygon(ring: p.points.map { LonLat(lon: $0.lon, lat: $0.lat) })
        case let .corridor(c):
            return .corridor(path: c.points.map { LonLat(lon: $0.lon, lat: $0.lat) }, radiusMeters: c.radiusMeters)
        }
    }

    public static func of(_ area: DownloadArea) -> ZoneShape {
        switch area {
        case let .rectangle(bounds):
            return .rectangle(Rectangle(west: bounds.minX, south: bounds.minY, east: bounds.maxX, north: bounds.maxY))
        case let .polygon(ring):
            return .polygon(Polygon(points: ring.map { Point(lon: $0.lon, lat: $0.lat) }))
        case let .corridor(path, radiusMeters):
            return .corridor(Corridor(points: path.map { Point(lon: $0.lon, lat: $0.lat) }, radiusMeters: radiusMeters))
        }
    }

    private enum CodingKeys: String, CodingKey {
        case type, west, south, east, north, points, radiusMeters
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let type = try c.decode(String.self, forKey: .type)
        switch type {
        case ZoneShape.rectangleType:
            let west = try c.decode(Double.self, forKey: .west)
            let south = try c.decode(Double.self, forKey: .south)
            let east = try c.decode(Double.self, forKey: .east)
            let north = try c.decode(Double.self, forKey: .north)
            self = .rectangle(Rectangle(west: west, south: south, east: east, north: north))
        case ZoneShape.polygonType:
            let points = try c.decode([Point].self, forKey: .points)
            self = .polygon(Polygon(points: points))
        case ZoneShape.corridorType:
            let points = try c.decode([Point].self, forKey: .points)
            let radius = try c.decode(Double.self, forKey: .radiusMeters)
            self = .corridor(Corridor(points: points, radiusMeters: radius))
        default:
            throw DecodingError.dataCorruptedError(forKey: .type, in: c, debugDescription: "Unknown zone shape \(type)")
        }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case let .rectangle(r):
            try c.encode(ZoneShape.rectangleType, forKey: .type)
            try c.encode(r.west, forKey: .west)
            try c.encode(r.south, forKey: .south)
            try c.encode(r.east, forKey: .east)
            try c.encode(r.north, forKey: .north)
        case let .polygon(p):
            try c.encode(ZoneShape.polygonType, forKey: .type)
            try c.encode(p.points, forKey: .points)
        case let .corridor(corridor):
            try c.encode(ZoneShape.corridorType, forKey: .type)
            try c.encode(corridor.points, forKey: .points)
            try c.encode(corridor.radiusMeters, forKey: .radiusMeters)
        }
    }
}

/// Zoom bounds one source is limited to in a download (a layer's display range); nil = unbounded.
public struct ZoomLimit: Equatable, Hashable, Sendable, Codable {
    public let min: Int?
    public let max: Int?

    public init(min: Int? = nil, max: Int? = nil) {
        self.min = min
        self.max = max
    }

    /// Kotlin `zoom in limit`.
    public func contains(_ zoom: Int) -> Bool {
        let aboveMin = min.map { zoom >= $0 } ?? true
        let belowMax = max.map { zoom <= $0 } ?? true
        return aboveMin && belowMax
    }

    private enum CodingKeys: String, CodingKey {
        case min, max
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        min = try c.decodeIfPresent(Int.self, forKey: .min)
        max = try c.decodeIfPresent(Int.self, forKey: .max)
    }

    /// Both keys are written, nulls included (`encodeDefaults = true` on Android).
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(min, forKey: .min)
        try c.encode(max, forKey: .max)
    }
}

/// One zone download: which `sourceIds` to fetch over the `shape`, for the tile zooms `minZoom`...`maxZoom` (each
/// source only gets the zooms it declares). Throws `ToolMapError` for an empty source list or an invalid zoom range,
/// when built and when read from JSON (Kotlin checks it in `init`).
public struct ZoneRequest: Equatable, Hashable, Sendable, Codable {
    /// Kotlin `ZoneRequest.MAX_ZOOM` (renamed: `maxZoom` is the request's own maximum).
    public static let maxAllowedZoom = 22

    public let id: String
    public let name: String
    public let sourceIds: [String]
    public let minZoom: Int
    public let maxZoom: Int
    public let shape: ZoneShape
    public let createdAtMillis: Int64
    /// Per-source zoom limits (layer display ranges), when the user chose to respect them.
    public let zoomLimits: [String: ZoomLimit]

    public init(
        id: String,
        name: String,
        sourceIds: [String],
        minZoom: Int,
        maxZoom: Int,
        shape: ZoneShape,
        createdAtMillis: Int64 = 0,
        zoomLimits: [String: ZoomLimit] = [:]
    ) throws {
        try require(!sourceIds.isEmpty, "no source selected")
        let limit = ZoneRequest.maxAllowedZoom
        let valid = minZoom >= 0 && minZoom <= limit && maxZoom >= minZoom && maxZoom <= limit
        try require(valid, "invalid zoom range \(minZoom)..\(maxZoom)")
        self.id = id
        self.name = name
        self.sourceIds = sourceIds
        self.minZoom = minZoom
        self.maxZoom = maxZoom
        self.shape = shape
        self.createdAtMillis = createdAtMillis
        self.zoomLimits = zoomLimits
    }

    /// Kotlin `zooms: IntRange`, `minZoom..maxZoom`.
    public var zooms: IntRange { IntRange(minZoom, maxZoom) }

    private enum CodingKeys: String, CodingKey {
        case id, name, sourceIds, minZoom, maxZoom, shape, createdAtMillis, zoomLimits
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let id = try c.decode(String.self, forKey: .id)
        let name = try c.decode(String.self, forKey: .name)
        let sourceIds = try c.decode([String].self, forKey: .sourceIds)
        let minZoom = try c.decode(Int.self, forKey: .minZoom)
        let maxZoom = try c.decode(Int.self, forKey: .maxZoom)
        let shape = try c.decode(ZoneShape.self, forKey: .shape)
        let createdAtMillis = try c.decodeIfPresent(Int64.self, forKey: .createdAtMillis) ?? 0
        let zoomLimits = try c.decodeIfPresent([String: ZoomLimit].self, forKey: .zoomLimits) ?? [:]
        try self.init(
            id: id,
            name: name,
            sourceIds: sourceIds,
            minZoom: minZoom,
            maxZoom: maxZoom,
            shape: shape,
            createdAtMillis: createdAtMillis,
            zoomLimits: zoomLimits
        )
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(name, forKey: .name)
        try c.encode(sourceIds, forKey: .sourceIds)
        try c.encode(minZoom, forKey: .minZoom)
        try c.encode(maxZoom, forKey: .maxZoom)
        try c.encode(shape, forKey: .shape)
        try c.encode(createdAtMillis, forKey: .createdAtMillis)
        try c.encode(zoomLimits, forKey: .zoomLimits)
    }
}

/// Where a zone download stands; written to disk by the downloader so the screen and the notification agree.
public enum ZoneStatus: String, Codable, CaseIterable, Sendable {
    case queued = "QUEUED"
    case running = "RUNNING"
    case paused = "PAUSED"
    case interrupted = "INTERRUPTED"
    case done = "DONE"
    case failed = "FAILED"
}

/// Counters of a zone download. All values are tile counts except `bytes`.
public struct ZoneProgress: Equatable, Hashable, Sendable, Codable {
    public let total: Int64
    /// Downloaded now.
    public let downloaded: Int64
    /// Already stored before the job reached them.
    public let alreadyStored: Int64
    /// The server has no tile there (HTTP 404 / empty): normal at the edge of a map's coverage.
    public let missing: Int64
    public let failed: Int64
    public let bytes: Int64

    public init(
        total: Int64 = 0,
        downloaded: Int64 = 0,
        alreadyStored: Int64 = 0,
        missing: Int64 = 0,
        failed: Int64 = 0,
        bytes: Int64 = 0
    ) {
        self.total = total
        self.downloaded = downloaded
        self.alreadyStored = alreadyStored
        self.missing = missing
        self.failed = failed
        self.bytes = bytes
    }

    public var processed: Int64 { downloaded + alreadyStored + missing + failed }

    public var fraction: Float {
        if total == 0 { return 1 }
        let value = Float(processed) / Float(total)
        if value < 0 { return 0 }
        if value > 1 { return 1 }
        return value
    }

    private enum CodingKeys: String, CodingKey {
        case total, downloaded, alreadyStored, missing, failed, bytes
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        total = try c.decodeIfPresent(Int64.self, forKey: .total) ?? 0
        downloaded = try c.decodeIfPresent(Int64.self, forKey: .downloaded) ?? 0
        alreadyStored = try c.decodeIfPresent(Int64.self, forKey: .alreadyStored) ?? 0
        missing = try c.decodeIfPresent(Int64.self, forKey: .missing) ?? 0
        failed = try c.decodeIfPresent(Int64.self, forKey: .failed) ?? 0
        bytes = try c.decodeIfPresent(Int64.self, forKey: .bytes) ?? 0
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(total, forKey: .total)
        try c.encode(downloaded, forKey: .downloaded)
        try c.encode(alreadyStored, forKey: .alreadyStored)
        try c.encode(missing, forKey: .missing)
        try c.encode(failed, forKey: .failed)
        try c.encode(bytes, forKey: .bytes)
    }
}

/// A job as persisted: the request, its status and the last progress.
public struct ZoneJob: Equatable, Hashable, Sendable, Codable {
    public let request: ZoneRequest
    public let status: ZoneStatus
    public let progress: ZoneProgress
    public let message: String?
    public let updatedAtMillis: Int64

    public init(
        request: ZoneRequest,
        status: ZoneStatus = .queued,
        progress: ZoneProgress = ZoneProgress(),
        message: String? = nil,
        updatedAtMillis: Int64 = 0
    ) {
        self.request = request
        self.status = status
        self.progress = progress
        self.message = message
        self.updatedAtMillis = updatedAtMillis
    }

    private enum CodingKeys: String, CodingKey {
        case request, status, progress, message, updatedAtMillis
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        request = try c.decode(ZoneRequest.self, forKey: .request)
        status = try c.decodeIfPresent(ZoneStatus.self, forKey: .status) ?? .queued
        progress = try c.decodeIfPresent(ZoneProgress.self, forKey: .progress) ?? ZoneProgress()
        message = try c.decodeIfPresent(String.self, forKey: .message)
        updatedAtMillis = try c.decodeIfPresent(Int64.self, forKey: .updatedAtMillis) ?? 0
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(request, forKey: .request)
        try c.encode(status, forKey: .status)
        try c.encode(progress, forKey: .progress)
        try c.encode(message, forKey: .message)
        try c.encode(updatedAtMillis, forKey: .updatedAtMillis)
    }
}

/// The download jobs as JSON files (the keys and defaults of the Kotlin classes, unknown keys ignored).
public enum ZoneJobCodec {
    public static func encode(_ job: ZoneJob) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let data = try encoder.encode(job)
        return String(decoding: data, as: UTF8.self)
    }

    /// nil for a damaged file.
    public static func decodeOrNull(_ text: String) -> ZoneJob? {
        try? JSONDecoder().decode(ZoneJob.self, from: Data(text.utf8))
    }
}
