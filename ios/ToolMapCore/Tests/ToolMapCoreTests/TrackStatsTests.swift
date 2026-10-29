// Port of core/src/test/kotlin/fr/toolmap/core/track/TrackStatsTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

/// `java.util.Random`, so that the noisy profiles are the very ones of the Kotlin test.
private final class JavaRandom {
    private var seed: UInt64

    init(_ seed: Int64) {
        self.seed = (UInt64(bitPattern: seed) ^ 0x5_DEEC_E66D) & ((1 << 48) - 1)
    }

    private func next(_ bits: Int) -> Int64 {
        seed = (seed &* 0x5_DEEC_E66D &+ 0xB) & ((1 << 48) - 1)
        return Int64(Int32(truncatingIfNeeded: seed >> UInt64(48 - bits)))
    }

    func nextDouble() -> Double {
        Double((next(26) << 27) + next(27)) * 0x1.0p-53
    }
}

struct TrackStatsTests {
    /// Meters per degree of longitude on the equator for the sphere of `Geodesy`.
    private let metersPerDegree = Double.pi * Geodesy.earthRadiusMeters / 180.0

    /// A straight walk east along the equator: `count` points, `stepMeters` apart, `stepMillis` between fixes.
    private func walk(
        _ count: Int,
        stepMeters: Double = 10.0,
        stepMillis: Int64 = 7000,
        startMillis: Int64 = 1_700_000_000_000,
        _ ele: (Int) -> Double? = { _ in nil }
    ) -> [TrackPoint] {
        (0 ..< count).map { i in
            TrackPoint(
                lat: 0.0,
                lon: Double(i) * stepMeters / metersPerDegree,
                ele: ele(i),
                timeMillis: startMillis + Int64(i) * stepMillis
            )
        }
    }

    private func shifted(_ points: [TrackPoint], by lon: Double) -> [TrackPoint] {
        points.map { TrackPoint(lat: $0.lat, lon: $0.lon + lon, ele: $0.ele, timeMillis: $0.timeMillis, extras: $0.extras) }
    }

    @Test("distance of a straight walk and its duration and speed")
    func straightWalk() throws {
        let stats = TrackStatsCalculator.compute([walk(1001)])
        #expect(abs(stats.distance2dMeters - 10000.0) <= 0.5)
        #expect(stats.totalDurationMillis == 7_000_000)
        let average = try #require(stats.averageSpeedMps)
        let moving = try #require(stats.averageMovingSpeedMps)
        let maximum = try #require(stats.maxSpeedMps)
        #expect(abs(average - 10000.0 / 7000.0) <= 1e-3)
        #expect(abs(moving - average) <= 1e-3)
        #expect(abs(maximum - 10.0 / 7.0) <= 1e-3)
        #expect(stats.pointCount == 1001)
        #expect(stats.segmentCount == 1)
    }

    @Test("climb counts, noise does not")
    func climb() throws {
        let ramp = TrackStatsCalculator.compute([walk(1001) { Double($0) * 0.1 }])
        #expect(abs(ramp.ascentMeters - 100.0) <= 1e-6)
        #expect(abs(ramp.descentMeters - 0.0) <= 1e-6)
        let minEle = try #require(ramp.minEleMeters)
        let maxEle = try #require(ramp.maxEleMeters)
        #expect(abs(minEle - 0.0) <= 1e-6)
        #expect(abs(maxEle - 100.0) <= 1e-6)

        let random = JavaRandom(7)
        let noisy = TrackStatsCalculator.compute([walk(1001) { _ in 500 + (random.nextDouble() - 0.5) * 3 }])
        #expect(abs(noisy.ascentMeters - 0.0) <= 1e-6)
        #expect(abs(noisy.descentMeters - 0.0) <= 1e-6)

        let noisyRamp = TrackStatsCalculator.compute([walk(1001) { Double($0) * 0.1 + (random.nextDouble() - 0.5) * 3 }])
        #expect(abs(noisyRamp.ascentMeters - 100) < 8, "ascent \(noisyRamp.ascentMeters)")
        #expect(noisyRamp.descentMeters < 8, "descent \(noisyRamp.descentMeters)")
    }

