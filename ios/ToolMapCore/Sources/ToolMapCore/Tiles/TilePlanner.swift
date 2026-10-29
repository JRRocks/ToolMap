// Port of core/src/main/kotlin/fr/toolmap/core/tiles/TilePlanner.kt (TileId is in TileResolver.swift).

import Foundation

/// The ground to download: always WGS84 degrees (Kotlin sealed interface `DownloadArea`).
public enum DownloadArea: Equatable, Hashable, Sendable {
    /// A box; `bounds` uses x = longitude, y = latitude, with west <= east (no antimeridian crossing).
    case rectangle(bounds: Bounds)
    /// A simple polygon (the closing edge is implicit).
    case polygon(ring: [LonLat])
    /// Every place within `radiusMeters` (on the ground) of the polyline `path`, e.g. a GPX track.
    case corridor(path: [LonLat], radiusMeters: Double)
}

/// The zone asks for more tiles than the safety limit allows. Kotlin: `TooManyTilesException`.
public struct TooManyTilesError: Error, Equatable, Sendable, CustomStringConvertible {
    public let limit: Int

    public init(limit: Int) {
        self.limit = limit
    }

    public var message: String { "more than \(limit) tiles: reduce the zone or the zoom levels" }

    public var description: String { message }
}

/// Which tiles cover a `DownloadArea`; shared by the zone download and the PDF composer.
///
/// Kotlin returns a lazy sequence that throws while it is walked; here `forEachTile` walks the plan and `tiles` lists
/// it. Tile counts are `Int` (64 bits, Kotlin `Long`).
public enum TilePlanner {
    /// Safety limit for one plan: beyond it the zone is refused rather than enumerated.
    public static let maxTiles = 3_000_000

    /// Beyond this many stored tiles in the box of a polygon, `countStored` estimates instead of listing them.
    public static let storedSampleLimit = 500_000

    public static let defaultTileBytes = 20_000

    /// Polygon candidates are cheap to reject, so the box may be this many times larger than the limit.
    private static let candidateFactor = 8

    /// Calls `body` with each tile of `area` at each zoom of `zooms`, ordered by zoom then row then column. Polygons
    /// and corridors are exact enough to never miss a tile that touches the area (corridors err on the side of a few
    /// extra tiles). Throws `TooManyTilesError` when the plan exceeds `limit`.
    public static func forEachTile(
        _ area: DownloadArea,
        _ zooms: some Sequence<Int>,
        limit: Int = maxTiles,
        _ body: (TileId) throws -> Void
    ) throws {
        var produced = 0
        for z in zooms {
            try tilesAt(area, z, remaining: limit - produced) { tile in
                produced += 1
                if produced > limit { throw TooManyTilesError(limit: limit) }
                try body(TileId(z, tile.x, tile.y))
            }
        }
    }

    /// The tiles of `area` at each zoom of `zooms`, ordered by zoom then row then column (see `forEachTile`).
    public static func tiles(_ area: DownloadArea, _ zooms: some Sequence<Int>, limit: Int = maxTiles) throws -> [TileId] {
        var out: [TileId] = []
        try forEachTile(area, zooms, limit: limit) { tile in
            out.append(tile)
        }
        return out
    }

    /// Number of tiles at each zoom (exact for the box, enumerated for polygons and corridors).
    public static func count(_ area: DownloadArea, _ zooms: some Sequence<Int>, limit: Int = maxTiles) throws -> [Int: Int] {
        var result: [Int: Int] = [:]
        var total = 0
        for z in zooms {
            var n = 0
            if case let .rectangle(bounds: bounds) = area {
                let range = try rectangleRange(bounds, z)
                n = range.xs.count * range.ys.count
            } else {
                try tilesAt(area, z, remaining: limit - total) { _ in
                    n += 1
                }
            }
            total += n
            if total > limit { throw TooManyTilesError(limit: limit) }
            result[z] = n
        }
        return result
    }

    private struct TileRange {
        let xs: ClosedRange<Int>
        let ys: ClosedRange<Int>
    }

    private static func rectangleRange(_ bounds: Bounds, _ z: Int) throws -> TileRange {
        try require(bounds.minX <= bounds.maxX && bounds.minY <= bounds.maxY, "empty rectangle")
        let topLeft = try TileMath.tileAt(bounds.minX, bounds.maxY, z)
        let bottomRight = try TileMath.tileAt(bounds.maxX, bounds.minY, z)
        guard topLeft.x <= bottomRight.x, topLeft.y <= bottomRight.y else {
            throw ToolMapError.invalidArgument("empty rectangle")
        }
        return TileRange(xs: topLeft.x ... bottomRight.x, ys: topLeft.y ... bottomRight.y)
    }

