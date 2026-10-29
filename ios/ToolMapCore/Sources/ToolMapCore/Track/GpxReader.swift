// Port of core/src/main/kotlin/fr/toolmap/core/track/GpxReader.kt.

import Foundation

/// Reads GPX 1.0 and 1.1 (any namespace prefix, or none). Elements the model does not interpret (extensions of any
/// vendor, `magvar`, `hdop`...) are kept as `RawXml` so that `GpxWriter` writes them back.
///
/// Unlike the JVM's SAX, XMLParser does not give the attributes in document order: the attributes of a kept fragment
/// are written in the order of their qualified names.
public enum GpxReader {
    public static let nsGpx10 = "http://www.topografix.com/GPX/1/0"
    public static let nsGpx11 = "http://www.topografix.com/GPX/1/1"

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

    static let maxText = 100_000

    private static let pointFields: Set<String> = [
        "ele", "time", "name", "cmt", "desc", "src", "link", "url", "urlname", "sym", "type",
    ]
    private static let itemFields: Set<String> = ["name", "cmt", "desc", "src", "link", "url", "urlname", "number", "type"]

    /// Children each element understands; anything else under it is kept (or dropped) as a raw fragment.
    private static let knownChildren: [String: Set<String>] = [
        "gpx": [
            "metadata", "wpt", "rte", "trk", "name", "desc", "author", "email", "url", "urlname", "time", "keywords",
            "bounds",
        ],
        "metadata": ["name", "desc", "author", "link", "time", "keywords", "bounds"],
        "wpt": GpxReader.pointFields,
        "rtept": GpxReader.pointFields,
        "trkpt": ["ele", "time"],
        "rte": GpxReader.itemFields.union(["rtept"]),
        "trk": GpxReader.itemFields.union(["trkseg"]),
        "trkseg": ["trkpt"],
        "link": ["text", "type"],
        "author": ["name"],
    ]

    /// Elements that have a list to put unknown children in.
    private static let keepsExtras: Set<String> = ["gpx", "metadata", "wpt", "rtept", "trkpt", "rte", "trk"]

    /// GPX 1.0 point fields with no GPX 1.1 equivalent: dropped (speed and course can be computed from the points).
    private static let v10DroppedPointFields: Set<String> = ["speed", "course"]

    private final class PointBuilder {
        let lat: Double?
        let lon: Double?
        var ele: Double?
        var time: Int64?
        var name: String?
        var cmt: String?
        var desc: String?
        var src: String?
        var sym: String?
        var type: String?
        var links: [GpxLink] = []
        var extras: [RawXml] = []

        init(lat: Double?, lon: Double?) {
            self.lat = lat
            self.lon = lon
        }

        var isValid: Bool {
            guard let latitude = lat, let longitude = lon else { return false }
            return latitude.isFinite && longitude.isFinite && latitude >= -90.0 && latitude <= 90.0 &&
                longitude >= -180.0 && longitude <= 180.0
        }
    }

    private final class ItemBuilder {
        var name: String?
        var cmt: String?
        var desc: String?
        var src: String?
        var number: Int?
        var type: String?
        var links: [GpxLink] = []
        var extras: [RawXml] = []
        var points: [Waypoint] = []
        var segments: [[TrackPoint]] = []
    }

    /// Re-serialises one unknown element, with the namespace declarations it makes itself.
    private final class Capture {
        let discard: Bool
        var xml = ""
        var depth = 0
        private var text = ""
        private var textLength = 0

        init(discard: Bool) {
            self.discard = discard
        }

        func start(_ qName: String, _ attributes: SaxAttributes, _ declarations: [(prefix: String, uri: String)]) {
            if discard {
                depth += 1
                return
            }
            flushText()
            xml += "<" + qName
            for declaration in declarations {
                xml += " xmlns" + (declaration.prefix.isEmpty ? "" : ":" + declaration.prefix)
                xml += "=\"" + XmlText.attribute(declaration.uri) + "\""
            }
            for entry in attributes.entries {
                xml += " " + entry.qName + "=\"" + XmlText.attribute(entry.value) + "\""
            }
            xml += ">"
            depth += 1
        }

