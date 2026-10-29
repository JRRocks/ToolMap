// Port of core/src/main/kotlin/fr/toolmap/core/track/TrackModel.kt.

import Foundation

/// A fragment of XML the model does not interpret (Garmin `gpxtpx` heart rate, OsmAnd extensions, `magvar`, `hdop`...),
/// kept as it was read so that exporting writes it back. Namespace prefixes are those of the source document: the writer
/// declares them on the root element. Encoded in JSON as a plain string, like the Kotlin value class.
public struct RawXml: Equatable, Hashable, Sendable, Codable {
    public let xml: String

    public init(_ xml: String) {
        self.xml = xml
    }

    public init(from decoder: Decoder) throws {
        xml = try decoder.singleValueContainer().decode(String.self)
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(xml)
    }

    /// Qualified name of the fragment's root element, e.g. `extensions` or `gpxtpx:hr`.
    public var name: String {
        String(xml.dropFirst().prefix { !KotlinText.isWhitespace($0.unicodeScalars.first ?? " ") && $0 != ">" && $0 != "/" })
    }

    /// Local name, without prefix.
    public var localName: String { name.substringAfter(":", missing: name) }

    /// The same fragment with an `xmlns:prefix` declaration on its root for every prefix of `namespaces` it uses and does
    /// not declare itself, so that it stays valid wherever it is written.
    public func declaring(_ namespaces: [String: String]) -> RawXml {
        var used: [String] = []
        for prefix in RawXml.prefixesUsed(in: xml) where !used.contains(prefix) {
            used.append(prefix)
        }
        let missing = used.filter { namespaces[$0] != nil && !xml.contains(" xmlns:\($0)=") }
        if missing.isEmpty { return self }
        let rootEnd = xml.index(xml.startIndex, offsetBy: name.count + 1)
        let declarations = missing.map { " xmlns:\($0)=\"\(XmlText.attribute(namespaces[$0] ?? ""))\"" }.joined()
        return RawXml(String(xml[..<rootEnd]) + declarations + String(xml[rootEnd...]))
    }

    /// The prefixes of Kotlin `[<\s]/?([A-Za-z_][\w.-]*):[A-Za-z_]`: `<prefix:name` and ` prefix:attribute=`.
    static func prefixesUsed(in text: String) -> [String] {
        let chars = Array(text.unicodeScalars)
        func isStart(_ c: Unicode.Scalar) -> Bool { ("A" ... "Z").contains(c) || ("a" ... "z").contains(c) || c == "_" }
        func isWord(_ c: Unicode.Scalar) -> Bool {
            // Java's \w is ASCII only.
            isStart(c) || ("0" ... "9").contains(c) || c == "." || c == "-"
        }
        var out: [String] = []
        var i = 0
        while i < chars.count {
            let c = chars[i]
            guard c == "<" || KotlinText.isRegexSpace(c) else {
                i += 1
                continue
            }
            var j = i + 1
            if j < chars.count, chars[j] == "/" { j += 1 }
            guard j < chars.count, isStart(chars[j]) else {
                i += 1
                continue
            }
            var k = j + 1
            while k < chars.count, isWord(chars[k]) {
                k += 1
            }
            if k + 1 < chars.count, chars[k] == ":", isStart(chars[k + 1]) {
                out.append(String(String.UnicodeScalarView(chars[j ..< k])))
                i = k + 2
            } else {
                i += 1
            }
        }
        return out
    }
}

/// A `<link>` of GPX (or a GPX 1.0 `<url>` / `<urlname>` pair).
public struct GpxLink: Equatable, Hashable, Sendable, Codable {
    public let href: String
    public let text: String?
    public let type: String?

    public init(href: String, text: String? = nil, type: String? = nil) {
        self.href = href
        self.text = text
        self.type = type
    }
}

/// A recorded or drawn position. `timeMillis` is milliseconds since the epoch (UTC).
public struct TrackPoint: Equatable, Hashable, Sendable {
    public let lat: Double
    public let lon: Double
    public let ele: Double?
    public let timeMillis: Int64?
    public let extras: [RawXml]

    public init(lat: Double, lon: Double, ele: Double? = nil, timeMillis: Int64? = nil, extras: [RawXml] = []) {
        self.lat = lat
        self.lon = lon
        self.ele = ele
        self.timeMillis = timeMillis
        self.extras = extras
    }
}

/// A named place. Also used for the points of a `Route`, whose points may carry a name (turn instructions).
public struct Waypoint: Equatable, Hashable, Sendable, Codable {
    public let lat: Double
    public let lon: Double
    public let ele: Double?
    public let timeMillis: Int64?
    public let name: String?
    public let cmt: String?
    public let desc: String?
    public let src: String?
    public let links: [GpxLink]
    public let sym: String?
    public let type: String?
    public let extras: [RawXml]
    /// Name of the KML folder it came from; GPX has no such notion.
    public let folder: String?

