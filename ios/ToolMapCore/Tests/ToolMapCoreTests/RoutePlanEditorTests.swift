// Port of core/src/test/kotlin/fr/toolmap/core/route/RoutePlanEditorTest.kt.

import Testing
@testable import ToolMapCore

struct RoutePlanEditorTests {
    private func stop(_ i: Int, _ name: String? = nil) -> RouteStop {
        RouteStop(
            lat: 45.0 + Double(i) * 0.01,
            lon: 6.0 + Double(i) * 0.005,
            kind: name != nil ? .named : .passage,
            name: name
        )
    }

    /// A way through a point beside the straight line, as BRouter would give it (ends a few cm off the stops).
    private func way(_ from: RouteStop, _ to: RouteStop) -> LegResult.Ok {
        LegResult.Ok(
            [
                TrackPoint(lat: from.lat + 0.000001, lon: from.lon, ele: 1000.0),
                TrackPoint(lat: (from.lat + to.lat) / 2, lon: (from.lon + to.lon) / 2 + 0.002, ele: 1100.0),
                TrackPoint(lat: to.lat, lon: to.lon + 0.000001, ele: 1200.0),
            ],
            60_000
        )
    }

    /// Answers every pending leg as BRouter would; returns how many were asked.
    @discardableResult
    private func computeAll(_ e: RoutePlanEditor) -> Int {
        var asked = 0
        while let i = e.plan.pending.first {
            asked += 1
            e.applyResult(e.plan.key(i), .ok(way(e.plan.stops[i], e.plan.stops[i + 1])))
        }
        return asked
    }

    /// The invariant of every plan: one leg fewer than stops, legs meeting exactly on the stops.
    private func check(_ plan: RoutePlan) {
        #expect(plan.legs.count == Swift.max(0, plan.stops.count - 1))
        for (i, leg) in plan.legs.enumerated() {
            let first = leg.points[0]
            let last = leg.points[leg.points.count - 1]
            #expect(plan.stops[i].lat == first.lat)
            #expect(plan.stops[i].lon == first.lon)
            #expect(plan.stops[i + 1].lat == last.lat)
            #expect(plan.stops[i + 1].lon == last.lon)
        }
        for (i, at) in plan.stopIndices.enumerated() {
            #expect(plan.stops[i].lat == plan.geometry[at].lat)
            #expect(plan.stops[i].lon == plan.geometry[at].lon)
        }
    }

    private func editorWith(_ count: Int) -> RoutePlanEditor {
        let e = RoutePlanEditor()
        for i in 0 ..< count {
            e.add(stop(i))
        }
        computeAll(e)
        e.select(nil)
        return e
    }

    /// A zone of 100 m on the way of leg `leg` (its middle point, beside the straight line).
    private func zoneOn(_ e: RoutePlanEditor, _ leg: Int) -> AvoidZone {
        let p = e.plan.legs[leg].points
        return AvoidZone(lat: p[1].lat, lon: p[1].lon, radiusMeters: 100)
    }

    @Test("adding at the end makes one new leg to compute")
    func addAtEnd() {
        let e = RoutePlanEditor()
        #expect(e.add(stop(0)))
        #expect(e.plan.legs.isEmpty)
        #expect(e.plan.geometry.count == 1)
        e.add(stop(1))
        #expect(e.plan.pending == [0])
        #expect(computeAll(e) == 1)
        e.add(stop(2))
        #expect(e.plan.pending == [1])
        computeAll(e)
        check(e.plan)
        #expect(e.selected == 2)
        // The way of BRouter is kept and joined to the stops: 3 points per leg, the shared one once.
        #expect(e.plan.geometry.count == 5)
        #expect(e.plan.geometry[1].ele == 1100.0)
    }

    @Test("adding at the start puts the stop before the first")
    func addAtStart() {
        let e = editorWith(2)
        e.add(stop(9), atStart: true)
        #expect(e.plan.stops[0] == stop(9))
        #expect(e.plan.pending == [0])
        #expect(e.selected == 0)
        // The next one goes before it again.
        e.add(stop(8), atStart: true)
        #expect(e.plan.stops == [stop(8), stop(9), stop(0), stop(1)])
        computeAll(e)
        check(e.plan)
    }

