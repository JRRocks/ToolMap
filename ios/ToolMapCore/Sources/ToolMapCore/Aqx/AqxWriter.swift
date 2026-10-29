// Port of core/src/main/kotlin/fr/toolmap/core/aqx/AqxWriter.kt.

import Foundation

/// Writes sources as an `.aqx` document that `AqxParser` (and AlpineQuest) can read back: the "level" form
/// `<zoom-levels z="...">` with its settings as children. Only what the model keeps is written: a source flagged
/// unsupported (custom projection) loses its projection elements.
///
/// The parameters of a source are written sorted by name (Kotlin keeps the file order of its `LinkedHashMap`; a Swift
/// dictionary has no order).
public enum AqxWriter {
    public static let version = 11

    /// `redactedParams`: parameter names written with an empty value (API keys, when sharing a file).
    public static func write(
        _ sources: [TileSource],
        name: String? = nil,
        description: String? = nil,
        redactedParams: Set<String> = []
    ) -> String {
        var out = ""
        line(&out, "<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        line(&out, "<aqx version=\"\(version)\">")
        if let name {
            line(&out, "  <name>\(escape(name))</name>")
        }
        if let description {
            line(&out, "  <description>\(escape(description))</description>")
        }
        for source in sources {
            line(&out, "")
            writeSource(&out, source, redactedParams)
        }
        line(&out, "</aqx>")
        return out
    }

    private static func writeSource(_ out: inout String, _ source: TileSource, _ redacted: Set<String>) {
        var attributes = " id=\"\(escape(source.id))\""
        if let type = source.type {
            attributes += " type=\"\(escape(type))\""
        }
        if source.isLayer {
            attributes += " layer=\"true\""
        }
        if source.opacity != 100 {
            attributes += " opacity=\"\(source.opacity)\""
        }
        line(&out, "  <source\(attributes)>")
        line(&out, "    <name>\(escape(source.name))</name>")
        if let copyright = source.copyright {
            line(&out, "    <copyright>\(escape(copyright))</copyright>")
        }
        if let dataSource = source.dataSource {
            line(&out, "    <data-source>\(escape(dataSource))</data-source>")
        }
        if let region = source.region {
            line(&out, "    <region>\(escape(region))</region>")
        }
        if !source.outline.isEmpty {
            let text = source.outline.map { point -> String in
                number(point.lon) + "," + number(point.lat)
            }.joined(separator: " ")
            line(&out, "    <outline>\(text)</outline>")
        }
        if let preview = source.previewLocation {
            let text = number(preview.lon) + "," + number(preview.lat) + "," + String(preview.zoom)
            line(&out, "    <preview-location>\(text)</preview-location>")
        }
        if let location = source.defaultLocation {
            let text = number(location.lon) + "," + number(location.lat)
            line(&out, "    <default-location>\(text)</default-location>")
        }
        for key in source.params.keys.sorted() {
            let value = source.params[key] ?? ""
            let shown = redacted.contains(key) ? "" : value
            line(&out, "    <param name=\"\(escape(key))\">\(cdata(shown))</param>")
        }
        for level in source.levels {
            writeLevel(&out, level)
        }
        line(&out, "  </source>")
    }

    private static func writeLevel(_ out: inout String, _ level: LevelDef) {
        let insecure = level.insecure ? " insecure=\"true\"" : ""
        line(&out, "    <zoom-levels z=\"\(level.zooms.toSpec())\"\(insecure)>")
        if let referer = level.referer {
            line(&out, "      <referer>\(cdata(referer))</referer>")
        }
        if let userAgent = level.userAgent {
            line(&out, "      <user-agent>\(cdata(userAgent))</user-agent>")
        }
        if let cookie = level.cookie {
            line(&out, "      <cookie>\(cdata(cookie))</cookie>")
        }
        for header in level.headers {
            let text = header.name + ": " + header.value
            line(&out, "      <header>\(cdata(text))</header>")
        }
        if let updateDelay = level.updateDelay {
            line(&out, "      <update-delay>\(escape(updateDelay))</update-delay>")
        }
        if level.maxThreads != LevelDef.defaultMaxThreads {
            line(&out, "      <max-threads>\(level.maxThreads)</max-threads>")
        }
        if level.tileSize != LevelDef.defaultTileSize {
            line(&out, "      <tiles-size>\(level.tileSize)</tiles-size>")
        }
        if let color = level.clearColor {
            line(&out, "      " + clearColor(color))
        }
        if let keyUrl = level.keyUrl {
            line(&out, "      <key-url>\(cdata(keyUrl))</key-url>")
        }
        if level.bboxCrs != LevelDef.crsWGS84 {
            line(&out, "      <bbox-crs-code>\(level.bboxCrs)</bbox-crs-code>")
        }
        for server in level.servers {
            line(&out, "      <server>\(cdata(server))</server>")
        }
        line(&out, "    </zoom-levels>")
    }

