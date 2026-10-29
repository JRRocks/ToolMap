// Port of core/src/test/kotlin/fr/toolmap/core/route/RoutePlanGpxTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct RoutePlanGpxTests {
    private func plan() -> RoutePlan {
        let a = RouteStop(lat: 45.9, lon: 6.8, kind: .named, name: "Refuge <Nord> & co", sym: "Lodge")
        let b = RouteStop(lat: 45.91, lon: 6.81)
        let c = RouteStop(lat: 45.92, lon: 6.79)
        let ab = RouteLeg(
            profile: RoutingProfiles.hiking,
            mode: .routed,
            state: .ok,
            points: [
                TrackPoint(lat: 45.9, lon: 6.8, ele: 1200.0),
                TrackPoint(lat: 45.905, lon: 6.807, ele: 1300.0),
                TrackPoint(lat: 45.91, lon: 6.81, ele: 1350.0),
            ],
            timeMillis: 3_600_000
        )
        // The point shared by two legs is written once: the second leg starts on the end of the first.
        let bc = RouteLeg(
            profile: RoutingProfiles.hiking,
            mode: .routed,
            state: .noData,
            points: [ab.points[ab.points.count - 1], c.point]
        )
        return RoutePlan(stops: [a, b, c], legs: [ab, bc], zones: [AvoidZone(lat: 45.905, lon: 6.79, radiusMeters: 250)])
    }

    /// The route's points as a planned geometry (Kotlin `points.map { TrackPoint(it.lat, it.lon, it.ele) }`).
    private func geometry(_ route: Route) -> [TrackPoint] {
        route.points.map { TrackPoint(lat: $0.lat, lon: $0.lon, ele: $0.ele) }
    }

    @Test("the plan goes through a GPX file and comes back the same")
    func throughGpx() throws {
        let p = plan()
        let route = Route(name: "Tour", points: RoutePlanGpx.routePoints(p), extras: RoutePlanGpx.attach([], p))
        #expect(route.points[0].name == "Refuge <Nord> & co")
        #expect(route.points[0].sym == "Lodge")
        #expect(route.points[1].name == nil)
        let text = GpxWriter.toString(TrackDocument(routes: [route]))
        #expect(text.contains("<tm:plan"))
        let document = try GpxReader.read(Data(text.utf8))
        try #require(document.routes.count == 1)
        let back = document.routes[0]
        let read = RoutePlanGpx.planOf(geometry(back), back.extras)
        #expect(read == p)
    }

    @Test("points of interest go through the GPX, as waypoints too, which a new import does not take twice")
    func pointsOfInterest() throws {
        let p = plan().copy(pois: [RoutePoi(lat: 45.906, lon: 6.811, name: "Source & fontaine", sym: "Drinking Water")])
        let route = Route(name: "Tour", points: RoutePlanGpx.routePoints(p), extras: RoutePlanGpx.attach([], p))
        let wpts = RoutePlanGpx.poiWaypoints(route)
        try #require(wpts.count == 1)
        #expect(wpts[0].name == "Source & fontaine")
        #expect(wpts[0].sym == "Drinking Water")
        let text = GpxWriter.toString(TrackDocument(waypoints: wpts, routes: [route]))
        #expect(text.contains("<tm:poi"))
        let document = try GpxReader.read(Data(text.utf8))
        try #require(document.routes.count == 1)
        try #require(document.waypoints.count == 1)
        let back = document.routes[0]
        #expect(RoutePlanGpx.planOf(geometry(back), back.extras) == p)
        let waypoint = document.waypoints[0]
        #expect(RoutePlanGpx.isPoiOf(waypoint, document.routes))
        let other = Waypoint(lat: waypoint.lat, lon: waypoint.lon, ele: waypoint.ele, name: "Autre", sym: waypoint.sym)
        #expect(!RoutePlanGpx.isPoiOf(other, document.routes))
        #expect(RoutePlanGpx.poiWaypoints(Route(name: "Sans plan", points: route.points)).isEmpty)
    }

    @Test("a route changed elsewhere loses its plan")
    func changedElsewhere() {
        let p = plan()
        let extras = RoutePlanGpx.attach([], p)
        let geometry = p.geometry
        #expect(RoutePlanGpx.planOf(geometry, extras) != nil)
        #expect(RoutePlanGpx.planOf(Array(geometry.dropFirst()), extras) == nil)
        let moved = geometry.map { TrackPoint(lat: $0.lat + 0.001, lon: $0.lon, ele: $0.ele) }
        #expect(RoutePlanGpx.planOf(moved, extras) == nil)
        #expect(RoutePlanGpx.planOf(geometry, []) == nil)
    }

    @Test("other extensions are kept and the plan is replaced, not added")
    func otherExtensionsKept() throws {
        let color = RawXml("<extensions><color>#FF0000</color></extensions>")
        let once = RoutePlanGpx.attach([color], plan())
        try #require(once.count == 1)
        #expect(once[0].xml.hasPrefix("<extensions><color>#FF0000</color><tm:plan"))
        let twice = RoutePlanGpx.attach(once, plan())
        try #require(twice.count == 1)
        #expect(twice[0].xml.components(separatedBy: "<tm:plan").count - 1 == 1)
        #expect(RoutePlanGpx.attach(twice, nil) == [color])
        #expect(RoutePlanGpx.attach(RoutePlanGpx.attach([], plan()), nil).isEmpty)
    }

    @Test("a single stop is a plan too")
    func singleStop() {
        let p = RoutePlan(stops: [RouteStop(lat: 45.0, lon: 6.0)])
        #expect(RoutePlanGpx.planOf(p.geometry, RoutePlanGpx.attach([], p)) == p)
    }
}
