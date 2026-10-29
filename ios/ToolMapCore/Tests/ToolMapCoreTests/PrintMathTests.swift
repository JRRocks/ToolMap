// Port of core/src/test/kotlin/fr/toolmap/core/print/PrintMathTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

private func near(_ a: Double, _ b: Double, _ tolerance: Double) -> Bool {
    abs(a - b) <= tolerance
}

struct PrintMathTests {
    private let lat45 = 45.0

    @Test("ground resolution and tile zoom follow the reference table of the spec")
    func referenceTable() {
        // scale, dpi, r (m/px), z*, R(z*) (m/px)
        let table: [(Double, Int, Double, Int, Double)] = [
            (25_000.0, 300, 2.117, 16, 1.689),
            (25_000.0, 150, 4.233, 15, 3.378),
            (50_000.0, 300, 4.233, 15, 3.378),
            (100_000.0, 300, 8.467, 14, 6.756),
        ]
        for (scale, dpi, r, z, rz) in table {
            #expect(near(PrintMath.groundResolution(scale, dpi), r, 0.001), "r at 1:\(scale) / \(dpi)")
            #expect(PrintMath.tileZoom(scale, dpi, lat45) == z, "z* at 1:\(scale) / \(dpi)")
            #expect(near(PrintMath.tileResolution(z, lat45), rz, 0.001), "R(z*) at 1:\(scale) / \(dpi)")
        }
    }

    @Test("a lighter file takes one level less and the zoom is bounded")
    func lighter() {
        #expect(PrintMath.tileZoom(25_000.0, 300, lat45, lighter: true) == 15)
        #expect(PrintMath.tileZoom(1e12, 150, lat45, lighter: true) == 0)
        #expect(PrintMath.tileZoom(1.0, 300, lat45) == 24)
    }

    @Test("tiles are sharp enough at the chosen zoom and not at the one below")
    func sharpEnough() {
        for scale in [5_000.0, 10_000.0, 25_000.0, 50_000.0, 250_000.0] {
            for dpi in [150, 200, 300] {
                for lat in [0.0, 30.0, 45.0, 60.0] {
                    let z = PrintMath.tileZoom(scale, dpi, lat)
                    let r = PrintMath.groundResolution(scale, dpi)
                    #expect(PrintMath.tileResolution(z, lat) <= r + 1e-9, "z=\(z) too coarse at 1:\(scale)/\(dpi)/\(lat)")
                    #expect(PrintMath.tileResolution(z - 1, lat) > r, "z=\(z) is not the smallest at 1:\(scale)/\(dpi)/\(lat)")
                }
            }
        }
    }

    @Test("an exact power of two does not jump a level because of rounding")
    func exactPowerOfTwo() {
        // Pick the scale at which the equivalent zoom is exactly 15 at 45 degrees and 300 dpi.
        let r = PrintMath.tileResolution(15, lat45)
        let scale = r * 300 / 0.0254
        #expect(near(PrintMath.equivalentZoom(scale, 300, lat45), 15.0, 1e-9))
        #expect(PrintMath.tileZoom(scale, 300, lat45) == 15)
    }

    @Test("an A4 landscape frame covers 13,19 by 7,41 km at 1 to 50 000")
    func a4Frame() {
        // 8 mm margin, 7 mm of labels and 1.6 mm of graduated band around; the footer (28.6 mm) under the map.
        let setup = PageSetup(paper: .a4, landscape: true)
        #expect(near(setup.frameWidthMm, 263.8, 1e-9))
        #expect(near(setup.frameHeightMm, 148.2, 1e-9))
        #expect(near(PrintMath.groundMeters(setup.frameWidthMm, 50_000.0), 13_190.0, 1e-6))
        #expect(near(PrintMath.groundMeters(setup.frameHeightMm, 50_000.0), 7_410.0, 1e-6))
        #expect(near(PrintMath.groundMeters(setup.frameWidthMm, 25_000.0), 6_595.0, 1e-6))
        #expect((setup.landscape ? setup.heightPt : setup.widthPt) == 595)
        #expect(setup.widthPt == 842)
    }