    @Test("inserting on a leg splits it in two with its profile and mode")
    func insertOnLeg() {
        let e = editorWith(3)
        e.setLegProfile(1, RoutingProfiles.mtb)
        computeAll(e)
        let detour = RouteStop(lat: 45.016, lon: 6.0)
        #expect(e.insertOnLeg(1, detour))
        #expect(e.plan.stops == [stop(0), stop(1), detour, stop(2)])
        #expect(e.plan.pending == [1, 2])
        #expect(e.plan.legs[1].profile == RoutingProfiles.mtb)
        #expect(e.plan.legs[2].profile == RoutingProfiles.mtb)
        #expect(e.plan.legs[0].state == .ok)
        computeAll(e)
        check(e.plan)
    }

    @Test("the leg nearest to the crosshair is found along the way")
    func nearestLeg() throws {
        let e = editorWith(3)
        let mid = e.plan.legs[1].points[1]
        let found = try #require(e.plan.nearestLeg(mid.lat, mid.lon))
        #expect(found.leg == 1)
        #expect(found.meters < 1.0)
    }

    @Test("moving a stop computes both neighbours again")
    func moveStop() {
        let e = editorWith(3)
        e.select(1)
        #expect(e.moveSelected(45.02, 6.02))
        #expect(e.plan.pending == [0, 1])
        computeAll(e)
        check(e.plan)
        // The end has one neighbour only.
        e.select(2)
        e.moveSelected(45.05, 6.05)
        #expect(e.plan.pending == [1])
    }

    @Test("a stop taken at the crosshair is put down in one undoable step")
    func takenStop() {
        let e = editorWith(3)
        computeAll(e)
        let before = e.plan
        #expect(e.move(1, 45.04, 6.04))
        #expect(e.plan.stops[1].lat == 45.04)
        #expect(e.selected == 1)
        #expect(e.plan.pending == [0, 1])
        #expect(!e.move(3, 45.0, 6.0))
        #expect(e.undo())
        #expect(e.plan == before)
    }

    @Test("deleting a stop merges the two legs into one")
    func deleteStop() {
        let e = editorWith(4)
        e.select(2)
        #expect(e.deleteSelected())
        #expect(e.plan.stops == [stop(0), stop(1), stop(3)])
        #expect(e.plan.pending == [1])
        computeAll(e)
        check(e.plan)
        // The ends just lose their leg.
        #expect(e.delete(0))
        #expect(e.plan.stops == [stop(1), stop(3)])
        #expect(e.plan.pending.isEmpty)
        #expect(e.delete(1))
        #expect(e.delete(0))
        #expect(e.plan.stops.isEmpty)
        #expect(e.selected == nil)
    }

    @Test("a leg drawn by hand is a straight line and can be routed again")
    func legByHand() {
        let e = editorWith(3)
        #expect(e.setLegMode(0, .straight))
        #expect(e.plan.legs[0].points.count == 2)
        #expect(e.plan.legs[0].isByHand)
        #expect(e.plan.pending.isEmpty)
        #expect(!e.setLegMode(0, .straight))
        // Routed again: the way computed before comes back from the cache, BRouter is not asked.
        #expect(e.setLegMode(0, .routed))
        #expect(e.plan.pending.isEmpty)
        #expect(e.plan.legs[0].points.count == 3)
    }

    @Test("changing the profile of all legs computes the routed ones only")
    func profileOfAllLegs() {
        let e = editorWith(3)
        e.setLegMode(1, .straight)
        #expect(e.setProfile(RoutingProfiles.bike, allLegs: true))
        #expect(e.plan.pending == [0])
        #expect(e.plan.legs[1].mode == .straight)
        #expect(e.profile == RoutingProfiles.bike)
        // Only the new legs: nothing changes now, the next leg takes it.
        #expect(!e.setProfile(RoutingProfiles.car, allLegs: false))
        computeAll(e)
        e.add(stop(3))
        #expect(e.plan.legs.last?.profile == RoutingProfiles.car)
    }

