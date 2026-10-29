// The JSON of the sharing (saved state, Firebase answers and stream events), read the way kotlinx.serialization reads
// it on Android: object keys stay in document order (Firebase's control frames are handled in that order), a repeated
// key keeps its first place with its last value, numbers keep their text, and the few liberties of the kotlinx lexer
// (unquoted tokens such as `FALSE`, quoted numbers) are accepted where the Android app accepts them.

import Foundation

/// A JSON object whose keys keep the order of the document (Kotlin `LinkedHashMap`).
struct P2PJsonObject: Equatable, Sendable {
    private(set) var keys: [String] = []
    private var values: [String: P2PJson] = [:]

    init() {}

    subscript(key: String) -> P2PJson? { values[key] }

    /// Sets `key`; an existing key keeps its place, as in a `LinkedHashMap`.
    mutating func set(_ key: String, _ value: P2PJson) {
        if values[key] == nil { keys.append(key) }
        values[key] = value
    }

    var entries: [(String, P2PJson)] {
        keys.compactMap { key in values[key].map { (key, $0) } }
    }

    var isEmpty: Bool { keys.isEmpty }
}

/// A JSON value: `literal` is an unquoted token (a number, `true`, `false` or anything the lexer of kotlinx takes as a
/// literal), kept as written.
enum P2PJson: Equatable, Sendable {
    case null
    case literal(String)
    case string(String)
    case array([P2PJson])
    case object(P2PJsonObject)

    struct ParseError: Error, Equatable, Sendable {
        let message: String
    }

    var objectValue: P2PJsonObject? {
        if case let .object(o) = self { return o }
        return nil
    }

    /// The text of a primitive (kotlinx `JsonPrimitive.content`); `nil` for `null`, an array or an object.
    var content: String? {
        switch self {
        case let .literal(text), let .string(text): return text
        default: return nil
        }
    }

    /// The text of a quoted string only (kotlinx `takeIf { it.isString }?.content`).
    var stringValue: String? {
        if case let .string(text) = self { return text }
        return nil
    }

    /// kotlinx `JsonPrimitive.longOrNull` on a primitive.
    var longValue: Int64? {
        guard let text = content else { return nil }
        return P2PJsonNumber.parse(text, mode: .content)
    }

    static func parse(_ text: String) throws -> P2PJson {
        var parser = P2PJsonParser(Array(text.unicodeScalars))
        return try parser.document()
    }
}

/// The numeric literals of kotlinx (`AbstractJsonLexer.consumeNumericLiteral`): an optional minus, decimal digits, an
/// optional exponent that must leave a whole number; no point, no plus sign.
enum P2PJsonNumber {
    enum Mode {
        /// A field value: the whole token (or the whole quoted text) must be the number.
        case field
        /// `JsonPrimitive.longOrNull`: spaces are allowed around the number.
        case content
    }

