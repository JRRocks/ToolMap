// Port of data/src/main/kotlin/fr/toolmap/data/tracks/TrackEntities.kt (Android module :data, Room entities).
// The rows of the SQLite file `tracks.db`: same tables, same columns, same keys as Android's Room schema version 1
// (data/schemas/fr.toolmap.data.tracks.TrackDatabase/1.json), so that a backup made on one app opens in the other.

import Foundation

/// A folder of the library. Deleting it deletes what is inside (the user is asked first).
public struct FolderEntity: Equatable, Hashable, Sendable {
    public var id: Int64
    public var parentId: Int64?
    public var name: String
    public var visible: Bool
    public var createdAt: Int64
    /// XML namespace prefixes the raw extensions of the folder's items rely on (top-level import folders only).
    public var namespacesJson: String?

    public init(
        id: Int64 = 0,
        parentId: Int64? = nil,
        name: String,
        visible: Bool = true,
        createdAt: Int64,
        namespacesJson: String? = nil
    ) {
        self.id = id
        self.parentId = parentId
        self.name = name
        self.visible = visible
        self.createdAt = createdAt
        self.namespacesJson = namespacesJson
    }
}

/// A track. Its points live in `TrackChunkEntity` rows; what a list needs (size, bounds, distance, climb, dates) is
/// kept here so that showing the library never decodes a point.
public struct TrackEntity: Equatable, Hashable, Sendable {
    public var id: Int64
    public var folderId: Int64?
    public var name: String?
    public var cmt: String?
    public var desc: String?
    public var src: String?
    public var number: Int?
    public var type: String?
    public var linksJson: String?
    public var extrasJson: String?
    public var colorArgb: Int32
    public var widthDp: Float
    public var dashed: Bool
    public var arrows: Bool
    public var visible: Bool
    public var createdAt: Int64
    /// Format of the file it came from (`GPX`, `KML`...), or nil when drawn or recorded in the app.
    public var format: String?
    public var pointCount: Int
    public var segmentCount: Int
    public var minLat: Double
    public var minLon: Double
    public var maxLat: Double
    public var maxLon: Double
    public var distanceMeters: Double
    public var ascentMeters: Double?
    public var descentMeters: Double?
    public var startTimeMillis: Int64?
    public var endTimeMillis: Int64?

    public init(
        id: Int64 = 0,
        folderId: Int64? = nil,
        name: String? = nil,
        cmt: String? = nil,
        desc: String? = nil,
        src: String? = nil,
        number: Int? = nil,
        type: String? = nil,
        linksJson: String? = nil,
        extrasJson: String? = nil,
        colorArgb: Int32,
        widthDp: Float,
        dashed: Bool = false,
        arrows: Bool = false,
        visible: Bool = true,
        createdAt: Int64,
        format: String? = nil,
        pointCount: Int,
        segmentCount: Int,
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
        distanceMeters: Double,
        ascentMeters: Double? = nil,
        descentMeters: Double? = nil,
        startTimeMillis: Int64? = nil,
        endTimeMillis: Int64? = nil
    ) {
        self.id = id
        self.folderId = folderId
        self.name = name
        self.cmt = cmt
        self.desc = desc
        self.src = src
        self.number = number
        self.type = type
        self.linksJson = linksJson
        self.extrasJson = extrasJson
        self.colorArgb = colorArgb
        self.widthDp = widthDp
        self.dashed = dashed
        self.arrows = arrows
        self.visible = visible
        self.createdAt = createdAt
        self.format = format
        self.pointCount = pointCount
        self.segmentCount = segmentCount
        self.minLat = minLat
        self.minLon = minLon
        self.maxLat = maxLat
        self.maxLon = maxLon
        self.distanceMeters = distanceMeters
        self.ascentMeters = ascentMeters
        self.descentMeters = descentMeters
        self.startTimeMillis = startTimeMillis
        self.endTimeMillis = endTimeMillis
    }
}

