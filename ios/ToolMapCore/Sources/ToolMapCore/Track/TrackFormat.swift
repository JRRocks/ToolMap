// Port of core/src/main/kotlin/fr/toolmap/core/track/TrackFormat.kt.

import Foundation

/// Distances, durations and speeds as a hiker reads them. Pure functions: the caller picks the `Locale` (its decimal
/// separator, as Java's `String.format(locale, ...)`) and the `UnitSystem` (metric unless told otherwise).
public enum TrackFormat {
    private static let millisPerMinute: Int64 = 60000
    private static let minutesPerHour: Int64 = 60
    private static let mpsToKmh = 3.6
    private static let mpsToMph = 2.2369362920544

    /// International foot: exactly 0.3048 m.
    public static let feetPerMeter = 1 / 0.3048
    public static let feetPerMile = 5280.0
    private static let feetPerMileSwitch = 1000.0
    private static let squareMetersPerHectare = 10000.0
    private static let squareMetersPerKm2 = 1_000_000.0
    private static let squareFeetPerAcre = 43560.0
    private static let squareFeetPerSquareMile = 27_878_400.0
    private static let tenMilesFeet = 10 * feetPerMile

    private static func fixed(_ value: Double, _ decimals: Int, _ locale: Locale) -> String {
        TrackNumberText.fixed(value, decimals, separator: TrackNumberText.decimalSeparator(locale))
    }

    private static func rounded(_ value: Double) -> String { String(kotlinRound(value)) }

    /// Metric: `850 m` below 1 km, then `12,3 km` (two decimals under 10 km, one above). Imperial: `850 ft` below
    /// 1 000 ft, then `3.25 mi` (same decimals rule).
    public static func distance(_ meters: Double, locale: Locale = .current, units: UnitSystem = .metric) -> String {
        switch units {
        case .metric:
            if meters < 1000 { return rounded(meters) + " m" }
            if meters < 10000 { return fixed(meters / 1000, 2, locale) + " km" }
            return fixed(meters / 1000, 1, locale) + " km"
        case .imperial:
            let feet = meters * feetPerMeter
            if feet < feetPerMileSwitch { return rounded(feet) + " ft" }
            if feet < tenMilesFeet { return fixed(feet / feetPerMile, 2, locale) + " mi" }
            return fixed(feet / feetPerMile, 1, locale) + " mi"
        }
    }

    public static func elevation(_ meters: Double, locale: Locale = .current, units: UnitSystem = .metric) -> String {
        switch units {
        case .metric: rounded(meters) + " m"
        case .imperial: rounded(meters * feetPerMeter) + " ft"
        }
    }

    /// Metric: `850 m²` below 1 ha, `12,3 ha` below 1 km², then `2,35 km²`. Imperial: `5 400 ft²` below an acre,
    /// `12.3 ac` below a square mile, then `1.25 mi²`.
    public static func area(_ squareMeters: Double, locale: Locale = .current, units: UnitSystem = .metric) -> String {
        switch units {
        case .metric:
            if squareMeters < squareMetersPerHectare { return rounded(squareMeters) + " m²" }
            if squareMeters < squareMetersPerKm2 { return fixed(squareMeters / squareMetersPerHectare, 2, locale) + " ha" }
            return fixed(squareMeters / squareMetersPerKm2, 3, locale) + " km²"
        case .imperial:
            let squareFeet = squareMeters * feetPerMeter * feetPerMeter
            if squareFeet < squareFeetPerAcre { return rounded(squareFeet) + " ft²" }
            if squareFeet < squareFeetPerSquareMile { return fixed(squareFeet / squareFeetPerAcre, 2, locale) + " ac" }
            return fixed(squareFeet / squareFeetPerSquareMile, 3, locale) + " mi²"
        }
    }

    /// `45 min`, `2 h 05`, `26 h 10`: hours and minutes, no seconds.
    public static func duration(_ millis: Int64) -> String {
        let minutes = (millis &+ millisPerMinute / 2) / millisPerMinute
        let h = minutes / minutesPerHour
        let m = minutes % minutesPerHour
        if h == 0 { return "\(m) min" }
        let text = String(m)
        return "\(h) h " + (text.count < 2 ? String(repeating: "0", count: 2 - text.count) + text : text)
    }

    /// Kilometres (or miles) per hour with one decimal: `4,3 km/h`, `2.7 mph`.
    public static func speed(_ metersPerSecond: Double, locale: Locale = .current, units: UnitSystem = .metric) -> String {
        switch units {
        case .metric: fixed(metersPerSecond * mpsToKmh, 1, locale) + " km/h"
        case .imperial: fixed(metersPerSecond * mpsToMph, 1, locale) + " mph"
        }
    }

    /// Slope as a signed percentage: `+12 %`, `-4 %`.
    public static func grade(_ percent: Double, locale: Locale = .current) -> String {
        TrackNumberText.integer(kotlinRound(percent), plusSign: true) + " %"
    }
}
