// Port of core/src/test/kotlin/fr/toolmap/core/altitude/AtmosphereTest.kt.

import Testing
@testable import ToolMapCore

struct AtmosphereTests {
    /// The pressure at `altitude` when it is `seaLevel` at sea level (the inverse of the altitude formula).
    private func pressureAt(_ altitude: Double, _ seaLevel: Double = Atmosphere.standardSeaLevelHpa) -> Double {
        seaLevel / Atmosphere.seaLevelHpa(1.0, altitude)
    }

    @Test("the standard atmosphere gives the textbook pressures")
    func textbook() {
        // ICAO standard atmosphere: 898.75 hPa at 1000 m, 795.0 at 2000 m, 701.1 at 3000 m, 540.2 at 5000 m.
        #expect(abs(pressureAt(1000.0) - 898.75) <= 0.05)
        #expect(abs(pressureAt(2000.0) - 795.0) <= 0.1)
        #expect(abs(pressureAt(3000.0) - 701.1) <= 0.1)
        #expect(abs(pressureAt(5000.0) - 540.2) <= 0.1)
        #expect(abs(Atmosphere.altitudeMeters(1013.25, 1013.25)) <= 1e-9)
    }

    @Test("altitude and sea-level pressure are each other's inverse")
    func inverse() {
        for altitude in [-100.0, 0.0, 450.0, 1830.0, 4808.0] {
            for seaLevel in [985.0, 1013.25, 1032.0] {
                let p = pressureAt(altitude, seaLevel)
                #expect(abs(Atmosphere.altitudeMeters(p, seaLevel) - altitude) <= 1e-6)
                #expect(abs(Atmosphere.seaLevelHpa(p, altitude) - seaLevel) <= 1e-6)
            }
        }
    }

    @Test("a hectopascal is about eight meters near the ground and more higher up")
    func hectopascal() {
        let perMeter = Atmosphere.seaLevelHpaPerMeter(1013.25, 0.0)
        #expect(abs(perMeter - 0.1201) <= 0.0005)
        #expect(abs(1.0 / perMeter - 8.3) <= 0.1)
        // Higher up the same altitude error stands for a slightly larger calibration error.
        #expect(Atmosphere.seaLevelHpaPerMeter(1013.25, 3000.0) > perMeter)
    }

    @Test("a lower pressure than the sea level is a higher altitude")
    func lowerPressure() {
        #expect(Atmosphere.altitudeMeters(900.0, 1013.25) > 0.0)
        #expect(Atmosphere.altitudeMeters(1020.0, 1013.25) < 0.0)
    }
}
