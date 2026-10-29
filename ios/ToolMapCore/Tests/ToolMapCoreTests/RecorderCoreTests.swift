// Port of core/src/test/kotlin/fr/toolmap/core/record/RecorderCoreTest.kt (RecorderCoreTest and RecorderProfileTest).

import Testing
@testable import ToolMapCore

struct RecorderCoreTests {
    /// A walker going north at about 1.4 m/s: one fix a second, 0.0000126 degree is 1.4 m.
    private func fix(
        _ second: Int,
        lat: Double? = nil,
        ele: Double? = nil,
        accuracy: Double? = 5.0,
        speed: Double? = nil
    ) -> Fix {
        Fix(
            lat: lat ?? 45.0 + Double(second) * 0.0000126,
            lon: 6.0,
            ele: ele,
            timeMillis: 1_000_000 + Int64(second) * 1000,
            accuracyMeters: accuracy,
            speedMps: speed
        )
    }

    private func started(_ settings: RecorderCoreSettings = RecorderCoreSettings()) throws -> RecorderCore {
        let r = RecorderCore(settings)
        try r.start(1_000_000)
        return r
    }

    private let everyFix = RecorderCoreSettings(minIntervalMillis: 0, minDistanceMeters: 0.0)

    /// Kotlin's `accepted`: the fix must be kept; gives whether it starts a segment.
    @discardableResult
    private func accepted(_ r: RecorderCore, _ f: Fix) -> Bool {
        switch r.onFix(f) {
        case let .accepted(_, newSegment):
            return newSegment
        case .rejected:
            Issue.record("the fix was rejected")
            return false
        }
    }

    /// Kotlin's `rejected`: the fix must be refused; gives why.
    private func rejected(_ r: RecorderCore, _ f: Fix) -> RejectReason? {
        switch r.onFix(f) {
        case .accepted:
            Issue.record("the fix was accepted")
            return nil
        case let .rejected(reason):
            return reason
        }
    }

