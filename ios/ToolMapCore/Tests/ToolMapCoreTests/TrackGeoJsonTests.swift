// Port of core/src/test/kotlin/fr/toolmap/core/track/TrackGeoJsonTest.kt. The JSON is read back with the reader of
// GeoJsonReader (`TrackJsonParser`), which keeps the text of the numbers as kotlinx's `JsonPrimitive.content` does.

import Foundation
import Testing
@testable import ToolMapCore

struct TrackGeoJsonTests {
    private func features(_ text: String) throws -> [TrackJsonNode] {
        let root = try TrackJsonParser.parse(text)
        return try #require(root["features"]?.elements)
    }

    @Test("simplification keeps the ends and the corners and drops collinear points")
    func simplification() throws {
        let straight = (0 ..< 1000).map { TrackPoint(lat: 45.0, lon: 6.0 + Double($0) * 1e-4) }
        #expect(PathSimplifier.simplify(straight, 1.0) == [straight[0], straight[999]])

        let east: [TrackPoint] = (0 ..< 50).map { TrackPoint(lat: 45.0, lon: 6.0 + Double($0) * 1e-4) }
        let north: [TrackPoint] = (0 ..< 50).map { TrackPoint(lat: 45.0 + Double($0 + 1) * 1e-4, lon: 6.0049) }
        let corner = east + north
        let simplified = PathSimplifier.simplify(corner, 1.0)
        try #require(simplified.count == 3)
        #expect(simplified[1] == corner[49])

        #expect(PathSimplifier.simplify(corner, 0.0) == corner)
        #expect(PathSimplifier.simplify(Array(corner.prefix(2)), 5.0).count == 2)
    }

    @Test("a wiggle larger than the tolerance is kept, a smaller one is not")
    func wiggle() {
        let wiggle = [TrackPoint(lat: 45.0, lon: 6.0), TrackPoint(lat: 45.00001, lon: 6.0005), TrackPoint(lat: 45.0, lon: 6.001)]
        #expect(PathSimplifier.simplify(wiggle, 0.5).count == 3)
        #expect(PathSimplifier.simplify(wiggle, 5.0).count == 2)
    }

    @Test("lines become features with their style")
    func lineStyle() throws {
        let line = LineFeature(
            id: 42,
            colorArgb: Int32(bitPattern: 0x80FF_8800),
            widthDp: 4,
            dashed: true,
            segments: [
                [TrackPoint(lat: 45.5, lon: 6.25), TrackPoint(lat: 45.75, lon: 6.5)],
                [TrackPoint(lat: 1.0, lon: 2.0), TrackPoint(lat: 3.0, lon: 4.0)],
            ],
            arrows: true
        )
        let list = try features(TrackGeoJson.lines([line]))
        #expect(list.count == 1)
        let f = try #require(list.first)
        let props = try #require(f["properties"])
        #expect(props["id"]?.content.flatMap { Int($0) } == 42)
        #expect(props["color"]?.content == "#ff8800")
        let opacity = try #require(props["opacity"]?.double)
        #expect(abs(opacity - 0.5) <= 0.01)
        #expect(props["width"]?.double == 4.0)
        #expect(props["dashed"]?.content == "true")
        #expect(props["arrows"]?.content == "true")
        let geometry = try #require(f["geometry"])
        #expect(geometry["type"]?.content == "MultiLineString")
        let first = try #require(geometry["coordinates"]?.elements?.first?.elements?.first?.elements)
        try #require(first.count == 2)
        #expect(first[0].double == 6.25)
        #expect(first[1].double == 45.5)
    }

    @Test("single segment is a LineString, empty or one-point lines are skipped")
    func singleSegment() throws {
        let red = Int32(bitPattern: 0xFFFF_0000)
        let items = [
            LineFeature(id: 1, colorArgb: red, widthDp: 3, dashed: false, segments: [[TrackPoint(lat: 0.0, lon: 0.0), TrackPoint(lat: 0.001, lon: 0.001)]]),
            LineFeature(id: 2, colorArgb: red, widthDp: 3, dashed: false, segments: [[TrackPoint(lat: 0.0, lon: 0.0)]]),
            LineFeature(id: 3, colorArgb: red, widthDp: 3, dashed: false, segments: []),
        ]
        let result = try features(TrackGeoJson.lines(items))
        #expect(result.count == 1)
        let f = try #require(result.first)
        #expect(f["geometry"]?["type"]?.content == "LineString")
        #expect(f["properties"]?["opacity"]?.content == "1")
        #expect(try features(TrackGeoJson.lines([])).count == 0)
    }

    @Test("waypoints keep awkward names and coordinates have at most six decimals")
    func awkwardNames() throws {
        let text = TrackGeoJson.points([
            PointFeature(id: 7, name: "Col \"du\" Géant\n1", lat: -33.1234567891, lon: -70.5),
            PointFeature(id: 8, name: nil, lat: 0.0000004, lon: 179.9999996),
        ])
        let result = try features(text)
        try #require(result.count == 2)
        #expect(result[0]["properties"]?["name"]?.content == "Col \"du\" Géant\n1")
        let coords = try #require(result[0]["geometry"]?["coordinates"]?.elements)
        try #require(coords.count == 2)
        #expect(coords[0].double == -70.5)
        #expect(coords[1].double == -33.123457)
        let second = try #require(result[1]["geometry"]?["coordinates"]?.elements)
        try #require(second.count == 2)
        #expect(second[0].double == 180.0)
        #expect(second[1].double == 0.0)
        #expect(!text.contains("E-"), "no scientific notation")
    }

    @Test("a 50000-point track is simplified and written quickly")
    func bigTrack() throws {
        let points = (0 ..< 50000).map { i in
            TrackPoint(
                lat: 45.0 + sin(Double(i) / 300.0) * 0.01,
                lon: 6.0 + Double(i) * 2e-6 + cos(Double(i) / 40.0) * 1e-5
            )
        }
        let started = Date()
        let text = TrackGeoJson.lines([
            LineFeature(id: 1, colorArgb: Int32(bitPattern: 0xFFFF_0000), widthDp: 4, dashed: false, segments: [points]),
        ])
        let millis = Date().timeIntervalSince(started) * 1000
        let list = try features(text)
        let count = try #require(list.first?["geometry"]?["coordinates"]?.elements?.count)
        #expect(count >= 2 && count < points.count, "\(count) points kept")
        #expect(millis < 2000, "took \(millis) ms")
    }

    @Test("JsonObject type is what the parser returns")
    func objectType() throws {
        #expect(try TrackJsonParser.parse(TrackGeoJson.point(1.0, 2.0)).members != nil)
    }
}