    public init(
        lat: Double,
        lon: Double,
        ele: Double? = nil,
        timeMillis: Int64? = nil,
        name: String? = nil,
        cmt: String? = nil,
        desc: String? = nil,
        src: String? = nil,
        links: [GpxLink] = [],
        sym: String? = nil,
        type: String? = nil,
        extras: [RawXml] = [],
        folder: String? = nil
    ) {
        self.lat = lat
        self.lon = lon
        self.ele = ele
        self.timeMillis = timeMillis
        self.name = name
        self.cmt = cmt
        self.desc = desc
        self.src = src
        self.links = links
        self.sym = sym
        self.type = type
        self.extras = extras
        self.folder = folder
    }

    private enum CodingKeys: String, CodingKey {
        case lat, lon, ele, timeMillis, name, cmt, desc, src, links, sym, type, extras, folder
    }

    /// Like kotlinx.serialization: missing keys take their default value.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        lat = try c.decode(Double.self, forKey: .lat)
        lon = try c.decode(Double.self, forKey: .lon)
        ele = try c.decodeIfPresent(Double.self, forKey: .ele)
        timeMillis = try c.decodeIfPresent(Int64.self, forKey: .timeMillis)
        name = try c.decodeIfPresent(String.self, forKey: .name)
        cmt = try c.decodeIfPresent(String.self, forKey: .cmt)
        desc = try c.decodeIfPresent(String.self, forKey: .desc)
        src = try c.decodeIfPresent(String.self, forKey: .src)
        links = try c.decodeIfPresent([GpxLink].self, forKey: .links) ?? []
        sym = try c.decodeIfPresent(String.self, forKey: .sym)
        type = try c.decodeIfPresent(String.self, forKey: .type)
        extras = try c.decodeIfPresent([RawXml].self, forKey: .extras) ?? []
        folder = try c.decodeIfPresent(String.self, forKey: .folder)
    }

    /// Like kotlinx.serialization's default: values equal to their default are left out.
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(lat, forKey: .lat)
        try c.encode(lon, forKey: .lon)
        try c.encodeIfPresent(ele, forKey: .ele)
        try c.encodeIfPresent(timeMillis, forKey: .timeMillis)
        try c.encodeIfPresent(name, forKey: .name)
        try c.encodeIfPresent(cmt, forKey: .cmt)
        try c.encodeIfPresent(desc, forKey: .desc)
        try c.encodeIfPresent(src, forKey: .src)
        if !links.isEmpty { try c.encode(links, forKey: .links) }
        try c.encodeIfPresent(sym, forKey: .sym)
        try c.encodeIfPresent(type, forKey: .type)
        if !extras.isEmpty { try c.encode(extras, forKey: .extras) }
        try c.encodeIfPresent(folder, forKey: .folder)
    }
}

/// How a line is drawn. Colours are ARGB in a signed 32-bit integer, like Android's.
public struct PathStyle: Equatable, Hashable, Sendable {
    public static let defaultColor = Int32(bitPattern: 0xFFE5_3935)
    public static let defaultWidth: Float = 4

    /// Colours given in turn to the tracks of an import, and offered by the style picker.
    public static let palette: [Int32] = [
        0xFFE5_3935, 0xFF1E_88E5, 0xFF43_A047, 0xFFFB_8C00, 0xFF8E_24AA, 0xFF00_ACC1, 0xFF6D_4C41, 0xFF21_2121,
    ].map { Int32(bitPattern: $0) }

    public let colorArgb: Int32
    public let widthDp: Float
    public let dashed: Bool
    /// Direction arrows along the line.
    public let arrows: Bool

    public init(colorArgb: Int32 = PathStyle.defaultColor, widthDp: Float = PathStyle.defaultWidth, dashed: Bool = false, arrows: Bool = false) {
        self.colorArgb = colorArgb
        self.widthDp = widthDp
        self.dashed = dashed
        self.arrows = arrows
    }
}

/// An ordered list of points to follow (GPX `<rte>`; a KML polygon outline is imported as one too).
public struct Route: Equatable, Hashable, Sendable {
    public let name: String?
    public let cmt: String?
    public let desc: String?
    public let src: String?
    public let links: [GpxLink]
    public let number: Int?
    public let type: String?
    public let points: [Waypoint]
    public let extras: [RawXml]
    public let style: PathStyle?
    public let folder: String?

    public init(
        name: String? = nil,
        cmt: String? = nil,
        desc: String? = nil,
        src: String? = nil,
        links: [GpxLink] = [],
        number: Int? = nil,
        type: String? = nil,
        points: [Waypoint] = [],
        extras: [RawXml] = [],
        style: PathStyle? = nil,
        folder: String? = nil
    ) {
        self.name = name
        self.cmt = cmt
        self.desc = desc
        self.src = src
        self.links = links
        self.number = number
        self.type = type
        self.points = points
        self.extras = extras
        self.style = style
        self.folder = folder
    }
}