    @Test("nothing is kept before start or while paused")
    func nothingBeforeStart() throws {
        let r = RecorderCore()
        #expect(rejected(r, fix(0)) == .notRecording)
        try r.start(1_000_000)
        #expect(accepted(r, fix(0)))
        r.pause(1_005_000)
        #expect(rejected(r, fix(10)) == .notRecording)
        #expect(throws: RecorderStateError.self) {
            try r.start(0)
        }
    }

    @Test("interval and distance thresholds both apply")
    func thresholds() throws {
        let r = try started(RecorderCoreSettings(minIntervalMillis: 2_000, minDistanceMeters: 5.0))
        accepted(r, fix(0))
        #expect(rejected(r, fix(1)) == .tooSoon)
        // 2 s later but only 2.8 m away: too close.
        #expect(rejected(r, fix(2)) == .tooClose)
        // 4 s, 5.6 m: kept, in the same segment.
        #expect(!accepted(r, fix(4)))
        #expect(r.stats(1_004_000).pointCount == 2)
        #expect(abs(r.stats(1_004_000).distanceMeters - 5.6) <= 0.2)
    }

    @Test("poor accuracy and a clock running backwards are refused")
    func refused() throws {
        let r = try started()
        accepted(r, fix(10))
        #expect(rejected(r, fix(20, accuracy: 80.0)) == .accuracy)
        #expect(rejected(r, fix(5)) == .timeBackwards)
        // Unknown accuracy is not a reason to refuse.
        accepted(r, fix(30, accuracy: nil))
    }

    @Test("a pause ends the segment and is not counted as time or distance")
    func pauseEndsSegment() throws {
        let r = try started()
        accepted(r, fix(0))
        accepted(r, fix(10))
        r.pause(1_010_000)
        r.resume(1_110_000)
        // 100 s of pause and a long way: the first fix after resuming opens a new segment, whatever the distance.
        #expect(accepted(r, fix(110, lat: 45.5)))
        accepted(r, fix(120, lat: 45.5 + 0.00014))
        let s = r.stats(1_120_000)
        #expect(s.segmentCount == 2)
        #expect(s.pointCount == 4)
        // 14 m before the pause, 15.6 m after it, and nothing for the 55 km between.
        #expect(abs(s.distanceMeters - 29.6) <= 0.5)
    }

    @Test("elapsed time excludes pauses")
    func elapsedExcludesPauses() throws {
        let r = try started()
        accepted(r, fix(0))
        r.pause(1_060_000)
        #expect(r.stats(1_500_000).elapsedMillis == 60_000)
        r.resume(1_500_000)
        #expect(r.stats(1_510_000).elapsedMillis == 70_000)
        r.stop(1_520_000)
        #expect(r.stats(9_999_999).elapsedMillis == 80_000)
        #expect(r.state == .idle)
    }

    @Test("a long silence starts a new segment")
    func longSilence() throws {
        let r = try started(RecorderCoreSettings(gapNewSegmentMillis: 60_000))
        accepted(r, fix(0))
        #expect(!accepted(r, fix(30)))
        #expect(accepted(r, fix(200)))
        #expect(r.stats(1_200_000).segmentCount == 2)
    }

    @Test("an isolated jump is dropped, a lasting move is followed in a new segment")
    func jumps() throws {
        let r = try started()
        accepted(r, fix(0))
        accepted(r, fix(4))
        // 55 km away in 4 s.
        #expect(rejected(r, fix(8, lat: 45.5)) == .jump)
        // Back on the path: the wild fix did not count.
        #expect(!accepted(r, fix(12)))
        // Four wild fixes in a row: the phone really is elsewhere.
        #expect(rejected(r, fix(16, lat: 46.0)) == .jump)
        #expect(rejected(r, fix(20, lat: 46.0)) == .jump)
        #expect(rejected(r, fix(24, lat: 46.0)) == .jump)
        #expect(accepted(r, fix(28, lat: 46.0)))
        #expect(r.stats(1_028_000).segmentCount == 2)
    }

    @Test("climb ignores wobbles and single wild altitudes")
    func climb() throws {
        let r = try started(everyFix)
        // 1000 m, a wild 1400 m reading, then a steady climb of 2 m per fix up to 1040 m with 1 m wobbles.
        accepted(r, fix(0, ele: 1000.0))
        accepted(r, fix(1, ele: 1400.0))
        for i in 2 ... 21 {
            let wobble = i % 2 == 0 ? 1.0 : 0.0
            accepted(r, fix(i, ele: 1000.0 + Double((i - 1) * 2) + wobble))
        }
        let s = r.stats(1_021_000)
        #expect((30.0 ... 45.0).contains(s.ascentMeters), "ascent \(s.ascentMeters)")
        #expect(abs(s.descentMeters) <= 6.0)
        let maxEle = try #require(s.maxEleMeters)
        #expect(maxEle < 1100.0, "the 1400 m reading is not the maximum: \(maxEle)")
    }

    @Test("current speed comes from the receiver or from the last seconds")
    func currentSpeed() throws {
        let r = try started(everyFix)
        for i in 0 ... 12 {
            accepted(r, fix(i))
        }
        let computed = try #require(r.stats(1_012_000).currentSpeedMps)
        #expect(abs(computed - 1.4) <= 0.1)
        accepted(r, fix(13, speed: 3.2))
        let reported = try #require(r.stats(1_013_000).currentSpeedMps)
        #expect(abs(reported - 3.2) <= 1e-9)
        let fresh = try started()
        #expect(fresh.stats(1_000_000).currentSpeedMps == nil)
    }

    @Test("moving time leaves out the stops")
    func movingTime() throws {
        let r = try started(everyFix)
        accepted(r, fix(0))
        accepted(r, fix(10)) // 10 s walked
        accepted(r, fix(20, lat: fix(10).lat)) // 10 s standing still
        #expect(r.stats(1_020_000).movingMillis == 10_000)
    }

    @Test("replay rebuilds the figures and waits for the user to resume")
    func replay() throws {
        let first = (0 ..< 5).map {
            TrackPoint(lat: 45.0 + Double($0) * 0.0001, lon: 6.0, ele: 1000.0 + Double($0), timeMillis: 1_000_000 + Int64($0) * 10_000)
        }
        let second = (0 ..< 3).map {
            TrackPoint(lat: 45.1 + Double($0) * 0.0001, lon: 6.0, ele: nil, timeMillis: 2_000_000 + Int64($0) * 10_000)
        }
        let r = RecorderCore()
        try r.replay([first, second], activeMillis: 90_000)
        #expect(r.state == .paused)
        let s = r.stats(5_000_000)
        #expect(s.pointCount == 8)
        #expect(s.segmentCount == 2)
        #expect(s.elapsedMillis == 90_000)
        #expect(abs(s.distanceMeters - 6 * 11.119) <= 1.0)
        r.resume(5_000_000)
        #expect(accepted(r, Fix(lat: 45.2, lon: 6.0, ele: nil, timeMillis: 5_000_000, accuracyMeters: 5.0)))
        #expect(r.stats(5_001_000).lastFix != nil)
        #expect(r.stats(5_001_000).segmentCount == 3)
    }
}

struct RecorderProfileTests {
    @Test("profiles are looked up by name and default to walking")
    func lookedUpByName() {
        #expect(RecorderProfile.fromName("BIKE") == .bike)
        #expect(RecorderProfile.fromName("nope") == .walk)
        #expect(RecorderProfile.fromName(nil) == .walk)
    }

    @Test("denser profiles keep more points")
    func denserProfiles() {
        #expect(RecorderProfile.precise.settings.minDistanceMeters < RecorderProfile.walk.settings.minDistanceMeters)
        #expect(RecorderProfile.walk.settings.minDistanceMeters < RecorderProfile.bike.settings.minDistanceMeters)
        #expect(RecorderProfile.bike.settings.minDistanceMeters < RecorderProfile.saver.settings.minDistanceMeters)
    }
}