    @Test("the edge error is about 0,075 percent for A4 at 1 to 50 000 and 45 degrees")
    func edgeError() {
        #expect(near(PrintMath.edgeErrorPercent(190.0, 50_000.0, lat45), 0.075, 0.002))
        #expect(near(PrintMath.edgeErrorPercent(190.0, 50_000.0, 0.0), 0.0, 1e-12))
    }

    @Test("an A4 at 1 to 50 000 needs about 200 tiles per layer at zoom 15")
    func tilesPerLayer() throws {
        let frame = try FrameProjection(lat45, 6.0, 50_000.0, 277.0, 190.0)
        let b = frame.mercatorBounds
        let origin = TileMath.originShiftM
        let size = 2 * origin / Double(1 << 15)
        let cols = floor((b.maxX + origin) / size) - floor((b.minX + origin) / size) + 1
        let rows = floor((origin - b.minY) / size) - floor((origin - b.maxY) / size) + 1
        #expect(cols * rows >= 170.0 && cols * rows <= 260.0, "\(cols) x \(rows)")
    }

    @Test("a source stopped at zoom 14 is enlarged by 3,2 for a print at 1 to 25 000 and 300 dpi")
    func enlarged() {
        let used = PrintMath.nativeZoom(16, Array(6 ... 14))
        #expect(used == 14)
        #expect(near(PrintMath.oversampling(14, 25_000.0, 300, lat45), 3.2, 1e-9))
        #expect(near(PrintMath.oversampling(16, 25_000.0, 300, lat45), 1.0, 1e-9))
        #expect(near(PrintMath.oversampling(17, 25_000.0, 300, lat45), 1.0, 1e-9))
    }

    @Test("a source with gaps gives the nearest zoom above, else its highest")
    func gaps() {
        #expect(PrintMath.nativeZoom(11, [4, 8, 12, 16]) == 12)
        #expect(PrintMath.nativeZoom(16, [4, 8, 12, 16]) == 16)
        #expect(PrintMath.nativeZoom(18, [4, 8, 12, 16]) == 16)
        #expect(PrintMath.nativeZoom(1, [4, 8, 12, 16]) == 4)
        #expect(PrintMath.nativeZoom(5, []) == nil)
    }

    @Test("pixel sizes of the output")
    func pixelSizes() {
        // A3 landscape at 300 dpi: a 386.8 x 235.2 mm frame is about 12.7 Mpx.
        let setup = PageSetup(paper: .a3, landscape: true)
        let w = PrintMath.pixels(setup.frameWidthMm, 300)
        let h = PrintMath.pixels(setup.frameHeightMm, 300)
        #expect(w == 4_569)
        #expect(h == 2_778)
        #expect(near(Double(w * h) / 1e6, 12.7, 0.1))
    }

    @Test("the page of a given paper has the standard point sizes")
    func pointSizes() {
        let a4 = PageSetup(paper: .a4, landscape: false)
        #expect(a4.widthPt == 595 && a4.heightPt == 842)
        let a4Landscape = PageSetup(paper: .a4, landscape: true)
        #expect(a4Landscape.widthPt == 842 && a4Landscape.heightPt == 595)
        let letter = PageSetup(paper: .letter, landscape: false)
        #expect(letter.widthPt == 612 && letter.heightPt == 792)
        let a3 = PageSetup(paper: .a3, landscape: false)
        #expect(a3.widthPt == 842 && a3.heightPt == 1191)
    }

