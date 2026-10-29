// Port of core/src/main/kotlin/fr/toolmap/core/aqx/AqxParser.kt.

import Foundation

/// Parser for AlpineQuest `.aqx` map definition files (see `docs/specs/aqx-format.md`).
///
/// Tolerant by design: unknown elements are reported as `AqxWarning`s, never as failures. Only a file that is not
/// XML, has the wrong root, or declares a DOCTYPE/entities (XXE) is rejected.
public enum AqxParser {
    public static let maxFileChars = 5_000_000

    /// Simultaneous downloads a file may ask for per source (the wizard offers the same range, ADR-058).
    public static let maxThreads = 16

    static let projectionTags: Set<String> = [
        "projection-name",
        "projection-geoid",
        "projection-true-origin-x",
        "projection-true-origin-y",
        "projection-false-origin-x",
        "projection-false-origin-y",
        "projection-resolution",
        "projection-factor-x",
        "projection-factor-y",
        "projection-scale-denominator",
        "tiles-bounds",
        "import-crs-txt-def",
    ]
    static let webMercatorCodes: Set<String> = ["EPSG:3857", "EPSG:900913", "EPSG:3785", "EPSG:102100", "EPSG:102113"]
    static let zoomLevelTags: Set<String> = ["level", "zoom-levels", "zoom-level"]

    /// Throws `AqxParseError` when the text is not a usable AQX document.
    public static func parse(_ text: String) throws -> AqxParseResult {
        let length = text.utf16.count
        if length > maxFileChars { throw AqxParseError("AQX file too large (\(length) characters)") }
        // Entities and DOCTYPEs are never needed by AQX: refuse them outright rather than rely on parser flags.
        if containsIgnoringCase(text, "<!DOCTYPE") || containsIgnoringCase(text, "<!ENTITY") {
            throw AqxParseError("DOCTYPE and entity declarations are not allowed in AQX files")
        }
        var body = text
        if body.unicodeScalars.first == "\u{FEFF}" { body.unicodeScalars.removeFirst() }
        let root: XmlElement
        do {
            root = try XmlTree.parse(body)
        } catch let error as XmlReadError {
            throw AqxParseError("Invalid XML: \(error.message)")
        }
        if !equalsIgnoringCase(root.tagName, "aqx") {
            throw AqxParseError("Root element is <\(root.tagName)>, expected <aqx>")
        }
        var reader = Reader()
        return reader.read(root)
    }

    /// A WMS URL asking for Web Mercator: Kotlin `[?&](crs|srs)=(epsg:)?(3857|900913|102100|102113)(&|$)`,
    /// case-insensitive, anywhere in the URL.
    static func asksForWebMercator(_ url: String) -> Bool {
        let text = Array(url.lowercased().utf8)
        let codes = ["3857", "900913", "102100", "102113"].map { Array($0.utf8) }
        func starts(_ word: [UInt8], at index: Int) -> Bool {
            index + word.count <= text.count && Array(text[index ..< index + word.count]) == word
        }
        for i in text.indices where text[i] == UInt8(ascii: "?") || text[i] == UInt8(ascii: "&") {
            guard starts(Array("crs=".utf8), at: i + 1) || starts(Array("srs=".utf8), at: i + 1) else { continue }
            var next = i + 5
            if starts(Array("epsg:".utf8), at: next) { next += 5 }
            for code in codes where starts(code, at: next) {
                let after = next + code.count
                if after == text.count || text[after] == UInt8(ascii: "&") { return true }
            }
        }
        return false
    }

    private struct Reader {
        private var warnings: [AqxWarning] = []

        mutating func read(_ root: XmlElement) -> AqxParseResult {
            var name: String?
            var description: String?
            var params: [String: String] = [:]
            var sourceElements: [XmlElement] = []
            for child in root.elements {
                switch child.tagName.lowercased() {
                case "name": name = text(child)
                case "description": description = text(child)
                case "param": readParam(child, into: &params, context: "file")
                case "source": sourceElements.append(child)
                default: warn("file", "unknown element <\(child.tagName)> ignored")
                }
            }
            var sources: [TileSource] = []
            for element in sourceElements {
                guard let source = readSource(element, fileParams: params) else { continue }
                if sources.contains(where: { $0.id == source.id }) {
                    warn("source \(source.id)", "duplicate source id: later definition ignored")
                } else {
                    sources.append(source)
                }
            }
            let file = AqxFile(
                version: kotlinInt(kotlinTrim(root.attribute("version"))),
                name: name,
                description: description,
                params: params,
                sources: sources
            )
            return AqxParseResult(file: file, warnings: warnings)
        }

        private mutating func readParam(_ element: XmlElement, into: inout [String: String], context: String) {
            let key = kotlinTrim(element.attribute("name"))
            if key.isEmpty {
                warn(context, "<param> without a name ignored")
                return
            }
            let value = element.attribute("value")
            into[key] = value.isEmpty ? text(element) : value
        }

