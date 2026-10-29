// Port of core/src/test/kotlin/fr/toolmap/core/route/BRouterTest.kt.

import Testing
@testable import ToolMapCore

struct BRouterTests {
    /// The shape of BRouter's `FormatJson` output (abridged).
    static let sample = """
    {
      "type": "FeatureCollection",
      "features": [
        {
          "type": "Feature",
          "properties": {
            "creator": "BRouter-1.7.8",
            "name": "brouter_hiking-mountain_0",
            "track-length": "1503",
            "filtered ascend": "212",
            "plain-ascend": "198",
            "total-time": "2710",
            "total-energy": "0",
            "cost": "4123",
            "messages": [["Longitude", "Latitude", "Elevation"], ["6800000", "45900000", "1200"]]
          },
          "geometry": {
            "type": "LineString",
            "coordinates": [
              [6.8, 45.9, 1200.25],
              [6.801, 45.901],
              [6.802, 45.902, 1250.0]
            ]
          }
        }
      ]
    }
    """

    @Test("reads the way, the elevations and the time")
    func readsTheWay() throws {
        let ok = try #require(BRouterJson.parse(BRouterTests.sample))
        #expect(ok.points.count == 3)
        #expect(ok.points[0] == TrackPoint(lat: 45.9, lon: 6.8, ele: 1200.25))
        #expect(ok.points[1].ele == nil)
        #expect(ok.timeMillis == 2_710_000)
    }

    @Test("anything else is not a way")
    func notAWay() {
        #expect(BRouterJson.parse("datafile E5_N45.rd5 not found") == nil)
        #expect(BRouterJson.parse(#"{"type":"FeatureCollection","features":[]}"#) == nil)
        #expect(BRouterJson.parse(#"{"features":[{"geometry":{"coordinates":[]}}]}"#) == nil)
    }

    @Test("sorts the messages of BRouter")
    func sortsMessages() {
        #expect(BRouterErrors.classify("datafile E5_N45.rd5 not found") == .noData)
        #expect(BRouterErrors.classify("segment directory /storage/emulated/0/brouter/segments4 does not exist") == .noData)
        #expect(BRouterErrors.classify("Profile mtb-perso does not exists") == .unknownProfile)
        #expect(BRouterErrors.classify("no track found at pass=0") == .noPath)
        #expect(BRouterErrors.classify("start island detected for section 0") == .noPath)
        #expect(BRouterErrors.classify("target island detected for section 0") == .noPath)
        #expect(BRouterErrors.classify("a wpt in restricted area") == .noPath)
        #expect(BRouterErrors.classify("operation killed by thread-priority-watchdog after 30 seconds") == .timeout)
        #expect(BRouterErrors.classify("memory limit reached") == .other)
    }

    @Test("a way that starts on a road away from the stop is joined to it")
    func joinedToTheStop() {
        let from = RouteStop(lat: 45.9, lon: 6.8)
        let to = RouteStop(lat: 45.91, lon: 6.81)
        let way = LegResult.Ok(
            [TrackPoint(lat: 45.9005, lon: 6.8, ele: 1000.0), TrackPoint(lat: 45.91, lon: 6.81, ele: 1100.0)],
            1000
        )
        let leg = RoutePlanEditor.legOf(from, to, RoutingProfiles.hiking, way)
        let expected = [from.point, TrackPoint(lat: 45.9005, lon: 6.8, ele: 1000.0), TrackPoint(lat: 45.91, lon: 6.81, ele: 1100.0)]
        #expect(leg.points == expected)
        #expect(leg.state == .ok)
        #expect(leg.timeMillis == 1000)
    }
}
