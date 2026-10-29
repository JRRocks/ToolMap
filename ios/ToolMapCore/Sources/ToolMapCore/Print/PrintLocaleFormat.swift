// Number formats of the JVM that the print code of `:core` relies on (`String.format(locale, …)`), so that a sheet
// reads the same on Android and on iPhone: `%.1f` rounds half up on the shortest decimal form of the value, `%,d`
// groups the thousands with the separator of the locale, `%s`, `%d` and `%1$s` take their argument by position.

import Foundation

enum PrintLocaleFormat {
    /// Java's `Locale.ROOT`: a point between the units and the decimals, a comma between the thousands.
    static let root = Locale(identifier: "en_US_POSIX")

    /// Java `String.format(locale, "%.<decimals>f", value)`: the shortest decimal form of the value, rounded half away
    /// from zero (Java's HALF_UP), with the decimal separator of `locale`.
    static func fixed(_ value: Double, _ decimals: Int, _ locale: Locale) -> String {
        if value.isNaN { return "NaN" }
        if value.isInfinite { return value < 0 ? "-Infinity" : "Infinity" }
        let places = Swift.max(decimals, 0)
        // Swift writes the shortest form of the value, as Java does; past 1e-5 or 1e16 it uses an exponent, which is
        // left to the binary value.
        let shortest = "\(value)"
        var exact = shortest.contains("e") ? Decimal(value) : (Decimal(string: shortest) ?? Decimal(value))
        var rounded = Decimal()
        NSDecimalRound(&rounded, &exact, places, .plain)
        var result = NSDecimalNumber(decimal: rounded).doubleValue
        if result == 0, value.sign == .minus {
            result = -0.0
        }
        return String(format: "%.\(places)f", locale: locale, result)
    }

    /// Java `String.format(locale, "%,d", value)`.
    static func grouped(_ value: Int, _ locale: Locale) -> String {
        let separator = groupingSeparator(locale)
        let digits = String(value.magnitude)
        var out = ""
        for (i, c) in digits.enumerated() {
            if i > 0, (digits.count - i) % 3 == 0 { out += separator }
            out.append(c)
        }
        return value < 0 ? "-" + out : out
    }

    static func groupingSeparator(_ locale: Locale) -> String {
        if locale.identifier == root.identifier { return "," }
        return locale.groupingSeparator ?? ","
    }

    static func decimalSeparator(_ locale: Locale) -> String {
        if locale.identifier == root.identifier { return "." }
        return locale.decimalSeparator ?? "."
    }

    /// Java `String.format(locale, pattern, args...)` for the conversions the page texts use: `%s`, `%d` (with `,` and
    /// zero padding), `%f` (with a precision), `%%` and `%n`; `%1$s` names its argument. A missing argument leaves
    /// nothing in its place.
    static func format(_ locale: Locale, _ pattern: String, _ args: Any...) -> String {
        formatList(locale, pattern, args)
    }

