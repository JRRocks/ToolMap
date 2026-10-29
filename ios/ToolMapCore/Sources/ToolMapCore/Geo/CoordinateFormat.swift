// Port of core/src/main/kotlin/fr/toolmap/core/geo/CoordinateFormat.kt.

import Foundation

/// A position as text. `outsideValidity` is set when the system is not meant for that place (Lambert 93 abroad).
public struct FormattedPosition: Equatable, Sendable {
    public let text: String
    public let outsideValidity: Bool

    public init(_ text: String, outsideValidity: Bool = false) {
        self.text = text
        self.outsideValidity = outsideValidity
    }
}

/// Writes a position in the format the user chose: degrees, UTM, MGRS or Lambert 93.
public enum CoordinateFormat {
    /// `lat`, `lon` in `system`. UTM and MGRS stop at 80 S and 84 N: beyond them the text falls back to decimal degrees.
    public static func format(_ system: CoordinateSystem, _ lat: Double, _ lon: Double) -> FormattedPosition {
        switch system {
        case .dd:
            return FormattedPosition(decimalDegrees(lat, lon))
        case .ddm:
            return FormattedPosition(degreesDecimalMinutes(lat, lon))
        case .dms:
            return FormattedPosition(degreesMinutesSeconds(lat, lon))
        case .utm:
            return FormattedPosition(utm(lat, lon) ?? decimalDegrees(lat, lon))
        case .mgrs:
            if let reference = Mgrs.of(lat, lon), let text = try? reference.format() {
                return FormattedPosition(text)
            }
            return FormattedPosition(decimalDegrees(lat, lon))
        case .lambert93:
            let p = Lambert93.toLambert(lat, lon)
            return FormattedPosition(
                "L93 \(kotlinRound(p.easting)) E \(kotlinRound(p.northing)) N",
                outsideValidity: !Lambert93.covers(lat, lon)
            )
        }
    }

    /// `31T 452482 E 5011717 N`: zone and latitude band, then meters east and north; nil outside 80 S ... 84 N.
    public static func utm(_ lat: Double, _ lon: Double) -> String? {
        guard let band = Utm.bandLetter(lat) else { return nil }
        let u = Utm.toUtm(lat, lon)
        return "\(u.zone)\(band) \(kotlinRound(u.easting)) E \(kotlinRound(u.northing)) N"
    }

    /// `44.80000, 5.90000` (latitude first, dot decimal separator whatever the device locale).
    public static func decimalDegrees(_ latitude: Double, _ longitude: Double, decimals: Int = 5) -> String {
        "\(formatFixed(latitude, decimals)), \(formatFixed(longitude, decimals))"
    }

    /// `44.80000° N, 5.90000° E`.
    public static func decimalDegreesWithHemisphere(_ latitude: Double, _ longitude: Double, decimals: Int = 5) -> String {
        let ns = latitude >= 0 ? "N" : "S"
        let ew = longitude >= 0 ? "E" : "W"
        return "\(formatFixed(abs(latitude), decimals))° \(ns), \(formatFixed(abs(longitude), decimals))° \(ew)"
    }

    /// `44° 48.000' N, 5° 54.000' E`.
    public static func degreesDecimalMinutes(_ latitude: Double, _ longitude: Double, decimals: Int = 3) -> String {
        "\(ddm(latitude, "N", "S", decimals)), \(ddm(longitude, "E", "W", decimals))"
    }

    /// `44° 48' 00.0" N, 5° 54' 00.0" E`.
    public static func degreesMinutesSeconds(_ latitude: Double, _ longitude: Double, decimals: Int = 1) -> String {
        "\(dms(latitude, "N", "S", decimals)), \(dms(longitude, "E", "W", decimals))"
    }

    private static func ddm(_ value: Double, _ positive: String, _ negative: String, _ decimals: Int) -> String {
        let unit = Foundation.pow(10.0, Double(decimals))
        // Work in rounded minute units so that 59.9996' carries into the degree instead of printing 60.000'.
        let total = kotlinRound(abs(value) * 60.0 * unit)
        let perDegree = kotlinToLong(60.0 * unit)
        let degrees = total / perDegree
        let minutes = Double(total % perDegree) / unit
        let width = decimals + 3
        return String(format: "%ld° %0\(width).\(decimals)f' %@", degrees, minutes, value >= 0 ? positive : negative)
    }

    private static func dms(_ value: Double, _ positive: String, _ negative: String, _ decimals: Int) -> String {
        let unit = Foundation.pow(10.0, Double(decimals))
        let total = kotlinRound(abs(value) * 3600.0 * unit)
        let perDegree = kotlinToLong(3600.0 * unit)
        let perMinute = kotlinToLong(60.0 * unit)
        let degrees = total / perDegree
        let minutes = (total % perDegree) / perMinute
        let seconds = Double(total % perMinute) / unit
        let width = decimals + 3
        return String(
            format: "%ld° %02ld' %0\(width).\(decimals)f\" %@",
            degrees,
            minutes,
            seconds,
            value >= 0 ? positive : negative
        )
    }
}