        private mutating func readSource(_ element: XmlElement, fileParams: [String: String]) -> TileSource? {
            let id = kotlinTrim(element.attribute("id"))
            if id.isEmpty {
                warn("file", "<source> without an id ignored")
                return nil
            }
            let context = "source \(id)"
            var name: String?
            var type = kotlinTrim(element.attribute("type")).lowercased().nilIfEmpty
            var copyright: String?
            var dataSource: String?
            var region: String?
            var outline: [LonLat] = []
            var preview: LonLatZoom?
            var defaultLocation: LonLat?
            var params = fileParams
            var levels: [LevelDef] = []
            var unsupported: [String] = []

            for child in element.elements {
                let tag = child.tagName.lowercased()
                switch tag {
                case "name": name = text(child)
                case "type": type = text(child).lowercased().nilIfEmpty
                case "copyright": copyright = text(child).nilIfEmpty
                case "data-source": dataSource = text(child).nilIfEmpty
                case "region": region = text(child).nilIfEmpty
                case "outline": outline = parseOutline(text(child), context)
                case "preview-location": preview = parsePreview(text(child), context)
                case "default-location": defaultLocation = parseLonLat(text(child), context)
                case "param": readParam(child, into: &params, context: context)
                default:
                    if AqxParser.zoomLevelTags.contains(tag) {
                        if let level = readLevel(child, context, &unsupported) { levels.append(level) }
                    } else {
                        warn(context, "unknown element <\(child.tagName)> ignored")
                    }
                }
            }
            if levels.isEmpty { unsupported.append("no zoom level definition") }
            var distinct: [String] = []
            for reason in unsupported where !distinct.contains(reason) {
                distinct.append(reason)
            }
            let opacity = kotlinInt(kotlinTrim(element.attribute("opacity"))).map { Swift.min(Swift.max($0, 0), 100) }
            return TileSource(
                id: id,
                name: name?.nilIfEmpty ?? id,
                type: type,
                isLayer: equalsIgnoringCase(kotlinTrim(element.attribute("layer")), "true"),
                opacity: opacity ?? 100,
                copyright: copyright,
                dataSource: dataSource,
                region: region,
                outline: outline,
                previewLocation: preview,
                defaultLocation: defaultLocation,
                params: params,
                levels: levels,
                unsupportedReason: distinct.joined(separator: "; ").nilIfEmpty
            )
        }

        private mutating func readLevel(
            _ element: XmlElement,
            _ context: String,
            _ unsupported: inout [String]
        ) -> LevelDef? {
            var zoomSpec = kotlinTrim(element.attribute("z")).nilIfEmpty
            var servers: [String] = []
            var headers: [HttpHeader] = []
            var referer: String?
            var userAgent: String?
            var cookie: String?
            var updateDelay: String?
            var maxThreads = LevelDef.defaultMaxThreads
            var tileSize = LevelDef.defaultTileSize
            var tileSizeX: Int?
            var tileSizeY: Int?
            var clearColor: ClearColor?
            var keyUrl: String?
            var bboxCrs = LevelDef.crsWGS84
            var bboxCrsWritten = false

            // Some files (OpenSnowMap) wrap the servers and their HTTP settings in <servers>: read them as if inline.
            let children = element.elements.flatMap { child in
                equalsIgnoringCase(child.tagName, "servers") ? child.elements : [child]
            }
            for child in children {
                let tag = child.tagName.lowercased()
                switch tag {
                case "zoom-values", "zoom-levels":
                    zoomSpec = text(child)
                case "server":
                    let server = text(child)
                    if !server.isEmpty { servers.append(server) }
                case "referer":
                    referer = text(child).nilIfEmpty
                case "user-agent":
                    userAgent = text(child).nilIfEmpty
                case "cookie":
                    cookie = text(child).nilIfEmpty
                case "header":
                    if let header = parseHeader(text(child), context) { headers.append(header) }
                case "update-delay":
                    updateDelay = text(child).nilIfEmpty
                case "max-threads":
                    maxThreads = Swift.min(Swift.max(kotlinInt(text(child)) ?? maxThreads, 1), AqxParser.maxThreads)
                case "tiles-size":
                    tileSize = kotlinInt(text(child)) ?? tileSize
                case "tiles-size-x":
                    tileSizeX = kotlinInt(text(child))
                case "tiles-size-y":
                    tileSizeY = kotlinInt(text(child))
                case "clear-color":
                    clearColor = parseClearColor(child, context)
                case "key-url":
                    keyUrl = text(child).nilIfEmpty
                case "bbox-crs-code":
                    bboxCrsWritten = true
                    bboxCrs = normalizeCrs(text(child))
                    if bboxCrs != LevelDef.crsWGS84, bboxCrs != LevelDef.crsWebMercator {
                        unsupported.append("unsupported {$bbox} CRS \(bboxCrs)")
                    }
                case "projection-code":
                    let code = normalizeCrs(text(child))
                    if !AqxParser.webMercatorCodes.contains(code), code != "" {
                        unsupported.append("unsupported projection \(code)")
                    }
                case "variable", "expression":
                    warn(context, "<\(child.tagName)> read but not evaluated: URLs using it may not work")
                default:
                    if AqxParser.projectionTags.contains(tag) {
                        unsupported.append("custom projection (<\(child.tagName)>)")
                    } else {
                        warn(context, "unknown element <\(child.tagName)> in level ignored")
                    }
                }
            }
            if tileSizeX != nil || tileSizeY != nil {
                let x = tileSizeX ?? tileSize
                let y = tileSizeY ?? tileSize
                if x == y {
                    tileSize = x
                } else {
                    unsupported.append("non-square tiles (\(x)x\(y))")
                }
            }
            let zooms: ZoomSet
            do {
                guard let spec = zoomSpec else { throw ToolMapError.invalidArgument("missing zoom levels") }
                zooms = try ZoomSet.parse(spec)
            } catch {
                warn(context, "level ignored: \((error as? ToolMapError)?.message ?? "\(error)")")
                return nil
            }
            if servers.isEmpty {
                warn(context, "level \(zooms.toSpec()) ignored: no <server> URL")
                return nil
            }
            if !bboxCrsWritten, servers.contains(where: { $0.contains("bbox}") && AqxParser.asksForWebMercator($0) }) {
                // A WMS URL asking for CRS=EPSG:3857 without <bbox-crs-code>: its {$bbox} must be in metres too.
                bboxCrs = LevelDef.crsWebMercator
            }
            return LevelDef(
                zooms: zooms,
                servers: servers,
                referer: referer,
                userAgent: userAgent,
                headers: headers,
                cookie: cookie,
                updateDelay: updateDelay,
                maxThreads: maxThreads,
                insecure: equalsIgnoringCase(kotlinTrim(element.attribute("insecure")), "true"),
                tileSize: tileSize,
                clearColor: clearColor,
                keyUrl: keyUrl,
                bboxCrs: bboxCrs
            )
        }