    @Test("reversing turns the stops and computes the routed legs again")
    func reversing() {
        let e = editorWith(3)
        e.setLegMode(1, .straight)
        e.select(0)
        #expect(e.reverse())
        #expect(e.plan.stops == [stop(2), stop(1), stop(0)])
        #expect(e.selected == 2)
        #expect(e.plan.legs[0].mode == .straight)
        #expect(e.plan.pending == [1])
        computeAll(e)
        check(e.plan)
        // Twice: back to the ways already known, nothing asked.
        e.reverse()
        #expect(e.plan.pending.isEmpty)
    }

    @Test("loop and out and back add the way home")
    func loopAndOutAndBack() {
        let e = editorWith(3)
        #expect(e.loopToStart())
        #expect(e.plan.stops.count == 4)
        #expect(e.plan.isLoop)
        #expect(e.plan.pending == [2])
        #expect(!e.loopToStart())

        let o = editorWith(3)
        o.setLegMode(0, .straight)
        #expect(o.outAndBack())
        #expect(o.plan.stops == [stop(0), stop(1), stop(2), stop(1), stop(0)])
        #expect(o.plan.legs[3].mode == .straight)
        #expect(o.plan.pending == [2])
        computeAll(o)
        check(o.plan)
    }

    @Test("starting from a stop drops what is before, or turns a loop")
    func startFrom() {
        let e = editorWith(4)
        #expect(e.stopsDroppedBy(2) == 2)
        #expect(e.startFrom(2))
        #expect(e.plan.stops == [stop(2), stop(3)])
        #expect(e.plan.pending.isEmpty)

        let loop = editorWith(4)
        loop.loopToStart()
        computeAll(loop)
        let length = loop.plan.stopDistances()[loop.plan.stops.count - 1]
        #expect(loop.stopsDroppedBy(2) == 0)
        #expect(loop.startFrom(2))
        #expect(loop.plan.stops == [stop(2), stop(3), stop(0), stop(1), stop(2)])
        #expect(loop.plan.pending.isEmpty)
        check(loop.plan)
        let turned = loop.plan.stopDistances()[loop.plan.stops.count - 1]
        #expect(abs(turned - length) <= 1e-6)
    }

    @Test("undo and redo cover everything and never ask again")
    func undoRedo() {
        let e = editorWith(3)
        e.select(1)
        e.moveSelected(45.03, 6.03)
        computeAll(e)
        let moved = e.plan
        #expect(e.undo())
        #expect(e.plan.pending.isEmpty)
        #expect(e.plan.stops[1] == stop(1))
        #expect(e.redo())
        #expect(e.plan == moved)
        e.setProfile(RoutingProfiles.bike, allLegs: true)
        #expect(computeAll(e) == 2)
        e.undo()
        #expect(e.plan.legs[0].profile == RoutingProfiles.hiking)
        e.redo()
        #expect(e.plan.pending.count == 0)
    }

    @Test("a pending leg restored by undo is filled from the cache")
    func pendingFromCache() {
        let e = editorWith(2)
        e.select(1)
        e.moveSelected(45.05, 6.05)
        // Moved back before BRouter answered, then forward again: that way was never computed.
        e.undo()
        e.redo()
        #expect(e.plan.pending == [0])
        computeAll(e)
        e.undo()
        e.undo()
        e.redo()
        e.redo()
        #expect(!e.fillFromCache())
        #expect(e.plan.pending.isEmpty)
    }

    @Test("an answer for a leg changed meanwhile is not applied")
    func staleAnswer() {
        let e = editorWith(2)
        e.add(stop(2))
        let key = e.plan.key(1)
        e.select(2)
        e.moveSelected(45.09, 6.09)
        #expect(!e.applyResult(key, .ok(way(stop(1), stop(2)))))
        #expect(e.plan.pending == [1])
    }

    @Test("no data turns the leg into a straight line that can be recomputed")
    func noData() {
        let e = editorWith(2)
        e.add(stop(2))
        e.applyResult(e.plan.key(1), .failed(LegResult.Failed(.noData, "datafile E5_N45.rd5 not found")))
        #expect(e.plan.legs[1].state == .noData)
        #expect(e.plan.legs[1].isByHand)
        #expect(e.recompute(1))
        #expect(e.plan.pending == [1])
        #expect(!e.recompute(0))
    }

