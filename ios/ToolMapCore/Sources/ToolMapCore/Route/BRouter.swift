// Port of core/src/main/kotlin/fr/toolmap/core/route/BRouter.kt.

import Foundation

/// Reads the GeoJSON BRouter gives (`trackFormat=json` of the Android service, `format=geojson` of its web server: the
/// same writer): one feature, a LineString of `[lon, lat, ele]` (the elevation is left out where BRouter has none) and
/// string properties such as `track-length` and `total-time` (s).
public enum BRouterJson {
    private static let millisPerSecond = 1000.0

    /// The way and its time, or `nil` when the text is not such a document.
    public static func parse(_ text: String) -> LegResult.Ok? {
        guard let root = try? JSONDecoder().decode(BRouterJsonValue.self, from: Data(text.utf8)),
              case let .object(rootObject) = root,
              case let .array(features)? = rootObject["features"],
              case let .object(feature)? = features.first,
              case let .object(geometry)? = feature["geometry"],
              case let .array(coordinates)? = geometry["coordinates"]
        else { return nil }
        var points: [TrackPoint] = []
        points.reserveCapacity(coordinates.count)
        for c in coordinates {
            guard case let .array(v) = c, v.count >= 2, let lon = v[0].doubleValue, let lat = v[1].doubleValue else { continue }
            points.append(TrackPoint(lat: lat, lon: lon, ele: v.count > 2 ? v[2].doubleValue : nil))
        }
        if points.isEmpty { return nil }
        var seconds: Double?
        if case let .object(properties)? = feature["properties"], let value = properties["total-time"] {
            switch value {
            case let .number(n): seconds = n
            case let .string(s): seconds = kotlinDouble(kotlinTrim(s))
            default: seconds = nil
            }
        }
        return LegResult.Ok(points, seconds.map { Int64(kotlinRound($0 * millisPerSecond)) })
    }
}

/// A JSON value as kotlinx.serialization's `JsonElement` sees it: a number is not a boolean, and a string holding a
/// number reads as that number (`doubleOrNull`).
private enum BRouterJsonValue: Decodable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([BRouterJsonValue])
    case object([String: BRouterJsonValue])

    /// Numbers and arrays are tried first: a way is thousands of `[lon, lat, ele]`. JSONDecoder reads neither a boolean
    /// as a number nor a number as a boolean, so the order changes nothing else.
    init(from decoder: any Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() {
            self = .null
        } else if let n = try? c.decode(Double.self) {
            self = .number(n)
        } else if let a = try? c.decode([BRouterJsonValue].self) {
            self = .array(a)
        } else if let s = try? c.decode(String.self) {
            self = .string(s)
        } else if let b = try? c.decode(Bool.self) {
            self = .bool(b)
        } else {
            self = try .object(c.decode([String: BRouterJsonValue].self))
        }
    }

    /// Kotlin `jsonPrimitive.doubleOrNull`.
    var doubleValue: Double? {
        switch self {
        case let .number(n): n
        case let .string(s): kotlinDouble(s)
        default: nil
        }
    }
}

/// Sorts the messages BRouter gives instead of a way (texts of its `RoutingEngine` and `BRouterWorker`).
public enum BRouterErrors {
    public static func classify(_ message: String) -> RoutingError {
        let m = message.lowercased()
        if m.contains("does not exist") && m.contains("profile") { return .unknownProfile }
        if m.contains(".rd5") || m.contains("datafile") || m.contains("segment directory") || m.contains("no routing data") {
            return .noData
        }
        if m.contains("no track found") || m.contains("island detected") || m.contains("restricted area") { return .noPath }
        if m.contains("operation killed") || m.contains("timeout") || m.contains("timed out") { return .timeout }
        return .other
    }
}
