// Numbers written as the JVM writes them, for the ports of core/src/main/kotlin/fr/toolmap/core/track/ (GPX, GeoJSON,
// issue details) and settings/UnitFormats.kt: `Double.toString()`, `Float.toString()`,
// `BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()` and `String.format(locale, "%.nf", d)`.

import Foundation

enum TrackNumberText {
    /// The shortest decimal digits that read back as `value` (finite, not zero): `value = 0.d1d2d3... × 10^point`.
    /// Swift and the JVM (Java 19 and later) both print the shortest form, so the digits are the same.
    static func shortestDigits(_ value: Double) -> (digits: [UInt8], point: Int) {
        digits(of: "\(Swift.abs(value))")
    }

    static func shortestDigits(_ value: Float) -> (digits: [UInt8], point: Int) {
        digits(of: "\(Swift.abs(value))")
    }

    /// Reads Swift's description of a positive finite number (`123.45`, `1e-05`, `1.5e+20`) into digits and point.
    private static func digits(of description: String) -> (digits: [UInt8], point: Int) {
        let bytes = Array(description.utf8)
        var mantissa: [UInt8] = []
        var exponent = 0
        var integerCount = 0
        var seenPoint = false
        var i = 0
        while i < bytes.count {
            let b = bytes[i]
            if b == UInt8(ascii: "e") || b == UInt8(ascii: "E") {
                exponent = Int(String(decoding: bytes[(i + 1)...], as: UTF8.self)) ?? 0
                break
            }
            if b == UInt8(ascii: ".") {
                seenPoint = true
            } else if b >= UInt8(ascii: "0"), b <= UInt8(ascii: "9") {
                mantissa.append(b - UInt8(ascii: "0"))
                if !seenPoint { integerCount += 1 }
            }
            i += 1
        }
        var point = integerCount + exponent
        while let first = mantissa.first, first == 0 {
            mantissa.removeFirst()
            point -= 1
        }
        while let last = mantissa.last, last == 0 {
            mantissa.removeLast()
        }
        return (mantissa, point)
    }

    private static func text(_ digits: ArraySlice<UInt8>) -> String {
        String(decoding: digits.map { $0 + UInt8(ascii: "0") }, as: UTF8.self)
    }

    private static func zeros(_ count: Int) -> String {
        count > 0 ? String(repeating: "0", count: count) : ""
    }

    /// Java `Double.toString(value)`: `45.0`, `0.001`, `1.0E-5`, `1.23456789E8`, `NaN`, `-Infinity`.
    static func javaString(_ value: Double) -> String {
        if value.isNaN { return "NaN" }
        if value.isInfinite { return value < 0 ? "-Infinity" : "Infinity" }
        let sign = value.sign == .minus ? "-" : ""
        if value == 0 { return sign + "0.0" }
        let (digits, point) = shortestDigits(value)
        return sign + javaLayout(digits, point, plainBetween: Swift.abs(value) >= 1e-3 && Swift.abs(value) < 1e7)
    }

    /// Java `Float.toString(value)`: `4.0`, `2.5`, `1.0E10`.
    static func javaString(_ value: Float) -> String {
        if value.isNaN { return "NaN" }
        if value.isInfinite { return value < 0 ? "-Infinity" : "Infinity" }
        let sign = value.sign == .minus ? "-" : ""
        if value == 0 { return sign + "0.0" }
        let (digits, point) = shortestDigits(value)
        return sign + javaLayout(digits, point, plainBetween: Swift.abs(value) >= 1e-3 && Swift.abs(value) < 1e7)
    }

    /// Kotlin's string template of a `Double?`: `null` or `Double.toString`.
    static func javaString(_ value: Double?) -> String {
        guard let value else { return "null" }
        return javaString(value)
    }

    private static func javaLayout(_ digits: [UInt8], _ point: Int, plainBetween plain: Bool) -> String {
        if plain {
            if point <= 0 { return "0." + zeros(-point) + text(digits[...]) }
            if point >= digits.count { return text(digits[...]) + zeros(point - digits.count) + ".0" }
            return text(digits[..<point]) + "." + text(digits[point...])
        }
        let rest = digits.count > 1 ? text(digits[1...]) : "0"
        return text(digits[..<1]) + "." + rest + "E" + String(point - 1)
    }

    /// `BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()`: the shortest decimal that reads back as the
    /// same double, never in scientific notation (`45`, `0.00001`, `150000000000000000000`). Zero is `0`.
    static func plain(_ value: Double) -> String {
        if !value.isFinite { return javaString(value) }
        if value == 0 { return "0" }
        let (digits, point) = shortestDigits(value)
        let sign = value < 0 ? "-" : ""
        if point <= 0 { return sign + "0." + zeros(-point) + text(digits[...]) }
        if point >= digits.count { return sign + text(digits[...]) + zeros(point - digits.count) }
        return sign + text(digits[..<point]) + "." + text(digits[point...])
    }

    /// Java `String.format(locale, "%.<decimals>f", value)`: rounded half up on the shortest decimal digits of the value
    /// (`1.005` gives `1.01`, `0.15` gives `0.2`), with `separator` as the decimal separator. A negative value keeps its
    /// minus sign even when it rounds to zero (`-0,00`), as on the JVM.
    static func fixed(_ value: Double, _ decimals: Int, separator: String = ".") -> String {
        if value.isNaN { return "NaN" }
        if value.isInfinite { return value < 0 ? "-Infinity" : "Infinity" }
        let sign = value.sign == .minus ? "-" : ""
        var digits: [UInt8] = []
        var point = 0
        if value != 0 {
            let shortest = shortestDigits(value)
            digits = shortest.digits
            point = shortest.point
            let keep = point + decimals
            if keep < 0 {
                digits = []
                point = 0
            } else if keep < digits.count {
                let roundUp = digits[keep] >= 5
                digits.removeLast(digits.count - keep)
                if roundUp {
                    var i = digits.count - 1
                    var carry = true
                    while carry, i >= 0 {
                        if digits[i] == 9 {
                            digits[i] = 0
                            i -= 1
                        } else {
                            digits[i] += 1
                            carry = false
                        }
                    }
                    if carry {
                        digits.insert(1, at: 0)
                        point += 1
                    }
                }
            }
        }
        // value = 0.digits × 10^point; integer part then `decimals` fraction digits.
        var integerPart = ""
        if point > 0 {
            for k in 0 ..< point {
                integerPart += k < digits.count ? String(digits[k]) : "0"
            }
        }
        if integerPart.isEmpty { integerPart = "0" }
        var fraction = ""
        for k in 0 ..< decimals {
            let index = point + k
            fraction += index >= 0 && index < digits.count ? String(digits[index]) : "0"
        }
        return sign + integerPart + (decimals > 0 ? separator + fraction : "")
    }

    /// The decimal separator Java's `Formatter` uses for `locale`.
    static func decimalSeparator(_ locale: Locale) -> String {
        locale.decimalSeparator ?? "."
    }

    /// Java `String.format(locale, "%d", n)` / `"%+d"`.
    static func integer(_ value: Int, plusSign: Bool = false) -> String {
        plusSign && value >= 0 ? "+" + String(value) : String(value)
    }
}