    @Test("new legs are drawn by hand when the routing is off")
    func routingOff() {
        let e = editorWith(2)
        e.routing = false
        e.add(stop(2))
        #expect(e.plan.legs[1].mode == .straight)
        #expect(e.plan.pending.isEmpty)
        #expect(!e.plan.isManual)
        e.routing = true
        // A route drawn by hand all along follows the ways when asked.
        let m = RoutePlanEditor()
        m.routing = false
        for i in 0 ..< 3 {
            m.add(stop(i))
        }
        #expect(m.plan.isManual)
        #expect(m.routeAll(RoutingProfiles.bike))
        #expect(m.plan.pending == [0, 1])
    }

    @Test("an unknown profile falls back to the previous one")
    func unknownProfile() {
        let e = editorWith(2)
        e.setProfile("vtt-perso", allLegs: true)
        #expect(e.replaceProfile("vtt-perso", RoutingProfiles.hiking))
        #expect(e.profile == RoutingProfiles.hiking)
        #expect(e.plan.legs[0].profile == RoutingProfiles.hiking)
        // The hiking way is known: nothing to ask.
        #expect(e.plan.pending.isEmpty)
    }

    @Test("free hand strokes become passages linked by straight lines")
    func freeHand() {
        let e = RoutePlanEditor()
        e.routing = false
        let stroke = (0 ..< 50).map { TrackPoint(lat: 45.0 + Double($0) * 0.0002, lon: 6.0 + Double($0 % 2) * 0.00001) }
        #expect(e.addStroke(stroke, 5.0))
        #expect(e.plan.stops.count >= 2)
        #expect(e.plan.isManual)
        check(e.plan)
    }

    @Test("simplify keeps the ends and the named stops")
    func simplify() {
        let e = RoutePlanEditor()
        e.routing = false
        for i in 0 ..< 10 {
            let named = i == 4
            e.add(RouteStop(lat: 45.0 + Double(i) * 0.001, lon: 6.0, kind: named ? .named : .passage, name: named ? "Col" : nil))
        }
        #expect(e.simplify(5.0))
        #expect(e.plan.stops.count == 3)
        #expect(e.plan.stops[1].name == "Col")
        check(e.plan)
        #expect(e.endAt(1))
        #expect(e.plan.stops.count == 2)
    }

    @Test("distances to the stops follow the way")
    func stopDistances() {
        let e = editorWith(3)
        let d = e.plan.stopDistances()
        #expect(d[0] == 0.0)
        let straight = Geodesy.distance(stop(0).lat, stop(0).lon, stop(1).lat, stop(1).lon)
        #expect(d[1] > straight)
        #expect(d[2] > d[1])
    }

    @Test("a new zone only asks again for the legs whose way crosses it")
    func newZone() {
        let e = editorWith(4)
        let before = e.plan
        let zone = zoneOn(e, 1)
        #expect(e.addZone(zone))
        #expect(e.plan.zones == [zone])
        #expect(e.plan.pending == [1])
        #expect(e.plan.legs[0] == before.legs[0])
        #expect(e.plan.legs[2] == before.legs[2])
        // The leg is asked with the zone: its key differs from the one computed without it.
        #expect(e.plan.key(1).nogos == AvoidZone.nogos([zone]))
        #expect(computeAll(e) == 1)
        check(e.plan)
    }

    @Test("removing a zone asks again for the routed legs, the ways known before coming from the cache")
    func removeZone() {
        let e = editorWith(3)
        e.setLegMode(0, .straight)
        e.addZone(zoneOn(e, 1))
        computeAll(e)
        #expect(e.removeZone(0))
        #expect(e.plan.zones.isEmpty)
        // The way of leg 1 without the zone is in the cache; leg 0 is drawn by hand and does not change.
        #expect(e.plan.pending.isEmpty)
        #expect(e.plan.legs[0].mode == .straight)
        #expect(!e.removeZone(0))
    }

    @Test("a zone grown around itself is only stricter, a moved one asks every routed leg again")
    func replaceZone() {
        let e = editorWith(3)
        let zone = zoneOn(e, 1)
        e.addZone(zone)
        computeAll(e)
        #expect(e.replaceZone(0, zone.copy(radiusMeters: 150)))
        #expect(e.plan.pending == [1])
        computeAll(e)
        #expect(e.replaceZone(0, zone.copy(lat: zone.lat + 0.01)))
        #expect(e.plan.pending == [0, 1])
        #expect(!e.replaceZone(0, e.plan.zones[0]))
    }

