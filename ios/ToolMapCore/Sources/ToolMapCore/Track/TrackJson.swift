// Port of core/src/main/kotlin/fr/toolmap/core/track/TrackJson.kt.

import Foundation

/// JSON columns of the track database: small lists whose shape may grow, kept out of the schema. Same keys as
/// kotlinx.serialization (unknown keys ignored, values equal to their default left out), so that a database written by
/// one app reads in the other.
public enum TrackJson {
    private static func encoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.withoutEscapingSlashes]
        return encoder
    }

    private static func encode<T: Encodable>(_ value: T) throws -> String {
        let data = try encoder().encode(value)
        return String(decoding: data, as: UTF8.self)
    }

    private static func decode<T: Decodable>(_ type: T.Type, _ text: String) throws -> T {
        try JSONDecoder().decode(type, from: Data(text.utf8))
    }

    public static func encodeWaypoints(_ list: [Waypoint]) throws -> String {
        try encode(list)
    }

    public static func decodeWaypoints(_ text: String?) throws -> [Waypoint] {
        guard let text, !text.isEmpty else { return [] }
        return try decode([Waypoint].self, text)
    }

    public static func encodeLinks(_ list: [GpxLink]) throws -> String? {
        if list.isEmpty { return nil }
        return try encode(list)
    }

    public static func decodeLinks(_ text: String?) throws -> [GpxLink] {
        guard let text, !text.isEmpty else { return [] }
        return try decode([GpxLink].self, text)
    }

    public static func encodeExtras(_ list: [RawXml]) throws -> String? {
        if list.isEmpty { return nil }
        return try encode(list)
    }

    public static func decodeExtras(_ text: String?) throws -> [RawXml] {
        guard let text, !text.isEmpty else { return [] }
        return try decode([RawXml].self, text)
    }

    public static func encodeNamespaces(_ map: [String: String]) throws -> String? {
        if map.isEmpty { return nil }
        return try encode(map)
    }

    public static func decodeNamespaces(_ text: String?) throws -> [String: String] {
        guard let text, !text.isEmpty else { return [:] }
        return try decode([String: String].self, text)
    }
}
