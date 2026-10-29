// The JSON tree of kotlinx.serialization (`Json.parseToJsonElement`) as GeoJsonReader.kt reads it: a primitive keeps
// the text it was written with (`2000.5`, `3`, `true`), so that `content` and `doubleOrNull` behave as in Kotlin.

/// A JSON value: kotlinx's `JsonObject`, `JsonArray`, `JsonPrimitive` (a string or an unquoted literal) or `JsonNull`.
indirect enum TrackJsonNode: Equatable, Sendable {
    case object([String: TrackJsonNode])
    case array([TrackJsonNode])
    case primitive(String, isString: Bool)
    case null

    subscript(key: String) -> TrackJsonNode? {
        if case let .object(members) = self { return members[key] }
        return nil
    }

    var members: [String: TrackJsonNode]? {
        if case let .object(members) = self { return members }
        return nil
    }

    var elements: [TrackJsonNode]? {
        if case let .array(elements) = self { return elements }
        return nil
    }

    /// kotlinx `JsonPrimitive.contentOrNull`: the text of a string or a literal; nil for null and non-primitives.
    var content: String? {
        if case let .primitive(text, _) = self { return text }
        return nil
    }

    /// kotlinx `JsonPrimitive.doubleOrNull`: `content.toDoubleOrNull()`, also for a quoted number.
    var double: Double? {
        content.flatMap { kotlinDouble($0) }
    }
}

struct TrackJsonError: Error, Sendable {
    let message: String
}

/// Strict JSON as kotlinx reads it by default: quoted keys, no trailing comma, nothing after the value. An unquoted
/// value is kept as a literal without checking that it is a number (kotlinx does the same); `null` is `JsonNull`.
/// Nesting is limited to 128 levels (kotlinx has no limit) so that the recursion fits any thread's stack.
enum TrackJsonParser {
    static let maxDepth = 128

    static func parse(_ text: String) throws -> TrackJsonNode {
        var reader = Reader(Array(text.utf8))
        reader.skipSpaces()
        let value = try reader.value(depth: 0)
        reader.skipSpaces()
        if reader.index < reader.bytes.count {
            throw TrackJsonError(message: "Expected EOF after parsing, but had more at offset \(reader.index)")
        }
        return value
    }

    private struct Reader {
        let bytes: [UInt8]
        var index = 0

        init(_ bytes: [UInt8]) {
            self.bytes = bytes
        }

        mutating func skipSpaces() {
            while index < bytes.count {
                let b = bytes[index]
                if b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D {
                    index += 1
                } else {
                    return
                }
            }
        }

        private func fail(_ message: String) -> TrackJsonError {
            TrackJsonError(message: "\(message) at offset \(index)")
        }

        mutating func value(depth: Int) throws -> TrackJsonNode {
            if depth > TrackJsonParser.maxDepth { throw fail("JSON nested too deeply") }
            guard index < bytes.count else { throw fail("Unexpected end of input") }
            switch bytes[index] {
            case UInt8(ascii: "{"):
                return try object(depth: depth)
            case UInt8(ascii: "["):
                return try array(depth: depth)
            case UInt8(ascii: "\""):
                let text = try string()
                return .primitive(text, isString: true)
            default:
                let literal = try unquoted()
                return literal == "null" ? .null : .primitive(literal, isString: false)
            }
        }

        private mutating func object(depth: Int) throws -> TrackJsonNode {
            index += 1
            var members: [String: TrackJsonNode] = [:]
            skipSpaces()
            if index < bytes.count, bytes[index] == UInt8(ascii: "}") {
                index += 1
                return .object(members)
            }
            while true {
                skipSpaces()
                guard index < bytes.count, bytes[index] == UInt8(ascii: "\"") else {
                    throw fail("Expected quotation mark '\"'")
                }
                let key = try string()
                skipSpaces()
                guard index < bytes.count, bytes[index] == UInt8(ascii: ":") else { throw fail("Expected ':'") }
                index += 1
                skipSpaces()
                let member = try value(depth: depth + 1)
                members[key] = member
                skipSpaces()
                guard index < bytes.count else { throw fail("Unexpected end of input in an object") }
                if bytes[index] == UInt8(ascii: ",") {
                    index += 1
                    continue
                }
                if bytes[index] == UInt8(ascii: "}") {
                    index += 1
                    return .object(members)
                }
                throw fail("Expected ',' or '}'")
            }
        }