    static func parse(_ text: String, mode: Mode) -> Int64? {
        let chars = Array(text.unicodeScalars)
        var current = 0
        if mode == .content {
            while current < chars.count, isWhitespace(chars[current]) {
                current += 1
            }
        }
        var accumulator: Int64 = 0
        var exponentAccumulator: Int64 = 0
        var isNegative = false
        var isExponentPositive = false
        var hasExponent = false
        let start = current
        while current < chars.count {
            let ch = chars[current]
            if (ch == "e" || ch == "E"), !hasExponent {
                if current == start { return nil }
                isExponentPositive = true
                hasExponent = true
                current += 1
                continue
            }
            if ch == "-", hasExponent {
                if current == start { return nil }
                isExponentPositive = false
                current += 1
                continue
            }
            if ch == "+", hasExponent {
                if current == start { return nil }
                isExponentPositive = true
                current += 1
                continue
            }
            if ch == "-" {
                if current != start { return nil }
                isNegative = true
                current += 1
                continue
            }
            if !isOther(ch) { break }
            current += 1
            guard ch.value >= 0x30, ch.value <= 0x39 else { return nil }
            let digit = Int64(ch.value - 0x30)
            if hasExponent {
                exponentAccumulator = exponentAccumulator &* 10 &+ digit
                continue
            }
            let (times, overflow1) = accumulator.multipliedReportingOverflow(by: 10)
            let (next, overflow2) = times.subtractingReportingOverflow(digit)
            if overflow1 || overflow2 || next > 0 { return nil }
            accumulator = next
        }
        if start == current || (isNegative && start == current - 1) { return nil }
        // What follows the number: nothing for a field, spaces only for a primitive's content.
        while current < chars.count {
            if mode == .content, isWhitespace(chars[current]) {
                current += 1
            } else {
                return nil
            }
        }
        if hasExponent {
            let power = isExponentPositive
                ? pow(10.0, Double(exponentAccumulator))
                : pow(10.0, -Double(exponentAccumulator))
            let value = Double(accumulator) * power
            if value > 9.223372036854775807e18 || value < -9.223372036854775808e18 { return nil }
            if value.rounded(.down) != value { return nil }
            accumulator = Int64(kotlinToLong(value))
        }
        if isNegative { return accumulator }
        if accumulator == Int64.min { return nil }
        return -accumulator
    }

    /// The characters kotlinx reads inside a literal (its token class `TC_OTHER`).
    static func isOther(_ c: Unicode.Scalar) -> Bool {
        if c.value <= 0x20 { return false }
        switch c {
        case ",", ":", "{", "}", "[", "]", "\"", "\\": return false
        default: return true
        }
    }

    static func isWhitespace(_ c: Unicode.Scalar) -> Bool {
        c == " " || c == "\t" || c == "\n" || c == "\r"
    }
}

/// kotlinx's boolean literals: `true` or `false` in any ASCII case, quoted or not.
enum P2PJsonBool {
    static func parse(_ text: String) -> Bool? {
        let lower = String(String.UnicodeScalarView(text.unicodeScalars.map { scalar in
            scalar.value >= 0x41 && scalar.value <= 0x5A ? Unicode.Scalar(scalar.value + 0x20)! : scalar
        }))
        if lower == "true" { return true }
        if lower == "false" { return false }
        return nil
    }
}

private struct P2PJsonParser {
    private let chars: [Unicode.Scalar]
    private var pos = 0
    private var depth = 0
    private static let maxDepth = 512

    init(_ chars: [Unicode.Scalar]) {
        self.chars = chars
    }

    mutating func document() throws -> P2PJson {
        let v = try value()
        skipWhitespace()
        if pos != chars.count { throw P2PJson.ParseError(message: "trailing data") }
        return v
    }

    private mutating func skipWhitespace() {
        while pos < chars.count, P2PJsonNumber.isWhitespace(chars[pos]) {
            pos += 1
        }
    }

    private mutating func value() throws -> P2PJson {
        skipWhitespace()
        guard pos < chars.count else { throw P2PJson.ParseError(message: "EOF") }
        let c = chars[pos]
        switch c {
        case "{":
            return try object()
        case "[":
            return try array()
        case "\"":
            let text = try string()
            return .string(text)
        default:
            let start = pos
            while pos < chars.count, P2PJsonNumber.isOther(chars[pos]) {
                pos += 1
            }
            if pos == start { throw P2PJson.ParseError(message: "unexpected character") }
            let token = String(String.UnicodeScalarView(chars[start ..< pos]))
            return token == "null" ? .null : .literal(token)
        }
    }

    private mutating func enter() throws {
        depth += 1
        if depth > P2PJsonParser.maxDepth { throw P2PJson.ParseError(message: "too deep") }
    }

