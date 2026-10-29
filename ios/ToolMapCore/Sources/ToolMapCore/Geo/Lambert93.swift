// Port of core/src/main/kotlin/fr/toolmap/core/geo/Lambert93.kt.

import Foundation

/// A projected position in meters.
public struct PlanarCoordinate: Equatable, Sendable {
    public let easting: Double
    public let northing: Double

    public init(easting: Double, northing: Double) {
        self.easting = easting
        self.northing = northing
    }
}

/// Lambert-93 (RGF93, EPSG:2154), the legal projection of metropolitan France: Lambert conformal conic with standard
/// parallels 44 N and 49 N on the GRS80 ellipsoid. RGF93 and WGS84 differ by far less than a meter.
public enum Lambert93 {
    private static let a = 6_378_137.0
    private static let f = 1 / 298.257222101
    private static let lon0 = 3.0
    private static let e0 = 700_000.0
    private static let n0 = 6_600_000.0
    private static let iterations = 8
    private static let e = (f * (2 - f)).squareRoot()

    private static let phi1 = Angle.radians(44.0)
    private static let phi2 = Angle.radians(49.0)
    private static let phi0 = Angle.radians(46.5)

    private static let n = (log(m(phi1)) - log(m(phi2))) / (log(t(phi1)) - log(t(phi2)))
    private static let bigF = m(phi1) / (n * Foundation.pow(t(phi1), n))
    private static let rho0 = a * bigF * Foundation.pow(t(phi0), n)

    private static func m(_ phi: Double) -> Double {
        cos(phi) / (1 - e * e * sin(phi) * sin(phi)).squareRoot()
    }

    private static func t(_ phi: Double) -> Double {
        let s = e * sin(phi)
        return tan(Double.pi / 4 - phi / 2) / Foundation.pow((1 - s) / (1 + s), e / 2)
    }

    public static func toLambert(_ lat: Double, _ lon: Double) -> PlanarCoordinate {
        let rho = a * bigF * Foundation.pow(t(Angle.radians(lat)), n)
        let theta = n * Angle.radians(Utm.normalizeLon(lon - lon0))
        return PlanarCoordinate(easting: e0 + rho * sin(theta), northing: n0 + rho0 - rho * cos(theta))
    }

    public static func toLonLat(_ point: PlanarCoordinate) -> LonLat {
        let dx = point.easting - e0
        let dy = rho0 - (point.northing - n0)
        let sign: Double = n > 0 ? 1 : (n < 0 ? -1 : 0)
        let rho = sign * (dx * dx + dy * dy).squareRoot()
        let tt = Foundation.pow(rho / (a * bigF), 1 / n)
        let theta = atan2(sign * dx, sign * dy)
        var phi = Double.pi / 2 - 2 * atan(tt)
        for _ in 0 ..< iterations {
            let s = e * sin(phi)
            phi = Double.pi / 2 - 2 * atan(tt * Foundation.pow((1 - s) / (1 + s), e / 2))
        }
        return LonLat(lon: lon0 + Angle.degrees(theta / n), lat: Angle.degrees(phi))
    }

    /// Whether (lat, lon) is within the area EPSG:2154 is defined for (France and its seas, with a margin of 1°).
    public static func covers(_ lat: Double, _ lon: Double) -> Bool {
        (41.15 ... 51.56).contains(lat) && (-9.86 ... 10.38).contains(lon)
    }
}