    private static func tilesAt(
        _ area: DownloadArea,
        _ z: Int,
        remaining: Int,
        _ body: (TileCoord) throws -> Void
    ) throws {
        switch area {
        case let .rectangle(bounds: bounds):
            let range = try rectangleRange(bounds, z)
            if range.xs.count * range.ys.count > remaining { throw TooManyTilesError(limit: maxTiles) }
            for y in range.ys {
                for x in range.xs {
                    try body(TileCoord(x: x, y: y))
                }
            }
        case let .polygon(ring: ring):
            try polygonTiles(ring, z, remaining: remaining, body)
        case let .corridor(path: path, radiusMeters: radiusMeters):
            let found = try corridorTiles(path, radiusMeters, z)
            let sorted = found.sorted { a, b in
                a.y != b.y ? a.y < b.y : a.x < b.x
            }
            for tile in sorted {
                try body(tile)
            }
        }
    }

    // ---- polygon ------------------------------------------------------------------------------------------------

    private static func polygonTiles(
        _ ring: [LonLat],
        _ z: Int,
        remaining: Int,
        _ body: (TileCoord) throws -> Void
    ) throws {
        try require(ring.count >= 3, "a polygon needs at least 3 points")
        let px = ring.map { TileMath.mercatorX($0.lon) }
        let py = ring.map { TileMath.mercatorY($0.lat) }
        let lons = ring.map(\.lon)
        let lats = ring.map(\.lat)
        let topLeft = try TileMath.tileAt(lons.min() ?? 0, lats.max() ?? 0, z)
        let bottomRight = try TileMath.tileAt(lons.max() ?? 0, lats.min() ?? 0, z)
        let candidates = (bottomRight.x - topLeft.x + 1) * (bottomRight.y - topLeft.y + 1)
        if candidates > remaining * candidateFactor { throw TooManyTilesError(limit: maxTiles) }
        let size = try 2 * TileMath.originShiftM / Double(TileMath.tilesPerAxis(z))
        guard topLeft.x <= bottomRight.x, topLeft.y <= bottomRight.y else { return }
        for y in topLeft.y ... bottomRight.y {
            for x in topLeft.x ... bottomRight.x {
                let x0 = -TileMath.originShiftM + Double(x) * size
                let y1 = TileMath.originShiftM - Double(y) * size
                if polygonTouchesSquare(px, py, x0, y1 - size, x0 + size, y1) {
                    try body(TileCoord(x: x, y: y))
                }
            }
        }
    }

    /// True when the polygon (px, py) and the box share at least one point.
    private static func polygonTouchesSquare(
        _ px: [Double],
        _ py: [Double],
        _ x0: Double,
        _ y0: Double,
        _ x1: Double,
        _ y1: Double
    ) -> Bool {
        let n = px.count
        // A vertex inside the box.
        for i in 0 ..< n where px[i] >= x0 && px[i] <= x1 && py[i] >= y0 && py[i] <= y1 {
            return true
        }
        // A box corner inside the polygon.
        if containsPoint(px, py, x0, y0) || containsPoint(px, py, x1, y0) ||
            containsPoint(px, py, x1, y1) || containsPoint(px, py, x0, y1)
        {
            return true
        }
        // An edge crossing the box (clip test).
        for i in 0 ..< n {
            let j = (i + 1) % n
            if segmentTouchesBox(px[i], py[i], px[j], py[j], x0, y0, x1, y1) { return true }
        }
        return false
    }

