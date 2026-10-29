// Port of core/src/main/kotlin/fr/toolmap/core/track/GpxWriter.kt.

import Foundation

/// Writes GPX 1.1, children in the order of the schema. Imported GPX 1.0 documents are written as 1.1. Coordinates,
/// elevations and times round-trip exactly (milliseconds); the unmodelled fragments of `RawXml` are written back as
/// read and their namespace prefixes are declared on the root.
///
/// The namespace declarations of the root are written in the order of their prefixes (Kotlin keeps the order of the
/// source document, which a Swift dictionary does not have), so that the output is stable.
public enum GpxWriter {
    public static let defaultCreator = "ToolMap"

    private static let pointOrder = [
        "ele", "time", "magvar", "geoidheight", "name", "cmt", "desc", "src", "link", "sym", "type", "fix", "sat",
        "hdop", "vdop", "pdop", "ageofdgpsdata", "dgpsid", "extensions",
    ]
    private static let itemOrder = ["name", "cmt", "desc", "src", "link", "number", "type", "extensions"]
    private static let metadataOrder = [
        "name", "desc", "author", "copyright", "link", "time", "keywords", "bounds", "extensions",
    ]

    private typealias Field = (name: String, xml: String)

    public static func toString(_ document: TrackDocument, creator: String = GpxWriter.defaultCreator) -> String {
        var out = ""
        write(document, to: &out, creator: creator)
        return out
    }

    public static func toBytes(_ document: TrackDocument, creator: String = GpxWriter.defaultCreator) -> Data {
        Data(toString(document, creator: creator).utf8)
    }

    public static func write(_ document: TrackDocument, to out: inout String, creator: String = GpxWriter.defaultCreator) {
        out += "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        out += "<gpx version=\"1.1\" creator=\"" + XmlText.attribute(creator) + "\""
        out += " xmlns=\"" + GpxReader.nsGpx11 + "\""
        for prefix in document.namespaces.keys.sorted() {
            guard let uri = document.namespaces[prefix] else { continue }
            if uri == GpxReader.nsGpx11 || prefix == "xmlns" { continue }
            out += " xmlns:" + prefix + "=\"" + XmlText.attribute(uri) + "\""
        }
        out += ">\n"
        writeMetadata(document, &out)
        for w in document.waypoints {
            writePoint(&out, "wpt", w.lat, w.lon, pointFields(w), 1)
        }
        for r in document.routes {
            writeRoute(&out, r)
        }
        for t in document.tracks {
            writeTrack(&out, t)
        }
        for raw in document.extras {
            out += "  " + raw.xml + "\n"
        }
        out += "</gpx>\n"
    }

    private static func writeMetadata(_ d: TrackDocument, _ out: inout String) {
        var fields: [Field] = []
        if let name = d.name { fields.append(("name", leaf("name", name))) }
        if let description = d.description { fields.append(("desc", leaf("desc", description))) }
        if let author = d.author { fields.append(("author", "<author>" + leaf("name", author) + "</author>")) }
        for l in d.links {
            fields.append(("link", link(l)))
        }
        if let time = d.timeMillis { fields.append(("time", leaf("time", TrackTime.format(time)))) }
        if let keywords = d.keywords { fields.append(("keywords", leaf("keywords", keywords))) }
        if let box = bounds(d) { fields.append(("bounds", box)) }
        for raw in d.metadataExtras {
            fields.append((raw.localName, raw.xml))
        }
        if fields.isEmpty { return }
        out += "  <metadata>\n"
        for field in sortByOrder(fields, metadataOrder) {
            out += "    " + field.xml + "\n"
        }
        out += "  </metadata>\n"
    }

    private static func bounds(_ d: TrackDocument) -> String? {
        var minLat = Double.infinity
        var minLon = Double.infinity
        var maxLat = -Double.infinity
        var maxLon = -Double.infinity

        func see(_ lat: Double, _ lon: Double) {
            if lat < minLat { minLat = lat }
            if lat > maxLat { maxLat = lat }
            if lon < minLon { minLon = lon }
            if lon > maxLon { maxLon = lon }
        }
        for w in d.waypoints {
            see(w.lat, w.lon)
        }
        for r in d.routes {
            for p in r.points {
                see(p.lat, p.lon)
            }
        }
        for t in d.tracks {
            for s in t.segments {
                for p in s {
                    see(p.lat, p.lon)
                }
            }
        }
        if minLat > maxLat { return nil }
        let south = num(minLat)
        let west = num(minLon)
        let north = num(maxLat)
        let east = num(maxLon)
        return "<bounds minlat=\"\(south)\" minlon=\"\(west)\" maxlat=\"\(north)\" maxlon=\"\(east)\"/>"
    }

