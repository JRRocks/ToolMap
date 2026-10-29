// Port of core/src/main/kotlin/fr/toolmap/core/track/TrackImporter.kt.

import Foundation

/// Reads a track file whichever its format: the content decides, the file name is only a hint for the error.
public enum TrackImporter {
    /// Largest file we read; a 50 000-point GPX is about 5 MB.
    public static let maxBytes = 64 * 1024 * 1024

    private static let maxUnzipped = 128 * 1024 * 1024
    private static let sniffBytes = 4096

    /// Throws `TrackFormatError`.
    public static func read(_ data: Data, nameHint: String? = nil) throws -> TrackDocument {
        if data.count > maxBytes { throw TrackFormatError("File too large: \(data.count) bytes") }
        guard let format = detect(data) else {
            throw TrackFormatError("Unknown file format" + (nameHint.map { " (\($0))" } ?? ""))
        }
        switch format {
        case .gpx:
            return try GpxReader.read(data)
        case .kml:
            return try KmlReader.read(data)
        case .kmz:
            return try readKmz(data)
        case .geojson:
            // Kotlin `String(bytes, UTF_8).removePrefix("")`: the empty prefix removes nothing.
            return try GeoJsonReader.read(String(decoding: data, as: UTF8.self))
        }
    }

    /// Looks at the first bytes: a ZIP, a JSON object, or the root element of an XML document.
    public static func detect(_ data: Data) -> SourceFormat? {
        let head = [UInt8](data.prefix(sniffBytes))
        if head.count >= 4, head[0] == UInt8(ascii: "P"), head[1] == UInt8(ascii: "K"), head[2] == 3 {
            return .kmz
        }
        var scalars = Substring(String(decoding: head, as: UTF8.self)).unicodeScalars
        if scalars.first == "\u{FEFF}" { scalars = scalars.dropFirst() }
        while let first = scalars.first, KotlinText.isWhitespace(first) {
            scalars = scalars.dropFirst()
        }
        if scalars.first == "{" { return .geojson }
        guard let root = rootElement(Array(scalars)) else { return nil }
        switch root.substringAfter(":", missing: root) {
        case "gpx": return .gpx
        case "kml": return .kml
        default: return nil
        }
    }

    /// First element that is not a declaration, comment or DOCTYPE: Kotlin `<([A-Za-z_][\w.:-]*)` (`\w` is ASCII).
    private static func rootElement(_ chars: [Unicode.Scalar]) -> String? {
        func isStart(_ c: Unicode.Scalar) -> Bool { ("A" ... "Z").contains(c) || ("a" ... "z").contains(c) || c == "_" }
        func isName(_ c: Unicode.Scalar) -> Bool {
            isStart(c) || ("0" ... "9").contains(c) || c == "." || c == ":" || c == "-"
        }
        var i = 0
        while i + 1 < chars.count {
            if chars[i] == "<", isStart(chars[i + 1]) {
                var j = i + 2
                while j < chars.count, isName(chars[j]) {
                    j += 1
                }
                var name = String.UnicodeScalarView()
                name.append(contentsOf: chars[(i + 1) ..< j])
                return String(name)
            }
            i += 1
        }
        return nil
    }

    private static func readKmz(_ data: Data) throws -> TrackDocument {
        guard let kml = try findKml(data) else { throw TrackFormatError("KMZ archive without a .kml file") }
        var document = try KmlReader.read(kml)
        document.format = .kmz
        return document
    }

    /// The main KML of a KMZ: `doc.kml` if present, else the first `.kml` entry. Reading stops at `maxUnzipped`.
    private static func findKml(_ data: Data) throws -> Data? {
        let zip: ZipReader
        do {
            zip = try ZipReader(data)
        } catch {
            throw TrackFormatError("Not a readable KMZ archive: \(error)")
        }
        var chosen: Data?
        var total = 0
        for entry in zip.entries {
            if entry.isDirectory || !entry.name.lowercased().hasSuffix(".kml") { continue }
            let bytes: [UInt8]
            do {
                bytes = try zip.contents(of: entry, limit: maxUnzipped - total)
            } catch let error as ZipReader.ReadError {
                if error.message == "ZIP entry too large" {
                    throw TrackFormatError("KMZ archive too large once unzipped")
                }
                throw TrackFormatError("Not a readable KMZ archive: \(error.message)")
            }
            total += bytes.count
            if equalsIgnoringCase(entry.name, "doc.kml") { return Data(bytes) }
            if chosen == nil { chosen = Data(bytes) }
        }
        return chosen
    }
}
