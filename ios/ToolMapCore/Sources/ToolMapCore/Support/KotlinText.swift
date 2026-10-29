import Foundation

// Text conversions that behave like the Kotlin functions `:core` relies on (trim, toIntOrNull, toDoubleOrNull,
// split...), so that a file read on Android and on iPhone gives the same values.

enum KotlinText {
    /// Kotlin `Char.isWhitespace()`: Java whitespace (tab, line feed, vertical tab, form feed, carriage return,
    /// file to unit separators) or a Unicode space, line or paragraph separator.
    static func isWhitespace(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x09 ... 0x0D, 0x1C ... 0x1F:
            return true
        default:
            switch scalar.properties.generalCategory {
            case .spaceSeparator, .lineSeparator, .paragraphSeparator: return true
            default: return false
            }
        }
    }

    /// The characters of the regular expression `\s` on the JVM: space, tab, line feed, vertical tab, form feed,
    /// carriage return.
    static func isRegexSpace(_ scalar: Unicode.Scalar) -> Bool {
        scalar == " " || (0x09 ... 0x0D).contains(scalar.value)
    }

    static func isAsciiDigit(_ byte: UInt8) -> Bool {
        byte >= UInt8(ascii: "0") && byte <= UInt8(ascii: "9")
    }
}

/// Kotlin `String.trim()`.
func kotlinTrim(_ text: String) -> String {
    let scalars = text.unicodeScalars
    guard let first = scalars.firstIndex(where: { !KotlinText.isWhitespace($0) }),
          let last = scalars.lastIndex(where: { !KotlinText.isWhitespace($0) })
    else { return "" }
    return String(String.UnicodeScalarView(scalars[first ... last]))
}

/// Kotlin `String.toIntOrNull()`: an optional sign and ASCII digits, within the 32-bit range.
func kotlinInt(_ text: String) -> Int? {
    Int32(text).map(Int.init)
}

/// Kotlin `String.toDoubleOrNull()` on the JVM (`java.lang.Double.parseDouble` grammar, hexadecimal form left out):
/// surrounding control characters and spaces are ignored, `NaN` and `Infinity` are read, a final `f`, `F`, `d` or
/// `D` is allowed after digits.
func kotlinDouble(_ text: String) -> Double? {
    let scalars = Array(text.unicodeScalars)
    var start = 0
    var end = scalars.count
    while start < end, scalars[start].value <= 0x20 {
        start += 1
    }
    while end > start, scalars[end - 1].value <= 0x20 {
        end -= 1
    }
    var body = String(String.UnicodeScalarView(scalars[start ..< end]))
    var sign = ""
    if let first = body.first, first == "+" || first == "-" {
        sign = String(first)
        body.removeFirst()
    }
    if body == "NaN" { return Double.nan }
    if body == "Infinity" { return sign == "-" ? -Double.infinity : Double.infinity }
    if let last = body.last, "fFdD".contains(last) {
        body.removeLast()
    }
    guard isJavaDecimal(body) else { return nil }
    return Double(sign + body)
}

/// Digits with an optional point (at least one digit in all), then an optional exponent.
private func isJavaDecimal(_ text: String) -> Bool {
    let bytes = Array(text.utf8)
    var i = 0
    var digits = 0
    while i < bytes.count, KotlinText.isAsciiDigit(bytes[i]) {
        i += 1
        digits += 1
    }
    if i < bytes.count, bytes[i] == UInt8(ascii: ".") {
        i += 1
        while i < bytes.count, KotlinText.isAsciiDigit(bytes[i]) {
            i += 1
            digits += 1
        }
    }
    guard digits > 0 else { return false }
    if i < bytes.count, bytes[i] == UInt8(ascii: "e") || bytes[i] == UInt8(ascii: "E") {
        i += 1
        if i < bytes.count, bytes[i] == UInt8(ascii: "+") || bytes[i] == UInt8(ascii: "-") {
            i += 1
        }
        var exponentDigits = 0
        while i < bytes.count, KotlinText.isAsciiDigit(bytes[i]) {
            i += 1
            exponentDigits += 1
        }
        guard exponentDigits > 0 else { return false }
    }
    return i == bytes.count
}

/// Kotlin `Double.toInt()`: towards zero, saturated to the 32-bit range, 0 for NaN.
func kotlinToInt(_ value: Double) -> Int {
    if value.isNaN { return 0 }
    if value >= Double(Int32.max) { return Int(Int32.max) }
    if value <= Double(Int32.min) { return Int(Int32.min) }
    return Int(value)
}

/// Kotlin `Double.toLong()`: towards zero, saturated to the 64-bit range, 0 for NaN.
func kotlinToLong(_ value: Double) -> Int {
    if value.isNaN { return 0 }
    if value >= 9.223372036854775807e18 { return Int.max }
    if value <= -9.223372036854775808e18 { return Int.min }
    return Int(value)
}

/// Kotlin `String.split(vararg delimiters: Char)`: every part is kept, empty ones included.
func kotlinSplit(_ text: String, _ separators: Character...) -> [String] {
    text.split(omittingEmptySubsequences: false, whereSeparator: { separators.contains($0) }).map(String.init)
}

/// Kotlin `text.split(Regex("\\s+")).filter { it.isNotEmpty() }`.
func splitOnSpaces(_ text: String) -> [String] {
    text.split(whereSeparator: { $0.unicodeScalars.allSatisfy(KotlinText.isRegexSpace) }).map(String.init)
}

/// Kotlin `equals(other, ignoreCase = true)`.
func equalsIgnoringCase(_ text: String, _ other: String) -> Bool {
    text.lowercased() == other.lowercased()
}

/// Kotlin `contains(other, ignoreCase = true)`.
func containsIgnoringCase(_ text: String, _ other: String) -> Bool {
    text.range(of: other, options: .caseInsensitive) != nil
}

extension String {
    /// Kotlin `ifEmpty { null }`.
    var nilIfEmpty: String? { isEmpty ? nil : self }

    /// Kotlin `substringBefore(delimiter)`: the whole text when the delimiter is missing.
    func substringBefore(_ delimiter: Character) -> String {
        guard let index = firstIndex(of: delimiter) else { return self }
        return String(self[..<index])
    }

    /// Kotlin `substringAfter(delimiter, missing)`.
    func substringAfter(_ delimiter: Character, missing: String) -> String {
        guard let index = firstIndex(of: delimiter) else { return missing }
        return String(self[self.index(after: index)...])
    }
}
