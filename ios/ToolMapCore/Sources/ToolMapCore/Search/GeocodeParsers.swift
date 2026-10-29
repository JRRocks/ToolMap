// Port of core/src/main/kotlin/fr/toolmap/core/search/GeocodeParsers.kt.

import Foundation

/// Reads the answers of the two geocoders into `SearchResult`s. A reply that is not what the service documents gives
/// nil (the caller says "unreadable answer"); an entry missing its position or its name is skipped, the others are
/// kept: a service adding or dropping a field must not make the whole search fail.
///
/// JSON is read by `JSONSerialization`; a value is read as kotlinx.serialization's `JsonPrimitive` would be: a number
/// or a string holding a number both give a number, `null` gives nothing.
public enum GeocodeParsers {
    private static let maxLat = 90.0
    private static let maxLon = 180.0
    private static let nominatimTitleParts = 3

    /// A box smaller than this on both sides (about 100 m) is a point with a margin: nothing worth framing.
    private static let minSpanDegrees = 0.001

    /// `GET https://data.geopf.fr/geocodage/search`: a GeoJSON FeatureCollection, for the `poi` or `address` index.
    public static func parseIgn(_ text: String) -> [SearchResult]? {
        guard let root = parse(text) as? [String: Any], let features = root["features"] as? [Any] else { return nil }
        return features.compactMap { ignResult($0 as? [String: Any]) }
    }

    /// `GET https://nominatim.openstreetmap.org/search?format=jsonv2`: an array of places.
    public static func parseNominatim(_ text: String) -> [SearchResult]? {
        guard let places = parse(text) as? [Any] else { return nil }
        return places.compactMap { nominatimResult($0 as? [String: Any]) }
    }

    private static func parse(_ text: String) -> Any? {
        try? JSONSerialization.jsonObject(with: Data(text.utf8), options: [.fragmentsAllowed])
    }

    private static func ignResult(_ feature: [String: Any]?) -> SearchResult? {
        guard let feature,
              let geometry = feature["geometry"] as? [String: Any],
              let coordinates = geometry["coordinates"] as? [Any],
              coordinates.count >= 2,
              let lon = number(coordinates[0]),
              let lat = number(coordinates[1])
        else { return nil }
        if !valid(lat, lon) { return nil }
        guard let properties = feature["properties"] as? [String: Any] else { return nil }
        let isPoi = strings(properties, "_type").first == "poi" || properties["toponym"] != nil
        let titleText = strings(properties, "toponym").first
            ?? strings(properties, "name").first
            ?? strings(properties, "label").first
        guard let title = titleText else { return nil }
        let place = [strings(properties, "postcode").first, strings(properties, "city").first].compactMap { $0 }
        let category = isPoi ? strings(properties, "category").first : nil
        // The category is not repeated here: the list shows it in front of the subtitle, as for OpenStreetMap.
        let joined = place.joined(separator: " ")
        let subtitle = kotlinTrim(joined).isEmpty ? (strings(properties, "context").first ?? "") : joined
        return SearchResult(title: title, subtitle: subtitle, lat: lat, lon: lon, source: .ign, category: category)
    }

    private static func nominatimResult(_ place: [String: Any]?) -> SearchResult? {
        guard let place,
              let lat = place["lat"].flatMap(number),
              let lon = place["lon"].flatMap(number)
        else { return nil }
        if !valid(lat, lon) { return nil }
        let display = strings(place, "display_name").first ?? ""
        let parts = display.components(separatedBy: ", ").map(kotlinTrim).filter { !$0.isEmpty }
        let named = strings(place, "name").first.flatMap { kotlinTrim($0).isEmpty ? nil : $0 }
        guard let title = named ?? parts.first else { return nil }
        let rest = parts.first == title ? Array(parts.dropFirst()) : parts
        // The first few places say where it is, the last one says which country.
        let location: [String]
        if rest.count > nominatimTitleParts + 1 {
            location = Array(rest.prefix(nominatimTitleParts)) + [rest[rest.count - 1]]
        } else {
            location = rest
        }
        // In French since P29.10 (« Château fort », not « castle »).
        let category = OsmTypes.french(strings(place, "type").first)
        return SearchResult(
            title: title,
            subtitle: location.joined(separator: ", "),
            lat: lat,
            lon: lon,
            source: .osm,
            category: category,
            bounds: nominatimBounds(place)
        )
    }

    /// `boundingbox` is [south, north, west, east] as strings; the tiny box of a point or a building is dropped.
    private static func nominatimBounds(_ place: [String: Any]) -> Bounds? {
        guard let values = place["boundingbox"] as? [Any] else { return nil }
        let box = values.compactMap { number($0) }
        if box.count != 4 { return nil }
        let south = box[0]
        let north = box[1]
        let west = box[2]
        let east = box[3]
        if !(valid(south, west) && valid(north, east)) { return nil }
        let framed = east > west && north > south &&
            (east - west >= minSpanDegrees || north - south >= minSpanDegrees)
        return framed ? Bounds(minX: west, minY: south, maxX: east, maxY: north) : nil
    }

    private static func valid(_ lat: Double, _ lon: Double) -> Bool {
        lat >= -maxLat && lat <= maxLat && lon >= -maxLon && lon <= maxLon
    }

    /// A field that is a string or an array of strings, as the IGN index for addresses and for places differ.
    private static func strings(_ object: [String: Any], _ key: String) -> [String] {
        guard let value = object[key] else { return [] }
        if let array = value as? [Any] {
            return array.compactMap { content($0) }
        }
        if let text = content(value) {
            return [text]
        }
        return []
    }

    /// kotlinx `JsonPrimitive.contentOrNull`: the text of a string, number or boolean; nil for `null` or a structure.
    private static func content(_ value: Any) -> String? {
        if value is NSNull { return nil }
        if let text = value as? String { return text }
        if let number = value as? NSNumber {
            if isBoolean(number) { return number.boolValue ? "true" : "false" }
            return number.stringValue
        }
        return nil
    }

    /// kotlinx `JsonPrimitive.doubleOrNull`: a number, or a string that holds one.
    private static func number(_ value: Any) -> Double? {
        if value is NSNull { return nil }
        if let text = value as? String { return kotlinDouble(text) }
        if let number = value as? NSNumber {
            return isBoolean(number) ? nil : number.doubleValue
        }
        return nil
    }

    private static func isBoolean(_ number: NSNumber) -> Bool {
        CFGetTypeID(number as CFTypeRef) == CFBooleanGetTypeID()
    }
}