/// A run of consecutive points of one segment of a track, in `PointCodec` form. Chunks stay small (a few hundred
/// kilobytes at most), and a recorder can append chunks to a track without rewriting it.
public struct TrackChunkEntity: Equatable, Hashable, Sendable {
    public var trackId: Int64
    public var segment: Int
    public var chunk: Int
    public var pointCount: Int
    public var data: Data

    public init(trackId: Int64, segment: Int, chunk: Int, pointCount: Int, data: Data) {
        self.trackId = trackId
        self.segment = segment
        self.chunk = chunk
        self.pointCount = pointCount
        self.data = data
    }
}

/// A route: its few points (with names) are kept as JSON.
public struct RouteEntity: Equatable, Hashable, Sendable {
    public var id: Int64
    public var folderId: Int64?
    public var name: String?
    public var cmt: String?
    public var desc: String?
    public var src: String?
    public var number: Int?
    public var type: String?
    public var linksJson: String?
    public var extrasJson: String?
    public var colorArgb: Int32
    public var widthDp: Float
    public var dashed: Bool
    public var arrows: Bool
    public var visible: Bool
    public var createdAt: Int64
    public var format: String?
    public var pointCount: Int
    public var minLat: Double
    public var minLon: Double
    public var maxLat: Double
    public var maxLon: Double
    public var distanceMeters: Double
    public var pointsJson: String

    public init(
        id: Int64 = 0,
        folderId: Int64? = nil,
        name: String? = nil,
        cmt: String? = nil,
        desc: String? = nil,
        src: String? = nil,
        number: Int? = nil,
        type: String? = nil,
        linksJson: String? = nil,
        extrasJson: String? = nil,
        colorArgb: Int32,
        widthDp: Float,
        dashed: Bool = false,
        arrows: Bool = false,
        visible: Bool = true,
        createdAt: Int64,
        format: String? = nil,
        pointCount: Int,
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
        distanceMeters: Double,
        pointsJson: String
    ) {
        self.id = id
        self.folderId = folderId
        self.name = name
        self.cmt = cmt
        self.desc = desc
        self.src = src
        self.number = number
        self.type = type
        self.linksJson = linksJson
        self.extrasJson = extrasJson
        self.colorArgb = colorArgb
        self.widthDp = widthDp
        self.dashed = dashed
        self.arrows = arrows
        self.visible = visible
        self.createdAt = createdAt
        self.format = format
        self.pointCount = pointCount
        self.minLat = minLat
        self.minLon = minLon
        self.maxLat = maxLat
        self.maxLon = maxLon
        self.distanceMeters = distanceMeters
        self.pointsJson = pointsJson
    }
}

public struct WaypointEntity: Equatable, Hashable, Sendable {
    public var id: Int64
    public var folderId: Int64?
    public var name: String?
    public var cmt: String?
    public var desc: String?
    public var src: String?
    public var lat: Double
    public var lon: Double
    public var ele: Double?
    public var timeMillis: Int64?
    public var sym: String?
    public var type: String?
    public var linksJson: String?
    public var extrasJson: String?
    public var visible: Bool
    public var createdAt: Int64
    public var format: String?

    public init(
        id: Int64 = 0,
        folderId: Int64? = nil,
        name: String? = nil,
        cmt: String? = nil,
        desc: String? = nil,
        src: String? = nil,
        lat: Double,
        lon: Double,
        ele: Double? = nil,
        timeMillis: Int64? = nil,
        sym: String? = nil,
        type: String? = nil,
        linksJson: String? = nil,
        extrasJson: String? = nil,
        visible: Bool = true,
        createdAt: Int64,
        format: String? = nil
    ) {
        self.id = id
        self.folderId = folderId
        self.name = name
        self.cmt = cmt
        self.desc = desc
        self.src = src
        self.lat = lat
        self.lon = lon
        self.ele = ele
        self.timeMillis = timeMillis
        self.sym = sym
        self.type = type
        self.linksJson = linksJson
        self.extrasJson = extrasJson
        self.visible = visible
        self.createdAt = createdAt
        self.format = format
    }
}
