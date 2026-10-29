// Port of core/src/main/kotlin/fr/toolmap/core/track/TrackGeoJson.kt.

/// A line to draw: one track or route. `id` is the database id, given back by taps on the map.
public struct LineFeature: Equatable, Sendable {
    public let id: Int64
    public let colorArgb: Int32
    public let widthDp: Float
    public let dashed: Bool
    public let segments: [[TrackPoint]]
    /// Direction arrows along the line.
    public let arrows: Bool

    public init(
        id: Int64,
        colorArgb: Int32,
        widthDp: Float,
        dashed: Bool,
        segments: [[TrackPoint]],
        arrows: Bool = false
    ) {
        self.id = id
        self.colorArgb = colorArgb
        self.widthDp = widthDp
        self.dashed = dashed
        self.segments = segments
        self.arrows = arrows
    }
}

/// A place to draw.
public struct PointFeature: Equatable, Sendable {
    public let id: Int64
    public let name: String?
    public let lat: Double
    public let lon: Double
    /// GPX symbol name; decides the colour and the icon of the marker.
    public let sym: String?
    /// Colour of the disc instead of the symbol's (the ends of a computed route).
    public let colorArgb: Int32?
    /// Glyph instead of the symbol's (a letter on the ends of a computed route).
    public let iconId: String?

    public init(
        id: Int64,
        name: String?,
        lat: Double,
        lon: Double,
        sym: String? = nil,
        colorArgb: Int32? = nil,
        iconId: String? = nil
    ) {
        self.id = id
        self.name = name
        self.lat = lat
        self.lon = lon
        self.sym = sym
        self.colorArgb = colorArgb
        self.iconId = iconId
    }
}

/// GeoJSON text for MapLibre's `GeoJsonSource`. Built by hand rather than with a JSON tree: 50 000 points are a few
/// hundred kilobytes, written in a few tens of milliseconds, on a background thread. Lines are simplified to
/// `toleranceMeters` first (invisible at any zoom that shows a whole track) and positions are written with six
/// decimals (about 10 cm).
public enum TrackGeoJson {
    public static func lines(_ items: [LineFeature], toleranceMeters: Double = 1.0) -> String {
        var out = ""
        let pointCount = items.reduce(0) { total, f in total + f.segments.reduce(0) { $0 + $1.count } }
        out.reserveCapacity(pointCount * 24 + 64)
        out += "{\"type\":\"FeatureCollection\",\"features\":["
        var first = true
        for item in items {
            let segments = item.segments.map { PathSimplifier.simplify($0, toleranceMeters) }.filter { $0.count >= 2 }
            if segments.isEmpty { continue }
            if !first { out += "," }
            first = false
            out += "{\"type\":\"Feature\",\"properties\":{\"id\":" + String(item.id)
            out += ",\"color\":\"" + hex(item.colorArgb) + "\""
            out += ",\"opacity\":" + opacity(item.colorArgb)
            out += ",\"width\":" + TrackNumberText.javaString(item.widthDp)
            out += ",\"dashed\":" + (item.dashed ? "true" : "false")
            out += ",\"arrows\":" + (item.arrows ? "true" : "false") + "},\"geometry\":"
            if segments.count == 1 {
                out += "{\"type\":\"LineString\",\"coordinates\":"
                coordinates(&out, segments[0])
            } else {
                out += "{\"type\":\"MultiLineString\",\"coordinates\":["
                for (i, s) in segments.enumerated() {
                    if i > 0 { out += "," }
                    coordinates(&out, s)
                }
                out += "]"
            }
            out += "}}"
        }
        out += "]}"
        return out
    }

    public static func points(_ items: [PointFeature]) -> String {
        var out = ""
        out.reserveCapacity(items.count * 96 + 64)
        out += "{\"type\":\"FeatureCollection\",\"features\":["
        for (i, p) in items.enumerated() {
            if i > 0 { out += "," }
            out += "{\"type\":\"Feature\",\"properties\":{\"id\":" + String(p.id)
            out += ",\"name\":" + JsonText.quote(p.name ?? "")
            let icon = WaypointSymbols.iconOf(p.sym)
            out += ",\"color\":\"" + hex(p.colorArgb ?? icon.colorArgb) + "\""
            out += ",\"icon\":\"" + (p.iconId ?? icon.id) + "\""
            out += "},\"geometry\":{\"type\":\"Point\",\"coordinates\":["
            appendDegrees(&out, p.lon)
            out += ","
            appendDegrees(&out, p.lat)
            out += "]}}"
        }
        out += "]}"
        return out
    }

    /// One point, for the cursor that follows the elevation profile.
    public static func point(_ lat: Double, _ lon: Double) -> String {
        points([PointFeature(id: 0, name: nil, lat: lat, lon: lon)])
    }

    private static func coordinates(_ out: inout String, _ points: [TrackPoint]) {
        out += "["
        for (i, p) in points.enumerated() {
            if i > 0 { out += "," }
            out += "["
            appendDegrees(&out, p.lon)
            out += ","
            appendDegrees(&out, p.lat)
            out += "]"
        }
        out += "]"
    }

    /// Kotlin `"#%06x".format(argb and 0xFFFFFF)`.
    static func hex(_ argb: Int32) -> String {
        let digits = String(Int(argb) & 0xFF_FFFF, radix: 16)
        return "#" + String(repeating: "0", count: Swift.max(0, 6 - digits.count)) + digits
    }

    private static func opacity(_ argb: Int32) -> String {
        let alpha = Int((UInt32(bitPattern: argb) >> 24) & 0xFF)
        return alpha == 0xFF ? "1" : TrackNumberText.fixed(Double(alpha) / 255.0, 2)
    }

    /// Six decimals, trailing zeros removed, never in scientific notation.
    static func appendDegrees(_ out: inout String, _ value: Double) {
        let scaled = kotlinRound(value * 1e6)
        if scaled < 0 { out += "-" }
        let magnitude = scaled.magnitude
        out += String(magnitude / 1_000_000)
        let fraction = magnitude % 1_000_000
        if fraction == 0 { return }
        var digits = String(fraction)
        if digits.count < 6 { digits = String(repeating: "0", count: 6 - digits.count) + digits }
        while digits.hasSuffix("0") {
            digits.removeLast()
        }
        out += "." + digits
    }
}
