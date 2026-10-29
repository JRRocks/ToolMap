// Port of core/src/test/kotlin/fr/toolmap/core/route/RouteMarkersTest.kt.

import Testing
@testable import ToolMapCore

struct RouteMarkersTests {
    private let a = RouteStop(lat: 45.0, lon: 6.0)
    private let b = RouteStop(lat: 45.01, lon: 6.0, kind: .named, name: "Refuge", sym: "Lodge")
    private let c = RouteStop(lat: 45.02, lon: 6.01)

    private func plan(_ stops: [RouteStop]) -> RoutePlan {
        var legs: [RouteLeg] = []
        for i in 1 ..< stops.count {
            legs.append(RouteLeg.straight(stops[i - 1], stops[i], RoutingProfiles.hiking))
        }
        return RoutePlan(stops: stops, legs: legs)
    }

    @Test("a saved route shows its start, its named stops and its end")
    func startNamedEnd() throws {
        let icons: [String?] = RouteMarkers.saved(-3, plan([a, b, c])).map { $0.iconId }
        try #require(icons.count == 3)
        let start: String? = RouteMarkers.letterPrefix + "A"
        let end: String? = RouteMarkers.letterPrefix + "C"
        #expect(icons[0] == start)
        #expect(icons[2] == end)
    }

    @Test("a loop ends on « A » alone, without a second letter over it")
    func loopEndsOnA() {
        let markers = RouteMarkers.saved(-3, plan([a, b, c, a.discreet()]))
        let icons: [String?] = markers.map { $0.iconId }
        let expected: [String?] = [RouteMarkers.letterPrefix + "A", nil]
        #expect(icons == expected)
        #expect(markers.first?.colorArgb == RouteMarkers.startColor)
    }

    @Test("a saved route shows its points of interest, which open the route")
    func pointsOfInterest() throws {
        let spring = RoutePoi(lat: 45.005, lon: 6.002, name: "Source", sym: "Drinking Water")
        let markers = RouteMarkers.saved(-3, plan([a, b, c]).copy(pois: [spring]))
        #expect(markers.count == 4)
        let named = markers.filter { $0.name == "Source" }
        try #require(named.count == 1)
        let poi = named[0]
        #expect(poi.id == -3)
        #expect(poi.sym == "Drinking Water")
        #expect(poi.lat == spring.lat)
        #expect(poi.lon == spring.lon)
    }
}
