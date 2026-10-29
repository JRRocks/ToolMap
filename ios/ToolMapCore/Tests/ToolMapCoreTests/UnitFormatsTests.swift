// Port of core/src/test/kotlin/fr/toolmap/core/settings/UnitFormatsTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct UnitFormatsTests {
    private let fr = Locale(identifier: "fr_FR")

    @Test("bearings in degrees, NATO mils and gradians")
    func bearings() {
        #expect(BearingFormat.format(90.0, .degrees, locale: fr) == "90°")
        #expect(BearingFormat.format(90.0, .mils, locale: fr) == "1600 mil")
        #expect(BearingFormat.format(90.0, .gradians, locale: fr) == "100 gon")
    }

    @Test("a bearing that rounds to a full turn reads as zero")
    func fullTurn() {
        #expect(BearingFormat.value(359.7, .degrees) == 0)
        #expect(BearingFormat.value(-0.02, .mils) == 0)
        #expect(BearingFormat.value(399.9 / 400 * 360, .gradians) == 0)
    }

    @Test("negative and large bearings are brought into the turn")
    func normalized() {
        #expect(BearingFormat.value(-90.0, .degrees) == 270)
        #expect(BearingFormat.value(765.0, .degrees) == 45)
    }

    @Test("slopes as a percentage or as an angle")
    func slopes() {
        #expect(SlopeFormat.format(12.0, .percent, locale: fr) == "+12 %")
        #expect(SlopeFormat.format(-4.0, .percent, locale: fr) == "-4 %")
        // 100 % is 45°.
        #expect(SlopeFormat.format(100.0, .degrees, locale: fr) == "+45,0°")
    }

    @Test("pressures in hectopascals, millimetres of mercury and inches")
    func pressures() {
        #expect(PressureFormat.format(1013.25, .hpa, locale: fr) == "1013,25 hPa")
        #expect(PressureFormat.format(1013.25, .mmhg, locale: fr) == "760,00 mmHg")
        #expect(PressureFormat.format(1013.25, .inhg, locale: fr) == "29,92 inHg")
    }

    @Test("magnetic north is the true bearing minus the declination")
    func magnetic() {
        // Declination 2° east: a magnetic bearing of 0° is a true bearing of 2°, so true 90° reads 88° magnetic.
        let offset = NorthReferenceMath.offsetDegrees(.magnetic, 2.0, 0.0)
        #expect(abs(NorthReferenceMath.relative(90.0, offset) - 88.0) <= 1e-9)
        #expect(abs(NorthReferenceMath.relative(0.0, offset) - 358.0) <= 1e-9)
    }

    @Test("true north changes nothing and grid north uses the convergence")
    func trueAndGrid() {
        let trueOffset = NorthReferenceMath.offsetDegrees(.trueNorth, 5.0, 3.0)
        #expect(abs(NorthReferenceMath.relative(10.0, trueOffset) - 10.0) <= 1e-9)
        let gridOffset = NorthReferenceMath.offsetDegrees(.grid, 5.0, 3.0)
        #expect(abs(NorthReferenceMath.relative(10.0, gridOffset) - 7.0) <= 1e-9)
    }

    @Test("UTM convergence is zero on the central meridian and grows away from it")
    func convergence() {
        // Zone 31 has its central meridian at 3° E.
        #expect(abs(NorthReferenceMath.utmConvergenceDegrees(45.0, 3.0)) <= 1e-9)
        // Grenoble (5.72° E) is 2.72° east of the central meridian: convergence of about +1.93° at 45° N.
        let gamma = NorthReferenceMath.utmConvergenceDegrees(45.19, 5.72)
        #expect(gamma >= 1.9 && gamma <= 2.0, "convergence was \(gamma)")
        // Longitudes beyond 180° give the same answer as their equivalent.
        #expect(abs(NorthReferenceMath.utmConvergenceDegrees(45.19, 5.72 + 360) - gamma) <= 1e-9)
    }
}