    private static func pointFields(_ w: Waypoint) -> [Field] {
        var f: [Field] = []
        if let ele = w.ele { f.append(("ele", leaf("ele", num(ele)))) }
        if let time = w.timeMillis { f.append(("time", leaf("time", TrackTime.format(time)))) }
        if let name = w.name { f.append(("name", leaf("name", name))) }
        if let cmt = w.cmt { f.append(("cmt", leaf("cmt", cmt))) }
        if let desc = w.desc { f.append(("desc", leaf("desc", desc))) }
        if let src = w.src { f.append(("src", leaf("src", src))) }
        for l in w.links {
            f.append(("link", link(l)))
        }
        if let sym = w.sym { f.append(("sym", leaf("sym", sym))) }
        if let type = w.type { f.append(("type", leaf("type", type))) }
        for raw in w.extras {
            f.append((raw.localName, raw.xml))
        }
        return f
    }

    private static func writePoint(
        _ out: inout String,
        _ tag: String,
        _ lat: Double,
        _ lon: Double,
        _ fields: [Field],
        _ level: Int
    ) {
        let pad = String(repeating: "  ", count: level)
        let latText = num(lat)
        let lonText = num(lon)
        out += "\(pad)<\(tag) lat=\"\(latText)\" lon=\"\(lonText)"
        if fields.isEmpty {
            out += "\"/>\n"
            return
        }
        out += "\">\n"
        for field in sortByOrder(fields, pointOrder) {
            out += pad + "  " + field.xml + "\n"
        }
        out += pad + "</" + tag + ">\n"
    }

    private static func writeRoute(_ out: inout String, _ r: Route) {
        out += "  <rte>\n"
        var fields: [Field] = []
        itemFields(&fields, r.name, r.cmt, r.desc, r.src, r.links, r.number, r.type, r.extras)
        for field in sortByOrder(fields, itemOrder) {
            out += "    " + field.xml + "\n"
        }
        for p in r.points {
            writePoint(&out, "rtept", p.lat, p.lon, pointFields(p), 2)
        }
        out += "  </rte>\n"
    }

    private static func writeTrack(_ out: inout String, _ t: Track) {
        out += "  <trk>\n"
        var fields: [Field] = []
        itemFields(&fields, t.name, t.cmt, t.desc, t.src, t.links, t.number, t.type, t.extras)
        for field in sortByOrder(fields, itemOrder) {
            out += "    " + field.xml + "\n"
        }
        for segment in t.segments {
            out += "    <trkseg>\n"
            for p in segment {
                var f: [Field] = []
                if let ele = p.ele { f.append(("ele", leaf("ele", num(ele)))) }
                if let time = p.timeMillis { f.append(("time", leaf("time", TrackTime.format(time)))) }
                for raw in p.extras {
                    f.append((raw.localName, raw.xml))
                }
                writePoint(&out, "trkpt", p.lat, p.lon, f, 3)
            }
            out += "    </trkseg>\n"
        }
        out += "  </trk>\n"
    }

    // swiftlint:disable:next function_parameter_count
    private static func itemFields(
        _ f: inout [Field],
        _ name: String?,
        _ cmt: String?,
        _ desc: String?,
        _ src: String?,
        _ links: [GpxLink],
        _ number: Int?,
        _ type: String?,
        _ extras: [RawXml]
    ) {
        if let name { f.append(("name", leaf("name", name))) }
        if let cmt { f.append(("cmt", leaf("cmt", cmt))) }
        if let desc { f.append(("desc", leaf("desc", desc))) }
        if let src { f.append(("src", leaf("src", src))) }
        for l in links {
            f.append(("link", link(l)))
        }
        if let number { f.append(("number", leaf("number", String(number)))) }
        if let type { f.append(("type", leaf("type", type))) }
        for raw in extras {
            f.append((raw.localName, raw.xml))
        }
    }

    /// Stable sort by position in the schema; names the schema does not list go with `extensions`, at the end.
    private static func sortByOrder(_ fields: [Field], _ order: [String]) -> [Field] {
        let ranked = fields.enumerated().map { entry -> (rank: Int, index: Int, field: Field) in
            let rank = order.firstIndex(of: entry.element.name) ?? (order.count - 1)
            return (rank, entry.offset, entry.element)
        }
        return ranked.sorted { a, b in a.rank != b.rank ? a.rank < b.rank : a.index < b.index }.map { $0.field }
    }

    private static func leaf(_ name: String, _ value: String) -> String {
        let escaped = XmlText.text(value)
        return "<\(name)>\(escaped)</\(name)>"
    }

    private static func link(_ l: GpxLink) -> String {
        var inner = ""
        if let text = l.text { inner += leaf("text", text) }
        if let type = l.type { inner += leaf("type", type) }
        let href = XmlText.attribute(l.href)
        if inner.isEmpty { return "<link href=\"\(href)\"/>" }
        return "<link href=\"\(href)\">\(inner)</link>"
    }

    /// Shortest decimal that reads back as the same double, never in scientific notation.
    private static func num(_ value: Double) -> String { TrackNumberText.plain(value) }
}