    @Test("zones stay through the other edits, undo and clear")
    func zonesStay() {
        let e = editorWith(3)
        let zone = AvoidZone(lat: 46.0, lon: 7.0, radiusMeters: 500)
        e.addZone(zone)
        #expect(e.plan.pending.isEmpty)
        e.add(stop(5))
        e.reverse()
        e.insertOnLeg(0, stop(7))
        e.delete(1)
        e.outAndBack()
        e.startFrom(1)
        computeAll(e)
        #expect(e.plan.zones == [zone])
        check(e.plan)
        #expect(e.clear())
        #expect(e.plan.zones.isEmpty)
        e.undo()
        #expect(e.plan.zones == [zone])
        for _ in 0 ..< 20 {
            e.undo()
        }
        #expect(e.plan.zones.isEmpty)
    }

    @Test("zones alone can be placed and cleared before the first stop")
    func zonesAlone() {
        let e = RoutePlanEditor()
        #expect(e.addZone(AvoidZone(lat: 45.0, lon: 6.0, radiusMeters: 250)))
        #expect(e.add(stop(0)))
        #expect(e.plan.zones.count == 1)
        e.delete(0)
        #expect(e.clear())
        #expect(!e.clear())
    }

    @Test("the cache forgets the oldest legs")
    func cacheForgets() {
        let cache = LegCache(capacity: 2)
        let ok = LegResult.Ok([TrackPoint(lat: 0.0, lon: 0.0)], nil)
        for i in 0 ..< 3 {
            cache.put(LegKey.of(stop(i), stop(i + 1), "p"), ok)
        }
        #expect(cache.size() == 2)
        #expect(cache.get(LegKey.of(stop(0), stop(1), "p")) == nil)
    }

    @Test("points of interest change no leg, outlive the edits of the stops and are undone")
    func pointsOfInterest() {
        let e = editorWith(3)
        let legs = e.plan.legs
        let spring = RoutePoi(lat: 45.005, lon: 6.004, name: "Source du Pré", sym: "Drinking Water")
        #expect(e.addPoi(spring))
        #expect(e.plan.pois == [spring])
        #expect(e.plan.legs == legs, "a point of interest is not a stop: the way stays")
        #expect(e.plan.pending.isEmpty)
        e.add(stop(3))
        computeAll(e)
        e.reverse()
        computeAll(e)
        check(e.plan)
        #expect(e.plan.pois == [spring], "kept through the edits of the stops")
        let renamed = RoutePoi(lat: spring.lat, lon: spring.lon, name: "Fontaine", sym: "Water Source")
        #expect(e.replacePoi(0, renamed))
        #expect(!e.replacePoi(0, renamed), "the same again changes nothing")
        #expect(e.removePoi(0))
        #expect(e.plan.pois.isEmpty)
        e.undo()
        #expect(e.plan.pois == [renamed])
        e.undo()
        #expect(e.plan.pois == [spring])
        #expect(e.clear())
        #expect(e.plan.pois.isEmpty)
    }

    @Test("a point of interest stands at its distance along the way, beside it")
    func poiPlace() throws {
        let a = RouteStop(lat: 45.0, lon: 6.0)
        let b = RouteStop(lat: 45.01, lon: 6.0)
        let plan = RoutePlan(
            stops: [a, b],
            legs: [RouteLeg.straight(a, b, RoutingProfiles.hiking)],
            pois: [RoutePoi(lat: 45.005, lon: 6.001, name: "Source")]
        )
        let place = try #require(plan.placeOf(0))
        // Halfway along 1.11 km, and 0.001° of longitude (about 79 m) east of the way.
        #expect(abs(place.alongMeters - Geodesy.distance(45.0, 6.0, 45.005, 6.0)) <= 1.0)
        #expect(abs(place.offMeters - Geodesy.distance(45.005, 6.0, 45.005, 6.001)) <= 1.0)
        #expect(plan.nearestPoi(45.0051, 6.001, 30.0) == 0)
        #expect(plan.nearestPoi(45.006, 6.001, 30.0) == nil)
        #expect(plan.placeOf(1) == nil)
    }
}
