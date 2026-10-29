// Port of core/src/main/kotlin/fr/toolmap/core/tiles/TileMath.kt.

import Foundation

/// A tile column (x) and row (y) in the XYZ scheme.
public struct TileCoord: Equatable, Hashable, Sendable {
    public let x: Int
    public let y: Int

    public init(x: Int, y: Int) {
        self.x = x
        self.y = y
    }
}

/// Web Mercator (EPSG:3857) tile maths, XYZ scheme (origin at the top-left, like OSM and Google).
public enum TileMath {
    public static let earthRadiusM = 6_378_137.0
    public static let originShiftM = Double.pi * earthRadiusM

    /// Latitude at which the Web Mercator square is cut (rows 0 and n-1 end here).
    public static let maxLatitude = 85.0511287798

    /// Number of tiles along one axis at `zoom`.
    public static func tilesPerAxis(_ zoom: Int) throws -> Int {
        try require((0 ... 30).contains(zoom), "zoom out of range: \(zoom)")
        return 1 << zoom
    }

    /// Bing quadkey of a tile, e.g. `quadKey(3, 5, 3) == "213"`. Empty at zoom 0.
    public static func quadKey(_ x: Int, _ y: Int, _ zoom: Int) throws -> String {
        try requireTile(x, y, zoom)
        var key = ""
        var level = zoom
        while level >= 1 {
            let mask = 1 << (level - 1)
            var digit = 0
            if x & mask != 0 { digit += 1 }
            if y & mask != 0 { digit += 2 }
            key.append(String(digit))
            level -= 1
        }
        return key
    }

    /// Row in the TMS scheme (origin at the bottom) for an XYZ row `y`.
    public static func tmsY(_ y: Int, _ zoom: Int) throws -> Int {
        try requireTile(0, y, zoom)
        return try tilesPerAxis(zoom) - 1 - y
    }

    /// Column and row (XYZ scheme) of the tile that contains the given WGS84 position.
    public static func tileAt(_ lon: Double, _ lat: Double, _ zoom: Int) throws -> TileCoord {
        let n = try tilesPerAxis(zoom)
        let clampedLat = Swift.min(Swift.max(lat, -maxLatitude), maxLatitude)
        let x = clamp(kotlinToLong(floor((lon + 180.0) / 360.0 * Double(n))), 0, n - 1)
        let latRad = toRadians(clampedLat)
        let y = clamp(kotlinToLong(floor((1 - asinh(tan(latRad)) / Double.pi) / 2 * Double(n))), 0, n - 1)
        return TileCoord(x: x, y: y)
    }

    /// EPSG:3857 easting in meters of a longitude.
    public static func mercatorX(_ lon: Double) -> Double { earthRadiusM * toRadians(lon) }

    /// EPSG:3857 northing in meters of a latitude (clamped to the Web Mercator limit).
    public static func mercatorY(_ lat: Double) -> Double {
        earthRadiusM * asinh(tan(toRadians(Swift.min(Swift.max(lat, -maxLatitude), maxLatitude))))
    }

    /// Tile extent in degrees: x = longitude, y = latitude.
    public static func boundsLonLat(_ x: Int, _ y: Int, _ zoom: Int) throws -> Bounds {
        try requireTile(x, y, zoom)
        let n = try Double(tilesPerAxis(zoom))
        return Bounds(
            minX: Double(x) / n * 360.0 - 180.0,
            minY: latitudeOfRow(y + 1, n),
            maxX: Double(x + 1) / n * 360.0 - 180.0,
            maxY: latitudeOfRow(y, n)
        )
    }

    /// Tile extent in EPSG:3857 meters.
    public static func boundsMercator(_ x: Int, _ y: Int, _ zoom: Int) throws -> Bounds {
        try requireTile(x, y, zoom)
        let size = try 2 * originShiftM / Double(tilesPerAxis(zoom))
        return Bounds(
            minX: -originShiftM + Double(x) * size,
            minY: originShiftM - Double(y + 1) * size,
            maxX: -originShiftM + Double(x + 1) * size,
            maxY: originShiftM - Double(y) * size
        )
    }

    /// Ground resolution in meters per pixel at `latitude` for tiles of `tileSize` pixels.
    public static func metersPerPixel(_ latitude: Double, _ zoom: Int, _ tileSize: Int = 256) -> Double {
        2 * Double.pi * earthRadiusM * cos(toRadians(latitude)) / (Double(tileSize) * pow(2.0, Double(zoom)))
    }

    /// `Math.toRadians` of the JVM (Java 9 and later): the same product, so the same last bit.
    static func toRadians(_ degrees: Double) -> Double { degrees * 0.017453292519943295 }

    /// `Math.toDegrees` of the JVM (Java 9 and later).
    static func toDegrees(_ radians: Double) -> Double { radians * 57.29577951308232 }

    private static func latitudeOfRow(_ row: Int, _ n: Double) -> Double {
        toDegrees(atan(sinh(Double.pi * (1 - Double(2 * row) / n))))
    }

    private static func clamp(_ value: Int, _ low: Int, _ high: Int) -> Int {
        Swift.min(Swift.max(value, low), high)
    }

    private static func requireTile(_ x: Int, _ y: Int, _ zoom: Int) throws {
        let n = try tilesPerAxis(zoom)
        try require((0 ..< n).contains(x) && (0 ..< n).contains(y), "tile \(zoom)/\(x)/\(y) out of range")
    }
}
