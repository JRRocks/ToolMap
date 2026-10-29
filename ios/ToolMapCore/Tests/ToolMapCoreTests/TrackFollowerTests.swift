// Port of core/src/test/kotlin/fr/toolmap/core/alert/TrackFollowerTest.kt (TrackFollowerTest and TrackFollowerHintTest).

import Testing
@testable import ToolMapCore

struct TrackFollowerTests {
    private let line = TrackFollower(AlertField.line(0.0, 1000.0))

    @Test("length of the line is its geodesic length")
    func length() {
        #expect(abs(line.totalMeters - 1000.0) <= 1.0)
    }

    @Test("a point beside the line is off by its distance and along at its foot")
    func pointBeside() throws {
        let p = try #require(line.project(AlertField.lat(20.0), AlertField.lon(500.0)))
        #expect(abs(p.offMeters - 20.0) <= 0.5)
        #expect(abs(p.alongMeters - 500.0) <= 1.5)
        // Vertex 5 is at 500 m: the point is as near the segment before it as the one after it.
        #expect(p.segment == 4 || p.segment == 5, "segment \(p.segment)")
        #expect(abs(p.nearestLat - AlertField.lat(0.0)) <= 1e-6)
        #expect(abs(p.nearestLon - AlertField.lon(500.0)) <= 1e-6)
    }

    @Test("either side of the line counts the same")
    func eitherSide() throws {
        let north = try #require(line.project(AlertField.lat(35.0), AlertField.lon(300.0)))
        let south = try #require(line.project(AlertField.lat(-35.0), AlertField.lon(300.0)))
        #expect(abs(north.offMeters - south.offMeters) <= 0.5)
        #expect(abs(north.alongMeters - south.alongMeters) <= 0.5)
    }

    @Test("before the start the nearest point is the start and after the end it is the end")
    func beforeAndAfter() throws {
        let before = try #require(line.project(AlertField.lat(0.0), AlertField.lon(-80.0)))
        #expect(abs(before.offMeters - 80.0) <= 0.5)
        #expect(abs(before.alongMeters) <= 0.5)
        let after = try #require(line.project(AlertField.lat(0.0), AlertField.lon(1060.0)))
        #expect(abs(after.offMeters - 60.0) <= 0.5)
        #expect(abs(after.alongMeters - line.totalMeters) <= 0.5)
    }

    @Test("a corner is handled by the nearest of its two segments")
    func corner() throws {
        let bent = TrackFollower([AlertField.point(0.0, 0.0), AlertField.point(500.0, 0.0), AlertField.point(500.0, 500.0)])
        let p = try #require(bent.project(AlertField.lat(250.0), AlertField.lon(520.0)))
        #expect(abs(p.offMeters - 20.0) <= 0.5)
        #expect(abs(p.alongMeters - 750.0) <= 1.5)
        #expect(p.segment == 1)
    }

    @Test("the hint picks the leg of an out and back that matches the progress")
    func hintPicksLeg() throws {
        let outAndBack = TrackFollower([
            AlertField.point(0.0, 0.0),
            AlertField.point(1000.0, 0.0),
            AlertField.point(1000.0, 10.0),
            AlertField.point(0.0, 10.0),
        ])
        let lat = AlertField.lat(5.0)
        let lon = AlertField.lon(500.0)
        let going = try #require(outAndBack.project(lat, lon, hintAlongMeters: 450.0))
        let coming = try #require(outAndBack.project(lat, lon, hintAlongMeters: 1500.0))
        #expect(abs(going.alongMeters - 500.0) <= 2.0)
        #expect(abs(coming.alongMeters - 1510.0) <= 2.0)
        #expect(abs(going.offMeters - 5.0) <= 0.5)
        #expect(abs(coming.offMeters - 5.0) <= 0.5)
    }

    @Test("the hint does not drag the answer to a leg that is clearly further")
    func hintNotDragged() throws {
        let outAndBack = TrackFollower([
            AlertField.point(0.0, 0.0),
            AlertField.point(1000.0, 0.0),
            AlertField.point(1000.0, 200.0),
            AlertField.point(0.0, 200.0),
        ])
        let p = try #require(outAndBack.project(AlertField.lat(10.0), AlertField.lon(500.0), hintAlongMeters: 1500.0))
        #expect(abs(p.alongMeters - 500.0) <= 2.0)
    }

    @Test("reversed follows the line from its end")
    func reversedLine() throws {
        let back = line.reversedCopy()
        #expect(back.reversed)
        #expect(abs(back.totalMeters - line.totalMeters) <= 0.001)
        let nearStart = try #require(back.project(AlertField.lat(0.0), AlertField.lon(100.0)))
        #expect(abs(nearStart.alongMeters - 900.0) <= 1.5)
        #expect(line.end == back.start)
        #expect(!back.reversedCopy().reversed)
    }

    @Test("consecutive duplicates are dropped and a lone point is not a line")
    func duplicates() {
        let repeated = TrackFollower(Array(repeating: AlertField.point(10.0, 10.0), count: 5))
        #expect(!repeated.usable)
        #expect(repeated.project(AlertField.lat(0.0), AlertField.lon(0.0)) == nil)
        #expect(repeated.totalMeters == 0.0)
        let withRepeats = TrackFollower([AlertField.point(0.0, 0.0), AlertField.point(0.0, 0.0), AlertField.point(100.0, 0.0)])
        #expect(withRepeats.usable)
        #expect(abs(withRepeats.totalMeters - 100.0) <= 0.5)
        #expect(TrackFollower([]).project(0.0, 0.0) == nil)
    }

    // TrackFollowerHintTest

    @Test("walking forward along a straight line the hint does not hold the progress back")
    func hintDoesNotHoldBack() throws {
        let straight = TrackFollower(AlertField.line(0.0, 1000.0))
        var along = 0.0
        for x in [30.0, 130.0, 180.0, 330.0, 345.0, 601.0, 999.0] {
            let p = try #require(straight.project(AlertField.lat(40.0), AlertField.lon(x), hintAlongMeters: along))
            #expect(abs(p.offMeters - 40.0) <= 0.5, "off at \(x)")
            #expect(abs(p.alongMeters - x) <= 1.5, "along at \(x)")
            along = p.alongMeters
        }
    }

    @Test("at the start of a loop the start wins over the end when the hint is the start")
    func loopStartWins() throws {
        let loop = TrackFollower([
            AlertField.point(0.0, 0.0),
            AlertField.point(500.0, 0.0),
            AlertField.point(500.0, 500.0),
            AlertField.point(0.0, 500.0),
            AlertField.point(0.0, 0.0),
        ])
        let p = try #require(loop.project(AlertField.lat(3.0), AlertField.lon(0.0), hintAlongMeters: 0.0))
        #expect(abs(p.alongMeters) <= 5.0)
        let end = try #require(loop.project(AlertField.lat(3.0), AlertField.lon(0.0), hintAlongMeters: 1990.0))
        #expect(abs(end.alongMeters - 1997.0) <= 5.0)
    }
}
