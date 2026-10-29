// Port of core/src/test/kotlin/fr/toolmap/core/geo/CoordinateParserTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct CoordinateParserTests {
    private func ok(_ text: String) -> (Double, Double)? {
        let result = CoordinateParser.parse(text)
        guard case let .success(lat, lon) = result else {
            Issue.record("'\(text)' -> \(result)")
            return nil
        }
        return (lat, lon)
    }

    private func assertPos(_ text: String, _ lat: Double, _ lon: Double) {
        guard let position = ok(text) else { return }
        #expect(abs(position.0 - lat) <= 1e-6, "lat of '\(text)': \(position.0)")
        #expect(abs(position.1 - lon) <= 1e-6, "lon of '\(text)': \(position.1)")
    }

    private func fails(_ text: String, _ reason: ParsedCoordinate.Reason) {
        let result = CoordinateParser.parse(text)
        #expect(result == .failure(reason), "'\(text)' -> \(result)")
    }

    @Test("decimal degrees in the usual spellings")
    func decimalDegrees() {
        assertPos("44.8, 5.9", 44.8, 5.9)
        assertPos("44.8 5.9", 44.8, 5.9)
        assertPos("44.8;5.9", 44.8, 5.9)
        assertPos("44.8,5.9", 44.8, 5.9)
        assertPos("-44.8, -5.9", -44.8, -5.9)
        assertPos("  +44.8   ,  +5.9 ", 44.8, 5.9)
        assertPos("44.8° N, 5.9° E", 44.8, 5.9)
        assertPos("45 6", 45.0, 6.0)
    }

    @Test("hemisphere letters before or after, French words, and swapped axes")
    func hemispheres() {
        assertPos("N44.8 E5.9", 44.8, 5.9)
        assertPos("44.8S 5.9W", -44.8, -5.9)
        assertPos("44.8 N 5.9 O", 44.8, -5.9)
        assertPos("5.9E 44.8N", 44.8, 5.9)
        assertPos("Nord 44.8 Est 5.9", 44.8, 5.9)
        assertPos("44.8 sud, 5.9 ouest", -44.8, -5.9)
    }

    @Test("degrees decimal minutes")
    func degreesDecimalMinutes() {
        assertPos("N 44° 48.000' E 5° 54.000'", 44.8, 5.9)
        assertPos("44 48.0 N 5 54.0 E", 44.8, 5.9)
        assertPos("44°48.5'N 5°54.25'W", 44.808333, -5.904167)
    }

    @Test("degrees minutes seconds")
    func degreesMinutesSeconds() {
        assertPos("44°48'00\"N 5°54'00\"E", 44.8, 5.9)
        assertPos("44° 48' 36\" N, 5° 54' 36\" E", 44.81, 5.91)
        assertPos("44 48 36 N 5 54 36 W", 44.81, -5.91)
        assertPos("S 33°51'54\" E 151°12'36\"", -33.865, 151.21)
        assertPos("44º48′36″N 5º54′36″E", 44.81, 5.91)
    }

    @Test("decimal comma when the pair is told apart otherwise")
    func decimalComma() {
        assertPos("44,8 5,9", 44.8, 5.9)
        assertPos("44,8; 5,9", 44.8, 5.9)
        assertPos("44,8 N 5,9 E", 44.8, 5.9)
        assertPos("44,5", 44.0, 5.0)
        assertPos("44, 5", 44.0, 5.0)
    }

    @Test("out of range and malformed input is refused with a reason")
    func refused() {
        fails("91 5", .range)
        fails("44 181", .range)
        fails("44°60'00\"N 5°0'0\"E", .sexagesimal)
        fails("44°10'60\"N 5°0'0\"E", .sexagesimal)
        fails("44.5°10' N 5° E", .sexagesimal)
        fails("44 N 45 N", .axes)
        fails("5 E 6 W", .axes)
        fails("-44 S 5 E", .axes)
        fails("", .syntax)
        fails("hello", .syntax)
        fails("44.8", .syntax)
        fails("44 5 6 7 8 9 10", .syntax)
        fails("44.8 5.9 7.0", .syntax)
    }

    @Test("what the formatter writes the parser reads back")
    func formatterRoundTrip() {
        let samples: [(Double, Double)] = [(44.80001, 5.9), (-33.8688, 151.2093), (0.0, 0.0), (47.99999, -179.99999), (-89.5, 0.00001)]
        for (lat, lon) in samples {
            let dd = CoordinateFormat.decimalDegreesWithHemisphere(lat, lon, decimals: 6)
            let ddm = CoordinateFormat.degreesDecimalMinutes(lat, lon, decimals: 5)
            let dms = CoordinateFormat.degreesMinutesSeconds(lat, lon, decimals: 3)
            let texts: [(String, Double)] = [(dd, 1e-6), (ddm, 1e-6), (dms, 1e-5)]
            for (text, tolerance) in texts {
                guard let position = ok(text) else { continue }
                #expect(abs(position.0 - lat) <= tolerance, "lat of '\(text)'")
                #expect(abs(position.1 - lon) <= tolerance, "lon of '\(text)'")
            }
        }
    }

    @Test("formatters carry rounding into the larger unit")
    func formatterRounding() {
        #expect(CoordinateFormat.degreesDecimalMinutes(44.8, 5.9) == "44° 48.000' N, 5° 54.000' E")
        #expect(CoordinateFormat.degreesDecimalMinutes(44.9999999, 0.0) == "45° 00.000' N, 0° 00.000' E")
        #expect(CoordinateFormat.degreesMinutesSeconds(44.81, -5.91) == "44° 48' 36.0\" N, 5° 54' 36.0\" W")
        #expect(CoordinateFormat.degreesMinutesSeconds(-44.99999999, 0.99999999) == "45° 00' 00.0\" S, 1° 00' 00.0\" E")
    }
}
