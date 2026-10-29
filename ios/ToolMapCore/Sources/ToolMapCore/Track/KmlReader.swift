// Port of core/src/main/kotlin/fr/toolmap/core/track/KmlReader.kt.

import Foundation

/// Reads the part of KML that is about paths and places: `Placemark` with `Point`, `LineString`, `Polygon` (outer
/// ring), `MultiGeometry` and Google's `gx:Track`; `Folder` names, inline and shared `Style` line colours and widths
/// (also through `StyleMap`). Overlays, tours, network links and icons are ignored.
public enum KmlReader {
    /// Throws `TrackFormatError`.
    public static func read(_ data: Data) throws -> TrackDocument {
        let handler = Handler()
        do {
            try SafeSax.parse(data, handler)
        } catch let error as XmlReadError {
            if let failure = handler.failure { throw failure }
            throw TrackFormatError(error.message)
        }
        if let failure = handler.failure { throw failure }
        return try handler.document()
    }

    static let maxText = 1_000_000

    private final class StyleBuilder {
        let id: String?
        var color: Int32?
        var width: Float?

        init(id: String?) {
            self.id = id
        }

        func toStyle() -> PathStyle? {
            if color == nil && width == nil { return nil }
            return PathStyle(colorArgb: color ?? PathStyle.defaultColor, widthDp: width ?? PathStyle.defaultWidth)
        }
    }

    private final class PlacemarkBuilder {
        var name: String?
        var desc: String?
        var styleUrl: String?
        var inlineStyle: StyleBuilder?
        var timeMillis: Int64?
        var points: [TrackPoint] = []
        var lines: [[TrackPoint]] = []
        var rings: [[TrackPoint]] = []
    }

    private typealias StyleRef = (url: String?, inline: StyleBuilder?)

    private final class Handler: SaxHandler {
        /// Set when the document is not KML: every later event is ignored and `read` throws it.
        var failure: TrackFormatError?

        private let issues = IssueLog()
        private var stack: [String] = []
        private var text = ""
        private var textLength = 0
        private var folders: [String] = []
        private var docName: String?
        private var docDesc: String?

        private var waypoints: [Waypoint] = []
        private var routes: [Route] = []
        private var tracks: [Track] = []
        private var trackStyleRefs: [StyleRef] = []
        private var routeStyleRefs: [StyleRef] = []

        private var styles: [String: StyleBuilder] = [:]
        private var styleMaps: [String: String] = [:]
        private var style: StyleBuilder?
        private var styleMapId: String?
        private var pairKey: String?
        private var pairUrl: String?

        private var placemark: PlacemarkBuilder?
        private var gxTimes: [Int64?] = []
        private var gxCoords: [TrackPoint?] = []
        private var rootSeen = false

        func document() throws -> TrackDocument {
            if !rootSeen { throw TrackFormatError("Empty document: no <kml> element") }
            var styledRoutes: [Route] = []
            for (i, r) in routes.enumerated() {
                styledRoutes.append(
                    Route(
                        name: r.name, cmt: r.cmt, desc: r.desc, src: r.src, links: r.links, number: r.number,
                        type: r.type, points: r.points, extras: r.extras, style: resolve(routeStyleRefs[i]),
                        folder: r.folder
                    )
                )
            }
            var styledTracks: [Track] = []
            for (i, t) in tracks.enumerated() {
                styledTracks.append(
                    Track(
                        name: t.name, cmt: t.cmt, desc: t.desc, src: t.src, links: t.links, number: t.number,
                        type: t.type, segments: t.segments, extras: t.extras, style: resolve(trackStyleRefs[i]),
                        folder: t.folder
                    )
                )
            }
            return TrackDocument(
                name: docName,
                description: docDesc,
                waypoints: waypoints,
                routes: styledRoutes,
                tracks: styledTracks,
                issues: issues.toList(),
                format: .kml
            )
        }

        private func resolve(_ ref: StyleRef) -> PathStyle? {
            if let inline = ref.inline?.toStyle() { return inline }
            guard let first = ref.url else { return nil }
            var url = removeHash(first)
            if let mapped = styleMaps[url] { url = removeHash(mapped) }
            return styles[url]?.toStyle()
        }

