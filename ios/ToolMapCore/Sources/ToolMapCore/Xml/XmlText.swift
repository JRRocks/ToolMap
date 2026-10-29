// Port of core/src/main/kotlin/fr/toolmap/core/xml/XmlText.kt and JsonText.kt.

/// Escaping for the XML we write (GPX export, captured fragments).
public enum XmlText {
    public static func text(_ value: String) -> String { escape(value, quotes: false) }

    public static func attribute(_ value: String) -> String { escape(value, quotes: true) }

    private static func escape(_ value: String, quotes: Bool) -> String {
        let special: (Unicode.Scalar) -> Bool = { $0 == "&" || $0 == "<" || $0 == ">" || $0 == "\"" || $0.value < 0x20 }
        if !value.unicodeScalars.contains(where: special) { return value }
        var out = ""
        out.reserveCapacity(value.utf8.count + 16)
        for c in value.unicodeScalars {
            switch c {
            case "&": out += "&amp;"
            case "<": out += "&lt;"
            case ">": out += "&gt;"
            case "\"" where quotes: out += "&quot;"
            case "\n" where quotes: out += "&#10;"
            case "\r" where quotes: out += "&#13;"
            case "\t" where quotes: out += "&#9;"
            default:
                // Control characters other than tab / newline / carriage return are not allowed in XML 1.0.
                if c.value < 0x20, c != "\t", c != "\n", c != "\r" { continue }
                out.unicodeScalars.append(c)
            }
        }
        return out
    }
}

/// Writes a JSON string literal, for the few places that build JSON by hand for speed.
public enum JsonText {
    public static func quote(_ value: String) -> String {
        var out = "\""
        for c in value.unicodeScalars {
            switch c {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
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
