// Port of core/src/main/kotlin/fr/toolmap/core/alert/AlertGeoJson.kt.

import Foundation

/// GeoJSON of what the alerts watch, for the map.
public enum AlertGeoJson {
    private static let earthRadiusMeters = 6_371_008.8
    private static let empty = #"{"type":"FeatureCollection","features":[]}"#
    private static let steps = 48

    /// One polygon per watched place: the circle of its radius, as a ring of `steps` points.
    public static func circles(_ places: [WatchedPlace]) -> String {
        if places.isEmpty { return empty }
        var features: [String] = []
        features.reserveCapacity(places.count)
        for place in places {
            var ring: [String] = []
            ring.reserveCapacity(steps + 1)
            for i in 0 ... steps {
                ring.append(coordinate(place, i == steps ? 0 : i))
            }
            let feature = #"{"type":"Feature","properties":{"id":"# + String(place.id)
                + #"},"geometry":{"type":"Polygon","coordinates":[["# + ring.joined(separator: ",") + "]]}}"
            features.append(feature)
        }
        return #"{"type":"FeatureCollection","features":["# + features.joined(separator: ",") + "]}"
    }

    /// The point at `step` of `steps` around the place, at the place's radius, written `[lon,lat]`.
    private static func coordinate(_ place: WatchedPlace, _ step: Int) -> String {
        let bearing = Angle.radians(Double(step) * 360.0 / Double(steps))
        let angular = place.radiusMeters / earthRadiusMeters
        let lat1 = Angle.radians(place.lat)
        let lon1 = Angle.radians(place.lon)
        let sinLat2 = sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing)
        let lat2 = asin(sinLat2)
        let y = sin(bearing) * sin(angular) * cos(lat1)
        let x = cos(angular) - sin(lat1) * sin(lat2)
        let lon2 = lon1 + atan2(y, x)
        return "[" + TrackNumberText.fixed(Angle.degrees(lon2), 7) + "," + TrackNumberText.fixed(Angle.degrees(lat2), 7) + "]"
    }
}
