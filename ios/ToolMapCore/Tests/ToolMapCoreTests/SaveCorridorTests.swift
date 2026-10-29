// Port of core/src/test/kotlin/fr/toolmap/core/maps/SaveCorridorTest.kt.

import Testing
@testable import ToolMapCore

struct SaveCorridorTests {
    private let line = [
        ZoneShape.Point(lon: 5.9, lat: 45.4),
        ZoneShape.Point(lon: 5.95, lat: 45.45),
        ZoneShape.Point(lon: 6.0, lat: 45.42),
    ]

    private func near(_ expected: Double, _ actual: Double, _ tolerance: Double) -> Bool {
        abs(expected - actual) <= tolerance
    }

    @Test("the slider goes from 50 m to 5 km, by 50 m up to 1 km and by 250 m above")
    func sliderSteps() {
        #expect(CorridorWidth.fromFraction(0.0) == 50)
        #expect(CorridorWidth.fromFraction(1.0) == 5_000)
        #expect(CorridorWidth.fromFraction(0.5) == 500)
        for step in 0 ... 100 {
            let m = CorridorWidth.fromFraction(Double(step) / 100.0)
            #expect(m >= 50 && m <= 5_000)
            #expect(m % (m <= 1_000 ? 50 : 250) == 0, "\(m) is on its step")
        }
        #expect(CorridorWidth.round(1_180.0) == 1_250)
        #expect(CorridorWidth.round(760.0) == 750)
    }

    @Test("the slider stands where its width is")
    func sliderPosition() {
        for m in [50, 100, 500, 1_000, 2_500, 5_000] {
            #expect(CorridorWidth.fromFraction(CorridorWidth.toFraction(m)) == m)
        }
        #expect(near(0.0, CorridorWidth.toFraction(10), 1e-9))
        #expect(near(1.0, CorridorWidth.toFraction(9_000), 1e-9))
        #expect(CorridorWidth.clamp(3) == 50)
        #expect(CorridorWidth.clamp(777) == 777)
    }

    @Test("the band is a corridor of its width, inside its rectangle")
    func band() throws {
        let band = try AreaCorridor(name: "Tour du Granier", path: line, radiusMeters: 500)
        let shape = band.toShape()
        #expect(shape.points == line)
        #expect(shape.radiusMeters == 500.0)
        let box = try band.bounds()
        // 500 m beyond the southernmost point, and beyond the westernmost one.
        #expect(near(500.0, Geodesy.distance(45.4, 5.9, box.south, 5.9), 1.0))
        #expect(Geodesy.distance(45.4, 5.9, 45.4, box.west) >= 499.0)
        #expect(box.north > 45.45 && box.east > 6.0)
    }
}
