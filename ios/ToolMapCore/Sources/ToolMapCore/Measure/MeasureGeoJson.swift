// Port of core/src/main/kotlin/fr/toolmap/core/measure/MeasureGeoJson.kt.

/// What the tool measures: the length of a line, or the area of the polygon through the points.
public enum MeasureMode: String, Codable, CaseIterable, Sendable {
    case path = "PATH"
    case area = "AREA"
}

/// GeoJSON of the measure overlay: the line (closed in area mode), the fill, and the vertices.
public enum MeasureGeoJson {
    private static let empty = "{\"type\":\"FeatureCollection\",\"features\":[]}"
    private static let minPolygonPoints = 3

    public static func line(_ points: [TrackPoint], _ mode: MeasureMode) -> String {
        if mode == .area, points.count >= minPolygonPoints {
            return EditorGeoJson.line(points + [points[0]])
        }
        return EditorGeoJson.line(points)
    }

    /// The polygon to fill, only in area mode with three points or more.
    public static func fill(_ points: [TrackPoint], _ mode: MeasureMode) -> String {
        if mode != .area || points.count < minPolygonPoints { return empty }
        let ring = points + [points[0]]
        var out = ""
        out.reserveCapacity(ring.count * 24 + 96)
        out += "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{}"
        out += ",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[["
        for (i, p) in ring.enumerated() {
            if i > 0 { out += "," }
            out += "["
            TrackGeoJson.appendDegrees(&out, p.lon)
            out += ","
            TrackGeoJson.appendDegrees(&out, p.lat)
            out += "]"
        }
        out += "]]}}]}"
        return out
    }

    public static func vertices(_ points: [TrackPoint]) -> String {
        EditorGeoJson.handles(points, selected: nil, midpoints: false)
    }
}
