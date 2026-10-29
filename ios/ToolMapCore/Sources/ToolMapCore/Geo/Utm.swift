// Port of core/src/main/kotlin/fr/toolmap/core/geo/Utm.kt.

import Foundation

/// A UTM position: `zone` 1...60, `north` hemisphere, easting and northing in meters (WGS84).
public struct UtmCoordinate: Equatable, Sendable {
    public let zone: Int
    public let north: Bool
    public let easting: Double
    public let northing: Double

    public init(zone: Int, north: Bool, easting: Double, northing: Double) {
        self.zone = zone
        self.north = north
        self.easting = easting
        self.northing = northing
    }
}

/// Universal Transverse Mercator on the WGS84 ellipsoid (Krüger series to the 4th order, error below a micrometer
/// within the zone and its overlap). Latitudes outside 84 N ... 80 S are not part of UTM.
public enum Utm {
    private static let a = 6_378_137.0
    private static let f = 1 / 298.257223563
    private static let k0 = 0.9996
    private static let falseEasting = 500_000.0
    private static let falseNorthingSouth = 10_000_000.0

    private static let n = f / (2 - f)
    private static let rectifyingRadius = a / (1 + n) * (1 + n * n / 4 + n * n * n * n / 64)
    private static let e = (f * (2 - f)).squareRoot()

    private static func power(_ x: Double, _ k: Int) -> Double {
        var r = 1.0
        for _ in 0 ..< k {
            r *= x
        }
        return r
    }

    private static let alpha: [Double] = [
        n / 2 - 2 * n * n / 3 + 5 * power(n, 3) / 16 + 41 * power(n, 4) / 180,
        13 * n * n / 48 - 3 * power(n, 3) / 5 + 557 * power(n, 4) / 1440,
        61 * power(n, 3) / 240 - 103 * power(n, 4) / 140,
        49561 * power(n, 4) / 161_280,
    ]
    private static let beta: [Double] = [
        n / 2 - 2 * n * n / 3 + 37 * power(n, 3) / 96 - power(n, 4) / 360,
        n * n / 48 + power(n, 3) / 15 - 437 * power(n, 4) / 1440,
        17 * power(n, 3) / 480 - 37 * power(n, 4) / 840,
        4397 * power(n, 4) / 161_280,
    ]
    private static let delta: [Double] = [
        2 * n - 2 * n * n / 3 - 2 * power(n, 3) + 116 * power(n, 4) / 45,
        7 * n * n / 3 - 8 * power(n, 3) / 5 - 227 * power(n, 4) / 45,
        56 * power(n, 3) / 15 - 136 * power(n, 4) / 35,
        4279 * power(n, 4) / 630,
    ]

    private static let bands = Array("CDEFGHJKLMNPQRSTUVWX")
    private static let norwaySouth = 56.0
    private static let norwayNorth = 64.0
    private static let norwayWest = 3.0
    private static let norwayEast = 12.0
    private static let norwayZone = 32
    private static let svalbardSouth = 72.0
    /// The east edges of 31X, 33X, 35X and 37X.
    private static let svalbardEdges: [Double] = [9.0, 21.0, 33.0, 42.0]
    private static let svalbardZones = [31, 33, 35, 37]

    /// The regular 6° zone of a longitude, without the exceptions of Norway and Svalbard.
    public static func zoneOf(_ lon: Double) -> Int {
        Swift.min(Swift.max(kotlinToInt(floor((normalizeLon(lon) + 180.0) / 6.0)) + 1, 1), 60)
    }

    /// The zone of a position: the regular 6° zone, except west of Norway (32V reaches west to 3° E between 56 and
    /// 64 N) and around Svalbard (only 31X, 33X, 35X and 37X between 72 and 84 N).
    public static func zoneOf(lat: Double, lon: Double) -> Int {
        let l = normalizeLon(lon)
        if lat >= norwaySouth, lat < norwayNorth, l >= norwayWest, l < norwayEast {
            return norwayZone
        }
        if lat >= svalbardSouth, l >= 0.0, l < svalbardEdges[svalbardEdges.count - 1] {
            return svalbardZones[svalbardEdges.firstIndex { l < $0 } ?? 0]
        }
        return zoneOf(l)
    }

