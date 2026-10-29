// Port of core/src/test/kotlin/fr/toolmap/core/altitude/BarometricAltimeterTest.kt. Each test builds its own
// altimeter (the Kotlin class gets a fresh one per test too).

import Testing
@testable import ToolMapCore

struct BarometricAltimeterTests {
    private func pressureAt(_ altitude: Double, _ seaLevel: Double = Atmosphere.standardSeaLevelHpa) -> Double {
        seaLevel / Atmosphere.seaLevelHpa(1.0, altitude)
    }

    /// Feeds `hpa` once a second for `seconds` seconds from `fromMillis`; returns the time after the last one.
    private func settle(_ altimeter: BarometricAltimeter, _ hpa: Double, _ fromMillis: Int64, seconds: Int = 40) -> Int64 {
        var t = fromMillis
        for _ in 0 ..< seconds {
            altimeter.onPressure(hpa, t)
            t += 1000
        }
        return t - 1000
    }

    @Test("without a reference there is no altitude")
    func noReference() {
        let altimeter = BarometricAltimeter()
        altimeter.onPressure(900.0, 0)
        #expect(altimeter.reading(0) == nil)
        #expect(!altimeter.calibrated)
        #expect(altimeter.seaLevelHpa == nil)
    }

    @Test("without a pressure a reference cannot be used")
    func noPressure() {
        let altimeter = BarometricAltimeter()
        #expect(altimeter.onReference(1000.0, 5.0, 0, .terrain) == .noPressure)
        #expect(!altimeter.calibrated)
    }

    @Test("the first reference calibrates so that the altitude is the reference")
    func firstReference() throws {
        let altimeter = BarometricAltimeter()
        let p = pressureAt(1000.0, 1021.0)
        let t = settle(altimeter, p, 0)
        #expect(altimeter.onReference(1000.0, 5.0, t, .terrain) == .accepted)
        let reading = try #require(altimeter.reading(t))
        #expect(abs(reading.altitudeMeters - 1000.0) <= 0.01)
        #expect(abs(reading.seaLevelHpa - 1021.0) <= 0.01)
        #expect(altimeter.calibrated)
        // The uncertainty is the reference's plus the noise of the pressure.
        #expect(abs(reading.uncertaintyMeters - 6.0) <= 0.5)
    }

    @Test("a change of pressure is a change of altitude whatever the calibration")
    func climb() throws {
        let altimeter = BarometricAltimeter()
        let t0 = settle(altimeter, pressureAt(1000.0, 1015.0), 0)
        _ = altimeter.onReference(1000.0, 5.0, t0, .terrain)
        // Climb 300 m: the pressure falls as the standard atmosphere says.
        let t1 = settle(altimeter, pressureAt(1300.0, 1015.0), t0 + 1000)
        let reading = try #require(altimeter.reading(t1))
        #expect(abs(reading.altitudeMeters - 1300.0) <= 1.0)
    }

    @Test("a spike in the pressure is smoothed")
    func spike() throws {
        let altimeter = BarometricAltimeter()
        let t0 = settle(altimeter, pressureAt(1000.0), 0)
        _ = altimeter.onReference(1000.0, 5.0, t0, .terrain)
        // A reading that alone would mean +100 m, one second after the last one.
        altimeter.onPressure(pressureAt(1100.0), t0 + 1000)
        let reading = try #require(altimeter.reading(t0 + 1000))
        let moved = reading.altitudeMeters - 1000.0
        #expect((10.0 ... 30.0).contains(moved), "moved \(moved) m of a 100 m spike")
    }

    @Test("a more precise reference weighs more than the estimate and a vaguer one less")
    func weights() throws {
        let altimeter = BarometricAltimeter()
        let t0 = settle(altimeter, pressureAt(1000.0), 0)
        _ = altimeter.onReference(1000.0, 5.0, t0, .terrain)
        let t1 = settle(altimeter, pressureAt(1000.0), t0 + 1000, seconds: 60)
        // A vague GPS 40 m higher barely moves a calibration made with 5 m.
        #expect(altimeter.onReference(1040.0, 20.0, t1, .gpsMsl) == .accepted)
        let reading = try #require(altimeter.reading(t1))
        let shifted = reading.altitudeMeters - 1000.0
        #expect((1.0 ... 4.0).contains(shifted), "shifted \(shifted) m")
    }

    @Test("a reference far from the estimate is an outlier until it persists")
    func outlier() throws {
        let altimeter = BarometricAltimeter()
        let t0 = settle(altimeter, pressureAt(1000.0), 0)
        _ = altimeter.onReference(1000.0, 5.0, t0, .gpsMsl)
        var t = t0
        var results: [ReferenceResult] = []
        for _ in 0 ..< 5 {
            t = settle(altimeter, pressureAt(1000.0), t + 1000, seconds: 35)
            results.append(altimeter.onReference(1500.0, 5.0, t, .gpsMsl))
        }
        let expected: [ReferenceResult] = [.rejectedOutlier, .rejectedOutlier, .rejectedOutlier, .rejectedOutlier, .accepted]
        #expect(results == expected)
        let reading = try #require(altimeter.reading(t))
        #expect(abs(reading.altitudeMeters - 1500.0) <= 0.5)
    }

    @Test("references of one source are throttled and another source is independent")
    func throttled() {
        let altimeter = BarometricAltimeter()
        let t0 = settle(altimeter, pressureAt(500.0), 0)
        #expect(altimeter.onReference(500.0, 10.0, t0, .gpsMsl) == .accepted)
        #expect(altimeter.onReference(500.0, 10.0, t0 + 10000, .gpsMsl) == .throttled)
        #expect(altimeter.onReference(500.0, 10.0, t0 + 10000, .terrain) == .accepted)
        // The sensor is still reporting; 31 s after the first reference the next one is independent of it.
        altimeter.onPressure(pressureAt(500.0), t0 + 30500)
        #expect(altimeter.onReference(500.0, 10.0, t0 + 31000, .gpsMsl) == .accepted)
    }

