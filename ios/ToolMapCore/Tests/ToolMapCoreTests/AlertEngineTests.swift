// Port of core/src/test/kotlin/fr/toolmap/core/alert/AlertEngineTest.kt and Field.kt.

import Foundation
import Testing
@testable import ToolMapCore

/// A flat test field around 45°N 6°E: x meters east and y meters north of the origin (Kotlin's `Field`).
enum AlertField {
    private static let lat0 = 45.0
    private static let lon0 = 6.0
    private static let metersPerDegree = 111_194.9266
    private static let kx = metersPerDegree * cos(lat0 * Double.pi / 180.0)

    static func lat(_ y: Double) -> Double { lat0 + y / metersPerDegree }

    static func lon(_ x: Double) -> Double { lon0 + x / kx }

    static func point(_ x: Double, _ y: Double) -> LonLat { LonLat(lon: lon(x), lat: lat(y)) }

    static func fix(_ x: Double, _ y: Double, _ timeMillis: Int64 = 0, accuracy: Double? = 5.0) -> Fix {
        Fix(lat: lat(y), lon: lon(x), timeMillis: timeMillis, accuracyMeters: accuracy)
    }

    /// A straight line from `fromX` to `toX` at height `y`, with a vertex every `step` meters.
    static func line(_ fromX: Double, _ toX: Double, y: Double = 0.0, step: Double = 100.0) -> [LonLat] {
        var out: [LonLat] = []
        var x = fromX
        while x < toX {
            out.append(point(x, y))
            x += step
        }
        out.append(point(toX, y))
        return out
    }
}

struct AlertEngineTests {
    private let hut = WatchedPlace(id: 7, name: "Refuge", lat: AlertField.lat(0.0), lon: AlertField.lon(1000.0), radiusMeters: 100.0)

    private func engineWatching(_ places: [WatchedPlace], _ settings: AlertSettings = AlertSettings()) -> AlertEngine {
        let engine = AlertEngine(settings)
        engine.setWatched(places)
        return engine
    }

    private func walk(_ engine: AlertEngine, _ xs: [Double], y: Double = 0.0, startMillis: Int64 = 0) -> [(x: Double, update: AlertUpdate)] {
        var out: [(x: Double, update: AlertUpdate)] = []
        for (i, x) in xs.enumerated() {
            out.append((x: x, update: engine.onFix(AlertField.fix(x, y, startMillis + Int64(i) * 1000))))
        }
        return out
    }

    private func steps(_ from: Double, _ to: Double, _ step: Double = 25.0) -> [Double] {
        var out: [Double] = []
        var x = from
        if from <= to {
            while x <= to {
                out.append(x)
                x += step
            }
        } else {
            while x >= to {
                out.append(x)
                x -= step
            }
        }
        return out
    }

    private func fired(_ updates: [(x: Double, update: AlertUpdate)]) -> [(x: Double, update: AlertUpdate)] {
        updates.filter { !$0.update.events.isEmpty }
    }

    /// The ids of the proximity events.
    private func placeIds(_ events: [AlertEvent]) -> [Int64] {
        events.compactMap { event -> Int64? in
            if case let .proximity(place, _) = event { return place.id }
            return nil
        }
    }

    private func isOffTrack(_ event: AlertEvent?) -> Bool {
        if case .offTrack? = event { return true }
        return false
    }

    // --- proximity ----------------------------------------------------------------------------------------------

    @Test("entering a watched place fires once, at the first fix inside the radius")
    func enteringFiresOnce() throws {
        let engine = engineWatching([hut])
        // The steps are offset by 3 m so that no fix lies exactly on the 100 m edge.
        let hits = fired(walk(engine, steps(3.0, 1000.0)))
        try #require(hits.count == 1)
        #expect(hits[0].x == 903.0)
        try #require(hits[0].update.events.count == 1)
        guard case let .proximity(place, distance) = hits[0].update.events[0] else {
            Issue.record("not a proximity event")
            return
        }
        #expect(place.id == 7)
        #expect(abs(distance - 97.0) <= 1.0)
    }

    @Test("leaving past the exit margin re-arms the place")
    func leavingReArms() {
        let engine = engineWatching([hut])
        let route = steps(0.0, 1000.0) + steps(1000.0, 1150.0) + steps(1150.0, 800.0)
        #expect(fired(walk(engine, route)).count == 2)
    }

