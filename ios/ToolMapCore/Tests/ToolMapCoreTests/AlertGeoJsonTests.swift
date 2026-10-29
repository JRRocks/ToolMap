// Port of core/src/test/kotlin/fr/toolmap/core/alert/AlertGeoJsonTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct AlertGeoJsonTests {
    private let place = WatchedPlace(id: 7, name: "Refuge", lat: 45.0, lon: 6.0, radiusMeters: 250.0)

    private func features(_ text: String) throws -> [[String: Any]] {
        let parsed = try JSONSerialization.jsonObject(with: Data(text.utf8))
        let root = try #require(parsed as? [String: Any])
        return try #require(root["features"] as? [[String: Any]])
    }

    /// The rings of the features, as `(lon, lat)`.
    private func rings(_ text: String) throws -> [[(lon: Double, lat: Double)]] {
        var out: [[(lon: Double, lat: Double)]] = []
        for feature in try features(text) {
            let geometry = try #require(feature["geometry"] as? [String: Any])
            let coordinates = try #require(geometry["coordinates"] as? [[[Double]]])
            let ring = try #require(coordinates.first)
            out.append(ring.map { (lon: $0[0], lat: $0[1]) })
        }
        return out
    }

    @Test("no place gives an empty collection")
    func noPlace() throws {
        let parsed = try JSONSerialization.jsonObject(with: Data(AlertGeoJson.circles([]).utf8))
        let root = try #require(parsed as? [String: Any])
        #expect(root["type"] as? String == "FeatureCollection")
        let list = try #require(root["features"] as? [Any])
        #expect(list.isEmpty)
    }

    @Test("a circle is a closed ring of points at the radius from the centre")
    func closedRing() throws {
        let all = try rings(AlertGeoJson.circles([place]))
        try #require(all.count == 1)
        let ring = all[0]
        try #require(ring.count == 49)
        #expect(ring[0].lon == ring[48].lon && ring[0].lat == ring[48].lat, "ring closed")
        for p in ring {
            let d = Geodesy.distance(place.lat, place.lon, p.lat, p.lon)
            #expect(abs(d - 250.0) <= 0.5)
        }
    }

    @Test("each place has its own ring with its own radius and id")
    func ownRingAndId() throws {
        let big = WatchedPlace(id: 8, name: nil, lat: 46.0, lon: 7.0, radiusMeters: 1000.0)
        let text = AlertGeoJson.circles([place, big])
        let found = try rings(text)
        try #require(found.count == 2)
        let d = Geodesy.distance(46.0, 7.0, found[1][5].lat, found[1][5].lon)
        #expect(abs(d - 1000.0) <= 1.0)
        var ids: [Int64] = []
        for feature in try features(text) {
            let properties = try #require(feature["properties"] as? [String: Any])
            let id = try #require(properties["id"] as? NSNumber)
            ids.append(id.int64Value)
        }
        #expect(ids == [7, 8])
    }
}
