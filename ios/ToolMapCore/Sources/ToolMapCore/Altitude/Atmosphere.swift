// Port of core/src/main/kotlin/fr/toolmap/core/altitude/Atmosphere.kt.

import Foundation

/// The international standard atmosphere below 11 km, which is what a phone barometer is read against.
public enum Atmosphere {
    public static let standardSeaLevelHpa = 1013.25

    /// Scale height of the troposphere formula, in meters.
    private static let heightScale = 44_330.77

    /// The exponent of the pressure ratio: `p / p0 = (1 - h / heightScale) ^ exponent`.
    private static let exponent = 5.255876

    /// Lowest and highest pressures a barometer on the ground can read; anything else is a faulty reading.
    public static let minPlausibleHpa = 300.0
    public static let maxPlausibleHpa = 1100.0

    /// Altitude where the pressure is `pressureHpa` when it is `seaLevelHpa` at sea level.
    public static func altitudeMeters(_ pressureHpa: Double, _ seaLevelHpa: Double) -> Double {
        heightScale * (1.0 - Foundation.pow(pressureHpa / seaLevelHpa, 1.0 / exponent))
    }

    /// The sea-level pressure that makes a barometer reading `pressureHpa` correspond to `altitudeMeters`.
    public static func seaLevelHpa(_ pressureHpa: Double, _ altitudeMeters: Double) -> Double {
        pressureHpa / Foundation.pow(1.0 - altitudeMeters / heightScale, exponent)
    }

    /// How many hectopascals of sea-level pressure one meter of altitude error stands for, at `altitudeMeters`: the
    /// factor between an altitude uncertainty and the uncertainty of the calibration.
    public static func seaLevelHpaPerMeter(_ seaLevelHpa: Double, _ altitudeMeters: Double) -> Double {
        seaLevelHpa * exponent / (heightScale - altitudeMeters)
    }
}