    private static func clearColor(_ color: ClearColor) -> String {
        func range(_ r: IntRange) -> String {
            r.first == r.last ? String(r.first) : String(r.first) + "-" + String(r.last)
        }
        let r = range(color.r)
        let g = range(color.g)
        let b = range(color.b)
        return "<clear-color r=\"\(r)\" g=\"\(g)\" b=\"\(b)\"/>"
    }

    /// Kotlin `appendLine`: the text and a line feed.
    private static func line(_ out: inout String, _ text: String) {
        out += text
        out += "\n"
    }

    /// Shortest decimal text that parses back to the same value: an integral value without decimals, any other value
    /// as Java's `Double.toString` writes it (`5.9`, `5.0E-4`).
    static func number(_ value: Double) -> String {
        if value == value.rounded(.toNearestOrEven) {
            return String(kotlinToLong(value))
        }
        return javaDoubleText(value)
    }

    /// Java `Double.toString`: the shortest digits that read back to `value`, written as a plain decimal from 10^-3 to
    /// 10^7 and as `d.dddE<n>` outside.
    static func javaDoubleText(_ value: Double) -> String {
        if value.isNaN { return "NaN" }
        if value.isInfinite { return value < 0 ? "-Infinity" : "Infinity" }
        if value == 0 { return value.sign == .minus ? "-0.0" : "0.0" }
        let magnitude = Swift.abs(value)
        // Swift's description gives the same shortest digits, in another layout ("0.0005", "5e-05", "1.5e+16").
        let shortest = Array("\(magnitude)".utf8)
        var mantissa = shortest
        var exponent = 0
        if let e = shortest.firstIndex(where: { $0 == UInt8(ascii: "e") || $0 == UInt8(ascii: "E") }) {
            mantissa = Array(shortest[..<e])
            exponent = Int(String(decoding: shortest[(e + 1)...], as: UTF8.self)) ?? 0
        }
        let dot = mantissa.firstIndex(of: UInt8(ascii: ".")) ?? mantissa.count
        var digits = mantissa.filter { $0 != UInt8(ascii: ".") }
        // The value is 0.<digits> times 10^point.
        var point = dot + exponent
        while digits.count > 1, digits[0] == UInt8(ascii: "0") {
            digits.removeFirst()
            point -= 1
        }
        while digits.count > 1, digits[digits.count - 1] == UInt8(ascii: "0") {
            digits.removeLast()
        }
        let text: String
        if magnitude >= 1e-3, magnitude < 1e7 {
            if point <= 0 {
                text = "0." + String(repeating: "0", count: -point) + ascii(digits[...])
            } else if point >= digits.count {
                text = ascii(digits[...]) + String(repeating: "0", count: point - digits.count) + ".0"
            } else {
                text = ascii(digits[..<point]) + "." + ascii(digits[point...])
            }
        } else {
            let rest = digits.count > 1 ? ascii(digits[1...]) : "0"
            text = ascii(digits[..<1]) + "." + rest + "E" + String(point - 1)
        }
        return value < 0 ? "-" + text : text
    }

    private static func ascii(_ bytes: ArraySlice<UInt8>) -> String {
        String(decoding: bytes, as: UTF8.self)
    }

    private static func escape(_ text: String) -> String {
        var out = ""
        for scalar in text.unicodeScalars {
            switch scalar {
            case "&": out += "&amp;"
            case "<": out += "&lt;"
            case ">": out += "&gt;"
            case "\"": out += "&quot;"
            default: out.unicodeScalars.append(scalar)
            }
        }
        return out
    }

    /// CDATA keeps URLs readable (`&` in query strings); an embedded `]]>` is split across two sections.
    private static func cdata(_ text: String) -> String {
        "<![CDATA[" + text.replacingOccurrences(of: "]]>", with: "]]]]><![CDATA[>", options: .literal) + "]]>"
    }
}
