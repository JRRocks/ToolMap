// Port of core/src/main/kotlin/fr/toolmap/core/geo/ScaleBar.kt (also used by the print frame and its scale bar).

import Foundation

/// Scale bar maths: ground distance of one density-independent pixel, then a rounded 1-2-5 distance.
public enum ScaleBar {
    private static let earthCircumferenceM = 40_075_016.685578488
    private static let cameraTileSize = 512.0
    private static let tilePixels = 256.0

    /// Printed length of one dp: Android's dp is 1/160 inch.
    private static let dpMeters = 0.0254 / 160.0

    private static let metersPerFoot = 0.3048
    private static let feetPerMile = 5280.0
    private static let feetPerMileSwitch = 1000.0

    /// The bar to draw: its length in meters, its width in dp and its label.
    public struct Bar: Equatable, Hashable, Sendable {
        public let meters: Double
        public let widthDp: Double
        public let label: String

        public init(meters: Double, widthDp: Double, label: String) {
            self.meters = meters
            self.widthDp = widthDp
            self.label = label
        }
    }

    /// Meters covered by one dp at `latitude` for a MapLibre camera `cameraZoom`. The camera works with 512 dp tiles:
    /// at zoom 0 the whole world is 512 dp wide.
    public static func metersPerDp(_ latitude: Double, _ cameraZoom: Double) -> Double {
        earthCircumferenceM * cos(Angle.radians(latitude)) / (cameraTileSize * Foundation.pow(2.0, cameraZoom))
    }

    /// The camera zoom at which one dp covers `metersPerDp` meters at `latitude`: the inverse of `metersPerDp`.
    public static func cameraZoomFor(_ latitude: Double, _ metersPerDp: Double) -> Double {
        log(earthCircumferenceM * cos(Angle.radians(latitude)) / (cameraTileSize * metersPerDp)) / log(2.0)
    }

    /// Ground meters under one dp at `latitude` when the map is shown at the displayed zoom `displayedZoom` (the `z` of
    /// 256 px tiles, `ZoomMapping`) on a screen of `density`: one tile pixel per screen pixel, as AlpineQuest draws it.
    public static func groundMetersPerDp(_ latitude: Double, _ displayedZoom: Double, _ density: Double) -> Double {
        earthCircumferenceM * cos(Angle.radians(latitude)) / (tilePixels * Foundation.pow(2.0, displayedZoom)) * density
    }

    /// The scale denominator, the `n` of "1:n": `groundMetersPerDp` over the printed length of a dp, which is 1/160
    /// inch on Android whatever the density. At zoom 16 near 46° on a 2.625 density screen, about 27 370.
    public static func denominator(_ latitude: Double, _ displayedZoom: Double, _ density: Double) -> Double {
        groundMetersPerDp(latitude, displayedZoom, density) / dpMeters
    }

    /// "1:27,410" for a denominator of 27 410.4: the thousands are separated by a comma, whatever the locale.
    public static func ratioText(_ denominator: Double) -> String {
        "1:" + grouped(kotlinRound(denominator))
    }

    /// Largest 1, 2 or 5 × 10^n that does not exceed `maxMeters` (0 for a non-positive input).
    public static func niceDistance(_ maxMeters: Double) -> Double {
        if !(maxMeters > 0.0) || maxMeters.isInfinite { return 0.0 }
        let exponent = floor(log10(maxMeters))
        let base = Foundation.pow(10.0, exponent)
        let mantissa = maxMeters / base
        // Guard against log10 rounding at exact powers of ten.
        let step: Double
        if mantissa >= 5.0 {
            step = 5.0
        } else if mantissa >= 2.0 {
            step = 2.0
        } else if mantissa >= 1.0 {
            step = 1.0
        } else {
            step = 0.5
        }
        return step * base
    }

    /// "50 cm", "200 m", "2 km".
    public static func format(_ meters: Double) -> String {
        if meters < 1.0 { return "\(trim(meters * 100.0)) cm" }
        if meters < 1000.0 { return "\(trim(meters)) m" }
        return "\(trim(meters / 1000.0)) km"
    }

    /// "100 ft", "500 ft", "2 mi": the imperial counterpart of `format`, for a length given in meters.
    public static func formatImperial(_ meters: Double) -> String {
        let feet = meters / metersPerFoot
        return feet < feetPerMileSwitch ? "\(trim(feet)) ft" : "\(trim(feet / feetPerMile)) mi"
    }

    /// The bar to draw: at most `maxWidthDp` wide, whose length is a round ground distance in the unit of `units`
    /// (1-2-5 meters or kilometres, or feet or miles). `Bar.meters` is always the length in meters.
    public static func compute(
        _ latitude: Double,
        _ cameraZoom: Double,
        maxWidthDp: Double,
        units: UnitSystem = .metric
    ) -> Bar {
        barFor(metersPerDp(latitude, cameraZoom), maxWidthDp, units)
    }

    /// The bar of a map shown at the displayed zoom `displayedZoom` on a screen of `density`, the same way as
    /// `denominator`: its length is a round ground distance, and a bar of `maxWidthDp` dp is the widest it may be.
    public static func forDisplayedZoom(
        _ latitude: Double,
        _ displayedZoom: Double,
        _ density: Double,
        maxWidthDp: Double,
        units: UnitSystem = .metric
    ) -> Bar {
        barFor(groundMetersPerDp(latitude, displayedZoom, density), maxWidthDp, units)
    }

    private static func barFor(_ perDp: Double, _ maxWidthDp: Double, _ units: UnitSystem) -> Bar {
        let maxMeters = perDp * maxWidthDp
        if units == .metric {
            let meters = niceDistance(maxMeters)
            return Bar(meters: meters, widthDp: perDp > 0.0 ? meters / perDp : 0.0, label: format(meters))
        }
        let maxFeet = maxMeters / metersPerFoot
        let meters: Double
        if maxFeet < feetPerMileSwitch {
            meters = niceDistance(maxFeet) * metersPerFoot
        } else {
            meters = niceDistance(maxFeet / feetPerMile) * feetPerMile * metersPerFoot
        }
        return Bar(meters: meters, widthDp: perDp > 0.0 ? meters / perDp : 0.0, label: formatImperial(meters))
    }

    /// Kotlin: `Math.round(value * 100.0) / 100.0`, written without decimals when it is a whole number.
    private static func trim(_ value: Double) -> String {
        let rounded = Double(kotlinRound(value * 100.0)) / 100.0
        if rounded == floor(rounded) { return String(kotlinToLong(rounded)) }
        return "\(rounded)"
    }

    /// Java `%,d` in `Locale.ROOT`: the digits in groups of three separated by commas.
    private static func grouped(_ value: Int) -> String {
        let digits = Array(String(value.magnitude))
        var out = ""
        for (i, digit) in digits.enumerated() {
            if i > 0, (digits.count - i) % 3 == 0 { out.append(",") }
            out.append(digit)
        }
        return value < 0 ? "-" + out : out
    }
}
