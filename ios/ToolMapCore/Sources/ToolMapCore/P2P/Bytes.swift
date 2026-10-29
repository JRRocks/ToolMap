// Port of core/src/main/kotlin/fr/toolmap/core/p2p/Bytes.kt, with the few JVM behaviours the codecs rely on
// (java.util.Base64, the hexadecimal forms of java.lang.Long, the UTF-8 encoder of String).

import Foundation

/// Thrown when bytes received from a peer or read from a code do not follow the format (Kotlin `WireException`).
public struct WireError: Error, Equatable, Sendable, CustomStringConvertible {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var description: String { message }
}

/// Big-endian writer for the binary formats of the pairing codes and of the messages.
final class ByteWriter {
    private var out: [UInt8] = []

    static let maxShortString = 255

    @discardableResult
    func u8(_ v: Int) -> ByteWriter {
        out.append(UInt8(truncatingIfNeeded: v))
        return self
    }

    @discardableResult
    func u16(_ v: Int) -> ByteWriter {
        u8(v >> 8)
        u8(v)
        return self
    }

    @discardableResult
    func u32(_ v: Int64) -> ByteWriter {
        u16(Int(truncatingIfNeeded: v >> 16))
        u16(Int(truncatingIfNeeded: v))
        return self
    }

    @discardableResult
    func i32(_ v: Int32) -> ByteWriter {
        u32(Int64(UInt32(bitPattern: v)))
    }

    @discardableResult
    func i16(_ v: Int) -> ByteWriter {
        u16(v)
    }

    @discardableResult
    func u64(_ v: Int64) -> ByteWriter {
        let bits = UInt64(bitPattern: v)
        u32(Int64(bits >> 32))
        u32(Int64(bits & 0xFFFF_FFFF))
        return self
    }

    @discardableResult
    func bytes(_ b: [UInt8]) -> ByteWriter {
        out.append(contentsOf: b)
        return self
    }

    /// One-byte length then UTF-8, cut on a character boundary when longer than `max` bytes.
    @discardableResult
    func str(_ s: String, _ max: Int = ByteWriter.maxShortString) -> ByteWriter {
        let b = ByteWriter.truncateUtf8(s, max)
        u8(b.count)
        bytes(b)
        return self
    }

    /// Two-byte length then UTF-8.
    @discardableResult
    func longStr(_ s: String, _ max: Int) -> ByteWriter {
        longStrUtf8(Array(s.utf8), max)
    }

    /// Two-byte length then the UTF-8 bytes `utf8` (already encoded), cut on a character boundary.
    @discardableResult
    func longStrUtf8(_ utf8: [UInt8], _ max: Int) -> ByteWriter {
        let b = ByteWriter.truncateUtf8Bytes(utf8, max)
        u16(b.count)
        bytes(b)
        return self
    }

    func toByteArray() -> [UInt8] { out }

    static func truncateUtf8(_ s: String, _ max: Int) -> [UInt8] {
        truncateUtf8Bytes(Array(s.utf8), max)
    }

    static func truncateUtf8Bytes(_ all: [UInt8], _ max: Int) -> [UInt8] {
        if all.count <= max { return all }
        var end = max
        // Do not cut a multi-byte sequence in the middle: back up to a lead byte.
        while end > 0, all[end] & 0xC0 == 0x80 {
            end -= 1
        }
        return Array(all[0 ..< end])
    }

    /// The UTF-8 bytes of Kotlin's `text.take(maxUnits)` as the JVM encodes them: `take` counts UTF-16 units and may
    /// split a surrogate pair, whose lone first half the JVM writes as `?`.
    static func javaUtf8Prefix(_ text: String, _ maxUnits: Int) -> [UInt8] {
        let units = Array(text.utf16.prefix(maxUnits))
        if let last = units.last, UTF16.isLeadSurrogate(last) {
            let kept = String(decoding: units.dropLast(), as: UTF16.self)
            return Array(kept.utf8) + [0x3F]
        }
        return Array(String(decoding: units, as: UTF16.self).utf8)
    }
}