    private mutating func object() throws -> P2PJson {
        try enter()
        pos += 1
        var result = P2PJsonObject()
        skipWhitespace()
        if pos < chars.count, chars[pos] == "}" {
            pos += 1
            depth -= 1
            return .object(result)
        }
        while true {
            skipWhitespace()
            guard pos < chars.count, chars[pos] == "\"" else { throw P2PJson.ParseError(message: "expected a key") }
            let key = try string()
            skipWhitespace()
            guard pos < chars.count, chars[pos] == ":" else { throw P2PJson.ParseError(message: "expected ':'") }
            pos += 1
            let v = try value()
            result.set(key, v)
            skipWhitespace()
            guard pos < chars.count else { throw P2PJson.ParseError(message: "EOF") }
            if chars[pos] == "," {
                pos += 1
                continue
            }
            if chars[pos] == "}" {
                pos += 1
                depth -= 1
                return .object(result)
            }
            throw P2PJson.ParseError(message: "expected ',' or '}'")
        }
    }

    private mutating func array() throws -> P2PJson {
        try enter()
        pos += 1
        var result: [P2PJson] = []
        skipWhitespace()
        if pos < chars.count, chars[pos] == "]" {
            pos += 1
            depth -= 1
            return .array(result)
        }
        while true {
            let element = try value()
            result.append(element)
            skipWhitespace()
            guard pos < chars.count else { throw P2PJson.ParseError(message: "EOF") }
            if chars[pos] == "," {
                pos += 1
                continue
            }
            if chars[pos] == "]" {
                pos += 1
                depth -= 1
                return .array(result)
            }
            throw P2PJson.ParseError(message: "expected ',' or ']'")
        }
    }

    /// A quoted string, the opening quote at `pos`; escapes as in JSON (a lone surrogate becomes U+FFFD).
    private mutating func string() throws -> String {
        pos += 1
        var units: [UInt16] = []
        while true {
            guard pos < chars.count else { throw P2PJson.ParseError(message: "unterminated string") }
            let c = chars[pos]
            pos += 1
            if c == "\"" { break }
            if c != "\\" {
                units.append(contentsOf: Array(String(Character(c)).utf16))
                continue
            }
            guard pos < chars.count else { throw P2PJson.ParseError(message: "unterminated string") }
            let e = chars[pos]
            pos += 1
            switch e {
            case "\"": units.append(0x22)
            case "\\": units.append(0x5C)
            case "/": units.append(0x2F)
            case "b": units.append(0x08)
            case "f": units.append(0x0C)
            case "n": units.append(0x0A)
            case "r": units.append(0x0D)
            case "t": units.append(0x09)
            case "u":
                guard pos + 4 <= chars.count else { throw P2PJson.ParseError(message: "bad escape") }
                var v: UInt16 = 0
                for i in 0 ..< 4 {
                    guard let d = hexValue(chars[pos + i]) else { throw P2PJson.ParseError(message: "bad escape") }
                    v = v << 4 | UInt16(d)
                }
                pos += 4
                units.append(v)
            default:
                throw P2PJson.ParseError(message: "bad escape")
            }
        }
        return String(decoding: units, as: UTF16.self)
    }

    private func hexValue(_ c: Unicode.Scalar) -> Int? {
        switch c.value {
        case 0x30 ... 0x39: return Int(c.value - 0x30)
        case 0x41 ... 0x46: return Int(c.value - 0x41 + 10)
        case 0x61 ... 0x66: return Int(c.value - 0x61 + 10)
        default: return nil
        }
    }
}

/// Writes JSON text as kotlinx.serialization does: strings escaped with `\"`, `\\`, `\b`, `\f`, `\n`, `\r`, `\t`
/// and `\u00xx` (lowercase) for the other control characters; nothing else escaped.
enum P2PJsonText {
    static func quote(_ value: String) -> String {
        var out = "\""
        for c in value.unicodeScalars {
            switch c {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if c.value < 0x20 {
                    let hex = String(c.value, radix: 16)
                    out += "\\u" + String(repeating: "0", count: 4 - hex.count) + hex
                } else {
                    out.unicodeScalars.append(c)
                }
            }
        }
        return out + "\""
    }
}
