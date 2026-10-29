// Port of core/src/test/kotlin/fr/toolmap/core/geo/ScaleBarTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct ScaleBarTests {
    @Test("world at zoom 0 is 512 dp wide")
    func worldAtZoom0() {
        // Equator: 40 075 016.69 m / 512 dp
        #expect(abs(ScaleBar.metersPerDp(0.0, 0.0) - 78271.517) <= 0.001)
    }

    @Test("each zoom level halves the resolution and latitude scales by cosine")
    func halving() {
        let z10 = ScaleBar.metersPerDp(0.0, 10.0)
        #expect(abs(ScaleBar.metersPerDp(0.0, 11.0) - z10 / 2) <= 1e-9)
        #expect(abs(ScaleBar.metersPerDp(45.0, 10.0) - z10 * cos(Angle.radians(45.0))) <= 1e-9)
    }

    @Test("camera zoom 12 near Grenoble is about 13_47 m per dp")
    func grenoble() {
        // The camera uses 512 dp tiles: at zoom 12 the world is 512 * 4096 dp wide.
        // 40 075 016.69 m * cos(45.19 deg) / 2 097 152 dp = 13.4674 m (checked with Python).
        #expect(abs(ScaleBar.metersPerDp(45.19, 12.0) - 13.4674) <= 0.001)
    }

    @Test("nice distances follow the 1-2-5 series")
    func niceDistances() {
        #expect(ScaleBar.niceDistance(1.0) == 1.0)
        #expect(ScaleBar.niceDistance(1.99) == 1.0)
        #expect(ScaleBar.niceDistance(2.0) == 2.0)
        #expect(ScaleBar.niceDistance(4.99) == 2.0)
        #expect(ScaleBar.niceDistance(5.0) == 5.0)
        #expect(ScaleBar.niceDistance(9.99) == 5.0)
        #expect(ScaleBar.niceDistance(10.0) == 10.0)
        #expect(ScaleBar.niceDistance(1234.0) == 1000.0)
        #expect(ScaleBar.niceDistance(37000.0) == 20000.0)
        #expect(abs(ScaleBar.niceDistance(0.3) - 0.2) <= 1e-12)
        #expect(abs(ScaleBar.niceDistance(0.99) - 0.5) <= 1e-12)
    }

    @Test("nice distance never exceeds its input and is within a factor 2_5")
    func niceBounds() {
        var value = 0.013
        for _ in 0 ..< 200 {
            let nice = ScaleBar.niceDistance(value)
            #expect(nice <= value, "\(nice) > \(value)")
            #expect(nice * 2.5 > value, "\(nice) too small for \(value)")
            value *= 1.137
        }
    }

    @Test("invalid inputs give 0")
    func invalidInputs() {
        #expect(ScaleBar.niceDistance(0.0) == 0.0)
        #expect(ScaleBar.niceDistance(-3.0) == 0.0)
        #expect(ScaleBar.niceDistance(Double.nan) == 0.0)
        #expect(ScaleBar.niceDistance(Double.infinity) == 0.0)
    }

    @Test("labels use cm, m and km")
    func labels() {
        #expect(ScaleBar.format(0.5) == "50 cm")
        #expect(ScaleBar.format(20.0) == "20 m")
        #expect(ScaleBar.format(500.0) == "500 m")
        #expect(ScaleBar.format(1000.0) == "1 km")
        #expect(ScaleBar.format(20000.0) == "20 km")
        #expect(ScaleBar.format(2_000_000.0) == "2000 km")
    }

    @Test("computed bar fits in the available width")
    func barFits() {
        for zoom in [2.0, 5.5, 9.0, 12.3, 15.0, 18.7] {
            let bar = ScaleBar.compute(45.0, zoom, maxWidthDp: 120.0)
            #expect(bar.widthDp <= 120.0 + 1e-9, "zoom \(zoom): \(bar.widthDp)")
            #expect(bar.widthDp > 120.0 / 2.5 - 1e-9, "zoom \(zoom): too short \(bar.widthDp)")
            #expect(!bar.label.isEmpty)
        }
    }

    @Test("with MapLibre's default tile size the tile zoom is camera zoom plus one")
    func standardMapping() {
        #expect(ZoomMapping.standard.toTileZoom(11.0) == 12.0)
        #expect(ZoomMapping.standard.toCameraZoom(12.0) == 11.0)
    }

    // Kotlin "decimal degrees ignore the device locale" sets the JVM default locale to French; Swift's
    // `String(format:)` has no default locale, so the check is the plain one.
    @Test("decimal degrees ignore the device locale")
    func decimalDegreesLocale() {
        #expect(CoordinateFormat.decimalDegrees(44.8, 5.9) == "44.80000, 5.90000")
        #expect(CoordinateFormat.decimalDegreesWithHemisphere(44.8, -5.9) == "44.80000° N, 5.90000° W")
        #expect(CoordinateFormat.decimalDegreesWithHemisphere(-12.5, 5.9) == "12.50000° S, 5.90000° E")
    }

    @Test("imperial bars are round feet or miles")
    func imperial() {
        #expect(ScaleBar.formatImperial(30.48) == "100 ft")
        #expect(ScaleBar.formatImperial(1609.344 * 2) == "2 mi")
        let bar = ScaleBar.compute(45.0, 12.0, maxWidthDp: 120.0, units: .imperial)
        #expect(bar.widthDp <= 120.0 + 1e-9)
        #expect(bar.label.hasSuffix(" ft") || bar.label.hasSuffix(" mi"))
    }
}
