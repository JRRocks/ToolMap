// Port of core/src/test/kotlin/fr/toolmap/core/geo/GridReferenceTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

// References computed with PROJ and the `mgrs` library (see ProjectionsTests).
private struct GridRef {
    let lat: Double
    let lon: Double
    let e: Int
    let n: Int
    let zone: Int
    let lx: Int
    let ly: Int
    let mgrs: String
}

struct GridReferenceTests {
    private let refs: [GridRef] = [
        GridRef(lat: 44.57641126591504, lon: -2.6143853259437257, e: 530615, n: 4935969, zone: 30, lx: 254620, ly: 6402210, mgrs: "30TWQ3061535969"),
        GridRef(lat: 47.68387749387861, lon: -3.5945464166557155, e: 455379, n: 5281336, zone: 30, lx: 205822, ly: 6752146, mgrs: "30TVT5537981336"),
        GridRef(lat: 46.59087904091355, lon: 0.07111146140731961, e: 275640, n: 5163869, zone: 31, lx: 475810, ly: 6614251, mgrs: "31TBM7564063869"),
        GridRef(lat: 45.532932296853886, lon: 5.8356515584004764, e: 721409, n: 5046066, zone: 31, lx: 921274, ly: 6496579, mgrs: "31TGL2140946066"),
    ]

    private func position(_ text: String) -> (Double, Double)? {
        let result = CoordinateParser.parse(text)
        guard case let .success(lat, lon) = result else {
            Issue.record("'\(text)' -> \(result)")
            return nil
        }
        return (lat, lon)
    }

    private func assertWithin(_ text: String, _ lat: Double, _ lon: Double, _ meters: Double) {
        guard let p = position(text) else { return }
        let d = Geodesy.distance(lat, lon, p.0, p.1)
        #expect(d <= meters, "'\(text)' is \(formatFixed(d, 2)) m from (\(lat), \(lon))")
    }