        func characters(_ chunk: String) {
            if !discard, textLength < GpxReader.maxText {
                text += chunk
                textLength += chunk.utf16.count
            }
        }

        func end(_ qName: String) {
            if !discard {
                flushText()
                xml += "</" + qName + ">"
            }
            depth -= 1
        }

        private func flushText() {
            if text.unicodeScalars.contains(where: { !KotlinText.isWhitespace($0) }) {
                xml += XmlText.text(text)
            }
            text = ""
            textLength = 0
        }
    }

    private final class Handler: SaxHandler {
        /// Set when the document is not GPX: every later event is ignored and `read` throws it.
        var failure: TrackFormatError?

        private let issues = IssueLog()
        private var namespaces: [String: String] = [:]
        private var pending: [(prefix: String, uri: String)] = []
        private var stack: [String] = []
        private var text = ""
        private var textLength = 0
        private var capture: Capture?

        private var version: String?
        private var creator: String?
        private var docName: String?
        private var docDesc: String?
        private var docAuthor: String?
        private var docKeywords: String?
        private var docTime: Int64?
        private var docLinks: [GpxLink] = []
        private var metadataExtras: [RawXml] = []
        private var rootExtras: [RawXml] = []
        private var waypoints: [Waypoint] = []
        private var routes: [Route] = []
        private var tracks: [Track] = []

        private var point: PointBuilder?
        private var item: ItemBuilder?
        private var segment: [TrackPoint]?
        private var linkHref: String?
        private var linkText: String?
        private var linkType: String?
        private var rootSeen = false

        func document() throws -> TrackDocument {
            if !rootSeen { throw TrackFormatError("Empty document: no <gpx> element") }
            return TrackDocument(
                name: docName,
                description: docDesc,
                author: docAuthor,
                keywords: docKeywords,
                timeMillis: docTime,
                creator: creator,
                version: version,
                links: docLinks,
                waypoints: waypoints,
                routes: routes,
                tracks: tracks,
                metadataExtras: metadataExtras,
                extras: rootExtras,
                namespaces: namespaces,
                issues: issues.toList(),
                format: .gpx
            )
        }

        override func startPrefixMapping(prefix: String, uri: String) {
            if failure != nil { return }
            pending.append((prefix: prefix, uri: uri))
            if capture == nil, !prefix.isEmpty, prefix != "xml", namespaces[prefix] == nil {
                namespaces[prefix] = uri
            }
        }

        override func startElement(uri: String, localName: String, qName: String, attributes: SaxAttributes) {
            if failure != nil { return }
            let declarations = pending
            pending.removeAll()
            let local = localName.isEmpty ? qName.substringAfter(":", missing: qName) : localName
            if let active = capture {
                active.start(qName, attributes, declarations)
                return
            }
            if stack.isEmpty {
                startRoot(local, uri, attributes)
                return
            }
            let parent = stack[stack.count - 1]
            let inGpx = uri.isEmpty || uri == GpxReader.nsGpx10 || uri == GpxReader.nsGpx11
            if !inGpx || !(GpxReader.knownChildren[parent]?.contains(local) ?? false) {
                startUnknown(parent, local, qName, attributes, declarations)
                return
            }
            stack.append(local)
            resetText()
            switch local {
            case "wpt", "rtept", "trkpt":
                let lat = attributes.value("lat").flatMap { kotlinDouble(kotlinTrim($0)) }
                let lon = attributes.value("lon").flatMap { kotlinDouble(kotlinTrim($0)) }
                point = PointBuilder(lat: lat, lon: lon)
            case "rte", "trk":
                item = ItemBuilder()
            case "trkseg":
                segment = []
            case "link":
                linkHref = attributes.value("href")
                linkText = nil
                linkType = nil
            default:
                break
            }
        }