    static func formatList(_ locale: Locale, _ pattern: String, _ args: [Any]) -> String {
        let chars = Array(pattern)
        var out = ""
        var i = 0
        var ordinary = 0
        while i < chars.count {
            let c = chars[i]
            if c != "%" {
                out.append(c)
                i += 1
                continue
            }
            var j = i + 1
            // An argument index: digits then `$`.
            var explicit: Int?
            var k = j
            var number = 0
            var sawDigit = false
            while k < chars.count, let d = digit(chars[k]) {
                number = number * 10 + d
                sawDigit = true
                k += 1
            }
            if sawDigit, k < chars.count, chars[k] == "$" {
                explicit = number
                j = k + 1
            }
            var flags = ""
            while j < chars.count, "-#+ 0,(<".contains(chars[j]) {
                flags.append(chars[j])
                j += 1
            }
            var width: Int?
            while j < chars.count, let d = digit(chars[j]) {
                width = (width ?? 0) * 10 + d
                j += 1
            }
            var precision: Int?
            if j < chars.count, chars[j] == "." {
                j += 1
                var p = 0
                while j < chars.count, let d = digit(chars[j]) {
                    p = p * 10 + d
                    j += 1
                }
                precision = p
            }
            guard j < chars.count else {
                out += String(chars[i...])
                break
            }
            let conversion = chars[j]
            j += 1
            var text: String
            switch conversion {
            case "%":
                text = "%"
            case "n":
                text = "\n"
            case "s", "S", "d", "f":
                let index: Int
                if let explicit {
                    index = explicit - 1
                } else {
                    index = ordinary
                    ordinary += 1
                }
                guard index >= 0, index < args.count else {
                    text = ""
                    break
                }
                text = convert(args[index], conversion, flags, precision, locale)
            default:
                text = String(chars[i ..< j])
            }
            if let width, text.count < width {
                let padding = width - text.count
                if flags.contains("-") {
                    text += String(repeating: " ", count: padding)
                } else if flags.contains("0"), conversion == "d" || conversion == "f" {
                    let negative = text.hasPrefix("-")
                    let body = negative ? String(text.dropFirst()) : text
                    text = (negative ? "-" : "") + String(repeating: "0", count: padding) + body
                } else {
                    text = String(repeating: " ", count: padding) + text
                }
            }
            out += text
            i = j
        }
        return out
    }

    private static func convert(
        _ arg: Any,
        _ conversion: Character,
        _ flags: String,
        _ precision: Int?,
        _ locale: Locale
    ) -> String {
        switch conversion {
        case "d":
            guard let value = integer(arg) else { return "\(arg)" }
            return flags.contains(",") ? grouped(value, locale) : String(value)
        case "f":
            guard let value = real(arg) else { return "\(arg)" }
            return fixed(value, precision ?? 6, locale)
        default:
            var text = "\(arg)"
            if let precision, text.count > precision {
                text = String(text.prefix(precision))
            }
            return conversion == "S" ? text.uppercased() : text
        }
    }

    private static func integer(_ arg: Any) -> Int? {
        switch arg {
        case let v as Int: v
        case let v as Int64: Int(v)
        case let v as Int32: Int(v)
        case let v as UInt: Int(v)
        default: nil
        }
    }

    private static func real(_ arg: Any) -> Double? {
        switch arg {
        case let v as Double: v
        case let v as Float: Double(v)
        case let v as Int: Double(v)
        default: nil
        }
    }

    private static func digit(_ c: Character) -> Int? {
        guard let ascii = c.asciiValue, ascii >= 48, ascii <= 57 else { return nil }
        return Int(ascii - 48)
    }

    /// `n` written with at least `width` digits (Kotlin `toString().padStart(width, '0')`).
    static func padded(_ n: Int, _ width: Int) -> String {
        let text = String(n)
        return String(repeating: "0", count: Swift.max(width - text.count, 0)) + text
    }

    /// Java `Math.floorMod` and `Math.floorDiv` on integers.
    static func floorMod(_ a: Int, _ b: Int) -> Int {
        ((a % b) + b) % b
    }

    static func floorDiv(_ a: Int, _ b: Int) -> Int {
        (a - floorMod(a, b)) / b
    }

    /// Kotlin `sign(x)` of a Double: -1, 0 or 1 (and the value itself for a zero or NaN).
    static func sign(_ x: Double) -> Double {
        if x > 0 { return 1.0 }
        if x < 0 { return -1.0 }
        return x
    }

    /// Kotlin `String.trimEnd()`: trailing Java whitespace removed.
    static func trimEnd(_ text: String) -> String {
        var scalars = Array(text.unicodeScalars)
        while let last = scalars.last, KotlinText.isWhitespace(last) {
            scalars.removeLast()
        }
        return String(String.UnicodeScalarView(scalars))
    }

    /// Kotlin `isNotBlank()`.
    static func isNotBlank(_ text: String) -> Bool {
        !text.unicodeScalars.allSatisfy(KotlinText.isWhitespace)
    }
}
