// Port of core/src/main/kotlin/fr/toolmap/core/track/ExtensionStyle.kt.

import Foundation

/// Line colour that other apps write in GPX extensions: Garmin `gpxx:DisplayColor` (a colour name) and the `color`
/// element of OsmAnd and others (`#RRGGBB` or `#AARRGGBB`).
enum ExtensionStyle {
    private static let garmin: [String: UInt32] = [
        "black": 0xFF00_0000,
        "darkred": 0xFF8B_0000,
        "darkgreen": 0xFF00_6400,
        "darkyellow": 0xFF80_8000,
        "darkblue": 0xFF00_008B,
        "darkmagenta": 0xFF8B_008B,
        "darkcyan": 0xFF00_8B8B,
        "lightgray": 0xFFD3_D3D3,
        "darkgray": 0xFFA9_A9A9,
        "red": 0xFFFF_0000,
        "green": 0xFF00_FF00,
        "yellow": 0xFFFF_FF00,
        "blue": 0xFF00_00FF,
        "magenta": 0xFFFF_00FF,
        "cyan": 0xFF00_FFFF,
        "white": 0xFFFF_FFFF,
    ]

    /// Kotlin `<(?:[\w.-]+:)?(?:DisplayColor|color)\b[^>]*>\s*([^<]+?)\s*</`, case ignored (Java's `\w` and `\s` are
    /// ASCII only, hence the explicit classes).
    private static let colorElement = try! NSRegularExpression(
        pattern: #"<(?:[A-Za-z0-9_.-]+:)?(?:DisplayColor|color)\b[^>]*>[ \t\n\u000B\f\r]*([^<]+?)[ \t\n\u000B\f\r]*</"#,
        options: [.caseInsensitive]
    )

    static func fromExtras(_ extras: [RawXml]) -> PathStyle? {
        for raw in extras {
            if raw.localName != "extensions" { continue }
            let xml = raw.xml
            let whole = NSRange(xml.startIndex ..< xml.endIndex, in: xml)
            guard let match = colorElement.firstMatch(in: xml, options: [], range: whole),
                  let range = Range(match.range(at: 1), in: xml)
            else { continue }
            guard let color = parseColor(String(xml[range])) else { continue }
            return PathStyle(colorArgb: color)
        }
        return nil
    }

    static func parseColor(_ value: String) -> Int32? {
        let v = kotlinTrim(value)
        if let named = garmin[v.lowercased()] { return Int32(bitPattern: named) }
        let hex = v.hasPrefix("#") ? String(v.dropFirst()) : v
        let length = hex.utf16.count
        if length != 6 && length != 8 { return nil }
        // Kotlin `toLongOrNull(16)`: an optional sign, then hexadecimal digits.
        guard let n = Int64(hex, radix: 16) else { return nil }
        return length == 6
            ? Int32(truncatingIfNeeded: Int64(0xFF00_0000) | n)
            : Int32(truncatingIfNeeded: n)
    }
}
