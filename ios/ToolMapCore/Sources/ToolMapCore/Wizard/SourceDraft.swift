// Port of core/src/main/kotlin/fr/toolmap/core/wizard/SourceDraft.kt (SourceDraft, SourceIds, PreviewTile).

import Foundation

/// Why a `SourceDraft` cannot be saved (errors) or deserves a second look (warnings). Kotlin `wizard.DraftIssue`,
/// renamed because `DraftIssue` is the issue of a multi-zoom draft (`source/MultiZoomDraft.kt`) and Swift has one name
/// space per module.
public enum SourceDraftIssue: String, CaseIterable, Sendable {
    case nameEmpty = "NAME_EMPTY"
    case urlEmpty = "URL_EMPTY"
    case urlNotHttp = "URL_NOT_HTTP"
    case noTilePlaceholder = "NO_TILE_PLACEHOLDER"
    case unknownPlaceholder = "UNKNOWN_PLACEHOLDER"
    case missingParam = "MISSING_PARAM"
    case zoomInvalid = "ZOOM_INVALID"
    case tileSizeInvalid = "TILE_SIZE_INVALID"
    case updateDelayInvalid = "UPDATE_DELAY_INVALID"
    case headerInvalid = "HEADER_INVALID"
    case threadsInvalid = "THREADS_INVALID"
    case opacityInvalid = "OPACITY_INVALID"
    /// `http://` (not encrypted): works only because the app allows clear-text traffic; worth saying.
    case cleartext = "CLEARTEXT"
    /// An API key still sits in the URL instead of a parameter: it would be exported with the file.
    case keyInUrl = "KEY_IN_URL"

    public var isError: Bool { self != .cleartext && self != .keyInUrl }
}

/// What the add-a-map wizard collects, as plain text fields so that half-typed values can be kept and checked
/// (`validate`) before becoming a `TileSource` (`toSource`). One level only: the wizard creates and edits single-level
/// sources. The fields are `var` (Kotlin `copy(...)`).
public struct SourceDraft: Equatable, Hashable, Sendable {
    /// Kotlin `MAX_THREADS` (renamed: `maxThreads` is the draft's own value).
    public static let maxThreadsAllowed = 16
    public static let tileSizes = [256, 512]

    private static let knownPlaceholders: Set<String> = ["x", "y", "!y", "z", "q", "bbox", "ibbox"]
    /// Kotlin `[A-Za-z0-9!#$%&'*+.^_`|~-]+`.
    private static let headerNameChars = Set(
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!#$%&'*+.^_`|~-".unicodeScalars
    )
    /// Kotlin `\{\$([^\}]+)\}`.
    private static let placeholder = try! NSRegularExpression(pattern: "\\{\\$([^\\}]+)\\}")
    /// Kotlin `\{[$]param:([^\}]+)\}`.
    private static let paramRef = try! NSRegularExpression(pattern: "\\{[$]param:([^\\}]+)\\}")

    public var id: String
    public var name: String
    /// One URL template per server (several = round-robin).
    public var urls: [String]
    /// `{$param:key}` values: API keys and the like.
    public var params: [String: String]
    public var type: String?
    public var isLayer: Bool
    /// 0...100.
    public var opacity: Int
    public var copyright: String
    /// AQX zoom syntax (`1-17`, `0-5,8`).
    public var zoomSpec: String
    public var tileSize: Int
    public var referer: String
    public var userAgent: String
    /// One `Name: value` per line.
    public var headersText: String
    public var cookie: String
    /// `insecure="true"` of an imported `.aqx`, kept so that a copy exports it again. Never applied and no longer
    /// offered by the wizard.
    public var insecure: Bool
    public var maxThreads: Int
    /// `0D`, `2W`, `3M`, `1Y` or empty (never expires).
    public var updateDelay: String
    public var bboxCrs: String
    public var previewLocation: LonLatZoom?