        private mutating func parseHeader(_ text: String, _ context: String) -> HttpHeader? {
            guard let separator = text.firstIndex(of: ":"), separator > text.startIndex else {
                warn(context, "malformed <header> '\(text)' ignored (expected 'Name: value')")
                return nil
            }
            return HttpHeader(
                kotlinTrim(String(text[..<separator])),
                kotlinTrim(String(text[text.index(after: separator)...]))
            )
        }

        private mutating func parseClearColor(_ element: XmlElement, _ context: String) -> ClearColor? {
            func channel(_ name: String) -> IntRange? {
                let raw = kotlinTrim(element.attribute(name))
                if raw.isEmpty { return IntRange(0, 255) }
                let parts = kotlinSplit(raw, "-").map { kotlinInt(kotlinTrim($0)) }
                let first = parts.first ?? nil
                let last = parts.count == 2 ? parts[1] : first
                guard parts.count <= 2, let from = first, let to = last else { return nil }
                return IntRange(from, to)
            }
            guard let r = channel("r"), let g = channel("g"), let b = channel("b") else {
                warn(context, "malformed <clear-color> ignored")
                return nil
            }
            return ClearColor(r: r, g: g, b: b)
        }

        private func normalizeCrs(_ raw: String) -> String {
            let text = kotlinTrim(raw).uppercased()
            if text.isEmpty { return "" }
            if text.hasPrefix("EPSG:") { return text }
            if text.unicodeScalars.allSatisfy({ $0.properties.generalCategory == .decimalNumber }) {
                return "EPSG:\(text)"
            }
            return text
        }

        private mutating func parseLonLat(_ text: String, _ context: String) -> LonLat? {
            let parts = kotlinSplit(text, ",").map { kotlinDouble(kotlinTrim($0)) }
            guard parts.count >= 2, let lon = parts[0], let lat = parts[1] else {
                warn(context, "malformed coordinates '\(text)' ignored")
                return nil
            }
            return LonLat(lon: lon, lat: lat)
        }

        private mutating func parsePreview(_ text: String, _ context: String) -> LonLatZoom? {
            let parts = kotlinSplit(text, ",").map(kotlinTrim)
            let lonValue = parts.count > 0 ? kotlinDouble(parts[0]) : nil
            let latValue = parts.count > 1 ? kotlinDouble(parts[1]) : nil
            let zoomValue = parts.count > 2 ? kotlinDouble(parts[2]).map(kotlinToInt) : nil
            guard let lon = lonValue, let lat = latValue, let zoom = zoomValue else {
                warn(context, "malformed <preview-location> '\(text)' ignored (expected lon,lat,zoom)")
                return nil
            }
            return LonLatZoom(lon: lon, lat: lat, zoom: zoom)
        }

        private mutating func parseOutline(_ text: String, _ context: String) -> [LonLat] {
            var points: [LonLat?] = []
            for word in splitOnSpaces(text) {
                points.append(parseLonLat(word, context))
            }
            return points.contains(where: { $0 == nil }) ? [] : points.compactMap { $0 }
        }

        private mutating func warn(_ context: String, _ message: String) {
            warnings.append(AqxWarning(context: context, message: message))
        }

        /// Kotlin `Element.text()`: the trimmed text content.
        private func text(_ element: XmlElement) -> String {
            kotlinTrim(element.textContent)
        }
    }
}