    @Test("up, down, up swings are each counted in full")
    func swings() {
        let profile = (0 ..< 51).map { Double($0) } + (0 ..< 30).map { 49.0 - Double($0) } +
            (0 ..< 20).map { 20.0 + Double($0) + 1 }
        let stats = TrackStatsCalculator.compute([walk(profile.count) { profile[$0] }])
        // The median filter shaves about a meter off every turning point.
        #expect(abs(stats.ascentMeters - 70.0) <= 3.0)
        #expect(abs(stats.descentMeters - 30.0) <= 3.0)
    }

    @Test("a one-point altitude spike is removed by the median filter")
    func spike() throws {
        let stats = TrackStatsCalculator.compute([walk(200) { $0 == 100 ? 900.0 : 100.0 }])
        #expect(abs(stats.ascentMeters - 0.0) <= 1e-9)
        let maxEle = try #require(stats.maxEleMeters)
        #expect(abs(maxEle - 100.0) <= 1e-9)
    }

    @Test("3D distance adds the climb")
    func distance3d() {
        // 1000 m flat distance, 100 m of climb: sqrt(1000^2 + 100^2) when the slope is constant per step.
        let stats = TrackStatsCalculator.compute([walk(101) { Double($0) }])
        #expect(abs(stats.distance2dMeters - 1000.0) <= 0.1)
        #expect(abs(stats.distance3dMeters - (1000.0 * 1000.0 + 100.0 * 100.0).squareRoot()) <= 0.5)
        #expect(stats.distance3dMeters > stats.distance2dMeters)
    }

    @Test("stops are excluded from moving time but not from total time")
    func stops() throws {
        let moving1 = walk(100)
        let last = try #require(moving1.last)
        let lastTime = try #require(last.timeMillis)
        let stop = (0 ..< 100).map { TrackPoint(lat: 0.0, lon: last.lon, ele: nil, timeMillis: lastTime + Int64($0 + 1) * 7000) }
        let stopEnd = try #require(stop.last?.timeMillis)
        let moving2 = shifted(walk(100, startMillis: stopEnd + 7000), by: last.lon)
        let stats = TrackStatsCalculator.compute([moving1 + stop + moving2])
        let total = try #require(stats.totalDurationMillis)
        let moving = try #require(stats.movingDurationMillis)
        #expect(Double(moving) < Double(total) * 0.75 && Double(moving) > Double(total) * 0.55, "moving \(moving) of \(total)")
        let movingSpeed = try #require(stats.averageMovingSpeedMps)
        let averageSpeed = try #require(stats.averageSpeedMps)
        #expect(movingSpeed > averageSpeed)
    }

    @Test("segments are not joined by the gap between them")
    func segmentsApart() {
        let a = walk(11)
        let b = shifted(walk(11, startMillis: 1_800_000_000_000), by: 1.0)
        let stats = TrackStatsCalculator.compute([a, b])
        #expect(abs(stats.distance2dMeters - 200.0) <= 0.1)
        #expect(stats.segmentCount == 2)
        #expect(stats.totalDurationMillis == 1_800_000_000_000 + 70000 - 1_700_000_000_000)
    }

    @Test("DIN 33466 walking time")
    func walkingTime() {
        // 10 km with 600 m up and 600 m down: horizontal 2.5 h, vertical 2 h + 1.2 h = 3.2 h, so 3.2 + 1.25 h.
        let millis = TrackStatsCalculator.estimateWalkMillis(10000.0, 600.0, 600.0)
        #expect(millis == Int64(4.45 * 3_600_000))
        // Flat 12 km: 3 h; a short steep one: 200 m over 1 km up is 40 min vertical, 15 min horizontal.
        #expect(TrackStatsCalculator.estimateWalkMillis(12000.0, 0.0, 0.0) == 3 * 3_600_000)
        let steep = TrackStatsCalculator.estimateWalkMillis(1000.0, 200.0, 0.0)
        #expect(steep == Int64((200.0 / 300 + 0.25 / 2) * 3_600_000))
    }

