// Port of core/src/test/kotlin/fr/toolmap/core/print/PrintPlannerTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

private func near(_ a: Double, _ b: Double, _ tolerance: Double) -> Bool {
    abs(a - b) <= tolerance
}

private func heavyWarnings(_ plan: PrintPlan) -> [(dpi: Int, pixelsPerPage: Int)] {
    plan.warnings.compactMap { warning in
        if case let .heavy(dpi, pixels) = warning { return (dpi: dpi, pixelsPerPage: pixels) }
        return nil
    }
}

private func oversampledFactors(_ plan: PrintPlan) -> [Double] {
    plan.warnings.compactMap { warning in
        if case let .oversampled(_, _, factor, _, _) = warning { return factor }
        return nil
    }
}

private func outsideRangeCount(_ plan: PrintPlan) -> Int {
    plan.warnings.filter { warning in
        if case .outsideRange = warning { return true }
        return false
    }.count
}

struct PrintPlannerTests {
    private let sources: [String: TileSource]
    private let stack = [
        LayerConfig(id: "l1", sourceId: "ign-maps"),
        LayerConfig(id: "l2", sourceId: "ign-pentes", opacity: 0.5),
    ]
    private let here = PrintArea(centerLat: 45.0, centerLon: 6.0)

    init() throws {
        sources = try builtinSourcesById()
    }

    private func plan(
        _ template: PrintTemplate = PrintTemplate(scale: 50_000.0),
        _ area: PrintArea? = nil,
        layers: [LayerConfig]? = nil
    ) throws -> PrintPlan {
        let sources = sources
        return try PrintPlanner.plan(template, area ?? here, layers ?? stack) { sources[$0] }
    }

    private func area(bounds: Bounds? = nil, path: [LonLat] = []) -> PrintArea {
        PrintArea(centerLat: here.centerLat, centerLon: here.centerLon, bounds: bounds, path: path)
    }

    @Test("a single page at 1 to 50 000 reads about two hundred tiles per layer")
    func singlePage() throws {
        let p = try plan()
        #expect(p.canExport, "\(p.issues)")
        #expect(p.pages.count == 1)
        let page = try #require(p.pages.first)
        #expect(page.kind == PageKind.map)
        #expect(page.layers.layers.first?.tileZoom == 15)
        let setup = PageSetup()
        #expect(page.raster.widthPx == PrintMath.pixels(setup.frameWidthMm, 300))
        #expect(page.raster.heightPx == PrintMath.pixels(setup.frameHeightMm, 300))
        let counts = p.tilesByLayer
        #expect(Set(counts.keys) == Set(["l1", "l2"]))
        let l1 = try #require(counts["l1"])
        #expect(l1 >= 110 && l1 <= 260, "\(counts)")
        #expect(counts.values.reduce(0, +) == p.totalTiles)
        #expect(near(p.edgeErrorPercent, PrintMath.edgeErrorPercent(setup.frameHeightMm, 50_000.0, 45.0), 0.002))
        let (low, high) = p.estimatedBytes
        #expect(low >= 1 && low <= high)
    }

    @Test("the distinct tiles of a page are listed source by source")
    func distinctTiles() throws {
        let tiles = try plan().requiredTiles()
        #expect(Set(tiles.keys) == Set(["ign-maps", "ign-pentes"]))
        let maps = try #require(tiles["ign-maps"])
        #expect(try plan().tilesByLayer["l1"] == maps.count)
        #expect(maps.allSatisfy { $0.z == 15 })
    }

    @Test("a layer in an elevation band also needs the elevation tiles under its own")
    func elevationTiles() throws {
        let ski = [
            LayerConfig(id: "l1", sourceId: "ign-scan25"),
            LayerConfig(id: "l2", sourceId: "OSMSNOWM", displayMinElevation: 1550),
        ]
        let p = try plan(PrintTemplate(scale: 25_000.0), layers: ski)
        let tiles = try p.requiredTiles()
        #expect(Set(tiles.keys) == Set(["ign-scan25", "OSMSNOWM", "terrarium"]))
        let snow = try #require(tiles["OSMSNOWM"])
        let dem = try #require(tiles["terrarium"])
        // Zoom 16 tiles read their elevations at zoom 13: one elevation tile for 8 × 8 of them.
        #expect(snow.allSatisfy { $0.z == 16 })
        #expect(dem.allSatisfy { $0.z == 13 })
        #expect(Set(snow.map { TileId(13, $0.x >> 3, $0.y >> 3) }) == dem)
        // Without a band, no elevation tile.
        let plain = try plan(PrintTemplate(scale: 25_000.0), layers: Array(ski.prefix(1))).requiredTiles()
        #expect(Set(plain.keys) == Set(["ign-scan25"]))
    }

