// Port of core/src/test/kotlin/fr/toolmap/core/track/TrackFormatTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct TrackFormatTests {
    private let fr = Locale(identifier: "fr_FR")

    @Test("distances switch to kilometres at 1 km and lose a decimal at 10 km")
    func distances() {
        #expect(TrackFormat.distance(0.0, locale: fr) == "0 m")
        #expect(TrackFormat.distance(849.6, locale: fr) == "850 m")
        #expect(TrackFormat.distance(1000.0, locale: fr) == "1,00 km")
        #expect(TrackFormat.distance(9994.0, locale: fr) == "9,99 km")
        #expect(TrackFormat.distance(12345.0, locale: fr) == "12,3 km")
    }

    @Test("durations are hours and minutes")
    func durations() {
        #expect(TrackFormat.duration(10000) == "0 min")
        #expect(TrackFormat.duration(45 * 60000) == "45 min")
        #expect(TrackFormat.duration((2 * 60 + 5) * 60000) == "2 h 05")
        #expect(TrackFormat.duration((26 * 60 + 10) * 60000) == "26 h 10")
        // 59 min 40 s rounds up to the hour.
        #expect(TrackFormat.duration(59 * 60000 + 40000) == "1 h 00")
    }

    @Test("speeds, elevations and grades")
    func speedsElevationsGrades() {
        #expect(TrackFormat.speed(1.0, locale: fr) == "3,6 km/h")
        #expect(TrackFormat.elevation(1233.6, locale: fr) == "1234 m")
        #expect(TrackFormat.grade(12.4, locale: fr) == "+12 %")
        #expect(TrackFormat.grade(-4.0, locale: Locale(identifier: "en_US_POSIX")) == "-4 %")
    }

    @Test("numbers are rounded half up on their shortest decimal form, as Java's String.format")
    func javaRounding() {
        #expect(TrackNumberText.fixed(0.15, 1) == "0.2")
        #expect(TrackNumberText.fixed(1.005, 2) == "1.01")
        #expect(TrackNumberText.fixed(0.125, 2) == "0.13")
        #expect(TrackNumberText.fixed(999.995, 2) == "1000.00")
        #expect(TrackNumberText.fixed(0.5, 0) == "1")
        #expect(TrackNumberText.fixed(-0.04, 1, separator: ",") == "-0,0")
        #expect(TrackNumberText.javaString(1e-5) == "1.0E-5")
        #expect(TrackNumberText.javaString(1e7) == "1.0E7")
        #expect(TrackNumberText.javaString(123_456_789.0) == "1.23456789E8")
        #expect(TrackNumberText.javaString(0.001) == "0.001")
        #expect(TrackNumberText.javaString(Float(2.5)) == "2.5")
        #expect(TrackNumberText.plain(100.0) == "100")
        #expect(TrackNumberText.plain(1e-7) == "0.0000001")
        #expect(TrackNumberText.plain(-0.0) == "0")
    }
}