    @Test("references keep the altitude right while the weather changes")
    func weather() {
        // Standing at 1000 m for 6 hours while the sea-level pressure falls 1 hPa an hour.
        let tracked = BarometricAltimeter()
        let untracked = BarometricAltimeter()
        var worstTracked = 0.0
        var worstUntracked = 0.0
        var missing = 0
        let start = 1015.0
        for minute in 0 ... 360 {
            let t = Int64(minute) * 60000
            let p = pressureAt(1000.0, start - Double(minute) / 60.0)
            tracked.onPressure(p, t)
            untracked.onPressure(p, t)
            if minute == 0 {
                _ = tracked.onReference(1000.0, 10.0, t, .gpsMsl)
                _ = untracked.onReference(1000.0, 10.0, t, .gpsMsl)
            } else {
                _ = tracked.onReference(1000.0, 10.0, t, .gpsMsl)
            }
            if minute >= 60 {
                if let reading = tracked.reading(t) {
                    worstTracked = Swift.max(worstTracked, abs(reading.altitudeMeters - 1000.0))
                } else {
                    missing += 1
                }
            }
            if let reading = untracked.reading(t) {
                worstUntracked = Swift.max(worstUntracked, abs(reading.altitudeMeters - 1000.0))
            } else {
                missing += 1
            }
        }
        #expect(missing == 0)
        #expect(worstTracked < 8.0, "tracked error up to \(worstTracked) m")
        #expect(worstUntracked > 40.0, "untracked error only \(worstUntracked) m")
    }

    @Test("the uncertainty grows with time and shrinks with a new reference")
    func uncertainty() throws {
        let altimeter = BarometricAltimeter()
        let t0 = settle(altimeter, pressureAt(1000.0), 0)
        _ = altimeter.onReference(1000.0, 5.0, t0, .terrain)
        let freshReading = try #require(altimeter.reading(t0))
        let fresh = freshReading.uncertaintyMeters
        // Six hours later, with the sensor still reporting but no reference.
        let later = t0 + 6 * 3_600_000
        altimeter.onPressure(pressureAt(1000.0), later)
        let agedReading = try #require(altimeter.reading(later))
        let aged = agedReading.uncertaintyMeters
        #expect(aged > 15.0 && aged > fresh * 2, "uncertainty \(fresh) -> \(aged)")
        _ = altimeter.onReference(1000.0, 5.0, later, .terrain)
        let renewed = try #require(altimeter.reading(later))
        #expect(renewed.uncertaintyMeters < aged)
    }

    @Test("a stale pressure gives no altitude and no calibration")
    func stale() throws {
        let altimeter = BarometricAltimeter()
        let t0 = settle(altimeter, pressureAt(1000.0), 0)
        _ = altimeter.onReference(1000.0, 5.0, t0, .terrain)
        #expect(altimeter.reading(t0 + 20000) != nil)
        #expect(altimeter.reading(t0 + 120_000) == nil)
        #expect(altimeter.onReference(1000.0, 5.0, t0 + 120_000, .gpsMsl) == .noPressure)
        // A new reading brings it back, without a jump from the smoothing of the old one.
        altimeter.onPressure(pressureAt(1000.0), t0 + 121_000)
        let reading = try #require(altimeter.reading(t0 + 121_000))
        #expect(abs(reading.altitudeMeters - 1000.0) <= 0.5)
    }

    @Test("nonsense is ignored")
    func nonsense() {
        let altimeter = BarometricAltimeter()
        altimeter.onPressure(Double.nan, 0)
        altimeter.onPressure(5.0, 1000)
        altimeter.onPressure(2000.0, 2000)
        #expect(altimeter.onReference(100.0, 5.0, 3000, .terrain) == .noPressure, "none of those readings was kept")
        let t = settle(altimeter, pressureAt(100.0), 10000)
        #expect(altimeter.onReference(100.0, 0.0, t, .terrain) == .invalid)
        #expect(altimeter.onReference(100.0, Double.nan, t, .terrain) == .invalid)
        #expect(altimeter.onReference(20000.0, 5.0, t, .terrain) == .invalid)
        #expect(altimeter.onReference(Double.nan, 5.0, t, .terrain) == .invalid)
        #expect(!altimeter.calibrated)
    }

    @Test("a manual calibration wins at once over any estimate")
    func manual() throws {
        let altimeter = BarometricAltimeter()
        let t0 = settle(altimeter, pressureAt(1000.0), 0)
        _ = altimeter.onReference(1000.0, 5.0, t0, .terrain)
        #expect(altimeter.onReference(1100.0, 2.0, t0 + 1000, .manual) == .accepted)
        let reading = try #require(altimeter.reading(t0 + 1000))
        #expect(abs(reading.altitudeMeters - 1100.0) <= 0.5)
        // And again straight away: manual calibrations are not throttled.
        #expect(altimeter.onReference(1000.0, 2.0, t0 + 1500, .manual) == .accepted)
    }

    @Test("reset forgets everything")
    func reset() {
        let altimeter = BarometricAltimeter()
        let t0 = settle(altimeter, pressureAt(1000.0), 0)
        _ = altimeter.onReference(1000.0, 5.0, t0, .terrain)
        altimeter.reset()
        #expect(!altimeter.calibrated)
        #expect(altimeter.reading(t0) == nil)
        // The throttle is forgotten too.
        altimeter.onPressure(pressureAt(1000.0), t0 + 1000)
        #expect(altimeter.onReference(1000.0, 5.0, t0 + 1000, .terrain) == .accepted)
    }
}