    @Test("an unusable page, scale or layer set blocks the export")
    func blockingIssues() throws {
        #expect(try plan(PrintTemplate(page: PageSetup(marginMm: 80.0))).issues.contains(.pageSetup))
        #expect(try plan(PrintTemplate(scale: 10.0)).issues.contains(.scaleRange))
        #expect(try plan(PrintTemplate(scale: Double.nan)).issues.contains(.scaleRange))
        let hidden = try plan(layers: [LayerConfig(id: "l1", sourceId: "ign-maps", visible: false)])
        #expect(hidden.issues.contains(.noLayer))
        #expect(!hidden.canExport)
        let tooSmall = try plan(
            PrintTemplate(page: PageSetup(paper: .custom, marginMm: 25.0, customWidthMm: 60.0, customHeightMm: 60.0))
        )
        #expect(!tooSmall.canExport && tooSmall.pages.isEmpty)
    }

    @Test("a custom sheet bigger than the old 1,5 m can be printed when its resolution allows it")
    func bigSheet() throws {
        let poster = try plan(
            PrintTemplate(
                page: PageSetup(paper: .custom, customWidthMm: 2_000.0, customHeightMm: 1_000.0),
                scale: 25_000.0,
                dpi: 150
            )
        )
        #expect(poster.canExport, "\(poster.issues)")
        #expect(poster.mapPages.count == 1)
        #expect((poster.mapPages.first?.raster.widthPx ?? 0) > 11_000)
        #expect(heavyWarnings(poster).isEmpty)
        // At 300 dpi the page is above 150 Mpx: still made, with the warning of a heavy PDF (P17.9).
        var best = poster.template
        best.dpi = 300
        let atBest = try plan(best)
        #expect(atBest.canExport, "\(atBest.issues)")
        let heavy = heavyWarnings(atBest)
        #expect(heavy.count == 1)
        #expect(heavy.first?.dpi == 300)
        #expect((heavy.first?.pixelsPerPage ?? 0) > PrintPlanner.heavyPixels)
    }

    @Test("above 300 dpi the plan warns of a heavy PDF without refusing it")
    func highDpi() throws {
        #expect(try heavyWarnings(plan(PrintTemplate(scale: 25_000.0, dpi: 300))).isEmpty)
        let fine = try plan(PrintTemplate(scale: 25_000.0, dpi: 600))
        #expect(fine.canExport, "\(fine.issues)")
        let heavy = heavyWarnings(fine)
        #expect(heavy.count == 1)
        #expect(heavy.first?.dpi == 600)
        #expect(heavy.first?.pixelsPerPage == fine.mapPages.first?.raster.pixels)
        #expect((heavy.first?.pixelsPerPage ?? Int.max) < PrintPlanner.heavyPixels, "an A4 at 600 dpi is about 22 Mpx")
    }

    @Test("a huge sheet is only heavy, a huge number of tiles is refused")
    func hugeSheet() throws {
        let huge = try plan(
            PrintTemplate(
                page: PageSetup(paper: .custom, customWidthMm: 1_500.0, customHeightMm: 1_500.0),
                scale: 25_000.0,
                dpi: 600
            )
        )
        #expect(!heavyWarnings(huge).isEmpty)
        #expect(huge.issues.contains(.tooManyTiles) || huge.canExport, "\(huge.issues)")
        // Wider than a JPEG can be (65 500 px), though few pixels: a strip 12 m long at 150 dpi, drawn in pieces.
        let strip = try plan(
            PrintTemplate(
                page: PageSetup(paper: .custom, customWidthMm: 12_000.0, customHeightMm: 120.0),
                scale: 25_000.0,
                dpi: 150
            )
        )
        #expect((strip.mapPages.first?.raster.widthPx ?? 0) > 65_500)
        #expect(strip.canExport, "\(strip.issues)")
        // A sheet of a thousand kilometres is refused for its tiles, not overflowed.
        let absurd = try plan(PrintTemplate(page: PageSetup(paper: .custom, customWidthMm: 1e9, customHeightMm: 1e9)))
        #expect(absurd.issues.contains(.tooManyTiles) && !absurd.canExport, "\(absurd.issues)")
        let bounds = Bounds(minX: 5.0, minY: 44.0, maxX: 7.0, maxY: 45.5)
        let manyTiles = try plan(PrintTemplate(scale: 50_000.0, atlas: .grid), area(bounds: bounds))
        #expect(!manyTiles.issues.contains(.tooManyPages), "\(manyTiles.issues)")
        #expect(manyTiles.totalTiles > PrintPlanner.maxTiles)
        #expect(manyTiles.issues.contains(.tooManyTiles))
    }

