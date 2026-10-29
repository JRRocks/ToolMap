// Port of core/src/test/kotlin/fr/toolmap/core/altitude/AltitudeSelectorTest.kt.

import Testing
@testable import ToolMapCore

struct AltitudeSelectorTests {
    private func baro(_ meters: Double, _ uncertainty: Double) -> BaroReading {
        BaroReading(altitudeMeters: meters, uncertaintyMeters: uncertainty, pressureHpa: 900.0, seaLevelHpa: 1013.0)
    }

    @Test("GPS mode uses the GPS only")
    func gpsMode() {
        let a = AltitudeSelector.choose(.gps, 1200.0, 8.0, baro(1190.0, 3.0))
        #expect(a == Altitude(meters: 1200.0, source: .gps, uncertaintyMeters: 8.0))
        #expect(AltitudeSelector.choose(.gps, nil, nil, baro(1190.0, 3.0)) == nil)
    }

    @Test("barometer mode uses the barometer only, even uncalibrated it gives nothing")
    func barometerMode() {
        let a = AltitudeSelector.choose(.barometer, 1200.0, 8.0, baro(1190.0, 45.0))
        #expect(a == Altitude(meters: 1190.0, source: .barometer, uncertaintyMeters: 45.0))
        #expect(AltitudeSelector.choose(.barometer, 1200.0, 8.0, nil) == nil)
    }

    @Test("auto mode takes a calibrated barometer, else the GPS, else what there is")
    func autoMode() {
        #expect(
            AltitudeSelector.choose(.auto, 1200.0, 8.0, baro(1190.0, 10.0))
                == Altitude(meters: 1190.0, source: .barometer, uncertaintyMeters: 10.0)
        )
        #expect(
            AltitudeSelector.choose(.auto, 1200.0, 8.0, baro(1190.0, 60.0))
                == Altitude(meters: 1200.0, source: .gps, uncertaintyMeters: 8.0),
            "a barometer too unsure gives way to the GPS"
        )
        #expect(
            AltitudeSelector.choose(.auto, 1200.0, nil, nil) == Altitude(meters: 1200.0, source: .gps, uncertaintyMeters: nil)
        )
        #expect(
            AltitudeSelector.choose(.auto, nil, nil, baro(1190.0, 60.0))
                == Altitude(meters: 1190.0, source: .barometer, uncertaintyMeters: 60.0),
            "better a vague barometer than nothing"
        )
        #expect(AltitudeSelector.choose(.auto, nil, nil, nil) == nil)
    }
}