    public init(
        id: String = "",
        name: String = "",
        urls: [String] = [""],
        params: [String: String] = [:],
        type: String? = nil,
        isLayer: Bool = false,
        opacity: Int = 100,
        copyright: String = "",
        zoomSpec: String = "0-18",
        tileSize: Int = LevelDef.defaultTileSize,
        referer: String = "",
        userAgent: String = "",
        headersText: String = "",
        cookie: String = "",
        insecure: Bool = false,
        maxThreads: Int = LevelDef.defaultMaxThreads,
        updateDelay: String = "",
        bboxCrs: String = LevelDef.crsWGS84,
        previewLocation: LonLatZoom? = nil
    ) {
        self.id = id
        self.name = name
        self.urls = urls
        self.params = params
        self.type = type
        self.isLayer = isLayer
        self.opacity = opacity
        self.copyright = copyright
        self.zoomSpec = zoomSpec
        self.tileSize = tileSize
        self.referer = referer
        self.userAgent = userAgent
        self.headersText = headersText
        self.cookie = cookie
        self.insecure = insecure
        self.maxThreads = maxThreads
        self.updateDelay = updateDelay
        self.bboxCrs = bboxCrs
        self.previewLocation = previewLocation
    }

    /// Parsed `Name: value` lines; lines that do not fit are in `invalidHeaderLines`.
    public var headers: [HttpHeader] { headerLines().good }

    public var invalidHeaderLines: [String] { headerLines().bad }

    private func headerLines() -> (good: [HttpHeader], bad: [String]) {
        var good: [HttpHeader] = []
        var bad: [String] = []
        for raw in SourceDraft.lines(headersText) {
            let line = kotlinTrim(raw)
            if line.isEmpty { continue }
            let headerName = kotlinTrim(line.substringBefore(":"))
            guard let colon = line.firstIndex(of: ":"), colon > line.startIndex, SourceDraft.isHeaderName(headerName)
            else {
                bad.append(line)
                continue
            }
            good.append(HttpHeader(headerName, kotlinTrim(String(line[line.index(after: colon)...]))))
        }
        return (good, bad)
    }

    public var zoomSet: ZoomSet? { try? ZoomSet.parse(zoomSpec) }

    public func validate() -> [SourceDraftIssue] {
        var issues: [SourceDraftIssue] = []
        func add(_ issue: SourceDraftIssue) {
            if !issues.contains(issue) { issues.append(issue) }
        }
        if kotlinTrim(name).isEmpty { add(.nameEmpty) }
        let urlList = urls.map(kotlinTrim).filter { !$0.isEmpty }
        if urlList.isEmpty { add(.urlEmpty) }
        for url in urlList {
            for issue in urlIssues(url) {
                add(issue)
            }
        }
        if zoomSet == nil { add(.zoomInvalid) }
        if !SourceDraft.tileSizes.contains(tileSize) { add(.tileSizeInvalid) }
        let delay = kotlinTrim(updateDelay)
        if !delay.isEmpty, !SourceDraft.isUpdateDelay(delay) { add(.updateDelayInvalid) }
        if !invalidHeaderLines.isEmpty { add(.headerInvalid) }
        if maxThreads < 1 || maxThreads > SourceDraft.maxThreadsAllowed { add(.threadsInvalid) }
        if opacity < 0 || opacity > 100 { add(.opacityInvalid) }
        // Kotlin `sortedBy { !it.isError }`: the errors first, each group in its order.
        return issues.filter(\.isError) + issues.filter { !$0.isError }
    }

    public var isValid: Bool { !validate().contains { $0.isError } }

    private func urlIssues(_ url: String) -> [SourceDraftIssue] {
        var found: [SourceDraftIssue] = []
        let lower = url.lowercased()
        let isHttp = lower.hasPrefix("http://")
        if !isHttp, !lower.hasPrefix("https://") { found.append(.urlNotHttp) }
        if isHttp { found.append(.cleartext) }
        let placeholders = SourceDraft.groups(SourceDraft.placeholder, url)
        let hasXyz = placeholders.contains("z") && placeholders.contains("x") &&
            (placeholders.contains("y") || placeholders.contains("!y"))
        let hasTilePlaceholder = hasXyz || placeholders.contains("q") ||
            placeholders.contains("bbox") || placeholders.contains("ibbox")
        if !hasTilePlaceholder { found.append(.noTilePlaceholder) }
        for name in placeholders {
            if SourceDraft.knownPlaceholders.contains(name) { continue }
            if name.hasPrefix("param:") {
                if params[String(name.dropFirst("param:".count))] == nil { found.append(.missingParam) }
            } else {
                found.append(.unknownPlaceholder)
            }
        }
        if ApiKeyExtractor.extract(url).found { found.append(.keyInUrl) }
        return found
    }

