// Port of core/src/main/kotlin/fr/toolmap/core/geo/Navigation.kt.

/// A place the user chose to walk to.
public struct NavTarget: Equatable, Hashable, Sendable {
    public let lat: Double
    public let lon: Double
    public let name: String?
    /// GPX symbol of a waypoint target: its icon at the top of the start sheet (P30.3).
    public let sym: String?

    public init(lat: Double, lon: Double, name: String? = nil, sym: String? = nil) {
        self.lat = lat
        self.lon = lon
        self.name = name
        self.sym = sym
    }
}

/// How to get from here to a `NavTarget`. `relativeDegrees` is nil while the device heading is unknown.
public struct Guidance: Equatable, Hashable, Sendable {
    public let distanceMeters: Double
    public let bearingDegrees: Double
    public let relativeDegrees: Double?

    public init(distanceMeters: Double, bearingDegrees: Double, relativeDegrees: Double?) {
        self.distanceMeters = distanceMeters
        self.bearingDegrees = bearingDegrees
        self.relativeDegrees = relativeDegrees
    }
}

public enum Navigation {
    /// Arrival radius: closer than this and the target counts as reached.
    public static let arrivedMeters = 15.0

    private static let points = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"]

    public static func guide(
        _ fromLat: Double,
        _ fromLon: Double,
        _ target: NavTarget,
        headingDegrees: Double? = nil
    ) -> Guidance {
        let bearing = Geodesy.bearing(fromLat, fromLon, target.lat, target.lon)
        return Guidance(
            distanceMeters: Geodesy.distance(fromLat, fromLon, target.lat, target.lon),
            bearingDegrees: bearing,
            relativeDegrees: headingDegrees.map { Geodesy.relativeBearing($0, bearing) }
        )
    }

    public static func arrived(_ guidance: Guidance) -> Bool {
        guidance.distanceMeters <= arrivedMeters
    }

    /// Eight-wind compass name of a bearing: `NE`.
    public static func compassPoint(_ bearingDegrees: Double) -> String {
        let normalised = kotlinRem(kotlinRem(bearingDegrees, 360.0) + 360.0, 360.0)
        return points[kotlinToInt((normalised + 22.5) / 45.0) % 8]
    }
}