struct ByteReader {
    private let data: [UInt8]
    private var pos: Int

    init(_ data: [UInt8], _ pos: Int = 0) {
        self.data = data
        self.pos = pos
    }

    var remaining: Int { data.count - pos }

    private func need(_ n: Int) throws {
        if n < 0 || remaining < n { throw WireError("truncated data") }
    }

    mutating func u8() throws -> Int {
        try need(1)
        let v = Int(data[pos])
        pos += 1
        return v
    }

    mutating func u16() throws -> Int {
        let high = try u8()
        let low = try u8()
        return (high << 8) | low
    }

    mutating func i16() throws -> Int {
        let v = try u16()
        return Int(Int16(truncatingIfNeeded: v))
    }

    mutating func u32() throws -> Int64 {
        let high = try u16()
        let low = try u16()
        return (Int64(high) << 16) | Int64(low)
    }

    mutating func i32() throws -> Int32 {
        let v = try u32()
        return Int32(truncatingIfNeeded: v)
    }

    mutating func u64() throws -> Int64 {
        let high = try u32()
        let low = try u32()
        return Int64(bitPattern: (UInt64(high) << 32) | UInt64(low))
    }

    mutating func bytes(_ n: Int) throws -> [UInt8] {
        try need(n)
        let r = Array(data[pos ..< pos + n])
        pos += n
        return r
    }

    mutating func rest() throws -> [UInt8] {
        try bytes(remaining)
    }

    mutating func str() throws -> String {
        let n = try u8()
        let b = try bytes(n)
        return String(decoding: b, as: UTF8.self)
    }

    mutating func longStr() throws -> String {
        let n = try u16()
        let b = try bytes(n)
        return String(decoding: b, as: UTF8.self)
    }
}

/// `java.util.Base64`: the basic and URL-safe alphabets, with the strict decoder of the JVM (no line breaks, padding
/// optional but well formed, nothing after it).
enum P2PBase64 {
    private static let basic = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".utf8)
    private static let urlSafe = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".utf8)

    static func encode(_ data: [UInt8], url: Bool = false, padding: Bool = true) -> String {
        let alphabet = url ? urlSafe : basic
        var out: [UInt8] = []
        out.reserveCapacity((data.count + 2) / 3 * 4)
        var i = 0
        while i + 2 < data.count {
            let n = (Int(data[i]) << 16) | (Int(data[i + 1]) << 8) | Int(data[i + 2])
            out.append(alphabet[(n >> 18) & 63])
            out.append(alphabet[(n >> 12) & 63])
            out.append(alphabet[(n >> 6) & 63])
            out.append(alphabet[n & 63])
            i += 3
        }
        let left = data.count - i
        if left == 1 {
            let n = Int(data[i]) << 16
            out.append(alphabet[(n >> 18) & 63])
            out.append(alphabet[(n >> 12) & 63])
            if padding { out.append(contentsOf: [0x3D, 0x3D]) }
        } else if left == 2 {
            let n = (Int(data[i]) << 16) | (Int(data[i + 1]) << 8)
            out.append(alphabet[(n >> 18) & 63])
            out.append(alphabet[(n >> 12) & 63])
            out.append(alphabet[(n >> 6) & 63])
            if padding { out.append(0x3D) }
        }
        return String(decoding: out, as: UTF8.self)
    }