    @Test("jitter around the edge does not fire again")
    func jitter() {
        let engine = engineWatching([hut])
        // 99 m (enter), 110 m, 95 m, 120 m, 90 m: never beyond 125 m.
        let route = [0.0, 901.0, 890.0, 905.0, 880.0, 910.0]
        #expect(fired(walk(engine, route)).count == 1)
    }

    @Test("a place already entered when first seen is entered silently")
    func enteredSilently() {
        let engine = engineWatching([hut])
        let first = engine.onFix(AlertField.fix(950.0, 0.0, 0))
        #expect(first.events.isEmpty)
        // Still inside: nothing. Out and in again: fires.
        #expect(engine.onFix(AlertField.fix(960.0, 0.0, 1000)).events.isEmpty)
        engine.onFix(AlertField.fix(1200.0, 0.0, 2000))
        #expect(engine.onFix(AlertField.fix(950.0, 0.0, 3000)).events.count == 1)
    }

    @Test("each place has its own radius and state")
    func ownRadius() {
        let near = WatchedPlace(id: 1, name: "A", lat: AlertField.lat(0.0), lon: AlertField.lon(300.0), radiusMeters: 30.0)
        let far = WatchedPlace(id: 2, name: "B", lat: AlertField.lat(0.0), lon: AlertField.lon(300.0), radiusMeters: 200.0)
        let engine = engineWatching([near, far])
        engine.onFix(AlertField.fix(0.0, 0.0, 0))
        let at150 = engine.onFix(AlertField.fix(150.0, 0.0, 1000)).events
        #expect(placeIds(at150) == [2])
        #expect(at150.count == 1)
        let at280 = engine.onFix(AlertField.fix(280.0, 0.0, 2000)).events
        #expect(placeIds(at280) == [1])
        #expect(at280.count == 1)
    }

    @Test("a place removed from the watch list is forgotten and one added later is primed again")
    func removedPlaceForgotten() {
        let engine = engineWatching([hut])
        engine.onFix(AlertField.fix(0.0, 0.0, 0))
        engine.setWatched([])
        #expect(engine.onFix(AlertField.fix(950.0, 0.0, 1000)).events.isEmpty)
        engine.setWatched([hut])
        #expect(engine.onFix(AlertField.fix(950.0, 0.0, 2000)).events.isEmpty)
    }

    @Test("proximity can be switched off")
    func proximityOff() {
        let engine = engineWatching([hut], AlertSettings(proximityEnabled: false))
        #expect(fired(walk(engine, steps(0.0, 1000.0))).isEmpty)
        engine.updateSettings(AlertSettings(proximityEnabled: true))
        engine.onFix(AlertField.fix(0.0, 0.0, 99_000))
        #expect(engine.onFix(AlertField.fix(950.0, 0.0, 100_000)).events.count == 1)
    }

    @Test("a fix that is too imprecise is ignored")
    func impreciseIgnored() {
        let engine = engineWatching([hut])
        engine.onFix(AlertField.fix(0.0, 0.0, 0))
        let vague = engine.onFix(AlertField.fix(1000.0, 0.0, 1000, accuracy: 200.0))
        #expect(vague.events.isEmpty)
        #expect(engine.onFix(AlertField.fix(1000.0, 0.0, 2000, accuracy: 10.0)).events.count == 1)
    }

    // --- following a line ---------------------------------------------------------------------------------------

    private func following(
        _ settings: AlertSettings = AlertSettings(offTrackMeters: 50),
        line: TrackFollower = TrackFollower(AlertField.line(0.0, 2000.0))
    ) -> AlertEngine {
        let engine = AlertEngine(settings)
        engine.setFollow(line)
        return engine
    }

    @Test("status tells how far along the line the walker is")
    func statusAlong() throws {
        let engine = following()
        let status = try #require(engine.onFix(AlertField.fix(500.0, 10.0, 0)).status)
        #expect(abs(status.offMeters - 10.0) <= 0.5)
        #expect(abs(status.alongMeters - 500.0) <= 2.0)
        #expect(abs(status.remainingMeters - 1500.0) <= 2.0)
        #expect(abs(status.totalMeters - 2000.0) <= 2.0)
        #expect(abs(status.fraction - 0.25) <= 0.002)
        #expect(!status.offTrack && !status.arrived)
    }

