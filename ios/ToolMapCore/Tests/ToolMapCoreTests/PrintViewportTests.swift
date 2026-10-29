// Port of core/src/test/kotlin/fr/toolmap/core/print/PrintViewportTest.kt (PrintViewportTest and PrintFormatTest).

import Foundation
import Testing
@testable import ToolMapCore

private func near(_ a: Double, _ b: Double, _ tolerance: Double) -> Bool {
    abs(a - b) <= tolerance
}

struct PrintViewportTests {
    private let a4 = PageSetup()

    @Test("the frame covers its ground size at the scale")
    func groundSize() {
        // 277 mm at 1:25 000 is 6 925 m; the frame of an A4 landscape sheet is 263.8 x 148.2 mm.
        #expect(near(PrintViewport.groundMeters(277.0, 25_000.0), 6_925.0, 1e-9))
        let zoom = 13.0
        let (w, h) = PrintViewport.frameDp(a4, 25_000.0, 45.0, zoom)
        let metersPerDp = ScaleBar.metersPerDp(45.0, zoom)
        #expect(near(w * metersPerDp, a4.frameWidthMm * 25.0, 1e-6))
        #expect(near(h * metersPerDp, a4.frameHeightMm * 25.0, 1e-6))
    }

    @Test("the camera zoom is the inverse of the metres under a dp")
    func inverse() {
        for lat in [0.0, 45.0, 60.0] {
            for zoom in [3.5, 12.0, 17.25] {
                #expect(near(ScaleBar.cameraZoomFor(lat, ScaleBar.metersPerDp(lat, zoom)), zoom, 1e-9))
            }
        }
    }

    @Test("fitting the frame leaves it inside the available room and touching one side")
    func fitting() {
        let availableW = 360.0
        let availableH = 400.0
        let zoom = PrintViewport.zoomToFit(a4, 50_000.0, 45.0, availableW, availableH)
        let (w, h) = PrintViewport.frameDp(a4, 50_000.0, 45.0, zoom)
        #expect(w <= availableW + 1e-6 && h <= availableH + 1e-6, "\(w) x \(h)")
        #expect(abs(w - availableW) < 1e-6 || abs(h - availableH) < 1e-6, "\(w) x \(h)")
        // A4 landscape is wider than 360 x 400 allows: the width is what limits.
        #expect(near(w, availableW, 1e-6))
    }

    @Test("a larger scale denominator needs a smaller zoom to fit")
    func largerScale() {
        let close = PrintViewport.zoomToFit(a4, 10_000.0, 45.0, 360.0, 400.0)
        let far = PrintViewport.zoomToFit(a4, 100_000.0, 45.0, 360.0, 400.0)
        #expect(far < close)
        #expect(near(close - far, log(10.0) / log(2.0), 1e-9))
    }

    @Test("the pages of an atlas fit the room around their middle")
    func atlasFit() throws {
        // Two A4 landscape pages at 1:25 000, side by side along the parallel of 45° N.
        let first = try FrameProjection(45.0, 6.0, 25_000.0, a4.frameWidthMm, a4.frameHeightMm)
        let second = try FrameProjection(45.0, 6.08, 25_000.0, a4.frameWidthMm, a4.frameHeightMm)
        let corners = [first, second].flatMap { f in
            [f.toLonLat(0.0, 0.0), f.toLonLat(f.frameWidthMm, f.frameHeightMm)]
        }
        let fit = try #require(PrintViewport.fit(corners, 360.0, 400.0))
        #expect(near(fit.lon, 6.04, 1e-6))
        #expect(near(fit.lat, 45.0, 1e-3))
        // One page alone would fit with a larger zoom; the two together are wider and limited by the width.
        let one = PrintViewport.zoomToFit(a4, 25_000.0, 45.0, 360.0, 400.0)
        #expect(fit.zoom < one)
        let lons = corners.map(\.lon)
        let maxLon = try #require(lons.max())
        let minLon = try #require(lons.min())
        let span = (maxLon - minLon) * 111_320.0 * cos(45.0 * Double.pi / 180)
        #expect(near(span / ScaleBar.metersPerDp(fit.lat, fit.zoom), 360.0, 1.0))
        #expect(PrintViewport.fit([], 360.0, 400.0) == nil)
    }
}

struct PrintFormatTests {
    private let date = "2026-10-02"

    @Test("the file name carries the title, the scale and the date")
    func fileName() {
        #expect(PrintFormat.fileName("Belledonne", 25_000.0, date) == "ToolMap_Belledonne_1-25000_2026-10-02.pdf")
        #expect(
            PrintFormat.fileName("  Col de la Croix ", 50_000.0, date) == "ToolMap_Col_de_la_Croix_1-50000_2026-10-02.pdf"
        )
        #expect(PrintFormat.fileName("", 10_000.0, date) == "ToolMap_carte_1-10000_2026-10-02.pdf")
        #expect(
            PrintFormat.fileName("IGN Cartes \u{B7} Nivolet", 25_000.0, date)
                == "ToolMap_IGN_Cartes_Nivolet_1-25000_2026-10-02.pdf"
        )
    }

    @Test("the ground size has the same decimals on both sides and the separator of the language")
    func groundSize() {
        let france = Locale(identifier: "fr_FR")
        let small = PrintFormat.groundSize(6_595.0, 3_705.0, france)
        #expect(small.0 == "6,6 km" && small.1 == "3,7 km")
        let us = PrintFormat.groundSize(13_190.0, 7_410.0, Locale(identifier: "en_US"))
        #expect(us.0 == "13.2 km" && us.1 == "7.4 km")
        let meters = PrintFormat.groundSize(850.0, 480.0, france)
        #expect(meters.0 == "850 m" && meters.1 == "480 m")
        let wide = PrintFormat.groundSize(131_900.0, 74_100.0, france)
        #expect(wide.0 == "132 km" && wide.1 == "74 km")
    }

    @Test("forbidden characters never reach the file name")
    func forbiddenCharacters() {
        let name = PrintFormat.fileName("a/b\\c:d*e?f\"g<h>i|j\nk", 25_000.0, date)
        #expect(!name.contains { "/\\:*?\"<>|\n".contains($0) }, "\(name)")
        #expect(PrintFormat.fileName("///", 25_000.0, date) == "ToolMap_carte_1-25000_2026-10-02.pdf")
        #expect(PrintFormat.fileName(String(repeating: "x", count: 500), 25_000.0, date).count < 100)
    }

    @Test("the scale is written with no-break spaces and the declination with minutes")
    func scaleAndDeclination() {
        let texts = PrintTexts()
        #expect(PrintFormat.scale(25_000.0, texts) == "1:25\u{00A0}000")
        #expect(PrintFormat.scale(500.0, texts) == "1:500")
        #expect(PrintFormat.declination(2.25, "E", "W") == "2°15\u{2032} E")
        #expect(PrintFormat.declination(-0.5, "E", "W") == "0°30\u{2032} W")
    }
}