    public static func centralMeridian(_ zone: Int) -> Double {
        Double(zone - 1) * 6.0 - 180.0 + 3.0
    }

    /// Converts to UTM in `zone` (default: the zone of the position; a neighbouring zone gives its grid carried on).
    /// `north` picks the false northing: by default the hemisphere of `lat`.
    public static func toUtm(_ lat: Double, _ lon: Double, zone: Int? = nil, north: Bool? = nil) -> UtmCoordinate {
        let zone = zone ?? zoneOf(lat: lat, lon: lon)
        let north = north ?? (lat >= 0)
        let phi = Angle.radians(lat)
        let dLon = Angle.radians(normalizeLon(lon - centralMeridian(zone)))
        let sinPhi = sin(phi)
        // Conformal latitude through its tangent.
        let tau = sinh(atanh(sinPhi) - e * atanh(e * sinPhi))
        let xiP = atan2(tau, cos(dLon))
        let etaP = atanh(sin(dLon) / (1 + tau * tau).squareRoot())
        var xi = xiP
        var eta = etaP
        for j in 1 ... alpha.count {
            xi += alpha[j - 1] * sin(Double(2 * j) * xiP) * cosh(Double(2 * j) * etaP)
            eta += alpha[j - 1] * cos(Double(2 * j) * xiP) * sinh(Double(2 * j) * etaP)
        }
        return UtmCoordinate(
            zone: zone,
            north: north,
            easting: falseEasting + k0 * rectifyingRadius * eta,
            northing: k0 * rectifyingRadius * xi + (north ? 0.0 : falseNorthingSouth)
        )
    }

    /// Back to WGS84.
    public static func toLonLat(_ utm: UtmCoordinate) -> LonLat {
        let xi = (utm.northing - (utm.north ? 0.0 : falseNorthingSouth)) / (k0 * rectifyingRadius)
        let eta = (utm.easting - falseEasting) / (k0 * rectifyingRadius)
        var xiP = xi
        var etaP = eta
        for j in 1 ... beta.count {
            xiP -= beta[j - 1] * sin(Double(2 * j) * xi) * cosh(Double(2 * j) * eta)
            etaP -= beta[j - 1] * cos(Double(2 * j) * xi) * sinh(Double(2 * j) * eta)
        }
        let chi = asin(sin(xiP) / cosh(etaP))
        var phi = chi
        for j in 1 ... delta.count {
            phi += delta[j - 1] * sin(Double(2 * j) * chi)
        }
        let lon = centralMeridian(utm.zone) + Angle.degrees(atan(sinh(etaP) / cos(xiP)))
        return LonLat(lon: normalizeLon(lon), lat: Angle.degrees(phi))
    }

    /// Latitude band letter C...X (without I and O); nil outside 80 S ... 84 N.
    public static func bandLetter(_ lat: Double) -> Character? {
        if lat < -80.0 || lat > 84.0 { return nil }
        let index = Swift.min(kotlinToInt(floor((lat + 80.0) / 8.0)), 19)
        return bands[index]
    }

    /// The angle from true north to the grid north of `zone` at a position, in degrees, positive when grid north lies
    /// east of true north. The spherical formula `atan(tan(dLon) sin(lat))` is within 0.0001° of the ellipsoid's.
    public static func convergenceDegrees(lat: Double, lon: Double, zone: Int? = nil) -> Double {
        let zone = zone ?? zoneOf(lat: lat, lon: lon)
        let dLon = Angle.radians(normalizeLon(lon - centralMeridian(zone)))
        return Angle.degrees(atan(tan(dLon) * sin(Angle.radians(lat))))
    }

    static func normalizeLon(_ lon: Double) -> Double {
        var l = kotlinRem(lon, 360.0)
        if l >= 180.0 { l -= 360.0 }
        if l < -180.0 { l += 360.0 }
        return abs(l) < 1e-12 ? 0.0 : l
    }
}
