// Port of core/src/main/kotlin/fr/toolmap/core/track/GeoJsonReader.kt.

/// Reads GeoJSON (RFC 7946): `Point`, `MultiPoint`, `LineString`, `MultiLineString`, `Polygon` (outer ring, as a
/// route), `MultiPolygon`, `GeometryCollection`, `Feature`, `FeatureCollection`. Names come from the `name` / `title`
/// property; the `stroke` and `stroke-width` properties of the simplestyle convention give the line style.
public enum GeoJsonReader {
    /// Throws `TrackFormatError`.
    public static func read(_ text: String) throws -> TrackDocument {
        let root: TrackJsonNode
        do {
            root = try TrackJsonParser.parse(text)
        } catch {
            let message = (error as? TrackJsonError)?.message ?? "\(error)"
            throw TrackFormatError("Not a readable GeoJSON file: \(message)")
        }
        guard let obj = root.members else {
            throw TrackFormatError("Not a GeoJSON file: the top level is not an object")
        }
        let reader = Reader()
        reader.visit(obj, nil)
        if !reader.recognised {
            throw TrackFormatError("Not a GeoJSON file: no \"type\" of geometry or feature")
        }
        return TrackDocument(
            name: string(obj["name"]),
            waypoints: reader.waypoints,
            routes: reader.routes,
            tracks: reader.tracks,
            issues: reader.issues.toList(),
            format: .geojson
        )
    }

    /// Kotlin `JsonElement?.string()`: the content of a primitive that is not `null`.
    private static func string(_ element: TrackJsonNode?) -> String? {
        element?.content
    }

    /// Kotlin `JsonElement.number()`: `doubleOrNull` of a primitive.
    private static func number(_ element: TrackJsonNode?) -> Double? {
        element?.double
    }

    private final class Reader {
        let issues = IssueLog()
        var waypoints: [Waypoint] = []
        var routes: [Route] = []
        var tracks: [Track] = []
        var recognised = false

        func visit(_ node: [String: TrackJsonNode], _ properties: [String: TrackJsonNode]?) {
            let type = GeoJsonReader.string(node["type"])
            switch type ?? "" {
            case "FeatureCollection":
                recognised = true
                for feature in node["features"]?.elements ?? [] {
                    if let f = feature.members { visit(f, nil) }
                }
            case "Feature":
                recognised = true
                guard let shape = node["geometry"]?.members else { return }
                visit(shape, node["properties"]?.members)
            case "GeometryCollection":
                recognised = true
                for child in node["geometries"]?.elements ?? [] {
                    if let g = child.members { visit(g, properties) }
                }
            case "Point", "MultiPoint", "LineString", "MultiLineString", "Polygon", "MultiPolygon":
                recognised = true
                geometry(type ?? "", node["coordinates"], properties)
            default:
                issues.add(.unsupportedElement, type ?? "")
            }
        }

        private func geometry(_ type: String, _ coordinates: TrackJsonNode?, _ props: [String: TrackJsonNode]?) {
            let name = GeoJsonReader.string(props?["name"]) ?? GeoJsonReader.string(props?["title"])
            let desc = GeoJsonReader.string(props?["description"]) ?? GeoJsonReader.string(props?["desc"])
            let time = TrackTime.parse(GeoJsonReader.string(props?["time"]))
            let style = lineStyle(props)
            switch type {
            case "Point":
                if let p = position(coordinates) { waypoints.append(waypoint(p, name, desc, time)) }
            case "MultiPoint":
                for p in line(coordinates) {
                    waypoints.append(waypoint(p, name, desc, time))
                }
            case "LineString":
                addTrack(name, desc, [line(coordinates, props)], style)
            case "MultiLineString":
                addTrack(name, desc, array(coordinates).map { line($0, nil) }, style)
            case "Polygon":
                addRing(name, desc, array(coordinates).first, style)
            case "MultiPolygon":
                for polygon in array(coordinates) {
                    addRing(name, desc, array(polygon).first, style)
                }
            default:
                break
            }
        }

        private func waypoint(_ p: TrackPoint, _ name: String?, _ desc: String?, _ time: Int64?) -> Waypoint {
            Waypoint(lat: p.lat, lon: p.lon, ele: p.ele, timeMillis: time, name: name, desc: desc)
        }

        private func addTrack(_ name: String?, _ desc: String?, _ segments: [[TrackPoint]], _ style: PathStyle?) {
            let usable = segments.filter { !$0.isEmpty }
            if usable.isEmpty {
                issues.add(.emptyItemDropped, name ?? "")
            } else {
                tracks.append(Track(name: name, desc: desc, segments: usable, style: style))
            }
        }

        private func addRing(_ name: String?, _ desc: String?, _ ring: TrackJsonNode?, _ style: PathStyle?) {
            let points = line(ring).map { Waypoint(lat: $0.lat, lon: $0.lon, ele: $0.ele) }
            if points.isEmpty { return }
            issues.add(.polygonAsRoute, name ?? "")
            routes.append(Route(name: name, desc: desc, points: points, style: style))
        }

        private func array(_ e: TrackJsonNode?) -> [TrackJsonNode] {
            e?.elements ?? []
        }

        /// A list of positions; `coordTimes` (togeojson) gives their times.
        private func line(_ e: TrackJsonNode?, _ props: [String: TrackJsonNode]? = nil) -> [TrackPoint] {
            let times = props?["coordTimes"]?.elements?.map { TrackTime.parse(GeoJsonReader.string($0)) }
            var out: [TrackPoint] = []
            for (i, item) in array(e).enumerated() {
                guard let p = position(item) else {
                    issues.add(.invalidCoordinate, "position \(i)")
                    continue
                }
                if let times {
                    let time: Int64? = i < times.count ? times[i] : nil
                    out.append(TrackPoint(lat: p.lat, lon: p.lon, ele: p.ele, timeMillis: time, extras: p.extras))
                } else {
                    out.append(p)
                }
            }
            return out
        }

        /// `[lon, lat]` or `[lon, lat, altitude]`.
        private func position(_ e: TrackJsonNode?) -> TrackPoint? {
            guard let a = e?.elements, a.count >= 2 else { return nil }
            guard let lon = GeoJsonReader.number(a[0]), let lat = GeoJsonReader.number(a[1]) else { return nil }
            if !lat.isFinite || !lon.isFinite || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0 { return nil }
            let ele = a.count > 2 ? GeoJsonReader.number(a[2]) : nil
            return TrackPoint(lat: lat, lon: lon, ele: ele)
        }

        private func lineStyle(_ props: [String: TrackJsonNode]?) -> PathStyle? {
            let color = GeoJsonReader.string(props?["stroke"]).flatMap { ExtensionStyle.parseColor($0) }
            let width = GeoJsonReader.number(props?["stroke-width"]).map { Float($0) }
            if color == nil && width == nil { return nil }
            return PathStyle(colorArgb: color ?? PathStyle.defaultColor, widthDp: width ?? PathStyle.defaultWidth)
        }
    }
}