    @Test("there is no status without a followed line")
    func noStatusWithoutLine() {
        let engine = AlertEngine()
        #expect(engine.onFix(AlertField.fix(0.0, 0.0, 0)).status == nil)
        engine.setFollow(TrackFollower([]))
        #expect(engine.onFix(AlertField.fix(0.0, 0.0, 1000)).status == nil)
        engine.setFollow(TrackFollower(AlertField.line(0.0, 100.0)))
        #expect(engine.onFix(AlertField.fix(0.0, 0.0, 2000)).status != nil)
        engine.setFollow(nil)
        #expect(engine.onFix(AlertField.fix(0.0, 0.0, 3000)).status == nil)
    }

    @Test("leaving the line fires on the third fix beyond the limit, not before")
    func leavingOnThirdFix() throws {
        let engine = following()
        engine.onFix(AlertField.fix(500.0, 0.0, 0))
        #expect(engine.onFix(AlertField.fix(510.0, 80.0, 1000)).events.isEmpty)
        #expect(engine.onFix(AlertField.fix(520.0, 80.0, 2000)).events.isEmpty)
        let third = engine.onFix(AlertField.fix(530.0, 80.0, 3000))
        try #require(third.events.count == 1)
        guard case let .offTrack(distance, limit) = third.events[0] else {
            Issue.record("not an off-track event")
            return
        }
        #expect(abs(distance - 80.0) <= 1.0)
        #expect(limit == 50.0)
        let status = try #require(third.status)
        #expect(status.offTrack)
    }

    @Test("a single wild fix is not an alert")
    func singleWildFix() {
        let engine = following()
        engine.onFix(AlertField.fix(500.0, 0.0, 0))
        #expect(engine.onFix(AlertField.fix(500.0, 400.0, 1000)).events.isEmpty)
        #expect(engine.onFix(AlertField.fix(510.0, 0.0, 2000)).events.isEmpty)
        #expect(engine.onFix(AlertField.fix(520.0, 400.0, 3000)).events.isEmpty)
        #expect(engine.onFix(AlertField.fix(530.0, 0.0, 4000)).events.isEmpty)
    }

    @Test("while off the line the alert repeats every minute and not in between")
    func repeatsEveryMinute() {
        let engine = following()
        engine.onFix(AlertField.fix(500.0, 0.0, 0))
        var count = 0
        for i in 1 ... 130 {
            let events = engine.onFix(AlertField.fix(500.0, 80.0, Int64(i) * 1000)).events
            count += events.filter { isOffTrack($0) }.count
        }
        // Confirmed at 3 s, then at 63 s and 123 s.
        #expect(count == 3)
    }

    @Test("back on the line fires once, with a margin below the limit")
    func backOnLine() throws {
        let engine = following()
        engine.onFix(AlertField.fix(500.0, 0.0, 0))
        for i in 1 ... 3 {
            engine.onFix(AlertField.fix(500.0, 80.0, Int64(i) * 1000))
        }
        // 45 m: inside the limit but not by the margin yet.
        let between = engine.onFix(AlertField.fix(500.0, 45.0, 4000))
        #expect(between.events.isEmpty)
        let betweenStatus = try #require(between.status)
        #expect(betweenStatus.offTrack)
        let back = engine.onFix(AlertField.fix(500.0, 30.0, 5000))
        #expect(back.events == [.backOnTrack])
        let backStatus = try #require(back.status)
        #expect(!backStatus.offTrack)
        #expect(engine.onFix(AlertField.fix(500.0, 20.0, 6000)).events.isEmpty)
        // And it can fire again for the next excursion.
        for i in 7 ... 8 {
            engine.onFix(AlertField.fix(500.0, 90.0, Int64(i) * 1000))
        }
        let again = engine.onFix(AlertField.fix(500.0, 90.0, 9000)).events
        #expect(again.count == 1)
        #expect(isOffTrack(again.first))
    }

    @Test("the distance must also exceed the accuracy of the fix")
    func exceedAccuracy() {
        let engine = following()
        engine.onFix(AlertField.fix(500.0, 0.0, 0))
        for i in 1 ... 6 {
            #expect(engine.onFix(AlertField.fix(500.0, 60.0, Int64(i) * 1000, accuracy: 80.0)).events.isEmpty)
        }
    }

