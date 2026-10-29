// Port of core/src/main/kotlin/fr/toolmap/core/track/Geodesy.kt.

import Foundation

/// Distances on the WGS84 sphere of mean radius, the usual accuracy for GPS tracks (error under 0.5 %).
public enum Geodesy {
    public static let earthRadiusMeters = 6_371_008.8

    /// Great-circle distance in meters between two positions in degrees.
    public static func distance(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let p1 = Angle.radians(lat1)
        let p2 = Angle.radians(lat2)
        let dp = p2 - p1
        let dl = Angle.radians(lon2 - lon1)
        let a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * earthRadiusMeters * asin(Swift.min(1.0, a.squareRoot()))
    }

    /// Initial great-circle bearing from the first position to the second, degrees clockwise from north, [0, 360).
    public static func bearing(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let p1 = Angle.radians(lat1)
        let p2 = Angle.radians(lat2)
        let dl = Angle.radians(lon2 - lon1)
        let y = sin(dl) * cos(p2)
        let x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return kotlinRem(Angle.degrees(atan2(y, x)) + 360.0, 360.0)
    }

    /// Smallest signed turn from heading `from` to bearing `to`, in [-180, 180): negative is to the left.
    public static func relativeBearing(_ from: Double, _ to: Double) -> Double {
        kotlinRem(kotlinRem(to - from, 360.0) + 540.0, 360.0) - 180.0
    }
}
