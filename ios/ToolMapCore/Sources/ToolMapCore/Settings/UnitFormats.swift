// Port of core/src/main/kotlin/fr/toolmap/core/settings/UnitFormats.kt.

import Foundation

/// Bearings as the user reads them (AlpineQuest's « Bearing unit »): degrees, NATO mils (6 400 per turn) or gradians
/// (400 per turn). Pure functions.
public enum BearingFormat {
    public static func perTurn(_ unit: BearingUnit) -> Int {
        switch unit {
        case .degrees: 360
        case .mils: 6400
        case .gradians: 400
        }
    }

    /// The bearing in `[0, 360)` degrees, whatever the input.
    public static func normalize(_ degrees: Double) -> Double {
        kotlinRem(kotlinRem(degrees, 360.0) + 360.0, 360.0)
    }

    /// The bearing in the unit, in `[0, perTurn)` after rounding: `359.7°` is `0°`, not `360°`.
    public static func value(_ degrees: Double, _ unit: BearingUnit) -> Int {
        let turn = perTurn(unit)
        return Int(Int32(truncatingIfNeeded: kotlinRound(normalize(degrees) * Double(turn) / 360.0))) % turn
    }

    /// `128°`, `2 844 mil`, `142 gon`.
    public static func format(_ degrees: Double, _ unit: BearingUnit, locale: Locale = .current) -> String {
        let v = value(degrees, unit)
        switch unit {
        case .degrees: return "\(v)°"
        case .mils: return "\(v) mil"
        case .gradians: return "\(v) gon"
        }
    }
}

/// Slopes (AlpineQuest's « Slope unit »): a percentage, as `+12 %`, or an angle, as `+6.8°`.
public enum SlopeFormat {
    public static func format(_ percent: Double, _ unit: SlopeUnit, locale: Locale = .current) -> String {
        switch unit {
        case .percent:
            return TrackFormat.grade(percent, locale: locale)
        case .degrees:
            let degrees = Angle.degrees(atan(percent / 100.0))
            let text = TrackNumberText.fixed(degrees, 1, separator: TrackNumberText.decimalSeparator(locale))
            // Java `%+.1f`: a plus sign unless the value is negative (or NaN).
            return (degrees.sign == .minus || degrees.isNaN ? text : "+" + text) + "°"
        }
    }
}

/// Pressures (AlpineQuest's « Pressure unit »). The value is kept in hectopascals, as the barometer gives it.
public enum PressureFormat {
    public static let mmhgPerHpa = 0.750062
    public static let inhgPerHpa = 0.0295300

    public static func value(_ hectopascals: Double, _ unit: PressureUnit) -> Double {
        switch unit {
        case .hpa: hectopascals
        case .mmhg: hectopascals * mmhgPerHpa
        case .inhg: hectopascals * inhgPerHpa
        }
    }

    /// `953.54 hPa`, `715.12 mmHg`, `28.15 inHg`.
    public static func format(_ hectopascals: Double, _ unit: PressureUnit, locale: Locale = .current) -> String {
        let text = TrackNumberText.fixed(value(hectopascals, unit), 2, separator: TrackNumberText.decimalSeparator(locale))
        switch unit {
        case .hpa: return text + " hPa"
        case .mmhg: return text + " mmHg"
        case .inhg: return text + " inHg"
        }
    }
}

/// The north the bearings are measured from (AlpineQuest's « Reference north »). The offset is the angle from true
/// north to the reference north, positive clockwise: a magnetic bearing is the true one minus the declination (east
/// positive), a grid bearing is the true one minus the convergence of the grid.
public enum NorthReferenceMath {
    public static func offsetDegrees(
        _ north: NorthReference,
        _ declinationDegrees: Double,
        _ convergenceDegrees: Double
    ) -> Double {
        switch north {
        case .trueNorth: 0.0
        case .magnetic: declinationDegrees
        case .grid: convergenceDegrees
        }
    }

    /// A true bearing read from the reference north.
    public static func relative(_ trueDegrees: Double, _ offsetDegrees: Double) -> Double {
        BearingFormat.normalize(trueDegrees - offsetDegrees)
    }

    /// Convergence of the UTM grid at a position: the angle between true north and grid north, east positive, in the
    /// zone of the position (Norway and Svalbard exceptions included).
    public static func utmConvergenceDegrees(_ lat: Double, _ lon: Double) -> Double {
        Utm.convergenceDegrees(lat: lat, lon: lon)
    }
}