    @Test("the limit comes from the settings and can be switched off")
    func limitFromSettings() throws {
        let tight = following(AlertSettings(offTrackMeters: 20))
        tight.onFix(AlertField.fix(500.0, 0.0, 0))
        for i in 1 ... 2 {
            tight.onFix(AlertField.fix(500.0, 30.0, Int64(i) * 1000))
        }
        let tightEvents = tight.onFix(AlertField.fix(500.0, 30.0, 3000)).events
        #expect(tightEvents.count == 1)
        #expect(isOffTrack(tightEvents.first))

        let off = following(AlertSettings(offTrackEnabled: false))
        off.onFix(AlertField.fix(500.0, 0.0, 0))
        for i in 1 ... 10 {
            #expect(off.onFix(AlertField.fix(500.0, 300.0, Int64(i) * 1000)).events.isEmpty)
        }
        let status = try #require(off.onFix(AlertField.fix(500.0, 300.0, 11_000)).status)
        #expect(!status.offTrack)
    }

    @Test("reaching the end fires once")
    func reachingEnd() throws {
        let engine = following()
        var events: [AlertEvent] = []
        for (i, x) in steps(0.0, 2000.0, 50.0).enumerated() {
            events += engine.onFix(AlertField.fix(x, 0.0, Int64(i) * 1000)).events
        }
        events += engine.onFix(AlertField.fix(2000.0, 5.0, 100_000)).events
        #expect(events == [.arrived])
        let status = try #require(engine.onFix(AlertField.fix(2000.0, 5.0, 101_000)).status)
        #expect(status.arrived)
    }

    @Test("a loop does not arrive at its own start")
    func loopDoesNotArriveAtStart() throws {
        let loop = TrackFollower([
            AlertField.point(0.0, 0.0),
            AlertField.point(500.0, 0.0),
            AlertField.point(500.0, 500.0),
            AlertField.point(0.0, 500.0),
            AlertField.point(0.0, 0.0),
        ])
        let engine = following(line: loop)
        let atStart = engine.onFix(AlertField.fix(0.0, 3.0, 0))
        #expect(atStart.events.isEmpty)
        let startStatus = try #require(atStart.status)
        #expect(abs(startStatus.alongMeters) <= 5.0)
        // Walk the loop: arrival only when back at the end.
        var path: [(Double, Double)] = []
        path += steps(0.0, 500.0, 25.0).map { ($0, 0.0) }
        path += steps(0.0, 500.0, 25.0).map { (500.0, $0) }
        path += steps(500.0, 0.0, 25.0).map { ($0, 500.0) }
        path += steps(500.0, 0.0, 25.0).map { (0.0, $0) }
        var arrivals = 0
        var wherePoint = -1
        for (i, p) in path.enumerated() {
            let update = engine.onFix(AlertField.fix(p.0, p.1, Int64(i + 1) * 1000))
            if update.events.contains(.arrived) {
                arrivals += 1
                wherePoint = i
            }
        }
        #expect(arrivals == 1)
        #expect(wherePoint >= path.count - 3, "arrived at fix \(wherePoint) of \(path.count)")
    }

    @Test("following backwards counts progress from the other end")
    func followingBackwards() throws {
        let engine = following(line: TrackFollower(AlertField.line(0.0, 2000.0)).reversedCopy())
        let status = try #require(engine.onFix(AlertField.fix(1800.0, 0.0, 0)).status)
        #expect(abs(status.alongMeters - 200.0) <= 2.0)
        #expect(abs(status.remainingMeters - 1800.0) <= 2.0)
    }

    @Test("a new line starts the progress again")
    func newLineStartsAgain() throws {
        let engine = following()
        for (i, x) in steps(0.0, 2000.0, 50.0).enumerated() {
            engine.onFix(AlertField.fix(x, 0.0, Int64(i) * 1000))
        }
        engine.setFollow(TrackFollower(AlertField.line(0.0, 500.0)))
        let status = try #require(engine.onFix(AlertField.fix(100.0, 0.0, 1_000_000)).status)
        #expect(!status.arrived)
        #expect(abs(status.alongMeters - 100.0) <= 2.0)
    }

    @Test("an imprecise fix leaves the last status in place")
    func impreciseKeepsStatus() throws {
        let engine = following()
        let good = try #require(engine.onFix(AlertField.fix(500.0, 0.0, 0)).status)
        let vague = engine.onFix(AlertField.fix(1500.0, 500.0, 1000, accuracy: 300.0))
        #expect(vague.events.isEmpty)
        #expect(vague.status == good)
    }

    @Test("fix without accuracy is accepted")
    func fixWithoutAccuracy() {
        let engine = following()
        let fix = Fix(lat: AlertField.lat(0.0), lon: AlertField.lon(500.0), timeMillis: 0)
        #expect(engine.onFix(fix).status != nil)
    }
}
