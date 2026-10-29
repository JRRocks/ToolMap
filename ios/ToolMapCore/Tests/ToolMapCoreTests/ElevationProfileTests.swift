// Port of core/src/test/kotlin/fr/toolmap/core/track/ElevationProfileTest.kt.

import Testing
@testable import ToolMapCore

struct ElevationProfileTests {
    private let metersPerDegree = Double.pi * Geodesy.earthRadiusMeters / 180.0

    private func walk(_ count: Int, stepMeters: Double = 10.0, _ ele: (Int) -> Double?) -> TrackPath {
        TrackPath([(0 ..< count).map { TrackPoint(lat: 0.0, lon: Double($0) * stepMeters / metersPerDegree, ele: ele($0)) }])
    }

    @Test("a constant 10 percent climb gives a 10 percent grade and the right distances")
    func constantClimb() throws {
        let profile = try #require(ElevationProfile.build(walk(201) { Double($0) }))
        #expect(abs(profile.totalDistanceMeters - 2000.0) <= 0.5)
        let middle = try #require(profile.sampleAt(1000.0))
        #expect(abs(middle.gradePercent - 10.0) <= 0.01)
        #expect(GradeBand.of(middle.gradePercent) == .moderate)
        let minElevation = try #require(profile.minElevation)
        let maxElevation = try #require(profile.maxElevation)
        #expect(abs(minElevation - 0.0) <= 1e-9)
        #expect(abs(maxElevation - 200.0) <= 1e-9)
        #expect(profile.samples.first?.index == 0)
        #expect(profile.samples.last?.index == 200)
    }

    @Test("a long track is reduced to the sample budget, ends included")
    func sampleBudget() throws {
        let path = walk(50000) { 100 + Double($0 % 100) * 0.5 }
        let profile = try #require(ElevationProfile.build(path, options: ProfileOptions(maxSamples: 300)))
        #expect(profile.samples.count >= 290 && profile.samples.count <= 300, "\(profile.samples.count)")
        #expect(profile.samples.first?.index == 0)
        #expect(profile.samples.last?.index == 49999)
        let ordered = zip(profile.samples, profile.samples.dropFirst()).allSatisfy { pair in
            pair.0.distanceMeters <= pair.1.distanceMeters
        }
        #expect(ordered)
    }

    @Test("points without an elevation are skipped, and fewer than two give no profile")
    func missingElevations() throws {
        let path = walk(10) { $0 % 2 == 0 ? Double($0) : nil }
        let profile = try #require(ElevationProfile.build(path))
        #expect(profile.samples.map(\.index) == [0, 2, 4, 6, 8])
        #expect(ElevationProfile.build(walk(10) { $0 == 3 ? 5.0 : nil }) == nil)
        #expect(ElevationProfile.build(TrackPath([])) == nil)
    }

    @Test("sampleAt picks the nearest sample")
    func sampleAt() throws {
        let profile = try #require(ElevationProfile.build(walk(11) { Double($0) }))
        let at31 = try #require(profile.sampleAt(31.0))
        #expect(abs(at31.distanceMeters - 30.0) <= 0.1)
        let before = try #require(profile.sampleAt(-50.0))
        #expect(abs(before.distanceMeters - 0.0) <= 0.1)
        let after = try #require(profile.sampleAt(500.0))
        #expect(abs(after.distanceMeters - 100.0) <= 0.1)
        #expect(profile.sampleAt(0.0) != nil)
    }

    @Test("grade bands")
    func gradeBands() {
        #expect(GradeBand.of(0.0) == .flat)
        #expect(GradeBand.of(-2.9) == .flat)
        #expect(GradeBand.of(-3.0) == .gentle)
        #expect(GradeBand.of(8.0) == .moderate)
        #expect(GradeBand.of(-15.0) == .steep)
        #expect(GradeBand.of(25.0) == .verySteep)
    }
}