    private func wholeMatch(_ pattern: String, _ text: String) -> Bool {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return false }
        let range = NSRange(location: 0, length: (text as NSString).length)
        guard let match = regex.firstMatch(in: text, options: [], range: range) else { return false }
        return match.range.location == 0 && match.range.length == range.length
    }

    @Test("an MGRS reference of a real position gives it back to the meter")
    func mgrsBack() {
        for r in refs {
            assertWithin(r.mgrs, r.lat, r.lon, 1.5)
            // With the separators of a printed map.
            let chars = Array(r.mgrs)
            let parts: [String] = [String(chars[0 ..< 3]), String(chars[3 ..< 5]), String(chars[5 ..< 10]), String(chars[10...])]
            let spaced = parts.joined(separator: " ")
            assertWithin(spaced, r.lat, r.lon, 1.5)
            assertWithin(spaced.lowercased(), r.lat, r.lon, 1.5)
        }
    }

    @Test("fewer digits name a bigger square and give its centre")
    func fewerDigits() {
        // 31T DL 4 4 is a 10 km square: its centre is within 7.1 km of any point inside it.
        assertWithin("31T GL 2 4", 45.5329, 5.8356, 7_200.0)
        if let p = position("31T GL") {
            #expect(abs(p.0 - 45.5) <= 1.0)
        }
        // Each digit more halves the error: 5 digits is under 1 m, 3 digits under 71 m.
        assertWithin("31T GL 214 460", 45.532932, 5.835651, 75.0)
    }

    @Test("UTM references in the usual spellings")
    func utmSpellings() {
        for r in refs {
            assertWithin("\(r.zone)T \(r.e) E \(r.n) N", r.lat, r.lon, 1.5)
            assertWithin("UTM \(r.zone)T \(r.e) \(r.n)", r.lat, r.lon, 1.5)
            assertWithin("\(r.zone)T\(r.e) \(r.n)", r.lat, r.lon, 1.5)
            assertWithin("\(r.zone)T E\(r.e) N\(r.n)", r.lat, r.lon, 1.5)
            assertWithin("\(r.zone)T \(r.e)mE \(r.n)mN", r.lat, r.lon, 1.5)
            assertWithin("\(r.zone)t, \(r.e), \(r.n)", r.lat, r.lon, 1.5)
        }
    }

    @Test("Lambert 93 references")
    func lambert() {
        for r in refs {
            assertWithin("L93 \(r.lx) E \(r.ly) N", r.lat, r.lon, 1.5)
            assertWithin("Lambert 93 \(r.lx) \(r.ly)", r.lat, r.lon, 1.5)
            assertWithin("RGF93 \(r.lx), \(r.ly)", r.lat, r.lon, 1.5)
            assertWithin("EPSG:2154 \(r.lx) \(r.ly)", r.lat, r.lon, 1.5)
        }
    }

    @Test("southern hemisphere goes through the band letter")
    func southernHemisphere() throws {
        // Sydney. The band H is south: the northing carries the false northing of 10 000 000.
        let lat = -33.8568
        let lon = 151.2153
        let text = try #require(CoordinateFormat.utm(lat, lon))
        #expect(text.hasPrefix("56H "), "\(text)")
        assertWithin(text, lat, lon, 1.5)
        let reference = try #require(Mgrs.of(lat, lon))
        let mgrs = try reference.format()
        assertWithin(mgrs, lat, lon, 1.5)
    }

    @Test("format then parse is the identity for every system over a spread of places")
    func formatThenParse() {
        let places: [(Double, Double)] = [
            (48.8566, 2.3522), // Paris
            (45.8326, 6.8652), // Mont Blanc
            (-33.8568, 151.2153), // Sydney
            (64.1466, -21.9426), // Reykjavik
            (-54.8019, -68.3030), // Ushuaia
            (0.0, 0.0),
            (1.2, -179.9),
            (-0.5, 179.9),
            (35.6762, 139.6503), // Tokyo
            (-22.9068, -43.1729), // Rio
        ]
        for (lat, lon) in places {
            for system in [CoordinateSystem.utm, CoordinateSystem.mgrs, CoordinateSystem.dd] {
                let text = CoordinateFormat.format(system, lat, lon).text
                assertWithin(text, lat, lon, 1.5)
            }
        }
        // Lambert 93 is read back even outside France (the formula has no limit of its own).
        for (lat, lon) in places.prefix(3) {
            let text = CoordinateFormat.format(.lambert93, lat, lon).text
            let p = Lambert93.toLambert(lat, lon)
            if (0.0 ... 1_300_000.0).contains(p.easting), (6e6 ... 7.2e6).contains(p.northing) {
                assertWithin(text, lat, lon, 1.5)
            }
        }
    }

    @Test("impossible grid references are refused with the grid reason")
    func impossible() {
        let bad = [
            "31T AA 12345 12345", // column A is not in zone 31 (set J..R)
            "31T DQ 123 45", // odd digit count
            "61T DQ 12345 12345", // zone 61
            "31T 1500000 5000000", // easting beyond a zone
            "31T 500000 99999999", // northing beyond the pole
            "L93 5 6", // nowhere near France
            "L93 900000", // one number only
        ]
        for text in bad {
            let result = CoordinateParser.parse(text)
            #expect(result == .failure(.grid), "'\(text)' -> \(result)")
        }
    }

    @Test("plain degrees are not mistaken for a grid")
    func plainDegrees() {
        #expect(GridReference.parse("44.8, 5.9") == nil)
        #expect(GridReference.parse("45 6") == nil)
        #expect(GridReference.parse("44 48 36 N 5 54 36 E") == nil)
        #expect(GridReference.parse("N 44° 48.000' E 5° 54.000'") == nil)
        #expect(GridReference.parse("44°48'00\"N 5°54'00\"E") == nil)
        #expect(GridReference.parse("12 34567 1234567") == nil) // zone and two numbers, but no band and no 'UTM'
        #expect(GridReference.parse("   ") == nil)
        if let p = position("44.8, 5.9") {
            #expect(abs(p.0 - 44.8) <= 1e-9)
        }
    }

    @Test("the formatter writes UTM and Lambert in the shapes the chip shows")
    func formatterShapes() {
        let paris = CoordinateFormat.format(.utm, 48.8566, 2.3522)
        #expect(wholeMatch("^31U [0-9]{6} E [0-9]{7} N$", paris.text), "\(paris.text)")
        #expect(paris.outsideValidity == false)
        let lambert = CoordinateFormat.format(.lambert93, 48.8566, 2.3522)
        #expect(wholeMatch("^L93 [0-9]{6} E [0-9]{7} N$", lambert.text), "\(lambert.text)")
        #expect(lambert.outsideValidity == false)
        #expect(CoordinateFormat.format(.lambert93, 40.7, -74.0).outsideValidity == true)
        // Beyond the UTM latitudes the text falls back to decimal degrees, which can always be written.
        #expect(CoordinateFormat.format(.utm, 86.0, 10.0).text == "86.00000, 10.00000")
        #expect(CoordinateFormat.format(.mgrs, -85.0, 10.0).text == "-85.00000, 10.00000")
    }
}
