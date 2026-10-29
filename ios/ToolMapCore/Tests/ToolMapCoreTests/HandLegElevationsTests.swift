// Port of core/src/test/kotlin/fr/toolmap/core/route/HandLegElevationsTest.kt.

import Testing
@testable import ToolMapCore

struct HandLegElevationsTests {
    // Chartreuse, then south out of BRouter's E5_N45 tile: about 2.2 km, then 9 km.
    private let a = RouteStop(lat: 45.0, lon: 6.08)
    private let b = RouteStop(lat: 45.02, lon: 6.08)
    private let c = RouteStop(lat: 44.94, lon: 6.08)

    private func routed(_ from: RouteStop, _ to: RouteStop) -> RouteLeg {
        RouteLeg(
            profile: RoutingProfiles.hiking,
            mode: .routed,
            state: .ok,
            points: [TrackPoint(lat: from.lat, lon: from.lon, ele: 500.0), TrackPoint(lat: to.lat, lon: to.lon, ele: 700.0)],
            timeMillis: 1
        )
    }

    /// The point with another elevation (Kotlin `copy(ele = …)`).
    private func at(_ p: TrackPoint, _ ele: Double?) -> TrackPoint {
        TrackPoint(lat: p.lat, lon: p.lon, ele: ele, timeMillis: p.timeMillis, extras: p.extras)
    }

    /// A ridge 400 m above both ends, halfway along.
    private func ridge(_ points: [TrackPoint]) -> [TrackPoint] {
        let total = Geodesy.distance(points[0], points[points.count - 1])
        return points.map { p in
            let t = Geodesy.distance(points[0], p) / total
            return at(p, 700.0 + 400.0 * (1 - abs(2 * t - 1)))
        }
    }

    @Test("samples run straight on the map from one stop to the other")
    func samplesRunStraight() {
        let s = HandLegElevations.samples(b.point, c.point)
        #expect(s.first == b.point)
        #expect(s.last == c.point)
        let step = Geodesy.distance(b.point, c.point) / Double(s.count - 1)
        #expect(step <= HandLegElevations.spacingMeters, "step \(step)")
        #expect(step > HandLegElevations.spacingMeters * 0.9, "step \(step)")
        for k in 1 ..< s.count {
            #expect(s[k].lat < s[k - 1].lat)
        }
        #expect(s.allSatisfy { $0.lon == 6.08 && $0.ele == nil })
    }

    @Test("a very long leg stays a few hundred points")
    func veryLongLeg() {
        let far = TrackPoint(lat: 44.0, lon: 7.0)
        let s = HandLegElevations.samples(a.point, far)
        #expect(s.count == HandLegElevations.maxPoints)
        #expect(s.last == far)
    }

    @Test("a straight leg in a routed route is sampled along its line")
    func straightLegSampled() throws {
        let plan = RoutePlan(
            stops: [a, b, c],
            legs: [routed(a, b), RouteLeg.straight(b, c, RoutingProfiles.hiking, mode: .routed, state: .noData)]
        )
        let e = HandLegElevations()
        let wanted = e.wanted(plan)
        try #require(wanted.count == 1)
        let key = wanted[0].key
        let points = wanted[0].points
        #expect(points.count == HandLegElevations.sampleCount(b.point, c.point))

        e.put(key, ridge(points))
        let filled = e.apply(plan)
        #expect(filled.legs[0] == plan.legs[0])
        #expect(filled.legs[1].state == .noData)
        #expect(filled.legs[1].points.count == points.count)
        #expect(filled.geometry.allSatisfy { $0.ele != nil })
        #expect(e.wanted(filled).isEmpty)

        // The ridge counts: from 700 m up to 1100 m and back down, plus the 200 m of the routed leg.
        let s = RouteSummary.of(filled, RoutingProfiles.hiking)
        #expect(s.hasElevation)
        #expect(s.ascentMeters > 500.0, "ascent \(s.ascentMeters)")
        #expect(s.descentMeters > 300.0, "descent \(s.descentMeters)")
    }