        private func startUnknown(
            _ parent: String,
            _ local: String,
            _ qName: String,
            _ attributes: SaxAttributes,
            _ declarations: [(prefix: String, uri: String)]
        ) {
            let discard = !GpxReader.keepsExtras.contains(parent) ||
                (version == "1.0" && GpxReader.v10DroppedPointFields.contains(local))
            if discard { issues.add(.fieldNotKept, "\(parent)/\(local)") }
            let started = Capture(discard: discard)
            started.start(qName, attributes, declarations)
            capture = started
        }

        private func startRoot(_ local: String, _ ns: String, _ attributes: SaxAttributes) {
            if local != "gpx" {
                failure = TrackFormatError("Not a GPX file: root element is <\(local)>")
                return
            }
            rootSeen = true
            if ns == GpxReader.nsGpx10 {
                version = "1.0"
            } else {
                version = attributes.value("version").map { kotlinTrim($0) } ?? "1.1"
            }
            creator = attributes.value("creator")
            stack.append("gpx")
        }

        override func characters(_ chunk: String) {
            if failure != nil { return }
            if let active = capture {
                active.characters(chunk)
            } else if textLength < GpxReader.maxText {
                text += chunk
                textLength += chunk.utf16.count
            }
        }

        override func endElement(uri: String, localName: String, qName: String) {
            if failure != nil { return }
            if let active = capture {
                active.end(qName)
                if active.depth == 0 {
                    capture = nil
                    if !active.discard { attachExtra(RawXml(active.xml)) }
                }
                return
            }
            guard let local = stack.last else { return }
            let parent = stack.count >= 2 ? stack[stack.count - 2] : ""
            let value = kotlinTrim(text)
            resetText()
            endKnown(local, parent, value)
            stack.removeLast()
        }

        private func resetText() {
            text = ""
            textLength = 0
        }

        private func endKnown(_ local: String, _ parent: String, _ value: String) {
            switch local {
            case "ele":
                point?.ele = kotlinDouble(value).flatMap { $0.isFinite ? $0 : nil }
            case "time":
                setTime(parent, value)
            case "name":
                setName(parent, value)
            case "cmt":
                if isPoint(parent) { point?.cmt = value } else { item?.cmt = value }
            case "desc":
                setDesc(parent, value)
            case "src":
                if isPoint(parent) { point?.src = value } else { item?.src = value }
            case "sym":
                point?.sym = value
            case "type":
                setType(parent, value)
            case "number":
                item?.number = kotlinInt(value)
            case "keywords":
                docKeywords = value
            case "author":
                if parent == "gpx", !value.isEmpty { docAuthor = value }
            case "email":
                issues.add(.fieldNotKept, "gpx/email")
            case "url":
                appendLink(parent, GpxLink(href: value))
            case "urlname":
                renameLastLink(parent, value)
            case "text":
                if parent == "link" { linkText = value }
            case "link":
                if let href = linkHref { appendLink(parent, GpxLink(href: href, text: linkText, type: linkType)) }
            case "wpt", "rtept", "trkpt":
                endPoint(local)
            case "trkseg":
                endSegment()
            case "rte":
                endRoute()
            case "trk":
                endTrack()
            default:
                break
            }
        }

        private func isPoint(_ parent: String) -> Bool { parent == "wpt" || parent == "rtept" }

        /// Kotlin `linksOf(owner)?.add(link)`.
        private func appendLink(_ owner: String, _ link: GpxLink) {
            switch owner {
            case "gpx", "metadata": docLinks.append(link)
            case "wpt", "rtept": point?.links.append(link)
            case "rte", "trk": item?.links.append(link)
            default: break
            }
        }

        /// The GPX 1.0 `urlname` names the `url` just read.
        private func renameLastLink(_ owner: String, _ text: String) {
            func renamed(_ links: inout [GpxLink]) {
                guard let last = links.last else { return }
                links[links.count - 1] = GpxLink(href: last.href, text: text, type: last.type)
            }
            switch owner {
            case "gpx", "metadata":
                renamed(&docLinks)
            case "wpt", "rtept":
                if let p = point { renamed(&p.links) }
            case "rte", "trk":
                if let b = item { renamed(&b.links) }
            default:
                break
            }
        }