        private func removeHash(_ value: String) -> String {
            value.hasPrefix("#") ? String(value.dropFirst()) : value
        }

        override func startElement(uri: String, localName: String, qName: String, attributes: SaxAttributes) {
            if failure != nil { return }
            let local = localName.isEmpty ? qName.substringAfter(":", missing: qName) : localName
            if stack.isEmpty {
                if local != "kml" {
                    failure = TrackFormatError("Not a KML file: root element is <\(local)>")
                    return
                }
                rootSeen = true
            }
            let parent = stack.last
            stack.append(local)
            resetText()
            switch local {
            case "Folder":
                folders.append("")
            case "Placemark":
                placemark = PlacemarkBuilder()
            case "Style":
                let started = StyleBuilder(id: attributes.value("id"))
                if parent == "Placemark" { placemark?.inlineStyle = started }
                style = started
            case "StyleMap":
                styleMapId = attributes.value("id")
            case "Pair":
                pairKey = nil
                pairUrl = nil
            case "Track", "MultiTrack":
                gxTimes = []
                gxCoords = []
            default:
                break
            }
        }

        override func characters(_ chunk: String) {
            if failure != nil { return }
            if textLength < KmlReader.maxText {
                text += chunk
                textLength += chunk.utf16.count
            }
        }

        override func endElement(uri: String, localName: String, qName: String) {
            if failure != nil { return }
            guard let local = stack.last else { return }
            let parent = stack.count >= 2 ? stack[stack.count - 2] : ""
            let value = kotlinTrim(text)
            resetText()
            end(local, parent, value)
            stack.removeLast()
        }

        private func resetText() {
            text = ""
            textLength = 0
        }

        private func end(_ local: String, _ parent: String, _ value: String) {
            switch local {
            case "name":
                setName(parent, value)
            case "description":
                if parent == "Placemark" {
                    placemark?.desc = value
                } else if parent == "Document" {
                    docDesc = value
                }
            case "styleUrl":
                if parent == "Placemark" {
                    placemark?.styleUrl = value
                } else if parent == "Pair" {
                    pairUrl = value
                }
            case "key":
                if parent == "Pair" { pairKey = value }
            case "color":
                if parent == "LineStyle" { style?.color = parseColor(value) }
            case "width":
                if parent == "LineStyle" { style?.width = kotlinDouble(value).map { Float($0) } }
            case "when":
                endWhen(parent, value)
            case "coord":
                gxCoords.append(parseTuple(KmlReader.splitOnRegexSpaces(value)))
            case "coordinates":
                endCoordinates(parent, value)
            case "Pair":
                if pairKey == "normal" || (pairKey == nil && pairUrl != nil) {
                    if let id = styleMapId { styleMaps[id] = pairUrl ?? "" }
                }
            case "Style":
                if let current = style, let id = current.id { styles[id] = current }
            case "StyleMap":
                styleMapId = nil
            case "Track":
                endGxTrack()
            case "Folder":
                if !folders.isEmpty { folders.removeLast() }
            case "Placemark":
                endPlacemark()
            default:
                break
            }
        }

        private func setName(_ parent: String, _ value: String) {
            switch parent {
            case "Placemark": placemark?.name = value
            case "Folder": if !folders.isEmpty { folders[folders.count - 1] = value }
            case "Document": docName = value
            default: break
            }
        }

        private func endWhen(_ parent: String, _ value: String) {
            switch parent {
            case "TimeStamp": placemark?.timeMillis = TrackTime.parse(value)
            case "Track": gxTimes.append(TrackTime.parse(value))
            default: break
            }
        }

