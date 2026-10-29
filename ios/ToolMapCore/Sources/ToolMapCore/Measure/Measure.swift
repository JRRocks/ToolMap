// Port of core/src/main/kotlin/fr/toolmap/core/measure/Measure.kt.

import Foundation

/// What the measure tool computes from the points the user placed.
public struct MeasureResult: Equatable, Hashable, Sendable {
    public let pointCount: Int
    /// Length of the line through the points, in meters (0 for fewer than two points).
    public let lengthMeters: Double
    /// Length of the outline, closing segment included: needs at least three points.
    public let perimeterMeters: Double?
    /// Area enclosed by the points taken as a polygon, in square meters: needs at least three points.
    public let areaSquareMeters: Double?
    /// Initial bearing of the last segment, degrees clockwise from true north.
    public let lastBearingDegrees: Double?
    /// Length of the last segment, in meters.
    public let lastSegmentMeters: Double?
    /// Bearing and distance from the first to the last point (the "as the crow flies" figure).
    public let directBearingDegrees: Double?
    public let directMeters: Double?

    public init(
        pointCount: Int,
        lengthMeters: Double,
        perimeterMeters: Double?,
        areaSquareMeters: Double?,
        lastBearingDegrees: Double?,
        lastSegmentMeters: Double?,
        directBearingDegrees: Double?,
        directMeters: Double?
    ) {
        self.pointCount = pointCount
        self.lengthMeters = lengthMeters
        self.perimeterMeters = perimeterMeters
        self.areaSquareMeters = areaSquareMeters
        self.lastBearingDegrees = lastBearingDegrees
        self.lastSegmentMeters = lastSegmentMeters
        self.directBearingDegrees = directBearingDegrees
        self.directMeters = directMeters
    }
}

/// Distance, area and bearing between points placed on the map (feature P9.2). Distances are great-circle on the
/// mean-radius sphere like everything else in the app (error under 0.5 %); areas are computed exactly on the WGS84
/// ellipsoid with a cylindrical equal-area projection, whose polygon edges deviate from geodesics by a negligible
/// amount for the sizes of a walk.
public enum Measure {
    private static let semiMajorAxis = 6_378_137.0
    private static let flattening = 1 / 298.257223563
    private static let e2 = flattening * (2 - flattening)
    private static let e = e2.squareRoot()
    private static let minPolygonPoints = 3

    /// `q(φ)` of Snyder (3-12): proportional to the area between the equator and the parallel φ.
    private static func q(_ sinPhi: Double) -> Double {
        let first: Double = sinPhi / (1 - e2 * sinPhi * sinPhi)
        let second: Double = log((1 - e * sinPhi) / (1 + e * sinPhi)) / (2 * e)
        return (1 - e2) * (first - second)
    }

    private static let qPole = q(1.0)

    /// Radius of the sphere that has the area of the ellipsoid.
    private static let authalicRadius = semiMajorAxis * (qPole / 2).squareRoot()

    public static func measure(_ points: [LonLat]) -> MeasureResult {
        let n = points.count
        let length = pathLength(points)
        let closed = n >= minPolygonPoints
        let last: LonLat? = n >= 2 ? points[n - 1] : nil
        let before: LonLat? = n >= 2 ? points[n - 2] : nil
        var lastBearing: Double?
        var lastSegment: Double?
        if let from = before, let to = last {
            lastBearing = bearing(from, to)
            lastSegment = distance(from, to)
        }
        var directBearing: Double?
        var direct: Double?
        if let to = last, n >= 2 {
            directBearing = bearing(points[0], to)
            direct = distance(points[0], to)
        }
        return MeasureResult(
            pointCount: n,
            lengthMeters: length,
            perimeterMeters: closed ? length + distance(points[n - 1], points[0]) : nil,
            areaSquareMeters: closed ? areaSquareMeters(points) : nil,
            lastBearingDegrees: lastBearing,
            lastSegmentMeters: lastSegment,
            directBearingDegrees: directBearing,
            directMeters: direct
        )
    }

    public static func pathLength(_ points: [LonLat]) -> Double {
        var total = 0.0
        if points.count > 1 {
            for i in 1 ..< points.count {
                total += distance(points[i - 1], points[i])
            }
        }
        return total
    }

    public static func distance(_ a: LonLat, _ b: LonLat) -> Double {
        Geodesy.distance(a.lat, a.lon, b.lat, b.lon)
    }

    public static func bearing(_ from: LonLat, _ to: LonLat) -> Double {
        Geodesy.bearing(from.lat, from.lon, to.lat, to.lon)
    }

    /// Area of the polygon through `points` (closed implicitly), in square meters, on the ellipsoid. Zero for fewer
    /// than three points; the orientation of the points does not matter. A polygon that crosses the antimeridian is
    /// handled by taking each longitude step the short way round.
    public static func areaSquareMeters(_ points: [LonLat]) -> Double {
        let n = points.count
        if n < minPolygonPoints { return 0.0 }
        var sum = 0.0
        for i in 0 ..< n {
            let a = points[i]
            let b = points[(i + 1) % n]
            var dLon = Angle.radians(b.lon - a.lon)
            if dLon > Double.pi { dLon -= 2 * Double.pi }
            if dLon < -Double.pi { dLon += 2 * Double.pi }
            sum += dLon * (sinBeta(a.lat) + sinBeta(b.lat))
        }
        return Swift.abs(sum) * authalicRadius * authalicRadius / 2
    }

    /// Sine of the authalic latitude of a geodetic latitude in degrees.
    private static func sinBeta(_ latDegrees: Double) -> Double {
        Swift.min(Swift.max(q(sin(Angle.radians(latDegrees))) / qPole, -1.0), 1.0)
    }

    public static func ofTrack(_ points: [TrackPoint]) -> [LonLat] {
        points.map { LonLat(lon: $0.lon, lat: $0.lat) }
    }
}