    @Test("a layer outside its display range does not count and is reported once")
    func outsideRange() throws {
        let p = try plan(
            layers: [
                LayerConfig(id: "l1", sourceId: "ign-maps", displayMaxZoom: 10),
                LayerConfig(id: "l2", sourceId: "osm"),
            ]
        )
        #expect(p.pages.count == 1)
        #expect(p.pages.first?.layers.layers.map(\.layer.id) == ["l2"])
        #expect(outsideRangeCount(p) == 1)
        var limited = stack[0]
        limited.displayMaxZoom = 10
        let ignoring = try plan(PrintTemplate(scale: 50_000.0, respectLayerRanges: false), layers: [limited])
        #expect(ignoring.pages.first?.layers.layers.count == 1)
    }

    @Test("a grid atlas covers the area with numbered pages and adds an overview page first")
    func gridAtlas() throws {
        let bounds = Bounds(minX: 5.9, minY: 44.9, maxX: 6.3, maxY: 45.15)
        let p = try plan(PrintTemplate(scale: 50_000.0, atlas: .grid, overlapPercent: 10.0), area(bounds: bounds))
        let atlas = try #require(p.atlas)
        #expect(atlas.pages.count > 1)
        #expect(p.pages.first?.kind == PageKind.overview)
        #expect(p.mapPages.count == atlas.pages.count)
        #expect(p.mapPages.map(\.number) == Array(1 ... atlas.pages.count))
        // The overview is read at its own, lighter, resolution and scale.
        let overview = try #require(p.pages.first)
        #expect(overview.dpi == PrintPlanner.overviewDpi)
        #expect(overview.frame.scale > 50_000.0)
        // The tiles are counted for the overview too, and a tile shared by two pages is listed once.
        let required = try p.requiredTiles()
        let listed = try #require(required["ign-maps"]).count
        let counted = try #require(p.tilesByLayer["l1"])
        #expect(listed >= 1 && listed <= counted)
    }

    @Test("the overview frame holds every footprint with some air")
    func overviewFrame() throws {
        let bounds = Bounds(minX: 5.9, minY: 44.9, maxX: 6.3, maxY: 45.15)
        let template = PrintTemplate(scale: 50_000.0)
        let atlas = AtlasPlanner.grid(bounds, template.scale, template.page, 10.0)
        let footprints = try AtlasPlanner.overview(atlas, template.scale, template.page, 277.0, 190.0).footprints
        let fitted = try PrintPlanner.overviewFrame(footprints, 277.0, 190.0)
        let frame = try #require(fitted)
        for footprint in footprints {
            for corner in footprint.corners {
                let p = frame.toFrame(corner.lon, corner.lat)
                #expect(p.xMm >= 1.0 && p.xMm <= 276.0 && p.yMm >= 1.0 && p.yMm <= 189.0, "\(p)")
            }
        }
        #expect(try PrintPlanner.overviewFrame([], 277.0, 190.0) == nil)
    }

    @Test("an atlas without its area or track says so, and a too fine scale is refused")
    func atlasIssues() throws {
        #expect(try plan(PrintTemplate(atlas: .grid)).issues.contains(.noArea))
        #expect(try plan(PrintTemplate(atlas: .track)).issues.contains(.noTrack))
        let wide = Bounds(minX: -5.0, minY: 41.0, maxX: 9.0, maxY: 51.0)
        let refused = try plan(PrintTemplate(scale: 5_000.0, atlas: .grid), area(bounds: wide))
        #expect(refused.issues.contains(.tooManyPages))
        let refusedAtlas = try #require(refused.atlas)
        #expect(refusedAtlas.tooMany && refusedAtlas.pages.isEmpty)
    }

