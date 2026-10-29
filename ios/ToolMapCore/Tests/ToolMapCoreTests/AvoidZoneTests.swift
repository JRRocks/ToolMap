// Port of core/src/test/kotlin/fr/toolmap/core/route/AvoidZoneTest.kt.

import Testing
@testable import ToolMapCore

struct AvoidZoneTests {
    private let zone = AvoidZone(lat: 45.0, lon: 6.0, radiusMeters: 250)

    @Test("the nogos of BRouter are lon, lat and a whole radius, joined by a bar")
    func nogos() {
        #expect(AvoidZone.nogos([]) == "")
        let text = AvoidZone.nogos([zone, AvoidZone(lat: 45.12345678, lon: 5.98765432, radiusMeters: 1000)])
        #expect(text == "6.0000000,45.0000000,250|5.9876543,45.1234568,1000")
    }

    @Test("a way crosses the zone when one of its segments comes within the radius")
    func crosses() {
        // 0.002° of latitude is about 222 m: inside; 0.003° is about 333 m: outside.
        let through = [TrackPoint(lat: 45.002, lon: 5.99), TrackPoint(lat: 45.002, lon: 6.01)]
        let beside = [TrackPoint(lat: 45.003, lon: 5.99), TrackPoint(lat: 45.003, lon: 6.01)]
        #expect(zone.crosses(through))
        #expect(!zone.crosses(beside))
        #expect(zone.crosses([TrackPoint(lat: 45.0, lon: 6.001)]))
        #expect(zone.contains(45.002, 6.0))
        #expect(!zone.contains(45.003, 6.0))
    }

    @Test("a bigger circle around the same place covers the smaller one")
    func covers() {
        #expect(AvoidZone(lat: 45.0, lon: 6.0, radiusMeters: 500).covers(zone))
        #expect(AvoidZone(lat: 45.001, lon: 6.0, radiusMeters: 500).covers(zone))
        #expect(!AvoidZone(lat: 45.003, lon: 6.0, radiusMeters: 500).covers(zone))
        #expect(!zone.covers(AvoidZone(lat: 45.0, lon: 6.0, radiusMeters: 500)))
    }

    @Test("the handle gives sizes rounded to 50 m, from 50 m to 2 km")
    func resized() {
        #expect(AvoidZone.resized(262.0) == 250)
        #expect(AvoidZone.resized(276.0) == 300)
        #expect(AvoidZone.resized(3.0) == 50)
        #expect(AvoidZone.resized(5_000.0) == 2_000)
    }

    @Test("the handle sits on the circle, due east of the centre")
    func east() {
        let east = zone.east()
        #expect(abs(east.lat - zone.lat) <= 1e-9)
        #expect(east.lon > zone.lon)
        let d = Geodesy.distance(zone.lat, zone.lon, east.lat, east.lon)
        #expect(abs(d - 250.0) <= 1.0)
        let far = zone.east(1_500)
        let farD = Geodesy.distance(zone.lat, zone.lon, far.lat, far.lon)
        #expect(abs(farD - 1_500.0) <= 5.0)
    }

    @Test("the zone under the crosshair is the one whose centre is nearest")
    func zoneAt() {
        let plan = RoutePlan(zones: [
            AvoidZone(lat: 45.0, lon: 6.0, radiusMeters: 1000),
            AvoidZone(lat: 45.004, lon: 6.0, radiusMeters: 1000),
        ])
        #expect(plan.zoneAt(45.001, 6.0) == 0)
        #expect(plan.zoneAt(45.003, 6.0) == 1)
        #expect(plan.zoneAt(45.1, 6.0) == nil)
    }

    @Test("the zones are part of the key of a leg")
    func keys() {
        let a = RouteStop(lat: 45.0, lon: 6.0)
        let b = RouteStop(lat: 45.01, lon: 6.0)
        let between = AvoidZone(lat: 45.005, lon: 6.0, radiusMeters: 250)
        #expect(LegKey.of(a, b, "p") == LegKey.of(a, b, "p", zones: []))
        #expect(LegKey.of(a, b, "p") != LegKey.of(a, b, "p", zones: [between]))
    }

    @Test("a zone that holds an end of the leg is left out of it")
    func around() {
        let a = RouteStop(lat: 45.0, lon: 6.0)
        let b = RouteStop(lat: 45.01, lon: 6.0)
        let between = AvoidZone(lat: 45.005, lon: 6.0, radiusMeters: 250)
        let atB = AvoidZone(lat: 45.0101, lon: 6.0, radiusMeters: 100)
        // `zone` holds a: BRouter would find no way out of it.
        #expect(AvoidZone.around([zone, between, atB], a, b) == [between])
        #expect(LegKey.of(a, b, "p", zones: [between]) == LegKey.of(a, b, "p", zones: [zone, between, atB]))
        let leg = RouteLeg(
            profile: "p",
            mode: .routed,
            state: .pending,
            points: [TrackPoint(lat: a.lat, lon: a.lon), TrackPoint(lat: b.lat, lon: b.lon)]
        )
        let plan = RoutePlan(stops: [a, b], legs: [leg], zones: [zone, between])
        #expect(plan.legZones(0) == [between])
    }
}