    @Test("nothing to measure")
    func nothing() {
        let none = TrackStatsCalculator.compute([])
        #expect(none.pointCount == 0)
        #expect(none.distance2dMeters == 0.0)
        #expect(none.totalDurationMillis == nil)
        #expect(none.minEleMeters == nil)
        #expect(none.maxSpeedMps == nil)
        let single = TrackStatsCalculator.compute([[TrackPoint(lat: 1.0, lon: 2.0, ele: 3.0, timeMillis: 4)]])
        #expect(single.pointCount == 1)
        #expect(single.minEleMeters == 3.0)
        #expect(single.totalDurationMillis == nil)
    }

    @Test("real hike has coherent numbers")
    func realHike() throws {
        let doc = try GpxReader.read(Fixtures.data("gpx/korita-zbevnica.gpx"))
        let track = try #require(doc.tracks.max(by: { $0.pointCount < $1.pointCount }))
        let stats = TrackStatsCalculator.compute(track.segments)
        // Independent reference: the same haversine, summed with plain loops.
        var reference = 0.0
        for segment in track.segments where segment.count > 1 {
            for i in 1 ..< segment.count {
                reference += Geodesy.distance(segment[i - 1].lat, segment[i - 1].lon, segment[i].lat, segment[i].lon)
            }
        }
        #expect(abs(stats.distance2dMeters - reference) <= 1e-6)
        #expect(stats.distance2dMeters > 1000, "distance \(stats.distance2dMeters)")
        #expect(stats.distance3dMeters >= stats.distance2dMeters)
        #expect(stats.ascentMeters > 0 && stats.descentMeters > 0)
        let minEle = try #require(stats.minEleMeters)
        let maxEle = try #require(stats.maxEleMeters)
        #expect(minEle <= maxEle)
        #expect(stats.estimatedWalkMillis > 0)
    }

    @Test("Garmin run has times, moving time and speeds")
    func garminRun() throws {
        let doc = try GpxReader.read(Fixtures.data("gpx/garmin-connect-run.gpx"))
        let track = try #require(doc.tracks.first)
        let stats = TrackStatsCalculator.compute(track.segments)
        #expect(stats.startTimeMillis == TrackTime.parse("2012-10-24T23:29:40.000Z"))
        #expect(stats.endTimeMillis == TrackTime.parse("2012-10-24T23:45:28.000Z"))
        #expect(stats.totalDurationMillis == 948_000)
        let moving = try #require(stats.movingDurationMillis)
        let total = try #require(stats.totalDurationMillis)
        #expect(moving >= 1 && moving <= total)
        // A run: between 2 and 6 m/s on average.
        let speed = try #require(stats.averageMovingSpeedMps)
        #expect(speed >= 2.0 && speed <= 6.0, "speed \(speed)")
    }

    @Test("path distances and interpolation")
    func pathInterpolation() throws {
        let path = TrackPath([walk(11), shifted(walk(11), by: 1.0)])
        #expect(abs(path.length - 200.0) <= 0.1)
        #expect(path.segmentStarts == [0, 11])
        let middle = try #require(path.interpolate(55.0))
        #expect(abs(middle.lon - 55.0 / metersPerDegree) <= 1e-9)
        #expect(path.interpolate(-5.0) == path.points.first)
        #expect(path.interpolate(1e9) == path.points.last)
        #expect(path.nearestIndex(0.0, 1.0 + 40.0 / metersPerDegree) == 15)
    }
}
