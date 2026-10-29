// Port of core/src/test/kotlin/fr/toolmap/core/route/RouteSummaryTest.kt.

import Testing
@testable import ToolMapCore

struct RouteSummaryTests {
    private let a = RouteStop(lat: 45.0, lon: 6.0)
    private let b = RouteStop(lat: 45.01, lon: 6.0)
    private let c = RouteStop(lat: 45.02, lon: 6.0)

    private func routed(
        _ from: RouteStop,
        _ to: RouteStop,
        _ profile: String,
        _ time: Int64?,
        _ state: LegState = .ok
    ) -> RouteLeg {
        RouteLeg(profile: profile, mode: .routed, state: state, points: [from.point, to.point], timeMillis: time)
    }

    @Test("an empty route has no figures")
    func emptyRoute() {
        #expect(RouteSummary.of(RoutePlan(), RoutingProfiles.hiking) == RouteSummary.empty)
    }

    @Test("walking takes the DIN time of the whole route")
    func walking() {
        let plan = RoutePlan(
            stops: [a, b, c],
            legs: [routed(a, b, RoutingProfiles.hiking, 1), routed(b, c, RoutingProfiles.hiking, 1)]
        )
        let s = RouteSummary.of(plan, RoutingProfiles.hiking)
        let stats = TrackStatsCalculator.compute([plan.geometry])
        #expect(s.durationMillis == stats.estimatedWalkMillis)
        #expect(!s.durationIsMinimum)
        #expect(!s.hasElevation)
        #expect(abs(s.lengthMeters - stats.distance2dMeters) <= 1e-6)
    }

    @Test("other profiles add up BRouter's times, a minimum when a leg has none")
    func otherProfiles() {
        let full = RoutePlan(
            stops: [a, b, c],
            legs: [routed(a, b, RoutingProfiles.bike, 600_000), routed(b, c, RoutingProfiles.bike, 300_000)]
        )
        let s = RouteSummary.of(full, RoutingProfiles.bike)
        #expect(s.durationMillis == 900_000)
        #expect(!s.durationIsMinimum)

        let partial = full.copy(legs: [full.legs[0], RouteLeg.straight(b, c, RoutingProfiles.bike)])
        let p = RouteSummary.of(partial, RoutingProfiles.bike)
        #expect(p.durationMillis == 600_000)
        #expect(p.durationIsMinimum)
    }

    @Test("no time at all is no duration, not a minimum")
    func noTime() {
        let plan = RoutePlan(stops: [a, b], legs: [RouteLeg.straight(a, b, RoutingProfiles.bike)])
        let s = RouteSummary.of(plan, RoutingProfiles.bike)
        #expect(s.durationMillis == nil)
        #expect(!s.durationIsMinimum)
    }

    @Test("legs being computed are counted")
    func legsBeingComputed() {
        let plan = RoutePlan(
            stops: [a, b, c],
            legs: [routed(a, b, RoutingProfiles.hiking, nil, .pending), routed(b, c, RoutingProfiles.hiking, 1)]
        )
        #expect(RouteSummary.of(plan, RoutingProfiles.hiking).pendingLegs == 1)
    }

    @Test("climb and descent come from the elevations")
    func climbAndDescent() {
        let up = [
            TrackPoint(lat: 45.0, lon: 6.0, ele: 1000.0),
            TrackPoint(lat: 45.005, lon: 6.0, ele: 1200.0),
            TrackPoint(lat: 45.01, lon: 6.0, ele: 1400.0),
        ]
        let plan = RoutePlan(
            stops: [a, b],
            legs: [RouteLeg(profile: RoutingProfiles.hiking, mode: .routed, state: .ok, points: up, timeMillis: 1)]
        )
        let s = RouteSummary.of(plan, RoutingProfiles.hiking)
        #expect(s.hasElevation)
        #expect(s.ascentMeters > 300.0)
        #expect(abs(s.descentMeters) <= 1e-6)
    }

    @Test("a saved route gives its plan's profile, a route by hand only its length")
    func savedRoute() {
        let plan = RoutePlan(
            stops: [a, b, c],
            legs: [routed(a, b, RoutingProfiles.hiking, 1), routed(b, c, RoutingProfiles.bike, 60_000)]
        )
        let saved = SavedRouteInfo.of(plan.geometry, RoutePlanGpx.attach([], plan))
        #expect(saved.profile == RoutingProfiles.bike)
        #expect(saved.summary == RouteSummary.of(plan, RoutingProfiles.bike))

        let byHand = SavedRouteInfo.of([a.point, b.point, c.point], [])
        #expect(byHand.profile == nil)
        #expect(byHand.summary.durationMillis == nil)
        let length = TrackStatsCalculator.compute([[a.point, b.point, c.point]]).distance2dMeters
        #expect(abs(byHand.summary.lengthMeters - length) <= 1e-6)
    }
}