    /// The bytes of `text`, or `nil` where the JVM decoder throws an `IllegalArgumentException`.
    static func decode(_ text: String, url: Bool = false) -> [UInt8]? {
        let src = Array(text.utf8)
        var out: [UInt8] = []
        out.reserveCapacity(src.count * 3 / 4)
        var bits = 0
        var shiftTo = 18
        var sp = 0
        while sp < src.count {
            let c = src[sp]
            sp += 1
            guard let b = value(c, url: url) else {
                guard c == 0x3D else { return nil }
                // "=" alone, "xx=" without its second "=", or "xx=y": a wrong ending unit.
                if shiftTo == 18 { return nil }
                if shiftTo == 6 {
                    if sp == src.count || src[sp] != 0x3D { return nil }
                    sp += 1
                }
                break
            }
            bits |= b << shiftTo
            shiftTo -= 6
            if shiftTo < 0 {
                out.append(UInt8(truncatingIfNeeded: bits >> 16))
                out.append(UInt8(truncatingIfNeeded: bits >> 8))
                out.append(UInt8(truncatingIfNeeded: bits))
                shiftTo = 18
                bits = 0
            }
        }
        if shiftTo == 6 {
            out.append(UInt8(truncatingIfNeeded: bits >> 16))
        } else if shiftTo == 0 {
            out.append(UInt8(truncatingIfNeeded: bits >> 16))
            out.append(UInt8(truncatingIfNeeded: bits >> 8))
        } else if shiftTo == 12 {
            // A dangling single character.
            return nil
        }
        // Anything after the padding is refused.
        if sp < src.count { return nil }
        return out
    }

    private static func value(_ c: UInt8, url: Bool) -> Int? {
        switch c {
        case 0x41 ... 0x5A: return Int(c) - 0x41
        case 0x61 ... 0x7A: return Int(c) - 0x61 + 26
        case 0x30 ... 0x39: return Int(c) - 0x30 + 52
        case 0x2B: return url ? nil : 62
        case 0x2F: return url ? nil : 63
        case 0x2D: return url ? 62 : nil
        case 0x5F: return url ? 63 : nil
        default: return nil
        }
    }
}

/// Hexadecimal forms used by the protocol and the saved state.
enum P2PHex {
    private static let digits = Array("0123456789abcdef".utf8)

    /// Two lowercase digits per byte (Kotlin `joinToString("") { "%02x".format(...) }`).
    static func encode(_ bytes: [UInt8]) -> String {
        var out: [UInt8] = []
        out.reserveCapacity(bytes.count * 2)
        for b in bytes {
            out.append(digits[Int(b >> 4)])
            out.append(digits[Int(b & 0x0F)])
        }
        return String(decoding: out, as: UTF8.self)
    }

    /// Two uppercase digits per byte.
    static func encodeUpper(_ b: UInt8) -> String {
        let upper = Array("0123456789ABCDEF".utf8)
        return String(decoding: [upper[Int(b >> 4)], upper[Int(b & 0x0F)]], as: UTF8.self)
    }

    /// `java.lang.Long.toHexString`: the unsigned value in lowercase, without leading zeros.
    static func long(_ value: Int64) -> String {
        String(UInt64(bitPattern: value), radix: 16)
    }

    /// `java.lang.Long.parseUnsignedLong(text, 16)`, `nil` where it throws.
    static func parseUnsignedLong(_ text: String) -> Int64? {
        if text.isEmpty || text.hasPrefix("-") { return nil }
        var body = Substring(text)
        if body.hasPrefix("+") { body = body.dropFirst() }
        if body.isEmpty || !body.utf8.allSatisfy(isHexDigit) { return nil }
        guard let value = UInt64(body, radix: 16) else { return nil }
        return Int64(bitPattern: value)
    }

    /// Kotlin's `require(s.length % 2 == 0)` then `substring(...).toInt(16).toByte()` per pair; `nil` where it throws.
    static func decode(_ text: String) -> [UInt8]? {
        let units = Array(text.utf16)
        if units.count % 2 != 0 { return nil }
        var out: [UInt8] = []
        out.reserveCapacity(units.count / 2)
        var i = 0
        while i < units.count {
            let pair = String(decoding: units[i ..< i + 2], as: UTF16.self)
            // toInt(16) accepts a sign: "+f" is 15, "-1" is -1 (0xFF once a byte).
            guard let v = Int(pair, radix: 16) else { return nil }
            out.append(UInt8(truncatingIfNeeded: v))
            i += 2
        }
        return out
    }

    private static func isHexDigit(_ c: UInt8) -> Bool {
        (c >= 0x30 && c <= 0x39) || (c >= 0x41 && c <= 0x46) || (c >= 0x61 && c <= 0x66)
    }
}
