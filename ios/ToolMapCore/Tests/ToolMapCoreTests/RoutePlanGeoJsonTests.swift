// Port of core/src/test/kotlin/fr/toolmap/core/route/RoutePlanGeoJsonTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct RoutePlanGeoJsonTests {
    private static let emptyCollection = #"{"type":"FeatureCollection","features":[]}"#

    private let a = RouteStop(lat: 45.0, lon: 6.0)
    private let b = RouteStop(lat: 45.01, lon: 6.0, kind: .named, name: "Refuge \"Nord\"", sym: "Lodge")
    private let c = RouteStop(lat: 45.02, lon: 6.01)
    private let d = RouteStop(lat: 45.03, lon: 6.0)

    private func plan() -> RoutePlan {
        RoutePlan(
            stops: [a, b, c, d],
            legs: [
                RouteLeg(
                    profile: RoutingProfiles.hiking,
                    mode: .routed,
                    state: .ok,
                    points: [a.point, TrackPoint(lat: 45.005, lon: 6.002), b.point],
                    timeMillis: 1
                ),
                RouteLeg.straight(b, c, RoutingProfiles.hiking),
                RouteLeg.straight(c, d, RoutingProfiles.hiking, mode: .routed, state: .pending),
            ]
        )
    }

    /// Occurrences of `part` in `text`, overlapping ones included (Kotlin `windowed(...).count { it == part }`).
    private func count(_ text: String, _ part: String) -> Int {
        var n = 0
        var start = text.startIndex
        while start < text.endIndex, let found = text.range(of: part, range: start ..< text.endIndex) {
            n += 1
            start = text.index(after: found.lowerBound)
        }
        return n
    }

    /// Kotlin `substringAfter(delimiter)`: the whole text when the delimiter is missing.
    private func after(_ text: String, _ delimiter: String) -> String {
        guard let found = text.range(of: delimiter) else { return text }
        return String(text[found.upperBound...])
    }

    /// Kotlin `substringBefore(delimiter)`.
    private func before(_ text: String, _ delimiter: String) -> String {
        guard let found = text.range(of: delimiter) else { return text }
        return String(text[..<found.lowerBound])
    }

    @Test("the ends get letters and the named stops their icon")
    func icons() {
        let icons = RoutePlanGeoJson.icons(plan())
        let expected: Set<String> = [
            RouteMarkers.letterPrefix + "A",
            RouteMarkers.letterPrefix + "D",
            WaypointSymbols.iconOf("Lodge").id,
        ]
        #expect(icons == expected)
    }

    @Test("each leg says how it is drawn, with a label in the middle of the legs not routed")
    func lines() {
        let text = RoutePlanGeoJson.lines(plan(), "calcul…", "à la main")
        #expect(count(text, "\"LineString\"") == 3)
        #expect(text.contains("\"st\":\"r\""))
        #expect(text.contains("\"st\":\"h\""))
        #expect(text.contains("\"st\":\"p\""))
        #expect(text.contains("\"label\":\"à la main\""))
        #expect(text.contains("\"label\":\"calcul…\""))
        #expect(count(text, "\"label\"") == 2)
    }

    @Test("an empty label puts no label")
    func emptyLabel() {
        let text = RoutePlanGeoJson.lines(plan(), "calcul…", "")
        #expect(count(text, "\"label\"") == 1)
        #expect(!text.contains("à la main"))
    }

    @Test("an empty plan has no features")
    func emptyPlan() {
        #expect(RoutePlanGeoJson.lines(RoutePlan(), "", "") == RoutePlanGeoJsonTests.emptyCollection)
        #expect(RoutePlanGeoJson.handles(RoutePlan(), selected: nil) == RoutePlanGeoJsonTests.emptyCollection)
    }

    @Test("handles carry kind, selection, colour, icon and name")
    func handles() throws {
        let text = RoutePlanGeoJson.handles(plan(), selected: 2)
        #expect(text.contains("\"kind\":\"start\""))
        #expect(text.contains("\"kind\":\"end\""))
        #expect(text.contains("\"kind\":\"named\""))
        #expect(text.contains("\"kind\":\"pass-sel\",\"i\":2,\"sel\":true"))
        #expect(text.contains("\"color\":\"#43a047\""))
        #expect(text.contains("\"color\":\"#e53935\""))
        #expect(text.contains("\"name\":\"Refuge \\\"Nord\\\"\""))
        #expect(!text.contains("\"kind\":\"m\""))
        // The start is drawn last, above the end of a loop.
        let start = try #require(text.range(of: "\"kind\":\"start\"", options: .backwards))
        let end = try #require(text.range(of: "\"kind\":\"end\"", options: .backwards))
        #expect(start.lowerBound > end.lowerBound)
    }

    @Test("a loop shows « A » alone on its start")
    func loop() {
        let loop = RoutePlan(
            stops: [a, c, d, a.discreet()],
            legs: [
                RouteLeg.straight(a, c, RoutingProfiles.hiking),
                RouteLeg.straight(c, d, RoutingProfiles.hiking),
                RouteLeg.straight(d, a, RoutingProfiles.hiking),
            ]
        )
        let expected: Set<String> = [RouteMarkers.letterPrefix + "A"]
        #expect(RoutePlanGeoJson.icons(loop) == expected)
        let text = RoutePlanGeoJson.handles(loop, selected: nil)
        #expect(count(text, "\"icon\"") == 1)
        #expect(text.contains("\"kind\":\"end\""))
    }

    @Test("a stop being moved pales its legs, follows the crosshair and is joined to it by elastic lines")
    func stopBeingMoved() {
        let to = TrackPoint(lat: 45.015, lon: 6.02)
        let lines = RoutePlanGeoJson.lines(plan(), "calcul…", "à la main", taken: 2)
        #expect(count(lines, "\"st\":\"g\"") == 2)
        #expect(lines.contains("\"st\":\"r\""))
        #expect(count(lines, "\"label\"") == 0)
        let handles = RoutePlanGeoJson.handles(plan(), selected: 2, moved: to)
        #expect(handles.contains("\"i\":2,\"sel\":true},\"geometry\":{\"type\":\"Point\",\"coordinates\":[6.02,45.015]"))
        let elastic = RoutePlanGeoJson.elastic(plan(), 2, to)
        #expect(elastic.contains("[[6,45.01],[6.02,45.015],[6,45.03]]"))
        // The start has one neighbour: one stretch only.
        #expect(count(RoutePlanGeoJson.elastic(plan(), 0, to), "],[") == 1)
        #expect(RoutePlanGeoJson.elastic(plan(), 9, to) == RoutePlanGeoJsonTests.emptyCollection)
    }

    @Test("drawing by hand shows the middle of the straight legs only")
    func midpoints() {
        let text = RoutePlanGeoJson.handles(plan(), selected: nil, midpoints: true)
        #expect(count(text, "\"kind\":\"m\"") == 1)
        #expect(text.contains("\"kind\":\"m\",\"leg\":1"))
        let m = RoutePlanGeoJson.midpoint(plan(), 1)
        #expect(abs(m.lat - (b.lat + c.lat) / 2) <= 1e-4)
    }

    @Test("zones are circles with their sign, the one being placed a ring")
    func zones() {
        let zone = AvoidZone(lat: 45.0, lon: 6.0, radiusMeters: 250)
        #expect(RoutePlanGeoJson.zones(RoutePlan()) == RoutePlanGeoJsonTests.emptyCollection)
        let text = RoutePlanGeoJson.zones(RoutePlan(zones: [zone]), placing: zone.copy(lat: 45.01))
        #expect(count(text, "\"z\":\"zone\"") == 1)
        #expect(count(text, "\"z\":\"sign\"") == 1)
        #expect(count(text, "\"z\":\"ring\"") == 1)
        #expect(count(text, "\"Polygon\"") == 2)
        let outline = RoutePlanGeoJson.circle(zone)
        #expect(outline.first == outline.last)
        for p in outline {
            let d = Geodesy.distance(zone.lat, zone.lon, p.lat, p.lon)
            #expect(abs(d - 250.0) <= 1.0)
        }
    }

    @Test("the zone under the crosshair has a solid edge, drawn at the size its handle gives")
    func hoveredZone() throws {
        let zones = [AvoidZone(lat: 45.0, lon: 6.0, radiusMeters: 250), AvoidZone(lat: 45.02, lon: 6.0, radiusMeters: 250)]
        let text = RoutePlanGeoJson.zones(RoutePlan(zones: zones), hovered: 1, hoverRadius: 400)
        #expect(count(text, "\"z\":\"zone\"") == 1)
        #expect(count(text, "\"z\":\"hover\"") == 1)
        #expect(count(text, "\"z\":\"sign\"") == 2, "the zone under the crosshair keeps its sign")
        // The east point of the hovered circle is 400 m from its centre.
        let hovered = before(after(after(text, "\"z\":\"hover\""), "[[["), "]")
        let parts = hovered.split(separator: ",").map { String($0) }
        try #require(parts.count == 2)
        let lon = try #require(Double(parts[0]))
        let lat = try #require(Double(parts[1]))
        let d = Geodesy.distance(45.02, 6.0, lat, lon)
        #expect(abs(d - 400.0) <= 1.0)
    }

    @Test("while a zone is resized every leg is pale")
    func paleWhileResized() {
        let text = RoutePlanGeoJson.lines(plan(), "calcul", "à la main", pale: true)
        #expect(count(text, "\"st\":\"r\"") == 0)
        #expect(count(text, "\"st\":\"h\"") == 0)
        #expect(count(text, "\"st\":\"g\"") >= 2)
    }
}
