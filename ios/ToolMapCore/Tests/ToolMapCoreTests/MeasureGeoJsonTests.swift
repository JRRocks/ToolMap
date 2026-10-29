// Port of core/src/test/kotlin/fr/toolmap/core/measure/MeasureGeoJsonTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct MeasureGeoJsonTests {
    private let square = [
        TrackPoint(lat: 45.0, lon: 5.0), TrackPoint(lat: 45.0, lon: 5.01), TrackPoint(lat: 45.01, lon: 5.01),
        TrackPoint(lat: 45.01, lon: 5.0),
    ]

    private func coordinates(_ geoJson: String, _ geometry: String) throws -> [[Double]] {
        let root = try TrackJsonParser.parse(geoJson)
        let features = try #require(root["features"]?.elements)
        try #require(features.count == 1)
        let g = try #require(features[0]["geometry"])
        #expect(g["type"]?.content == geometry)
        let c = try #require(g["coordinates"]?.elements)
        let line = geometry == "Polygon" ? (c.first?.elements ?? []) : c
        return line.map { p in (p.elements ?? []).map { $0.content.flatMap { Double($0) } ?? .nan } }
    }

    @Test("a path is an open line and has no fill")
    func path() throws {
        #expect(try coordinates(MeasureGeoJson.line(square, .path), "LineString").count == 4)
        #expect(MeasureGeoJson.fill(square, .path).contains("\"features\":[]"))
    }

    @Test("an area is a closed line and a filled ring")
    func area() throws {
        let line = try coordinates(MeasureGeoJson.line(square, .area), "LineString")
        #expect(line.count == 5)
        #expect(line.first == line.last)
        let ring = try coordinates(MeasureGeoJson.fill(square, .area), "Polygon")
        #expect(ring.count == 5)
        #expect(ring.first == ring.last)
        // [lon, lat] order.
        try #require(ring.first?.count == 2)
        #expect(abs(ring[0][0] - 5.0) <= 1e-9)
        #expect(abs(ring[0][1] - 45.0) <= 1e-9)
    }

    @Test("two points are never closed or filled, even in area mode")
    func twoPoints() throws {
        let two = Array(square.prefix(2))
        #expect(try coordinates(MeasureGeoJson.line(two, .area), "LineString").count == 2)
        #expect(MeasureGeoJson.fill(two, .area).contains("\"features\":[]"))
    }

    @Test("vertices have no midpoint handles")
    func vertices() throws {
        let root = try TrackJsonParser.parse(MeasureGeoJson.vertices(square))
        let v = try #require(root["features"]?.elements)
        #expect(v.count == 4)
        let kinds = v.map { $0["properties"]?["kind"]?.content ?? "" }
        #expect(kinds == ["a", "v", "v", "z"])
        #expect(MeasureGeoJson.vertices([]).contains("\"features\":[]"))
    }
}
