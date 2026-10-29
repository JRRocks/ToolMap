// Port of core/src/test/kotlin/fr/toolmap/core/measure/MeasureTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct MeasureTests {
    private let a = 6_378_137.0
    private let e2 = (1 / 298.257223563) * (2 - 1 / 298.257223563)

    /// Area of the lat/lon rectangle by integrating the surface element of the ellipsoid, M·N·cosφ dφ dλ (Simpson):
    /// a different route from the equal-area projection under test.
    private func rectangleArea(_ lat1: Double, _ lat2: Double, _ dLonDegrees: Double) -> Double {
        let steps = 2000
        let h = Angle.radians(lat2 - lat1) / Double(steps)

        func f(_ phi: Double) -> Double {
            let s = sin(phi)
            let w = 1 - e2 * s * s
            // M = a(1-e²)/w^1.5, N = a/w^0.5, element = M N cosφ
            return a * a * (1 - e2) / (w * w) * cos(phi)
        }
        var sum = f(Angle.radians(lat1)) + f(Angle.radians(lat2))
        for i in 1 ..< steps {
            sum += f(Angle.radians(lat1) + Double(i) * h) * (i % 2 == 1 ? 4 : 2)
        }
        return sum * h / 3 * Angle.radians(dLonDegrees)
    }

    private func rectangle(_ lon1: Double, _ lat1: Double, _ lon2: Double, _ lat2: Double) -> [LonLat] {
        [LonLat(lon: lon1, lat: lat1), LonLat(lon: lon2, lat: lat1), LonLat(lon: lon2, lat: lat2), LonLat(lon: lon1, lat: lat2)]
    }

    /// Kotlin `assertEquals(expected, actual, expected * relative)`.
    private func near(_ expected: Double, _ actual: Double, _ relative: Double) -> Bool {
        abs(expected - actual) <= abs(expected * relative)
    }

    @Test("a lat-lon rectangle has the area of the ellipsoid surface integral")
    func rectangles() {
        let cases: [(Double, Double, Double)] = [
            (0.0, 1.0, 1.0),
            (44.0, 46.0, 3.0),
            (59.9, 60.1, 0.2),
            (-46.0, -44.0, 2.0),
            (10.0, 50.0, 40.0),
            (-1.0, 1.0, 1.0),
        ]
        for (lat1, lat2, dLon) in cases {
            let expected = rectangleArea(lat1, lat2, dLon)
            let actual = Measure.areaSquareMeters(rectangle(5.0, lat1, 5.0 + dLon, lat2))
            #expect(near(expected, actual, 1e-9), "(\(lat1)..\(lat2)) × \(dLon)")
        }
        // 1° × 1° at the equator: about 12 308 km² (a spherical Earth would say 12 364).
        #expect(abs(Measure.areaSquareMeters(rectangle(0.0, 0.0, 1.0, 1.0)) / 1e6 - 12308.5) <= 0.1)
    }

    @Test("a square of the UTM grid has its grid area corrected by the scale factor")
    func utmSquares() {
        // On the central meridian the scale factor is exactly 0.9996: the ground is 1/0.9996² larger than the grid.
        func square(_ east: Double, _ north: Double, _ side: Double) -> [LonLat] {
            [
                Utm.toLonLat(UtmCoordinate(zone: 31, north: true, easting: east, northing: north)),
                Utm.toLonLat(UtmCoordinate(zone: 31, north: true, easting: east + side, northing: north)),
                Utm.toLonLat(UtmCoordinate(zone: 31, north: true, easting: east + side, northing: north + side)),
                Utm.toLonLat(UtmCoordinate(zone: 31, north: true, easting: east, northing: north + side)),
            ]
        }
        let centre = Measure.areaSquareMeters(square(500_000.0, 5_000_000.0, 1000.0))
        // The polygon here has edges that are straight in UTM, and geodesic-ish in the projection: 1e-6 is plenty.
        #expect(near(1_000_000.0 / (0.9996 * 0.9996), centre, 2e-5))
        // 300 km east of the meridian the scale factor is about 0.9996 (1 + x² / 2R²).
        let edge = Measure.areaSquareMeters(square(800_000.0, 5_000_000.0, 1000.0))
        let k = 0.9996 * (1 + 300_000.0 * 300_000.0 / (2 * 6_375_000.0 * 6_375_000.0))
        #expect(near(1_000_000.0 / (k * k), edge, 1e-4))
        // A 10 km square.
        let big = Measure.areaSquareMeters(square(500_000.0, 5_000_000.0, 10000.0))
        #expect(near(100_000_000.0 / (0.9996 * 0.9996), big, 5e-5))
    }

    @Test("orientation, start point and the antimeridian do not change the area")
    func orientation() {
        let r = rectangle(5.0, 44.0, 6.0, 45.0)
        let area = Measure.areaSquareMeters(r)
        #expect(abs(Measure.areaSquareMeters(r.reversed()) - area) <= 1e-3)
        #expect(abs(Measure.areaSquareMeters(Array(r.dropFirst(2)) + Array(r.prefix(2))) - area) <= 1e-3)
        // The same cell either side of the antimeridian.
        let across = Measure.areaSquareMeters(rectangle(179.5, 44.0, -179.5, 45.0))
        #expect(near(area, across, 1e-9))
    }

    @Test("fewer than three points or points in a line enclose nothing")
    func degenerate() {
        #expect(Measure.areaSquareMeters([]) == 0.0)
        #expect(Measure.areaSquareMeters([LonLat(lon: 5.0, lat: 45.0), LonLat(lon: 5.1, lat: 45.0)]) == 0.0)
        let inLine = [LonLat(lon: 5.0, lat: 45.0), LonLat(lon: 5.1, lat: 45.0), LonLat(lon: 5.2, lat: 45.0)]
        #expect(abs(Measure.areaSquareMeters(inLine)) <= 1e-3)
        // A bow-tie (self-crossing) polygon has two lobes that cancel: the figure is the signed sum, here zero.
        let bowTie = [
            LonLat(lon: 5.0, lat: 45.0), LonLat(lon: 5.1, lat: 45.1), LonLat(lon: 5.1, lat: 45.0), LonLat(lon: 5.0, lat: 45.1),
        ]
        #expect(Measure.areaSquareMeters(bowTie) < 1.0)
    }

    @Test("measure of nothing, one point, two points and a polygon")
    func measures() throws {
        let none = Measure.measure([])
        #expect(none.pointCount == 0)
        #expect(none.lengthMeters == 0.0)
        #expect(none.areaSquareMeters == nil)
        #expect(none.lastBearingDegrees == nil)
        #expect(none.directMeters == nil)

        let one = Measure.measure([LonLat(lon: 5.0, lat: 45.0)])
        #expect(one.lengthMeters == 0.0)
        #expect(one.lastBearingDegrees == nil)

        // 0.01° of longitude along a parallel at 45 N, due east.
        let two = Measure.measure([LonLat(lon: 5.0, lat: 45.0), LonLat(lon: 5.01, lat: 45.0)])
        #expect(abs(two.lengthMeters - 786.0) <= 1.5) // 111 195 m × 0.01 × cos 45° ≈ 786 m
        let lastBearing = try #require(two.lastBearingDegrees)
        let lastSegment = try #require(two.lastSegmentMeters)
        let direct = try #require(two.directMeters)
        #expect(abs(lastBearing - 90.0) <= 0.1)
        #expect(abs(lastSegment - two.lengthMeters) <= 1e-9)
        #expect(abs(direct - two.lengthMeters) <= 1e-9)
        #expect(two.areaSquareMeters == nil)
        #expect(two.perimeterMeters == nil)

        let square = Measure.measure(rectangle(5.0, 45.0, 5.01, 45.01))
        #expect(square.pointCount == 4)
        #expect(square.areaSquareMeters != nil)
        // Three sides drawn, the closing one is in the perimeter only.
        let perimeter = try #require(square.perimeterMeters)
        let closing = Measure.distance(LonLat(lon: 5.0, lat: 45.01), LonLat(lon: 5.0, lat: 45.0))
        #expect(abs(perimeter - (square.lengthMeters + closing)) <= 1e-6)
        // Last segment goes west along the top edge.
        let squareBearing = try #require(square.lastBearingDegrees)
        #expect(abs(squareBearing - 270.0) <= 0.1)
        // "As the crow flies": first corner to the last one is due north.
        let directBearing = try #require(square.directBearingDegrees)
        #expect(abs(directBearing - 0.0) <= 0.1)
    }

    @Test("an out-and-back path is twice the way and a polygon is not its length")
    func outAndBack() {
        let way = [LonLat(lon: 5.0, lat: 45.0), LonLat(lon: 5.0, lat: 45.1), LonLat(lon: 5.1, lat: 45.1)]
        let back = way + Array(way.reversed().dropFirst())
        #expect(abs(Measure.pathLength(back) - 2 * Measure.pathLength(way)) <= 1e-6)
        let ratio = Measure.distance(LonLat(lon: 0.0, lat: 0.0), LonLat(lon: 1.0, lat: 1.0)) /
            Measure.distance(LonLat(lon: 0.0, lat: 0.0), LonLat(lon: 0.0, lat: 1.0))
        #expect(abs(ratio - 2.0.squareRoot()) <= 0.01)
    }

    @Test("areas are written in square metres, hectares and square kilometres, or feet, acres and square miles")
    func areaTexts() {
        let l = Locale(identifier: "en_US")
        #expect(TrackFormat.area(0.0, locale: l) == "0 m²")
        #expect(TrackFormat.area(9500.0, locale: l) == "9500 m²")
        #expect(TrackFormat.area(10000.0, locale: l) == "1.00 ha")
        #expect(TrackFormat.area(123_456.0, locale: l) == "12.35 ha")
        #expect(TrackFormat.area(1_000_000.0, locale: l) == "1.000 km²")
        #expect(TrackFormat.area(12_345_678.0, locale: l) == "12.346 km²")
        #expect(TrackFormat.area(100.0, locale: l, units: .imperial) == "1076 ft²")
        #expect(TrackFormat.area(4046.9, locale: l, units: .imperial) == "1.00 ac")
        #expect(TrackFormat.area(1_000_000.0, locale: l, units: .imperial) == "247.11 ac")
        #expect(TrackFormat.area(2_590_000.0, locale: l, units: .imperial) == "1.000 mi²")
    }
}
