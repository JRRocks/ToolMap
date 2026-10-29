// Port of core/src/test/kotlin/fr/toolmap/core/record/PositionTrailTest.kt and SignalWatchTest.kt.

import Testing
@testable import ToolMapCore

struct PositionTrailTests {
    @Test("a motionless phone adds one point only")
    func motionless() {
        let trail = PositionTrail(minStepMeters: 3.0)
        for i in 0 ..< 20 {
            trail.add(44.8, 5.9, Int64(i) * 1000)
        }
        #expect(trail.points(20_000).count == 1)
    }

    @Test("points older than the maximum age are dropped")
    func oldPointsDropped() {
        let trail = PositionTrail(maxAgeMillis: 60_000, minStepMeters: 0.0)
        trail.add(44.80, 5.90, 0)
        trail.add(44.81, 5.90, 90_000)
        #expect(trail.points(120_000).map { $0.timeMillis } == [90_000])
    }

    @Test("the number of points is bounded, the newest are kept")
    func bounded() {
        let trail = PositionTrail(maxPoints: 3, minStepMeters: 0.0)
        for i in 0 ..< 5 {
            trail.add(44.8 + Double(i) * 0.001, 5.9, Int64(i) * 1000)
        }
        #expect(trail.points(4000).map { $0.timeMillis } == [2000, 3000, 4000])
    }
}

struct SignalWatchTests {
    @Test("a minute without a fix is lost, once")
    func lostOnce() {
        let watch = SignalWatch(lostAfterMillis: 60_000)
        watch.start(0)
        #expect(watch.check(59_999) == nil)
        #expect(watch.check(60_000) == .lost)
        #expect(watch.check(90_000) == nil)
        #expect(watch.isLost)
    }

    @Test("a fix after the loss says the signal is back, once")
    func recoveredOnce() {
        let watch = SignalWatch(lostAfterMillis: 60_000)
        watch.start(0)
        watch.check(120_000)
        #expect(watch.onFix(130_000) == .recovered)
        #expect(watch.onFix(131_000) == nil)
        #expect(!watch.isLost)
    }

    @Test("regular fixes keep the signal ok")
    func regularFixes() {
        let watch = SignalWatch(lostAfterMillis: 60_000)
        watch.start(0)
        for t in 1 ... 10 {
            #expect(watch.onFix(Int64(t) * 10_000) == nil)
            #expect(watch.check(Int64(t) * 10_000 + 5_000) == nil)
        }
        #expect(!watch.isLost)
    }
}