    /// Moves API keys written in the URLs into `params` (see `ApiKeyExtractor`). Returns the new draft.
    public func extractKeys() -> SourceDraft {
        var known = params
        var rewritten: [String] = []
        for url in urls {
            let extraction = ApiKeyExtractor.extract(url, existingParams: known)
            for (key, value) in extraction.params {
                known[key] = value
            }
            rewritten.append(extraction.template)
        }
        var copy = self
        copy.urls = rewritten
        copy.params = known
        return copy
    }

    /// Throws `ToolMapError` when the draft has errors (Kotlin `IllegalStateException`): call `validate` first.
    public func toSource() throws -> TileSource {
        let errors = validate().filter(\.isError)
        if !errors.isEmpty {
            let names = errors.map(\.rawValue).joined(separator: ", ")
            throw ToolMapError.invalidArgument("Invalid draft: [\(names)]")
        }
        let zooms = try ZoomSet.parse(zoomSpec)
        let level = LevelDef(
            zooms: zooms,
            servers: urls.map(kotlinTrim).filter { !$0.isEmpty },
            referer: kotlinTrim(referer).nilIfEmpty,
            userAgent: kotlinTrim(userAgent).nilIfEmpty,
            headers: headers,
            cookie: kotlinTrim(cookie).nilIfEmpty,
            updateDelay: kotlinTrim(updateDelay).uppercased().nilIfEmpty,
            maxThreads: maxThreads,
            insecure: insecure,
            tileSize: tileSize,
            bboxCrs: bboxCrs
        )
        // Only parameters the URLs refer to are kept (a key left over from an edit is not written to files).
        var used = Set<String>()
        for url in urls {
            used.formUnion(SourceDraft.groups(SourceDraft.paramRef, url))
        }
        let kept = params.filter { used.contains($0.key) }
        let sourceType = type.flatMap { kotlinTrim($0).lowercased().nilIfEmpty }
        return TileSource(
            id: kotlinTrim(id).isEmpty ? SourceIds.fromName(name) : id,
            name: kotlinTrim(name),
            type: sourceType,
            isLayer: isLayer,
            opacity: opacity,
            copyright: kotlinTrim(copyright).nilIfEmpty,
            previewLocation: previewLocation,
            params: kept,
            levels: [level]
        )
    }

    /// The draft that would produce `source` again; nil when the wizard cannot represent it.
    public static func fromSource(_ source: TileSource) -> SourceDraft? {
        guard source.levels.count == 1, let level = source.levels.first else { return nil }
        if !source.isSupported || !level.hasServer || level.clearColor != nil {
            return nil
        }
        return SourceDraft(
            id: source.id,
            name: source.name,
            urls: level.servers,
            params: source.params,
            type: source.type,
            isLayer: source.isLayer,
            opacity: source.opacity,
            copyright: source.copyright ?? "",
            zoomSpec: level.zooms.toSpec(),
            tileSize: level.tileSize,
            referer: level.referer ?? "",
            userAgent: level.userAgent ?? "",
            headersText: level.headers.map { $0.name + ": " + $0.value }.joined(separator: "\n"),
            cookie: level.cookie ?? "",
            insecure: level.insecure,
            maxThreads: level.maxThreads,
            updateDelay: level.updateDelay ?? "",
            bboxCrs: level.bboxCrs,
            previewLocation: source.previewLocation
        )
    }

    /// The first group of every match of `regex` in `text`, left to right.
    private static func groups(_ regex: NSRegularExpression, _ text: String) -> [String] {
        let ns = text as NSString
        return regex.matches(in: text, options: [], range: NSRange(location: 0, length: ns.length)).map {
            ns.substring(with: $0.range(at: 1))
        }
    }