    @Test("a filled plan goes back and forth through the GPX with its samples")
    func filledPlanThroughGpx() throws {
        let plan = RoutePlan(
            stops: [a, b, c],
            legs: [routed(a, b), RouteLeg.straight(b, c, RoutingProfiles.hiking, mode: .straight)]
        )
        let e = HandLegElevations()
        for entry in e.wanted(plan) {
            e.put(entry.key, ridge(entry.points))
        }
        let filled = e.apply(plan)
        let points = RoutePlanGpx.routePoints(filled).map { TrackPoint(lat: $0.lat, lon: $0.lon, ele: $0.ele) }
        let back = try #require(RoutePlanGpx.planOf(points, RoutePlanGpx.attach([], filled)))
        #expect(back == filled)
        #expect(HandLegElevations().wanted(back).isEmpty)
    }

    @Test("a route wholly by hand only gives its stops an elevation")
    func whollyByHand() throws {
        let plan = RoutePlan(stops: [b, c], legs: [RouteLeg.straight(b, c, RoutingProfiles.hiking)])
        let e = HandLegElevations()
        let wanted = e.wanted(plan)
        try #require(wanted.count == 1)
        #expect(wanted[0].points == [b.point, c.point])
        e.put(wanted[0].key, [at(b.point, 700.0), at(c.point, 650.0)])
        let filled = e.apply(plan)
        let expected: [Double?] = [700.0, 650.0]
        let geometryElevations: [Double?] = filled.geometry.map { $0.ele }
        let stopElevations: [Double?] = RoutePlanGpx.stopPoints(filled).map { $0.ele }
        #expect(geometryElevations == expected)
        #expect(stopElevations == expected)
        #expect(e.wanted(filled).isEmpty)

        // Followed by a routed leg, the line is then sampled.
        let mixed = RoutePlan(stops: [b, c, a], legs: filled.legs + [routed(c, a)])
        let more = e.wanted(mixed)
        try #require(more.count == 1)
        #expect(more[0].points.count > 2)
    }

    @Test("routed legs and legs being computed are left to BRouter")
    func leftToBRouter() {
        let plan = RoutePlan(
            stops: [a, b, c],
            legs: [routed(a, b), RouteLeg.straight(b, c, RoutingProfiles.hiking, mode: .routed, state: .pending)]
        )
        #expect(HandLegElevations().wanted(plan).isEmpty)
    }

    @Test("a leg turned round is known already")
    func turnedRound() {
        let e = HandLegElevations()
        let there = RoutePlan(stops: [a, b], legs: [RouteLeg.straight(a, b, RoutingProfiles.hiking)])
        for entry in e.wanted(there) {
            e.put(entry.key, entry.points.map { at($0, 600.0 + ($0.lat - 45.0) * 1e4) })
        }
        let back = RoutePlan(stops: [b, a], legs: [RouteLeg.straight(b, a, RoutingProfiles.hiking)])
        #expect(e.wanted(back).isEmpty)
        let elevations = e.apply(back).geometry.map { ($0.ele ?? .nan).rounded(.toNearestOrEven) }
        #expect(elevations == [800.0, 600.0])
    }

    @Test("without every tile the leg has no elevation, and is not asked again")
    func withoutEveryTile() throws {
        let plan = RoutePlan(stops: [b, c], legs: [RouteLeg.straight(b, c, RoutingProfiles.hiking)])
        let e = HandLegElevations()
        let wanted = e.wanted(plan)
        try #require(wanted.count == 1)
        let points = wanted[0].points
        e.put(wanted[0].key, [at(points[0], 700.0), points[1]])
        #expect(e.apply(plan) == plan)
        #expect(e.wanted(plan).isEmpty)
        e.forgetFailures()
        #expect(e.wanted(plan).count == 1)
    }

    @Test("the editor takes the elevations without an undo step")
    func editorTakesElevations() {
        let editor = RoutePlanEditor(RoutePlan(stops: [b, c], legs: [RouteLeg.straight(b, c, RoutingProfiles.hiking)]))
        let e = HandLegElevations()
        #expect(!editor.applyElevations(e))
        for entry in e.wanted(editor.plan) {
            e.put(entry.key, entry.points.map { at($0, 650.0) })
        }
        #expect(editor.applyElevations(e))
        #expect(!editor.canUndo)
        #expect(!editor.plan.geometry.contains { $0.ele == nil })
    }
}
