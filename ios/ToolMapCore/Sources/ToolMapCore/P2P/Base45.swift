// Port of core/src/main/kotlin/fr/toolmap/core/p2p/Base45.kt.

/// Base45 (RFC 9285). Its 45 characters are exactly the alphanumeric mode of a QR code, which stores 5.5 bits per
/// character, so a payload costs about 1.5 characters per byte instead of the 8 bits of the byte mode.
public enum Base45 {
    public static let alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
    private static let chars = Array(alphabet.utf8)
    private static let base = 45

    public static func encode(_ data: [UInt8]) -> String {
        var out: [UInt8] = []
        out.reserveCapacity(data.count * 3 / 2 + 2)
        var i = 0
        while i + 1 < data.count {
            let n = (Int(data[i]) << 8) | Int(data[i + 1])
            out.append(chars[n % base])
            out.append(chars[(n / base) % base])
            out.append(chars[n / (base * base)])
            i += 2
        }
        if i < data.count {
            let n = Int(data[i])
            out.append(chars[n % base])
            out.append(chars[n / base])
        }
        return String(decoding: out, as: UTF8.self)
    }

    /// Decodes `text`; any character outside the alphabet or an impossible group is a `WireError`.
    public static func decode(_ text: String) throws -> [UInt8] {
        // Kotlin counts UTF-16 units; any unit outside the alphabet is refused anyway.
        let units = Array(text.utf16)
        if units.count % 3 == 1 { throw WireError("bad base45 length") }
        var out: [UInt8] = []
        out.reserveCapacity(units.count * 2 / 3)
        var i = 0
        while i < units.count {
            let c = try value(units[i])
            let d = try value(units[i + 1])
            if i + 2 < units.count {
                let e = try value(units[i + 2])
                let n = c + d * base + e * base * base
                if n > 0xFFFF { throw WireError("bad base45 group") }
                out.append(UInt8(n >> 8))
                out.append(UInt8(n & 0xFF))
                i += 3
            } else {
                let n = c + d * base
                if n > 0xFF { throw WireError("bad base45 group") }
                out.append(UInt8(n))
                i += 2
            }
        }
        return out
    }

    private static func value(_ unit: UInt16) throws -> Int {
        guard unit < 0x80, let v = chars.firstIndex(of: UInt8(unit)) else { throw WireError("bad base45 character") }
        return v
    }
}