    @Test("an atlas along a track follows it page after page")
    func trackAtlas() throws {
        let path = (0 ..< 60).map { i in LonLat(lon: 6.0 + Double(i) * 0.01, lat: 45.0 + Double(i) * 0.002) }
        let p = try plan(PrintTemplate(scale: 50_000.0, atlas: .track), area(path: path))
        #expect(p.canExport, "\(p.issues)")
        #expect(p.mapPages.count >= 2)
        #expect(near(p.mapPages.first?.frame.centerLat ?? 0, path[0].lat, 1e-9))
        let longitudes = p.mapPages.map(\.frame.centerLon)
        #expect(longitudes.sorted() == longitudes)
        // One page per frame, without the assembly page of a grid atlas (F9).
        #expect(!p.pages.contains { $0.kind == .overview })
        #expect(p.pages.count == p.mapPages.count)
        #expect(p.pages.map(\.number) == Array(1 ... p.pages.count))
    }

    @Test("an oversampled source warns once with its worst factor")
    func worstOversampling() throws {
        let bounds = Bounds(minX: 5.9, minY: 44.9, maxX: 5.96, maxY: 44.94)
        let p = try plan(
            PrintTemplate(scale: 6_250.0, atlas: .grid),
            area(bounds: bounds),
            layers: [LayerConfig(id: "l1", sourceId: "ign-scan25")]
        )
        let factors = oversampledFactors(p)
        #expect(factors.count == 1)
        #expect((factors.first ?? 0) >= 3.2)
        #expect(p.warnings.count == 1)
    }

    @Test("a template survives its JSON, and an old or broken file gives defaults or nothing")
    func json() throws {
        let template = PrintTemplate(
            page: PageSetup(paper: .a3, landscape: false, marginMm: 12.0),
            scale: 50_000.0,
            dpi: 200,
            title: "Belledonne",
            subtitle: "Sortie du 12",
            dressing: Dressing(legend: false, waypointNames: false),
            grid: .mgrs,
            gridStep: 2_000.0,
            atlas: .grid,
            overlapPercent: 15.0
        )
        let encoded = try PrintTemplateCodec.encode(template)
        #expect(PrintTemplateCodec.decode(encoded) == template)
        let minimal = try #require(PrintTemplateCodec.decode(#"{"scale": 10000, "future": true}"#))
        #expect(minimal.scale == 10_000.0)
        #expect(minimal.dpi == PrintTemplate.defaultDpi)
        #expect(PrintTemplateCodec.decode("not json") == nil)
        #expect(PrintTemplateCodec.decode("[1, 2") == nil)
        // A template saved before P17 (a name, a step in meters, a situation map) still reads.
        let oldFile = #"{"name": "Rando", "gridStepMeters": 500, "dressing": {"miniMap": true}}"#
        let old = try #require(PrintTemplateCodec.decode(oldFile))
        #expect(old.gridStep == nil)
        #expect(old.dressing.miniMap == false)
        // A template of the NATO style, removed on 2026-10-28 (ADR-088), reads as the same sheet in TOP 25.
        let nato = #"{"scale": 50000, "page": {"paper": "A3", "landscape": false, "style": "NATO"}}"#
        let natoTemplate = try #require(PrintTemplateCodec.decode(nato))
        #expect(natoTemplate.page == PageSetup(paper: .a3, landscape: false))
    }

    @Test("sanitizing brings figures back into range")
    func sanitizing() {
        let s = PrintTemplate(scale: 1.0, dpi: 5_000, gridStep: -3.0, overlapPercent: 90.0).sanitized()
        #expect(s.scale == PrintTemplate.minScale)
        #expect(s.dpi == 5_000, "no highest resolution (P17.9)")
        #expect(PrintTemplate(dpi: 10).sanitized().dpi == PrintTemplate.minDpi)
        #expect(s.overlapPercent == PrintTemplate.maxOverlapPercent)
        #expect(s.gridStep == nil)
        #expect(abs(PrintTemplate(scale: Double.nan).sanitized().scale - PrintTemplate.defaultScale) < 1e-9)
        #expect(PrintTemplate.scales.allSatisfy { Double($0) >= PrintTemplate.minScale })
    }
}
