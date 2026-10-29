// Port of core/src/test/kotlin/fr/toolmap/core/print/PrintPlanTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

private func near(_ a: Double, _ b: Double, _ tolerance: Double) -> Bool {
    abs(a - b) <= tolerance
}

/// The built-in sources by id (Kotlin `associateBy`: the last of a repeated id wins).
func builtinSourcesById() throws -> [String: TileSource] {
    try Dictionary(BuiltinSources.load().map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
}

struct PrintPlanTests {
    private let sources: [String: TileSource]
    private let lat45 = 45.0

    init() throws {
        sources = try builtinSourcesById()
    }

    private func layer(
        _ source: String,
        min: Int? = nil,
        max: Int? = nil,
        opacity: Float = 1,
        visible: Bool = true,
        minElevation: Int? = nil,
        logic: RangeLogic = .and
    ) -> LayerConfig {
        LayerConfig(
            id: "l-\(source)",
            sourceId: source,
            visible: visible,
            opacity: opacity,
            displayMinZoom: min,
            displayMaxZoom: max,
            displayMinElevation: minElevation,
            rangeLogic: logic
        )
    }

    private func plan(
        _ stack: [LayerConfig],
        scale: Double = 25_000.0,
        dpi: Int = 300,
        respect: Bool = true,
        lighter: Bool = false
    ) -> PrintLayerPlan {
        let sources = sources
        return PrintLayerPlanner.plan(
            stack: stack,
            source: { sources[$0] },
            scale: scale,
            dpi: dpi,
            latitude: lat45,
            respectRanges: respect,
            lighter: lighter
        )
    }

    @Test("SCAN 25 stops at zoom 16 and triggers the oversampling warning at 1 to 6 250 and 300 dpi")
    func scan25() throws {
        let p = plan([layer("ign-scan25")], scale: 6_250.0)
        #expect(p.idealZoom == 18)
        #expect(p.layers.count == 1)
        let used = try #require(p.layers.first)
        #expect(used.tileZoom == 16)
        #expect(near(used.oversampling, 3.2, 1e-9))
        #expect(p.warnings.count == 1)
        guard let warning = p.warnings.first, case let .oversampled(_, _, factor, usedZoom, wantedZoom) = warning else {
            Issue.record("expected an oversampling warning, got \(p.warnings)")
            return
        }
        #expect(near(factor, 3.2, 1e-9))
        #expect(usedZoom == 16)
        #expect(wantedZoom == 18)
        // At 1:25,000 it now goes deep enough.
        #expect(plan([layer("ign-scan25")]).warnings.isEmpty)
    }

    @Test("a source that goes deep enough is read at the ideal zoom without warning")
    func deepEnough() throws {
        let p = plan([layer("ign-maps")])
        let only = try #require(p.layers.first)
        #expect(p.layers.count == 1)
        #expect(only.tileZoom == 16)
        #expect(p.warnings.isEmpty)
        #expect(only.oversampling == 1.0)
    }

    @Test("hidden and transparent layers are left out and the order of the stack is kept")
    func hiddenLayers() {
        let p = plan([
            layer("ign-maps"),
            layer("osm", visible: false),
            layer("opentopomap", opacity: 0),
            layer("ign-pentes", opacity: 0.5),
        ])
        #expect(p.layers.map(\.source.id) == ["ign-maps", "ign-pentes"])
        #expect(p.warnings.isEmpty)
    }

    @Test("display ranges decide with the zoom the page is equivalent to")
    func displayRanges() {
        // 1:25 000 at 300 dpi and 45 degrees: equivalent zoom 15.67, as the screen would show at that zoom.
        let range = plan([layer("ign-maps", min: 8, max: 15), layer("ign-plan", min: 16, max: 19)])
        #expect(near(range.equivalentZoom, 15.67, 0.01))
        // 15.67 < 15 + 1: the first stays; 15.67 < 16: the second is not yet visible.
        #expect(range.layers.map(\.source.id) == ["ign-maps"])
        #expect(range.warnings.count == 1)
        guard let warning = range.warnings.first, case let .outsideRange(sourceId, _) = warning else {
            Issue.record("expected an out-of-range warning, got \(range.warnings)")
            return
        }
        #expect(sourceId == "ign-plan")
        // The same stack at 1:10 000 (equivalent zoom 17.0): the roles are swapped.
        let closer = plan([layer("ign-maps", min: 8, max: 15), layer("ign-plan", min: 16, max: 19)], scale: 10_000.0)
        #expect(closer.layers.map(\.source.id) == ["ign-plan"])
    }

    @Test("ranges can be ignored")
    func ignoredRanges() {
        let p = plan([layer("ign-maps", min: 1, max: 5)], respect: false)
        #expect(p.layers.map(\.source.id) == ["ign-maps"])
        #expect(p.warnings.isEmpty)
    }

    @Test("a layer in an elevation band is printed in that band, read from the elevation map, as on the screen")
    func elevationBand() throws {
        // Sortie Ski: IGN SCAN 25 under the snow map above 1550 m; the IGN layer stays whole.
        let ski = Workspaces.sortieSki.toStack { _ in true }.stack.layers
        let p = plan(ski)
        let scan = try #require(p.layers.first { $0.source.id == "ign-scan25" })
        let snow = try #require(p.layers.first { $0.source.id == "OSMSNOWM" })
        #expect(scan.band == nil)
        let band = try #require(snow.band)
        #expect(band.mask == ElevationMask(1550, nil))
        #expect(band.dem.id == LayerElevation.demSourceId)
        // Ignoring the zoom ranges does not drop the elevation band.
        let ignoring = plan(ski, respect: false).layers.first { $0.source.id == "OSMSNOWM" }
        #expect(ignoring?.band != nil)
    }

    @Test("with OU the band is printed outside the zoom range and the layer whole inside it")
    func either() throws {
        let either = layer("ign-maps", min: 8, max: 15, minElevation: 1500, logic: .or)
        // Equivalent zoom 15.67: inside 8–15, the layer is drawn whole.
        let inside = plan([either])
        #expect(inside.layers.count == 1)
        #expect(inside.layers.first?.band == nil)
        // Equivalent zoom 17.0: outside the range, the screen still draws the band, and so does the page.
        let closer = plan([either], scale: 10_000.0)
        #expect(closer.layers.first?.band?.mask.min == 1500)
        let outside = closer.warnings.contains { warning in
            if case .outsideRange = warning { return true }
            return false
        }
        #expect(!outside)
        // With ET, outside the range, it is left out as before.
        let both = layer("ign-maps", min: 8, max: 15, minElevation: 1500, logic: .and)
        #expect(plan([both], scale: 10_000.0).layers.isEmpty)
    }

    @Test("a layer of an unknown source is reported")
    func unknownSource() {
        let p = plan([layer("nowhere")])
        #expect(p.layers.isEmpty)
        #expect(p.warnings == [PrintWarning.unavailable(sourceId: "nowhere")])
    }

    @Test("lighter takes one level less")
    func lighter() {
        #expect(plan([layer("ign-maps")], lighter: true).layers.first?.tileZoom == 15)
    }

    @Test("the raster of an A4 page at 300 dpi is cut in bands and the bands cover it exactly")
    func bands() throws {
        let frame = try FrameProjection(lat45, 6.0, 25_000.0, 277.0, 190.0)
        let raster = try PrintRaster(frame, 300)
        #expect(raster.widthPx == 3_272)
        #expect(raster.heightPx == 2_244)
        #expect(raster.bandCount == 5)
        #expect(try raster.band(0).y0 == 0)
        #expect(try raster.band(0).heightPx == 512)
        #expect(try raster.band(4).y0 == 2_048)
        #expect(try raster.band(4).heightPx == 196)
        var total = 0
        for i in 0 ..< raster.bandCount {
            total += try raster.band(i).heightPx
        }
        #expect(total == raster.heightPx)
    }

    @Test("a wide page is cut in pieces no wider than 8 192 px that cover it edge to edge")
    func pieces() throws {
        // A 2 m wide frame at 300 dpi: ≈ 23 600 px wide, three pieces in every band.
        let raster = try PrintRaster(FrameProjection(lat45, 6.0, 25_000.0, 2_000.0, 300.0), 300)
        #expect(raster.bandHeightPx == 512)
        #expect(raster.pieceCount == 3)
        let band = try raster.band(0)
        let pieces = raster.pieces(band)
        #expect(pieces.map(\.x0) == [0, 8_192, 16_384])
        #expect(pieces.reduce(0) { $0 + $1.widthPx } == raster.widthPx)
        #expect(pieces.allSatisfy { $0.widthPx <= PrintRaster.maxPieceWidth && $0.heightPx == band.heightPx })
        // The tiles of a piece are placed in its own bitmap, the ones cut by its edges reaching beyond it.
        let z = 16
        let middle = try raster.placements(z, pieces[1])
        let leftmost = try #require(middle.map(\.left).min())
        let rightmost = try #require(middle.map(\.right).max())
        #expect(leftmost <= 0 && rightmost >= pieces[1].widthPx)
        // A tile lands at the same place of the page whichever piece draws it.
        var inBand: [TileId: TilePlacement] = [:]
        for p in try raster.placements(z, band) {
            inBand[p.tile] = p
        }
        var drawn = Set<TileId>()
        for (k, piece) in pieces.enumerated() {
            for p in try raster.placements(z, piece) {
                drawn.insert(p.tile)
                let whole = try #require(inBand[p.tile])
                #expect(whole.left == p.left + piece.x0, "piece \(k), \(p.tile)")
                #expect(whole.right == p.right + piece.x0, "piece \(k), \(p.tile)")
            }
        }
        // Every tile of the band is drawn by one piece or two (when cut by an edge), never left out.
        #expect(Set(inBand.keys) == drawn)
        // An A4 page is one piece, the whole band.
        let a4 = try PrintRaster(FrameProjection(lat45, 6.0, 25_000.0, 277.0, 190.0), 300)
        let a4Band = try a4.band(0)
        #expect(a4.pieces(a4Band) == [a4Band])
    }

    @Test("tile placements abut without a gap and cover the band")
    func placementsAbut() throws {
        let frame = try FrameProjection(lat45, 6.0, 25_000.0, 277.0, 190.0)
        let raster = try PrintRaster(frame, 300)
        let z = 16
        for i in 0 ..< raster.bandCount {
            let band = try raster.band(i)
            let placements = try raster.placements(z, band)
            #expect(!placements.isEmpty)
            // Every pixel of the band is covered by at least one tile: check the corners and the middle.
            let probes = [
                (0, 0),
                (raster.widthPx - 1, 0),
                (0, band.heightPx - 1),
                (raster.widthPx - 1, band.heightPx - 1),
                (raster.widthPx / 2, band.heightPx / 2),
            ]
            for (x, y) in probes {
                let covered = placements.contains { x >= $0.left && x < $0.right && y >= $0.top && y < $0.bottom }
                #expect(covered, "band \(i) pixel \(x),\(y)")
            }
            // Neighbours in a row share their edge exactly.
            let byRow = Dictionary(grouping: placements) { $0.tile.y }
            for row in byRow.values {
                let sorted = row.sorted { $0.tile.x < $1.tile.x }
                for k in stride(from: 1, to: sorted.count, by: 1) {
                    #expect(sorted[k - 1].right == sorted[k].left)
                }
            }
            let byColumn = Dictionary(grouping: placements) { $0.tile.x }
            for column in byColumn.values {
                let sorted = column.sorted { $0.tile.y < $1.tile.y }
                for k in stride(from: 1, to: sorted.count, by: 1) {
                    #expect(sorted[k - 1].bottom == sorted[k].top)
                }
            }
        }
    }

    @Test("a tile is placed at the size the scale gives it")
    func tileSize() throws {
        let frame = try FrameProjection(lat45, 6.0, 25_000.0, 277.0, 190.0)
        let raster = try PrintRaster(frame, 300)
        let z = 16
        let band = try raster.band(1)
        let placements = try raster.placements(z, band)
        let placement = try #require(placements.first)
        let tileMeters = 2 * TileMath.originShiftM / Double(1 << z)
        // A tile is tileMeters of the Mercator plane; the paper shows the plane at 1:25 000 x 1/cos(lat).
        let expectedMm = tileMeters / frame.mercatorPerMm
        let widthMm = Double(placement.right - placement.left) / 300.0 * 25.4
        #expect(near(widthMm, expectedMm, 0.1))
        #expect(abs((placement.right - placement.left) - (placement.bottom - placement.top)) <= 1)
    }

    @Test("the number of tiles of a page matches the count")
    func tileCount() throws {
        let frame = try FrameProjection(lat45, 6.0, 50_000.0, 277.0, 190.0)
        let raster = try PrintRaster(frame, 300)
        let tiles = try raster.tiles(15)
        #expect(try raster.tileCount(15) == tiles.count)
        #expect(tiles.count >= 170 && tiles.count <= 260, "\(tiles.count)")
        #expect(tiles.allSatisfy { $0.z == 15 })
        let centre = try raster.centerTile(15)
        #expect(tiles.contains(TileId(15, centre.x, centre.y)))
    }

    @Test("a frame at the edge of the map keeps its tile ranges inside the world")
    func edgeOfTheMap() throws {
        let frame = try FrameProjection(84.9, 179.99, 25_000.0, 277.0, 190.0)
        let raster = try PrintRaster(frame, 150)
        let (xs, ys) = try raster.tileRange(10)
        #expect(xs.first >= 0 && xs.last < 1024 && ys.first >= 0 && ys.last < 1024)
    }

    @Test("the scale bar of 1 to 25 000 is a round length that fits")
    func scaleBar() throws {
        let bar = ScaleBarPlanner.plan(25_000.0, maxWidthMm: 80.0, locale: Locale(identifier: "fr_FR"))
        // 80 mm is 2 km at 1:25 000: exactly 2 km, 4 blocks of 500 m.
        #expect(near(bar.totalMeters, 2_000.0, 1e-9))
        #expect(near(bar.widthMm, 80.0, 1e-9))
        #expect(bar.blocks.count == 4)
        #expect(bar.blocks.map(\.black) == [true, false, true, false])
        #expect(bar.labels.map(\.text) == ["0", "0,5", "1", "1,5", "2 km"])
        let first = try #require(bar.blocks.first)
        let last = try #require(bar.blocks.last)
        #expect(near(first.startMm, 0.0, 1e-9))
        #expect(near(last.endMm, bar.widthMm, 1e-9))
    }

    @Test("short scale bars are in meters and never wider than allowed")
    func shortScaleBars() {
        let bar = ScaleBarPlanner.plan(5_000.0, maxWidthMm: 60.0, locale: Locale(identifier: "fr_FR"))
        #expect(bar.widthMm <= 60.0)
        #expect(near(bar.totalMeters, 200.0, 1e-9))
        #expect(bar.labels.last?.text == "200 m")
        for scale in [1_000.0, 10_000.0, 20_000.0, 50_000.0, 100_000.0, 250_000.0] {
            for max in [40.0, 60.0, 100.0] {
                let b = ScaleBarPlanner.plan(scale, maxWidthMm: max)
                #expect(b.widthMm <= max + 1e-9 && b.widthMm > max / 2.6, "1:\(scale) max \(max) -> \(b.widthMm)")
            }
        }
        #expect(ScaleBarPlanner.plan(0.0, maxWidthMm: 10.0) == ScaleBarPlan.empty)
    }
}