        private mutating func array(depth: Int) throws -> TrackJsonNode {
            index += 1
            var elements: [TrackJsonNode] = []
            skipSpaces()
            if index < bytes.count, bytes[index] == UInt8(ascii: "]") {
                index += 1
                return .array(elements)
            }
            while true {
                skipSpaces()
                let element = try value(depth: depth + 1)
                elements.append(element)
                skipSpaces()
                guard index < bytes.count else { throw fail("Unexpected end of input in an array") }
                if bytes[index] == UInt8(ascii: ",") {
                    index += 1
                    continue
                }
                if bytes[index] == UInt8(ascii: "]") {
                    index += 1
                    return .array(elements)
                }
                throw fail("Expected ',' or ']'")
            }
        }

        /// A token up to the next space or structural character, as kotlinx's `consumeStringLenient`.
        private mutating func unquoted() throws -> String {
            let start = index
            while index < bytes.count {
                let b = bytes[index]
                if b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D || b == UInt8(ascii: ",") ||
                    b == UInt8(ascii: ":") || b == UInt8(ascii: "{") || b == UInt8(ascii: "}") ||
                    b == UInt8(ascii: "[") || b == UInt8(ascii: "]") || b == UInt8(ascii: "\"") {
                    break
                }
                index += 1
            }
            if index == start { throw fail("Unexpected character") }
            return String(decoding: bytes[start ..< index], as: UTF8.self)
        }

        private mutating func hexValue(_ count: Int) throws -> UInt32 {
            guard index + count <= bytes.count else { throw fail("Unexpected end of input in an escape") }
            var result: UInt32 = 0
            for _ in 0 ..< count {
                let b = bytes[index]
                let digit: UInt32
                switch b {
                case UInt8(ascii: "0") ... UInt8(ascii: "9"): digit = UInt32(b - UInt8(ascii: "0"))
                case UInt8(ascii: "a") ... UInt8(ascii: "f"): digit = UInt32(b - UInt8(ascii: "a") + 10)
                case UInt8(ascii: "A") ... UInt8(ascii: "F"): digit = UInt32(b - UInt8(ascii: "A") + 10)
                default: throw fail("Invalid escape")
                }
                result = result * 16 + digit
                index += 1
            }
            return result
        }

        /// A quoted string, `index` on its opening quote.
        mutating func string() throws -> String {
            index += 1
            var out = ""
            var runStart = index
            while index < bytes.count {
                let b = bytes[index]
                if b == UInt8(ascii: "\"") {
                    out += String(decoding: bytes[runStart ..< index], as: UTF8.self)
                    index += 1
                    return out
                }
                if b != UInt8(ascii: "\\") {
                    index += 1
                    continue
                }
                out += String(decoding: bytes[runStart ..< index], as: UTF8.self)
                index += 1
                guard index < bytes.count else { break }
                let escape = bytes[index]
                index += 1
                switch escape {
                case UInt8(ascii: "\""): out += "\""
                case UInt8(ascii: "\\"): out += "\\"
                case UInt8(ascii: "/"): out += "/"
                case UInt8(ascii: "b"): out += "\u{8}"
                case UInt8(ascii: "f"): out += "\u{C}"
                case UInt8(ascii: "n"): out += "\n"
                case UInt8(ascii: "r"): out += "\r"
                case UInt8(ascii: "t"): out += "\t"
                case UInt8(ascii: "u"):
                    let unit = try hexValue(4)
                    let decoded = try scalar(unit)
                    out.unicodeScalars.append(decoded)
                default:
                    throw fail("Invalid escaped char")
                }
                runStart = index
            }
            throw fail("Unexpected end of input in a string")
        }

        /// A `\u` escape, a surrogate pair read as one character; a lone surrogate becomes U+FFFD.
        private mutating func scalar(_ unit: UInt32) throws -> Unicode.Scalar {
            let replacement: Unicode.Scalar = "\u{FFFD}"
            if unit >= 0xD800, unit <= 0xDBFF {
                if index + 6 <= bytes.count, bytes[index] == UInt8(ascii: "\\"), bytes[index + 1] == UInt8(ascii: "u") {
                    let saved = index
                    index += 2
                    let low = try hexValue(4)
                    if low >= 0xDC00, low <= 0xDFFF {
                        let combined = 0x10000 + ((unit - 0xD800) << 10) + (low - 0xDC00)
                        return Unicode.Scalar(combined) ?? replacement
                    }
                    index = saved
                }
                return replacement
            }
            return Unicode.Scalar(unit) ?? replacement
        }
    }
}
