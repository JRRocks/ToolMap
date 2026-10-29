// Port of core/src/test/kotlin/fr/toolmap/core/elevation/ElevationMaskTest.kt.

import Testing
@testable import ToolMapCore

struct ElevationMaskTests {
    /// A 4 × 4 DEM whose elevation grows by 100 m per pixel to the east: 0, 100, 200, 300 on every row.
    private let ramp: [Float] = (0 ..< 16).map { Float($0 % 4) * 100 }

    @Test("the band travels in the tile URL query and back")
    func queryRoundTrip() {
        let masks = [
            ElevationMask(1500, nil),
            ElevationMask(nil, 1200),
            ElevationMask(-50, 2000, IntRange(8, 16)),
            ElevationMask(1500, nil, IntRange(14, 30)),
        ]
        for mask in masks {
            #expect(ElevationMask.parse(mask.query()) == mask)
        }
        #expect(ElevationMask(1500, nil).query() == "alt=1500,")
        #expect(ElevationMask(nil, 1200, IntRange(8, 16)).query() == "alt=,1200&whole=8,16")
    }

    @Test("a query without a band or with a broken one is a plain tile")
    func plainTile() {
        #expect(ElevationMask.parse(nil) == nil)
        #expect(ElevationMask.parse("") == nil)
        #expect(ElevationMask.parse("apikey=abc") == nil)
        #expect(ElevationMask.parse("alt=abc,") == nil)
        #expect(ElevationMask.parse("alt=1500") == nil)
        #expect(ElevationMask.parse("alt=1500,&whole=8") == nil)
    }

    @Test("tiles of the whole zooms are drawn whole")
    func wholeZooms() {
        let mask = ElevationMask(1500, nil, IntRange(8, 16))
        #expect(mask.isWholeAt(8))
        #expect(mask.isWholeAt(16))
        #expect(!mask.isWholeAt(7))
        #expect(!mask.isWholeAt(17))
        #expect(!ElevationMask(1500, nil).isWholeAt(12))
        #expect(ElevationMask(nil, nil).isWholeAt(12))
    }

    @Test("coverage of a tile from its lowest and highest elevations")
    func coverage() {
        let above = ElevationMask(1500, nil)
        #expect(above.coverage(200, 1499) == ElevationMask.Coverage.none)
        #expect(above.coverage(1500, 2800) == ElevationMask.Coverage.all)
        #expect(above.coverage(1200, 1800) == ElevationMask.Coverage.part)
        let band = ElevationMask(1000, 2000)
        #expect(band.coverage(2100, 3000) == ElevationMask.Coverage.none)
        #expect(band.coverage(1100, 1900) == ElevationMask.Coverage.all)
        #expect(band.coverage(1900, 2100) == ElevationMask.Coverage.part)
    }

    @Test("the DEM window of a tile finer than the DEM is a cell of a coarser tile")
    func demWindow() {
        #expect(DemWindow.of(10, 530, 365) == DemWindow(demZ: 10, demX: 530, demY: 365, depth: 0, col: 0, row: 0))
        #expect(DemWindow.of(13, 4250, 2925) == DemWindow(demZ: 13, demX: 4250, demY: 2925, depth: 0, col: 0, row: 0))
        // z16 tile (34003, 23403) lies in z13 tile (4250, 2925), cell (3, 3) of 8 × 8.
        #expect(DemWindow.of(16, 34003, 23403) == DemWindow(demZ: 13, demX: 4250, demY: 2925, depth: 3, col: 3, row: 3))
    }

    @Test("elevations are interpolated between DEM pixel centres")
    func interpolated() {
        let same = MaskRaster.elevations(ramp, 4, DemWindow.of(5, 0, 0, maxDemZoom: 5), 4)
        #expect(Array(same.prefix(4)) == [0, 100, 200, 300])
        // An 8 px tile over the same DEM: half-pixel steps, the border pixels repeated beyond the centres.
        let finer = MaskRaster.elevations(ramp, 4, DemWindow.of(5, 0, 0, maxDemZoom: 5), 8)
        #expect(Array(finer.prefix(8)) == [0, 25, 75, 125, 175, 225, 275, 300])
        // The eastern half of the DEM, one zoom finer: 4 px from 150 m to 300 m.
        let cell = MaskRaster.elevations(ramp, 4, DemWindow.of(6, 1, 0, maxDemZoom: 5), 4)
        #expect(Array(cell.prefix(4)) == [175, 225, 275, 300])
    }

    @Test("the range of a window includes the pixels its interpolation reads")
    func windowRange() {
        let whole = MaskRaster.range(ramp, 4, DemWindow.of(5, 0, 0, maxDemZoom: 5))
        #expect(whole.0 == 0 && whole.1 == 300)
        // Eastern half (columns 2 and 3) plus one pixel to the west (column 1).
        let east = MaskRaster.range(ramp, 4, DemWindow.of(6, 1, 0, maxDemZoom: 5))
        #expect(east.0 == 100 && east.1 == 300)
    }

    @Test("alphas are opaque inside the band, clear outside, with a one pixel soft edge")
    func softEdge() {
        // 1460 .. 1600 m eastwards, 20 m per pixel.
        let elevations: [Float] = (0 ..< 64).map { 1460 + Float($0 % 8) * 20 }
        let alphas = MaskRaster.alphas(elevations, 8, ElevationMask(1500, nil)).map { Int($0) }
        // Column 1 (1480 m) is a pixel below, column 2 (1500 m) on the contour, column 3 (1520 m) a pixel above.
        #expect(Array(alphas.prefix(8)) == [0, 0, 128, 255, 255, 255, 255, 255])
        #expect(Array(alphas.prefix(8)) == Array(alphas.suffix(8)))
    }

    @Test("an upper bound clears the pixels above it")
    func upperBound() {
        // 900, 1000, 1100, 1200 m eastwards.
        let elevations: [Float] = (0 ..< 16).map { 900 + Float($0 % 4) * 100 }
        let alphas = MaskRaster.alphas(elevations, 4, ElevationMask(nil, 1000)).map { Int($0) }
        #expect(alphas[0] == 255)
        #expect(alphas[1] == 128)
        #expect(alphas[2] == 0)
        #expect(alphas[3] == 0)
    }
}