    /// Kotlin `String.lines()`: split at `\r\n`, `\n` and `\r`.
    private static func lines(_ text: String) -> [String] {
        text.replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .components(separatedBy: "\n")
    }

    private static func isHeaderName(_ name: String) -> Bool {
        !name.isEmpty && name.unicodeScalars.allSatisfy { headerNameChars.contains($0) }
    }

    /// Kotlin `Regex("""\d{1,5}\s*[DWMYdwmy]""").matches(text)`.
    private static func isUpdateDelay(_ text: String) -> Bool {
        let scalars = Array(text.unicodeScalars)
        var i = 0
        while i < scalars.count, i < 5, ("0" ... "9").contains(scalars[i]) {
            i += 1
        }
        if i == 0 { return false }
        while i < scalars.count, KotlinText.isRegexSpace(scalars[i]) {
            i += 1
        }
        return i == scalars.count - 1 && "DWMYdwmy".unicodeScalars.contains(scalars[i])
    }
}

/// Ids of user-created sources: lower case, digits and dashes (they are path segments of the tile host).
public enum SourceIds {
    private static let maxLength = 40

    public static func fromName(_ name: String) -> String {
        var folded = String.UnicodeScalarView()
        for scalar in kotlinTrim(name).decomposedStringWithCanonicalMapping.unicodeScalars where !isMark(scalar) {
            folded.append(scalar)
        }
        var slug = trimDashes(replaceNonIdRuns(String(folded).lowercased()))
        slug = trimDashes(String(slug.prefix(maxLength)))
        return slug.isEmpty ? "map" : slug
    }

    /// `base`, or `base-2`, `base-3`... when `taken` already has it.
    public static func unique(_ base: String, taken: (String) -> Bool) -> String {
        if !taken(base) { return base }
        var n = 2
        while taken("\(base)-\(n)") {
            n += 1
        }
        return "\(base)-\(n)"
    }

    private static func isMark(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .nonspacingMark, .spacingMark, .enclosingMark: return true
        default: return false
        }
    }

    /// Kotlin `replace(Regex("[^a-z0-9]+"), "-")`.
    private static func replaceNonIdRuns(_ text: String) -> String {
        var out = String.UnicodeScalarView()
        var inRun = false
        for scalar in text.unicodeScalars {
            if ("a" ... "z").contains(scalar) || ("0" ... "9").contains(scalar) {
                out.append(scalar)
                inRun = false
            } else if !inRun {
                out.append("-")
                inRun = true
            }
        }
        return String(out)
    }

    /// Kotlin `trim('-')`.
    private static func trimDashes(_ text: String) -> String {
        var slice = Substring(text)
        while slice.hasPrefix("-") {
            slice = slice.dropFirst()
        }
        while slice.hasSuffix("-") {
            slice = slice.dropLast()
        }
        return String(slice)
    }
}

/// A tile to fetch to check that a source answers: its address in the source's own tile grid.
public struct PreviewTile: Equatable, Hashable, Sendable {
    /// Paris, used when the source does not say where its data is.
    public static let defaultLocation = LonLatZoom(lon: 2.35, lat: 48.85, zoom: 12)

    public let z: Int
    public let x: Int
    public let y: Int

    public init(z: Int, x: Int, y: Int) {
        self.z = z
        self.x = x
        self.y = y
    }

    /// The tile at `location` (the source's `preview-location`, else Paris), at the zoom the source really serves that
    /// is closest to the location's own zoom.
    public static func choose(_ zooms: ZoomSet, _ location: LonLatZoom?) throws -> PreviewTile {
        let place = location ?? defaultLocation
        let list = zooms.toList()
        var zoom = list[0]
        for candidate in list where abs(candidate - place.zoom) < abs(zoom - place.zoom) {
            zoom = candidate
        }
        let tile = try TileMath.tileAt(place.lon, place.lat, zoom)
        return PreviewTile(z: zoom, x: tile.x, y: tile.y)
    }
}
