// Port of core/src/test/kotlin/fr/toolmap/core/route/GoToTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct GoToTests {
    private let rules = GoToRules()

    /// About 1.1 km due north, a point every 0.001° (111 m), climbing 10 m from one to the next.
    private func north(_ profile: String = RoutingProfiles.hiking, time: Int64? = nil) -> GoToWay {
        let points = (0 ..< 11).map { TrackPoint(lat: 45.0 + Double($0) * 0.001, lon: 6.0, ele: 1000.0 + Double($0 * 10)) }
        return GoToWay(points: points, profile: profile, timeMillis: time, computedAtMillis: 0)
    }

    @Test("on the way, what remains is measured along it and the arrow leads ahead")
    func onTheWay() throws {
        let way = north()
        let start = try #require(GoTo.progress(way, 45.0, 6.0, rules))
        #expect(abs(start.remainingMeters - way.totalMeters) <= 1.0)
        let startAscent = try #require(start.remainingAscentMeters)
        #expect(abs(startAscent - 100.0) <= 15.0)
        let startMillis = try #require(start.remainingMillis)
        #expect(startMillis > 0)
        #expect(!start.offRoute)
        #expect(!start.arrived)
        #expect(start.headingDegrees < 5 || start.headingDegrees > 355, "north: \(start.headingDegrees)")

        let half = try #require(GoTo.progress(way, 45.005, 6.0, rules))
        #expect(abs(half.remainingMeters - way.totalMeters / 2) <= 2.0)
        let halfAscent = try #require(half.remainingAscentMeters)
        #expect(halfAscent < startAscent)
    }

    @Test("off the way, it says so and the arrow leads back to it")
    func offTheWay() throws {
        // 0.001° of longitude at 45° is about 79 m east of the way.
        let off = try #require(GoTo.progress(north(), 45.005, 6.001, rules))
        #expect(abs(off.offMeters - 79.0) <= 2.0)
        #expect(off.offRoute)
        #expect(abs(off.headingDegrees - 270.0) <= 5.0)
        let near = try #require(GoTo.progress(north(), 45.005, 6.0003, rules))
        #expect(!near.offRoute, "24 m off is still on it")
    }

    @Test("a profile that is not walking takes BRouter's time, in proportion")
    func brouterTime() throws {
        let way = north(RoutingProfiles.bike, time: 600_000)
        let half = try #require(GoTo.progress(way, 45.005, 6.0, rules))
        let millis = try #require(half.remainingMillis)
        #expect(abs(Double(millis) - 300_000.0) <= 5_000.0)
        let noTime = try #require(GoTo.progress(north(RoutingProfiles.bike), 45.005, 6.0, rules))
        #expect(noTime.remainingMillis == nil)
    }

    @Test("BRouter is asked again at the interval, at once off the way, and never once arrived")
    func askedAgain() throws {
        let way = north()
        let on = GoTo.progress(way, 45.005, 6.0, rules)
        let off = GoTo.progress(way, 45.005, 6.001, rules)
        let end = try #require(GoTo.progress(way, 45.01, 6.0, rules))
        #expect(end.arrived)
        #expect(GoTo.needsWay(nil, nil, nil, 0, rules), "no way yet")
        #expect(!GoTo.needsWay(way, on, 0, 60_000, rules))
        #expect(GoTo.needsWay(way, on, 0, 120_000, rules), "the interval elapsed")
        #expect(!GoTo.needsWay(way, off, 0, 10_000, rules), "off the way, but just asked")
        #expect(GoTo.needsWay(way, off, 0, 20_000, rules), "off the way")
        #expect(!GoTo.needsWay(way, end, 0, 999_000, rules), "arrived")
        // A question that gave no way (no data here) is asked again at the next interval, not at each position.
        #expect(!GoTo.needsWay(nil, nil, 0, 60_000, rules))
        #expect(GoTo.needsWay(nil, nil, 0, 120_000, rules))
    }

    @Test("a walker far from any path when the way was computed is not off it")
    func farFromAnyPath() throws {
        // Computed from 79 m east of the way: off the way only 50 m beyond that.
        let way = GoToWay(
            points: north().points,
            profile: RoutingProfiles.hiking,
            timeMillis: nil,
            computedAtMillis: 0,
            from: TrackPoint(lat: 45.0, lon: 6.001)
        )
        #expect(abs(way.startOffMeters - 79.0) <= 2.0)
        let there = try #require(GoTo.progress(way, 45.0, 6.001, rules))
        #expect(!there.offRoute)
        let farther = try #require(GoTo.progress(way, 45.0, 6.0018, rules))
        #expect(farther.offRoute)
    }

    @Test("the settings give the rules, and the time before the next question reads in minutes and seconds")
    func settingsAndCountdown() {
        #expect(GoToRules.of(5, 100) == GoToRules(intervalMillis: 300_000, offRouteMeters: 100.0))
        #expect(GoToRules.of(3, 60) == GoToRules(intervalMillis: 120_000, offRouteMeters: 50.0), "the nearest values offered")
        #expect(GoTo.countdown(99_500) == "1 min 40")
        #expect(GoTo.countdown(40_000) == "40 s")
        #expect(GoTo.countdown(120_000) == "2 min")
        #expect(GoTo.countdown(-5) == "0 s")
        #expect(GoTo.countdown(65_000) == "1 min 05")
    }

    @Test("the bearing is counted clockwise from the north")
    func bearing() {
        #expect(abs(GoTo.bearing(45.0, 6.0, 45.0, 6.01) - 90.0) <= 0.5)
        #expect(abs(GoTo.bearing(45.0, 6.0, 44.99, 6.0) - 180.0) <= 0.5)
        let west = 6.0 - 0.001 / cos(45.0 * Double.pi / 180.0)
        #expect(abs(GoTo.bearing(45.0, 6.0, 45.001, west) - 315.0) <= 1.0)
    }
}
