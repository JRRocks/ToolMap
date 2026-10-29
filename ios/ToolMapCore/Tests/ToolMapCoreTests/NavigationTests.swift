// Port of core/src/test/kotlin/fr/toolmap/core/geo/NavigationTest.kt.

import Testing
@testable import ToolMapCore

struct NavigationTests {
    private func near(_ a: Double, _ b: Double, _ tolerance: Double) -> Bool {
        abs(a - b) <= tolerance
    }

    @Test("bearings along the axes")
    func bearings() {
        #expect(near(Geodesy.bearing(45.0, 6.0, 46.0, 6.0), 0.0, 1e-9))
        #expect(near(Geodesy.bearing(45.0, 6.0, 44.0, 6.0), 180.0, 1e-9))
        #expect(near(Geodesy.bearing(0.0, 6.0, 0.0, 7.0), 90.0, 1e-9))
        #expect(near(Geodesy.bearing(0.0, 6.0, 0.0, 5.0), 270.0, 1e-9))
        // Paris to New York: initial bearing about 291.8 degrees.
        #expect(near(Geodesy.bearing(48.8566, 2.3522, 40.7128, -74.0060), 291.8, 0.2))
    }

    @Test("relative bearing is the short way round")
    func relativeBearing() {
        #expect(near(Geodesy.relativeBearing(350.0, 0.0), 10.0, 1e-9))
        #expect(near(Geodesy.relativeBearing(10.0, 0.0), -10.0, 1e-9))
        #expect(near(Geodesy.relativeBearing(0.0, 180.0), -180.0, 1e-9))
        #expect(near(Geodesy.relativeBearing(0.0, 90.0), 90.0, 1e-9))
        #expect(near(Geodesy.relativeBearing(123.0, 123.0), 0.0, 1e-9))
    }

    @Test("guidance gives distance bearing and the turn to make")
    func guidance() throws {
        let target = NavTarget(lat: 45.01, lon: 6.0, name: "Col")
        let g = Navigation.guide(45.0, 6.0, target, headingDegrees: 90.0)
        #expect(near(g.distanceMeters, 1112.0, 5.0))
        #expect(near(g.bearingDegrees, 0.0, 1e-6))
        let relative = try #require(g.relativeDegrees)
        #expect(near(relative, -90.0, 1e-6))
        #expect(Navigation.guide(45.0, 6.0, target).relativeDegrees == nil)
        #expect(!Navigation.arrived(g))
        #expect(Navigation.arrived(Navigation.guide(45.0, 6.0, NavTarget(lat: 45.00005, lon: 6.0))))
    }

    @Test("compass points")
    func compassPoints() {
        #expect(Navigation.compassPoint(0.0) == "N")
        #expect(Navigation.compassPoint(359.0) == "N")
        #expect(Navigation.compassPoint(22.4) == "N")
        #expect(Navigation.compassPoint(22.6) == "NE")
        #expect(Navigation.compassPoint(90.0) == "E")
        #expect(Navigation.compassPoint(180.0) == "S")
        #expect(Navigation.compassPoint(270.0) == "W")
        #expect(Navigation.compassPoint(-45.0) == "NW")
    }
}