    private static func containsPoint(_ px: [Double], _ py: [Double], _ x: Double, _ y: Double) -> Bool {
        var inside = false
        var j = px.count - 1
        for i in px.indices {
            if (py[i] > y) != (py[j] > y), x < (px[j] - px[i]) * (y - py[i]) / (py[j] - py[i]) + px[i] {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /// Liang-Barsky: does the segment (ax, ay)-(bx, by) intersect the closed box?
    private static func segmentTouchesBox(
        _ ax: Double,
        _ ay: Double,
        _ bx: Double,
        _ by: Double,
        _ x0: Double,
        _ y0: Double,
        _ x1: Double,
        _ y1: Double
    ) -> Bool {
        var t0 = 0.0
        var t1 = 1.0
        let dx = bx - ax
        let dy = by - ay
        let p = [-dx, dx, -dy, dy]
        let q = [ax - x0, x1 - ax, ay - y0, y1 - ay]
        for i in 0 ..< 4 {
            if p[i] == 0.0 {
                if q[i] < 0.0 { return false }
            } else {
                let t = q[i] / p[i]
                if p[i] < 0.0 {
                    if t > t1 { return false }
                    t0 = Swift.max(t0, t)
                } else {
                    if t < t0 { return false }
                    t1 = Swift.min(t1, t)
                }
            }
        }
        return true
    }

    // ---- corridor -----------------------------------------------------------------------------------------------

    /// The path is walked in steps of at most half a tile; around each step point every tile within the radius (plus
    /// half a step, so nothing between two points is missed) is kept.
    private static func corridorTiles(_ path: [LonLat], _ radiusMeters: Double, _ z: Int) throws -> Set<TileCoord> {
        try require(!path.isEmpty, "empty path")
        try require(radiusMeters >= 0, "negative radius")
        let n = try TileMath.tilesPerAxis(z)
        let origin = TileMath.originShiftM
        let size = 2 * origin / Double(n)
        let step = size / 2
        var found = Set<TileCoord>()

        func cell(_ value: Double) -> Int {
            Swift.min(Swift.max(kotlinToLong(floor(value)), 0), n - 1)
        }

        func addAround(_ mx: Double, _ my: Double, _ lat: Double) throws {
            // Mercator stretches distances by 1/cos(lat): convert the ground radius into Mercator meters.
            let clampedLat = Swift.min(Swift.max(lat, -TileMath.maxLatitude), TileMath.maxLatitude)
            let reach = radiusMeters / cos(Angle.radians(clampedLat)) + step / 2
            let colMin = cell((mx - reach + origin) / size)
            let colMax = cell((mx + reach + origin) / size)
            let rowMin = cell((origin - (my + reach)) / size)
            let rowMax = cell((origin - (my - reach)) / size)
            if (colMax - colMin + 1) * (rowMax - rowMin + 1) > maxTiles { throw TooManyTilesError(limit: maxTiles) }
            for row in stride(from: rowMin, through: rowMax, by: 1) {
                let top = origin - Double(row) * size
                for col in stride(from: colMin, through: colMax, by: 1) {
                    if found.count > maxTiles { throw TooManyTilesError(limit: maxTiles) }
                    let left = -origin + Double(col) * size
                    let dx = Swift.max(Swift.max(left - mx, 0.0), mx - (left + size))
                    let dy = Swift.max(Swift.max((top - size) - my, 0.0), my - top)
                    if hypot(dx, dy) <= reach { found.insert(TileCoord(x: col, y: row)) }
                }
            }
        }

        let first = path[0]
        try addAround(TileMath.mercatorX(first.lon), TileMath.mercatorY(first.lat), first.lat)
        for i in path.indices.dropFirst() {
            let a = path[i - 1]
            let b = path[i]
            let ax = TileMath.mercatorX(a.lon)
            let ay = TileMath.mercatorY(a.lat)
            let bx = TileMath.mercatorX(b.lon)
            let by = TileMath.mercatorY(b.lat)
            let length = hypot(bx - ax, by - ay)
            let steps = Swift.max(1, kotlinToInt(floor(length / step)) + 1)
            for k in 1 ... steps {
                let t = Double(k) / Double(steps)
                let lat = a.lat + (b.lat - a.lat) * t
                try addAround(ax + (bx - ax) * t, ay + (by - ay) * t, lat)
            }
        }
        return found
    }

    /// How many tiles of `area` at zoom `z` are already stored, from the store's answers about a box of tiles:
    /// `countIn` counts the stored tiles of the box, `storedIn` gives a membership test over them. A rectangle is its
    /// own box. A polygon or a corridor checks its tiles against its bounding box, unless the box holds more than
    /// `sampleLimit` stored tiles: the box count is then scaled by the share of the box the area covers.
    public static func countStored(
        _ area: DownloadArea,
        _ z: Int,
        countIn: (ClosedRange<Int>, ClosedRange<Int>) throws -> Int,
        storedIn: (ClosedRange<Int>, ClosedRange<Int>) throws -> (Int, Int) -> Bool,
        sampleLimit: Int = storedSampleLimit
    ) throws -> Int {
        if case let .rectangle(bounds: bounds) = area {
            let range = try rectangleRange(bounds, z)
            return try countIn(range.xs, range.ys)
        }
        var minX = Int.max
        var maxX = Int.min
        var minY = Int.max
        var maxY = Int.min
        var tiles = 0
        try tilesAt(area, z, remaining: maxTiles) { tile in
            minX = Swift.min(minX, tile.x)
            maxX = Swift.max(maxX, tile.x)
            minY = Swift.min(minY, tile.y)
            maxY = Swift.max(maxY, tile.y)
            tiles += 1
        }
        if tiles == 0 { return 0 }
        let xs = minX ... maxX
        let ys = minY ... maxY
        let inBox = try countIn(xs, ys)
        if inBox == 0 { return 0 }
        if inBox > sampleLimit {
            let box = (maxX - minX + 1) * (maxY - minY + 1)
            return inBox * tiles / box
        }
        let isStored = try storedIn(xs, ys)
        var stored = 0
        try tilesAt(area, z, remaining: maxTiles) { tile in
            if isStored(tile.x, tile.y) { stored += 1 }
        }
        return stored
    }

    /// Rough download size: `count` tiles of `averageTileBytes` each (sampled from the source when possible).
    public static func estimateBytes(_ count: Int, averageTileBytes: Int = defaultTileBytes) -> Int {
        count * averageTileBytes
    }
}