/// A recorded path made of one or more segments (a new segment starts after a GPS loss or a pause).
public struct Track: Equatable, Hashable, Sendable {
    public let name: String?
    public let cmt: String?
    public let desc: String?
    public let src: String?
    public let links: [GpxLink]
    public let number: Int?
    public let type: String?
    public let segments: [[TrackPoint]]
    public let extras: [RawXml]
    /// What the file itself says about colour and width (KML `LineStyle`, Garmin `DisplayColor`), if anything.
    public let style: PathStyle?
    public let folder: String?

    public init(
        name: String? = nil,
        cmt: String? = nil,
        desc: String? = nil,
        src: String? = nil,
        links: [GpxLink] = [],
        number: Int? = nil,
        type: String? = nil,
        segments: [[TrackPoint]] = [],
        extras: [RawXml] = [],
        style: PathStyle? = nil,
        folder: String? = nil
    ) {
        self.name = name
        self.cmt = cmt
        self.desc = desc
        self.src = src
        self.links = links
        self.number = number
        self.type = type
        self.segments = segments
        self.extras = extras
        self.style = style
        self.folder = folder
    }

    public var pointCount: Int { segments.reduce(0) { $0 + $1.count } }
}

public enum SourceFormat: String, Sendable, Codable {
    case gpx = "GPX"
    case kml = "KML"
    case kmz = "KMZ"
    case geojson = "GEOJSON"
}

/// What was odd in a file that was nevertheless imported. `detail` is for display, in the file's own words.
public struct TrackIssue: Equatable, Hashable, Sendable {
    public enum Kind: String, Sendable {
        /// A point with a missing or out-of-range latitude / longitude was skipped.
        case invalidCoordinate = "INVALID_COORDINATE"
        /// A time that could not be read was ignored.
        case invalidTime = "INVALID_TIME"
        /// A track segment or route without any usable point was dropped.
        case emptyItemDropped = "EMPTY_ITEM_DROPPED"
        /// A KML polygon was imported as a closed route.
        case polygonAsRoute = "POLYGON_AS_ROUTE"
        /// GPX 1.0 `speed`, `course` and `email`, or the e-mail and link of a GPX 1.1 author, are not kept.
        case fieldNotKept = "FIELD_NOT_KEPT"
        /// A geometry or element type that is not supported was skipped.
        case unsupportedElement = "UNSUPPORTED_ELEMENT"
    }

    public let kind: Kind
    public let detail: String

    public init(_ kind: Kind, _ detail: String = "") {
        self.kind = kind
        self.detail = detail
    }
}

/// Everything a GPX / KML / GeoJSON file holds, in one format-neutral shape.
public struct TrackDocument: Equatable, Sendable {
    public var name: String?
    public var description: String?
    public var author: String?
    public var keywords: String?
    public var timeMillis: Int64?
    public var creator: String?
    /// `1.0` or `1.1` for GPX; nil for other formats.
    public var version: String?
    public var links: [GpxLink]
    public var waypoints: [Waypoint]
    public var routes: [Route]
    public var tracks: [Track]
    /// Unmodelled children of `<metadata>` (e.g. `copyright`).
    public var metadataExtras: [RawXml]
    /// Unmodelled children of the root element (the document's own `<extensions>`).
    public var extras: [RawXml]
    /// Namespace prefixes (without the default one) declared in the source, needed by the raw fragments.
    public var namespaces: [String: String]
    public var issues: [TrackIssue]
    public var format: SourceFormat?

    public init(
        name: String? = nil,
        description: String? = nil,
        author: String? = nil,
        keywords: String? = nil,
        timeMillis: Int64? = nil,
        creator: String? = nil,
        version: String? = nil,
        links: [GpxLink] = [],
        waypoints: [Waypoint] = [],
        routes: [Route] = [],
        tracks: [Track] = [],
        metadataExtras: [RawXml] = [],
        extras: [RawXml] = [],
        namespaces: [String: String] = [:],
        issues: [TrackIssue] = [],
        format: SourceFormat? = nil
    ) {
        self.name = name
        self.description = description
        self.author = author
        self.keywords = keywords
        self.timeMillis = timeMillis
        self.creator = creator
        self.version = version
        self.links = links
        self.waypoints = waypoints
        self.routes = routes
        self.tracks = tracks
        self.metadataExtras = metadataExtras
        self.extras = extras
        self.namespaces = namespaces
        self.issues = issues
        self.format = format
    }

    public var isEmpty: Bool { waypoints.isEmpty && routes.isEmpty && tracks.isEmpty }

    public var pointCount: Int {
        tracks.reduce(0) { $0 + $1.pointCount } + routes.reduce(0) { $0 + $1.points.count } + waypoints.count
    }
}

/// The file is not a track file we can read, or is one we refuse to read (Kotlin `TrackFormatException`).
public struct TrackFormatError: Error, Equatable, Sendable, CustomStringConvertible {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var description: String { message }
}

extension Geodesy {
    public static func distance(_ a: TrackPoint, _ b: TrackPoint) -> Double {
        distance(a.lat, a.lon, b.lat, b.lon)
    }
}