    @Test("page issues")
    func pageIssues() {
        #expect(PageSetup().issues().isEmpty)
        #expect(PageSetup(marginMm: -1.0).issues().contains(.marginRange))
        #expect(PageSetup(marginMm: 60.0).issues().contains(.marginRange))
        #expect(PageSetup(paper: .custom, customWidthMm: 10.0).issues().contains(.customSize))
        #expect(PageSetup(paper: .custom, customWidthMm: Double.nan).issues().contains(.customSize))
        #expect(PageSetup(paper: .custom, customHeightMm: Double.infinity).issues().contains(.customSize))
        // No largest custom sheet (2026-10-29): 5 m by 1,5 m is a sheet like any other.
        #expect(PageSetup(paper: .custom, customWidthMm: 5_000.0, customHeightMm: 1_500.0).issues().isEmpty)
        let tiny = PageSetup(paper: .custom, marginMm: 25.0, customWidthMm: 60.0, customHeightMm: 60.0)
        #expect(tiny.issues().contains(.frameTooSmall))
        #expect(PageSetup(paper: .custom, landscape: true, customWidthMm: 300.0, customHeightMm: 200.0).widthMm == 300.0)
    }

    @Test("the frame projection puts the centre at the middle and keeps the scale along both axes")
    func frameProjection() throws {
        let frame = try FrameProjection(lat45, 6.0, 25_000.0, 277.0, 190.0)
        let centre = frame.toFrame(6.0, lat45)
        #expect(near(centre.xMm, 138.5, 1e-9))
        #expect(near(centre.yMm, 95.0, 1e-9))
        // 100 mm east of the centre along the parallel is 2.5 km at 1:25 000.
        let east = frame.toLonLat(centre.xMm + 100.0, centre.yMm)
        let metersEast = Geodesy.distance(lat45, 6.0, east.lat, east.lon)
        #expect(near(metersEast, 2_500.0, 2_500.0 * 0.002))
        // And 2.5 km north (100 mm up) along the meridian, to within the scale error of the edge.
        let north = frame.toLonLat(centre.xMm, centre.yMm - 100.0)
        let metersNorth = Geodesy.distance(lat45, 6.0, north.lat, north.lon)
        #expect(near(metersNorth, 2_500.0, 2_500.0 * 0.002))
        // Round trip.
        let p = frame.toFrame(6.05, 45.03)
        let back = frame.toLonLat(p.xMm, p.yMm)
        #expect(near(back.lon, 6.05, 1e-9))
        #expect(near(back.lat, 45.03, 1e-9))
        #expect(frame.contains(centre))
        #expect(!frame.contains(FramePoint(xMm: -1.0, yMm: 5.0)))
        let box = frame.lonLatBounds
        #expect(box.minX < 6.0 && box.maxX > 6.0 && box.minY < lat45 && box.maxY > lat45)
        #expect(abs(box.maxX - 6.0 - (6.0 - box.minX)) < 1e-9)
    }

    @Test("a ten kilometer segment is 200 millimeters at 1 to 50 000")
    func tenKilometers() throws {
        let frame = try FrameProjection(lat45, 6.0, 50_000.0, 277.0, 190.0)
        // Two points 10 km apart on the same parallel, symmetric around the centre of the frame.
        let halfDegrees = (5_000.0 / 6_371_000.0 / cos(lat45 * Double.pi / 180)) * 180 / Double.pi
        let a = frame.toFrame(6.0 - halfDegrees, lat45)
        let b = frame.toFrame(6.0 + halfDegrees, lat45)
        // The criterion of the spec: 200 mm +/- 0.5 % on the paper (ellipsoid and sphere differ by a few thousandths).
        #expect(near(b.xMm - a.xMm, 200.0, 1.0))
        #expect(near(a.yMm, b.yMm, 1e-9))
    }

    @Test("size estimates grow with pixels and pages")
    func sizeEstimates() {
        let (low, high) = PrintMath.estimatedBytes(14_500_000, 1)
        #expect(low >= 1_000_000 && low <= 5_000_000 && high >= 20_000_000 && high <= 40_000_000, "\(low) .. \(high)")
        #expect(abs(low * 3 - PrintMath.estimatedBytes(14_500_000, 3).0) <= 3)
    }
}