        private func endCoordinates(_ parent: String, _ value: String) {
            guard let pm = placemark else { return }
            let tuples = splitOnSpaces(value)
            let points = tuples.compactMap { parseTuple(kotlinSplit($0, ",")) }
            if points.count != tuples.count {
                issues.add(.invalidCoordinate, "coordinates")
            }
            switch parent {
            case "Point":
                pm.points.append(contentsOf: points)
            case "LineString":
                if !points.isEmpty { pm.lines.append(points) }
            case "LinearRing":
                if stack.contains("outerBoundaryIs"), !points.isEmpty { pm.rings.append(points) }
            default:
                break
            }
        }

        private func endGxTrack() {
            guard let pm = placemark else { return }
            var points: [TrackPoint] = []
            for (i, coordinate) in gxCoords.enumerated() {
                guard let p = coordinate else { continue }
                let time: Int64? = i < gxTimes.count ? gxTimes[i] : nil
                points.append(TrackPoint(lat: p.lat, lon: p.lon, ele: p.ele, timeMillis: time, extras: p.extras))
            }
            if !points.isEmpty { pm.lines.append(points) }
            gxCoords = []
            gxTimes = []
        }

        private func endPlacemark() {
            guard let pm = placemark else { return }
            placemark = nil
            let joined = folders.filter { !$0.isEmpty }.joined(separator: " / ")
            let folder: String? = joined.isEmpty ? nil : joined
            for p in pm.points {
                waypoints.append(
                    Waypoint(
                        lat: p.lat,
                        lon: p.lon,
                        ele: p.ele,
                        timeMillis: pm.timeMillis,
                        name: pm.name,
                        desc: pm.desc,
                        folder: folder
                    )
                )
            }
            if !pm.lines.isEmpty {
                tracks.append(Track(name: pm.name, desc: pm.desc, segments: pm.lines, folder: folder))
                trackStyleRefs.append((url: pm.styleUrl, inline: pm.inlineStyle))
            }
            for ring in pm.rings {
                issues.add(.polygonAsRoute, pm.name ?? "")
                let points = ring.map { Waypoint(lat: $0.lat, lon: $0.lon, ele: $0.ele) }
                routes.append(Route(name: pm.name, desc: pm.desc, points: points, folder: folder))
                routeStyleRefs.append((url: pm.styleUrl, inline: pm.inlineStyle))
            }
        }

        /// KML writes `lon,lat[,alt]`; `gx:coord` writes `lon lat [alt]`.
        private func parseTuple(_ parts: [String]) -> TrackPoint? {
            if parts.count < 2 { return nil }
            guard let lon = kotlinDouble(kotlinTrim(parts[0])), let lat = kotlinDouble(kotlinTrim(parts[1])) else {
                return nil
            }
            if !lat.isFinite || !lon.isFinite || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0 { return nil }
            let ele = parts.count > 2 ? kotlinDouble(kotlinTrim(parts[2])).flatMap { $0.isFinite ? $0 : nil } : nil
            return TrackPoint(lat: lat, lon: lon, ele: ele)
        }

        /// KML colours are `aabbggrr`.
        private func parseColor(_ value: String) -> Int32? {
            let hex = kotlinTrim(value)
            if hex.utf16.count != 8 { return nil }
            guard let n = Int64(hex, radix: 16) else { return nil }
            let a = (n >> 24) & 0xFF
            let b = (n >> 16) & 0xFF
            let g = (n >> 8) & 0xFF
            let r = n & 0xFF
            return Int32(truncatingIfNeeded: (a << 24) | (r << 16) | (g << 8) | b)
        }
    }

    /// Kotlin `text.split(Regex("\\s+"))`: every part is kept, an empty one at each end included when the text starts
    /// or ends with a space (Java's `\s`: space, tab, line feed, vertical tab, form feed, carriage return).
    static func splitOnRegexSpaces(_ text: String) -> [String] {
        var parts: [String] = []
        var current = String.UnicodeScalarView()
        var inSpaces = false
        for scalar in text.unicodeScalars {
            if KotlinText.isRegexSpace(scalar) {
                if !inSpaces {
                    parts.append(String(current))
                    current = String.UnicodeScalarView()
                    inSpaces = true
                }
            } else {
                current.append(scalar)
                inSpaces = false
            }
        }
        parts.append(String(current))
        return parts
    }
}