        private func setTime(_ parent: String, _ value: String) {
            let millis = TrackTime.parse(value)
            if millis == nil, !value.isEmpty { issues.add(.invalidTime, value) }
            if parent == "gpx" || parent == "metadata" { docTime = millis } else { point?.time = millis }
        }

        private func setName(_ parent: String, _ value: String) {
            switch parent {
            case "gpx", "metadata": docName = value
            case "author": docAuthor = value
            case "wpt", "rtept": point?.name = value
            case "rte", "trk": item?.name = value
            default: break
            }
        }

        private func setDesc(_ parent: String, _ value: String) {
            switch parent {
            case "gpx", "metadata": docDesc = value
            case "wpt", "rtept": point?.desc = value
            case "rte", "trk": item?.desc = value
            default: break
            }
        }

        private func setType(_ parent: String, _ value: String) {
            switch parent {
            case "wpt", "rtept": point?.type = value
            case "rte", "trk": item?.type = value
            case "link": linkType = value
            default: break
            }
        }

        private func attachExtra(_ raw: RawXml) {
            switch stack.last ?? "" {
            case "gpx": rootExtras.append(raw)
            case "metadata": metadataExtras.append(raw)
            case "wpt", "rtept", "trkpt": point?.extras.append(raw)
            case "rte", "trk": item?.extras.append(raw)
            default: break
            }
        }

        private func endPoint(_ kind: String) {
            guard let p = point else { return }
            point = nil
            guard p.isValid, let lat = p.lat, let lon = p.lon else {
                issues.add(
                    .invalidCoordinate,
                    "\(kind) lat=\(TrackNumberText.javaString(p.lat)) lon=\(TrackNumberText.javaString(p.lon))"
                )
                return
            }
            switch kind {
            case "trkpt":
                segment?.append(TrackPoint(lat: lat, lon: lon, ele: p.ele, timeMillis: p.time, extras: p.extras))
            case "rtept":
                item?.points.append(waypointOf(p, lat, lon))
            default:
                waypoints.append(waypointOf(p, lat, lon))
            }
        }

        private func waypointOf(_ p: PointBuilder, _ lat: Double, _ lon: Double) -> Waypoint {
            Waypoint(
                lat: lat,
                lon: lon,
                ele: p.ele,
                timeMillis: p.time,
                name: p.name,
                cmt: p.cmt,
                desc: p.desc,
                src: p.src,
                links: p.links,
                sym: p.sym,
                type: p.type,
                extras: p.extras
            )
        }

        private func endSegment() {
            guard let s = segment else { return }
            segment = nil
            if s.isEmpty {
                issues.add(.emptyItemDropped, "trkseg")
            } else {
                item?.segments.append(s)
            }
        }

        private func endRoute() {
            guard let b = item else { return }
            item = nil
            if b.points.isEmpty {
                issues.add(.emptyItemDropped, "rte \(b.name ?? "")")
                return
            }
            routes.append(
                Route(
                    name: b.name,
                    cmt: b.cmt,
                    desc: b.desc,
                    src: b.src,
                    links: b.links,
                    number: b.number,
                    type: b.type,
                    points: b.points,
                    extras: b.extras,
                    style: ExtensionStyle.fromExtras(b.extras)
                )
            )
        }

        private func endTrack() {
            guard let b = item else { return }
            item = nil
            if b.segments.isEmpty {
                issues.add(.emptyItemDropped, "trk \(b.name ?? "")")
                return
            }
            tracks.append(
                Track(
                    name: b.name,
                    cmt: b.cmt,
                    desc: b.desc,
                    src: b.src,
                    links: b.links,
                    number: b.number,
                    type: b.type,
                    segments: b.segments,
                    extras: b.extras,
                    style: ExtensionStyle.fromExtras(b.extras)
                )
            )
        }
    }
}
