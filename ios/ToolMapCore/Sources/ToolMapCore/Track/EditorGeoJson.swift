// Port of core/src/main/kotlin/fr/toolmap/core/track/EditorGeoJson.kt.

/// GeoJSON of the line being drawn or edited: the line itself and its handles. Handles carry a `kind` the map layer
/// styles by: `v` vertex, `s` selected vertex, `a` first, `z` last, `m` midpoint (tap to insert a vertex there).
public enum EditorGeoJson {
    /// Beyond this many vertices only the ends and the selected one are drawn: thousands of circles are no help.
    public static let maxHandles = 1500

    private static let empty = "{\"type\":\"FeatureCollection\",\"features\":[]}"

    public static func line(_ points: [TrackPoint]) -> String {
        if points.count < 2 { return empty }
        var out = ""
        out.reserveCapacity(points.count * 24 + 96)
        out += "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{}"
        out += ",\"geometry\":{\"type\":\"LineString\",\"coordinates\":["
        for (i, p) in points.enumerated() {
            if i > 0 { out += "," }
            out += "["
            TrackGeoJson.appendDegrees(&out, p.lon)
            out += ","
            TrackGeoJson.appendDegrees(&out, p.lat)
            out += "]"
        }
        out += "]}}]}"
        return out
    }

    public static func handles(_ points: [TrackPoint], selected: Int?, midpoints: Bool = true) -> String {
        if points.isEmpty { return empty }
        let all = points.count <= maxHandles
        var out = ""
        out.reserveCapacity(points.count * 80 + 96)
        out += "{\"type\":\"FeatureCollection\",\"features\":["
        var first = true

        func feature(_ kind: String, _ index: Int, _ lat: Double, _ lon: Double) {
            if !first { out += "," }
            first = false
            out += "{\"type\":\"Feature\",\"properties\":{\"kind\":\"" + kind
            out += "\",\"i\":" + String(index) + "},\"geometry\":{\"type\":\"Point\",\"coordinates\":["
            TrackGeoJson.appendDegrees(&out, lon)
            out += ","
            TrackGeoJson.appendDegrees(&out, lat)
            out += "]}}"
        }
        if all, midpoints, points.count > 1 {
            for i in 0 ..< points.count - 1 {
                let m = TrackOps.midpoint(points[i], points[i + 1])
                feature("m", i, m.lat, m.lon)
            }
        }
        let lastIndex = points.count - 1
        for (i, p) in points.enumerated() {
            let kind: String
            if i == selected {
                kind = "s"
            } else if i == 0 {
                kind = "a"
            } else if i == lastIndex {
                kind = "z"
            } else if all {
                kind = "v"
            } else {
                continue
            }
            feature(kind, i, p.lat, p.lon)
        }
        out += "]}"
        return out
    }

    /// What a tap at (`lat`, `lon`) means: the vertex within `reachMeters` (nearest wins), else the midpoint handle
    /// within reach, else a new vertex at that place.
    public static func interpret(_ points: [TrackPoint], _ lat: Double, _ lon: Double, reachMeters: Double) -> TapAction {
        var best = -1
        var bestDistance = reachMeters
        for i in points.indices {
            let d = Geodesy.distance(lat, lon, points[i].lat, points[i].lon)
            if d <= bestDistance {
                bestDistance = d
                best = i
            }
        }
        if best >= 0 { return .select(index: best) }
        if points.count >= 2, points.count <= maxHandles {
            var mid = -1
            var midDistance = reachMeters
            for i in 0 ..< points.count - 1 {
                let m = TrackOps.midpoint(points[i], points[i + 1])
                let d = Geodesy.distance(lat, lon, m.lat, m.lon)
                if d <= midDistance {
                    midDistance = d
                    mid = i
                }
            }
            if mid >= 0 { return .insertMidpoint(segment: mid) }
        }
        return .add
    }

    public enum TapAction: Equatable, Hashable, Sendable {
        case select(index: Int)
        case insertMidpoint(segment: Int)
        case add
    }
}
