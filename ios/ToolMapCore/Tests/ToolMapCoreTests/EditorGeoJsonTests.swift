// Port of core/src/test/kotlin/fr/toolmap/core/track/EditorGeoJsonTest.kt.

import Testing
@testable import ToolMapCore

struct EditorGeoJsonTests {
    private func line(_ count: Int) -> [TrackPoint] {
        (0 ..< count).map { TrackPoint(lat: 45.0 + Double($0) * 0.001, lon: 6.0) }
    }

    /// The handles as `kind` and index, e.g. `m0`, `s2`.
    private func kinds(_ text: String) throws -> [String] {
        let root = try TrackJsonParser.parse(text)
        let features = try #require(root["features"]?.elements)
        return try features.map { (feature: TrackJsonNode) throws -> String in
            let p = try #require(feature["properties"])
            let kind = try #require(p["kind"]?.content)
            let index = try #require(p["i"]?.content)
            return kind + index
        }
    }

    private func featureCount(_ text: String) throws -> Int {
        let root = try TrackJsonParser.parse(text)
        let features = try #require(root["features"]?.elements)
        return features.count
    }

    @Test("a line needs two points and is a valid linestring")
    func lineNeedsTwoPoints() throws {
        #expect(try featureCount(EditorGeoJson.line(line(1))) == 0)
        let root = try TrackJsonParser.parse(EditorGeoJson.line(line(3)))
        let list = try #require(root["features"]?.elements)
        #expect(list.count == 1)
        let g = try #require(list.first?["geometry"])
        #expect(g["type"]?.content == "LineString")
        #expect(g["coordinates"]?.elements?.count == 3)
    }

    @Test("handles tell the ends, the selection and the midpoints apart")
    func handles() throws {
        #expect(try kinds(EditorGeoJson.handles(line(4), selected: 2)) == ["m0", "m1", "m2", "a0", "v1", "s2", "z3"])
        #expect(try kinds(EditorGeoJson.handles(line(2), selected: nil, midpoints: false)) == ["a0", "z1"])
        #expect(try kinds(EditorGeoJson.handles([], selected: nil)).isEmpty)
        // A single point is both ends: it shows as the start.
        #expect(try kinds(EditorGeoJson.handles(line(1), selected: nil)) == ["a0"])
    }

    @Test("a very long line only shows its ends and the selected vertex")
    func longLine() throws {
        let long = line(EditorGeoJson.maxHandles + 1)
        let text = EditorGeoJson.handles(long, selected: 700)
        let root = try TrackJsonParser.parse(text)
        let features = try #require(root["features"]?.elements)
        let got = features.compactMap { f -> (kind: String, index: Int)? in
            guard let kind = f["properties"]?["kind"]?.content,
                  let index = f["properties"]?["i"]?.content.flatMap({ Int($0) })
            else { return nil }
            return (kind, index)
        }.sorted { $0.index < $1.index }
        #expect(got.map(\.kind) == ["a", "s", "z"])
        #expect(got.map(\.index) == [0, 700, EditorGeoJson.maxHandles])
    }

    @Test("a tap selects a vertex, inserts at a midpoint or adds")
    func taps() {
        let pts = line(3) // vertices 111 m apart
        #expect(EditorGeoJson.interpret(pts, 45.001, 6.0002, reachMeters: 30.0) == .select(index: 1))
        // 55 m from both neighbours: the midpoint handle.
        #expect(EditorGeoJson.interpret(pts, 45.0005, 6.0, reachMeters: 30.0) == .insertMidpoint(segment: 0))
        #expect(EditorGeoJson.interpret(pts, 45.0005, 6.01, reachMeters: 30.0) == .add)
        #expect(EditorGeoJson.interpret([], 1.0, 1.0, reachMeters: 30.0) == .add)
        // Two handles within reach: the vertex wins over the midpoint.
        #expect(EditorGeoJson.interpret(pts, 45.00001, 6.0, reachMeters: 100.0) == .select(index: 0))
    }

    @Test("point features carry the symbol colour and the icon")
    func pointFeatures() throws {
        let text = TrackGeoJson.points([
            PointFeature(id: 1, name: "Sommet", lat: 45.0, lon: 6.0, sym: "Summit"),
            PointFeature(id: 2, name: nil, lat: 0.0, lon: 0.0),
        ])
        let root = try TrackJsonParser.parse(text)
        let f = try #require(root["features"]?.elements)
        try #require(f.count == 2)
        let summit = try #require(f[0]["properties"])
        #expect(summit["color"]?.content == "#5a7a00")
        #expect(summit["icon"]?.content == "peak")
        let plain = try #require(f[1]["properties"])
        #expect(plain["color"]?.content == "#e53935")
        #expect(plain["icon"]?.content == "waypoint")
    }
}
