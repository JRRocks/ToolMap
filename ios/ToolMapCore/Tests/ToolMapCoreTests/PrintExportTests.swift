// Port of core/src/test/kotlin/fr/toolmap/core/print/PrintExportTest.kt. The tiles are drawn with CoreGraphics and
// compressed with ImageIO, as `java.awt` and `ImageIO` do on the JVM.

import CoreGraphics
import Foundation
import ImageIO
import Testing
@testable import ToolMapCore

private func near(_ a: Double, _ b: Double, _ tolerance: Double) -> Bool {
    abs(a - b) <= tolerance
}

private struct RenderFailure: Error {}

/// Draws tiles as plain squares with a one-pixel black border, enough to see where the maths puts them.
private final class SyntheticRenderer: BandRenderer {
    private let missingPerBand: Int
    private(set) var calls: [(PageKind, Band)] = []

    init(missingPerBand: Int = 0) {
        self.missingPerBand = missingPerBand
    }

    func render(_ page: PrintPagePlan, _ band: Band) throws -> RenderedBand {
        calls.append((page.kind, band))
        let width = band.widthPx
        let height = band.heightPx
        guard let context = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else { throw RenderFailure() }
        // y downwards, as the placements are.
        context.translateBy(x: 0, y: CGFloat(height))
        context.scaleBy(x: 1, y: -1)
        context.setFillColor(red: 238 / 255, green: 238 / 255, blue: 230 / 255, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        for layer in page.layers.layers {
            for p in try page.raster.placements(layer.tileZoom, band) {
                if (p.tile.x + p.tile.y) % 2 == 0 {
                    context.setFillColor(red: 198 / 255, green: 224 / 255, blue: 198 / 255, alpha: 1)
                } else {
                    context.setFillColor(red: 214 / 255, green: 230 / 255, blue: 248 / 255, alpha: 1)
                }
                let rect = CGRect(x: p.left, y: p.top, width: p.right - p.left, height: p.bottom - p.top)
                context.fill(rect)
                context.setStrokeColor(red: 0, green: 0, blue: 0, alpha: 1)
                context.setLineWidth(1)
                context.stroke(rect.insetBy(dx: 0.5, dy: 0.5))
            }
        }
        guard let image = context.makeImage() else { throw RenderFailure() }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data as CFMutableData, "public.jpeg" as CFString, 1, nil)
        else { throw RenderFailure() }
        let options = [kCGImageDestinationLossyCompressionQuality: 0.9] as CFDictionary
        CGImageDestinationAddImage(destination, image, options)
        guard CGImageDestinationFinalize(destination) else { throw RenderFailure() }
        return RenderedBand(image: PdfImage.jpeg(width, height, data as Data), missingTiles: missingPerBand)
    }
}

struct PrintExportTests {
    private let sources: [String: TileSource]
    private let stack = [LayerConfig(id: "l1", sourceId: "ign-maps")]
    private static let track = PrintTrack(
        name: "Col de la Croix",
        colorArgb: 0xFFD3_2F2F,
        widthMm: 0.6,
        segments: [(0 ..< 40).map { i in LonLat(lon: 5.95 + Double(i) * 0.003, lat: 44.97 + Double(i) * 0.0015) }]
    )
    private let content: PrintContent

    init() throws {
        sources = try builtinSourcesById()
        content = PrintContent(
            tracks: [Self.track],
            waypoints: [PrintWaypoint(name: "Refuge", lat: 45.0, lon: 6.0, colorArgb: 0xFF19_76D2)],
            attributions: ["\u{A9} IGN-F/G\u{E9}oportail", "\u{A9} les contributeurs d'OpenStreetMap"],
            declinationDegrees: 2.25,
            declinationYear: 2026,
            dateText: "02/10/2026"
        )
    }

    private func plan(_ template: PrintTemplate, _ area: PrintArea = PrintArea(centerLat: 45.0, centerLon: 6.0)) throws -> PrintPlan {
        let sources = sources
        return try PrintPlanner.plan(template, area, stack) { sources[$0] }
    }

    private func export(
        _ plan: PrintPlan,
        _ renderer: any BandRenderer = SyntheticRenderer(),
        content: PrintContent? = nil
    ) throws -> (PrintResult, Data) {
        let out = PdfDataOutput()
        let result = try PrintExporter(renderer).export(plan, content ?? self.content, out)
        return (result, out.data)
    }

    private func placements(_ content: String) -> [[String]] {
        PrintTestText.matches(#"\n([\d.]+) 0 0 (-[\d.]+) ([\d.]+) ([\d.]+) cm\n/Im\d+ Do"#, content)
    }

    @Test("an A4 page at 1 to 50 000 comes out as one valid page with its strips and furniture")
    func a4Page() throws {
        let template = PrintTemplate(
            scale: 50_000.0,
            dpi: 150,
            title: "Belledonne",
            subtitle: "Sortie du 2 octobre",
            dressing: Dressing(cornerCoordinates: true),
            grid: .utm
        )
        let p = try plan(template)
        #expect(p.canExport, "\(p.issues)")
        let renderer = SyntheticRenderer()
        let (result, bytes) = try export(p, renderer)
        #expect(result.pages == 1)
        #expect(result.bytes == bytes.count)
        let probe = try PdfProbe(bytes)
        #expect(try probe.pageNumbers().count == 1)
        #expect(try probe.mediaBox(0) == [0.0, 0.0, 841.89, 595.276])
        for number in probe.offsets.keys {
            _ = try probe.body(number)
        }

        let raster = try #require(p.pages.first).raster
        // One call per strip, each reaching two rows into the next; no situation map any more (P17).
        let strips = renderer.calls.filter { $0.0 == .map }.map { $0.1 }
        #expect(strips.count == raster.bandCount)
        let lastStrip = try #require(strips.last)
        #expect(lastStrip.y0 + lastStrip.heightPx == raster.heightPx)
        #expect(strips.dropLast().allSatisfy { $0.heightPx == raster.bandHeightPx + PrintExporter.seamRows })
        #expect(!renderer.calls.contains { $0.0 == .minimap })

        let content = try probe.contentOfPage(0)
        #expect(PrintTestText.matches(#"/Im\d+ Do"#, content).count == raster.bandCount)
        // The scale text "1:50 000" (with a no-break space) and the title are written, the credits too.
        #expect(content.contains(PrintTestText.shown("1:50\u{00A0}000")), "scale missing")
        #expect(content.contains(PrintTestText.shown("Belledonne")), "title missing")
        #expect(content.contains("A9"), "© missing")
    }

    @Test("strips are placed edge to edge over the exact frame")
    func stripsEdgeToEdge() throws {
        let template = PrintTemplate(scale: 50_000.0, dpi: 300, dressing: Dressing())
        let setup = template.page
        let p = try plan(template)
        let (_, bytes) = try export(p)
        let content = try PdfProbe(bytes).contentOfPage(0)
        // Every strip is "w 0 0 -h left y cm", the frame starting inside the margin, the label strip and the band.
        let found = placements(content)
        let raster = try #require(p.pages.first).raster
        #expect(found.count == raster.bandCount)
        let mmPerRow = setup.frameHeightMm / Double(raster.heightPx)
        var expectedTop = setup.frameTopMm
        for (i, m) in found.enumerated() {
            let width = Double(m[1]) ?? 0
            let height = -(Double(m[2]) ?? 0)
            let bottom = Double(m[4]) ?? 0
            #expect(near(width, setup.frameWidthMm, 1e-3))
            #expect(near(Double(m[3]) ?? 0, setup.frameLeftMm, 1e-9))
            #expect(near(bottom - height, expectedTop, 1e-3), "strip \(i) top")
            let rows = try raster.band(i).heightPx
            expectedTop += Double(rows) * mmPerRow
        }
        #expect(near(expectedTop, setup.frameTopMm + setup.frameHeightMm, 1e-2))
    }

    @Test("a page wider than a piece is drawn in pieces side by side over the exact frame")
    func piecesSideBySide() throws {
        let sheet = PageSetup(paper: .custom, customWidthMm: 1_500.0, customHeightMm: 100.0)
        let p = try plan(PrintTemplate(page: sheet, scale: 50_000.0, dpi: 150))
        #expect(p.canExport, "\(p.issues)")
        let raster = try #require(p.pages.first).raster
        #expect(raster.pieceCount == 2)
        let renderer = SyntheticRenderer()
        let (_, bytes) = try export(p, renderer)
        // Two calls per band: the first piece reaches two columns into the second, which ends at the frame's edge.
        let calls = renderer.calls.map { $0.1 }
        #expect(calls.count == 2 * raster.bandCount)
        #expect(calls[0].widthPx == PrintRaster.maxPieceWidth + PrintExporter.seamRows)
        #expect(calls[1].x0 == PrintRaster.maxPieceWidth)
        #expect(calls[1].x0 + calls[1].widthPx == raster.widthPx)
        let content = try PdfProbe(bytes).contentOfPage(0)
        let found = placements(content)
        #expect(found.count == 2 * raster.bandCount)
        let mmPerColumn = sheet.frameWidthMm / Double(raster.widthPx)
        var k = 0
        while k + 1 < found.count {
            let firstLeft = Double(found[k][3]) ?? 0
            let firstWidth = Double(found[k][1]) ?? 0
            let secondLeft = Double(found[k + 1][3]) ?? 0
            let secondWidth = Double(found[k + 1][1]) ?? 0
            #expect(near(firstLeft, sheet.frameLeftMm, 1e-6))
            #expect(near(secondLeft, sheet.frameLeftMm + Double(PrintRaster.maxPieceWidth) * mmPerColumn, 1e-3))
            #expect(firstLeft + firstWidth > secondLeft, "the first piece overlaps the second")
            #expect(near(secondLeft + secondWidth, sheet.frameLeftMm + sheet.frameWidthMm, 1e-3))
            k += 2
        }
    }

    @Test("the sheet of an A4 landscape keeps the map frame, the label strips and a footer of one row")
    func a4Sheet() {
        let setup = PageSetup()
        // 8 mm margin, 7 mm of labels, 1.6 mm of graduated band on each side.
        #expect(near(setup.frameLeftMm, 16.6, 1e-9))
        #expect(near(setup.frameWidthMm, 297.0 - 2 * 16.6, 1e-9))
        #expect(SheetLayout.rows(297.0 - 16.0) == 1)
        #expect(SheetLayout.rows(210.0 - 16.0) == 1)
        #expect(near(setup.footerTopMm + setup.footerHeightMm, 210.0 - 8.0, 1e-9))
        #expect(setup.frameHeightMm > 140.0, "\(setup.frameHeightMm)")
        // A narrow sheet takes a second row of boxes rather than smaller letters.
        #expect(SheetLayout.rows(148.0 - 16.0) == 2)
        let portrait = PageSetup(paper: .a5, landscape: false)
        #expect(portrait.footerHeightMm > setup.footerHeightMm)
        let blocks = SheetLayout.place(297.0 - 16.0)
        #expect(blocks.map(\.block) == FooterBlock.allCases)
        for i in stride(from: 1, to: blocks.count, by: 1) {
            let a = blocks[i - 1]
            let b = blocks[i]
            #expect(a.xMm + a.widthMm < b.xMm, "\(a) overlaps \(b)")
        }
        if let last = blocks.last {
            #expect(last.xMm + last.widthMm <= 297.0 - 16.0 - SheetLayout.padMm + 1e-9)
        }
    }

    @Test("a sheet carries the chosen grid only, never the degrees around a projected grid")
    func chosenGridOnly() throws {
        // The user's UTM and MGRS sheets of 2026-10-28 had the corners and the band in degrees, and MGRS the « 7 » of
        // the hundreds of km before its two figures (ADR-087).
        // Corners are written to the second; the declination of the norths box (« 2°15' E ») is not a corner.
        let corner = #"\d+°\d{2}\D\d{2}"#
        var base = PrintTemplate(scale: 25_000.0, dpi: 100, dressing: Dressing(cornerCoordinates: true))
        let pontcharra = PrintArea(centerLat: 45.4274, centerLon: 6.004)

        func text(_ grid: GridKind) throws -> String {
            base.grid = grid
            let (_, bytes) = try export(plan(base, pontcharra))
            return try PrintTestText.written(PdfProbe(bytes).contentOfPage(0))
        }
        let utm = try text(.utm)
        #expect(!PrintTestText.contains(corner, utm), "\(utm)")
        #expect(utm.contains(" m E") && utm.contains(" m N"), "\(utm)")
        let mgrs = try text(.mgrs)
        #expect(!PrintTestText.contains(corner, mgrs), "\(mgrs)")
        #expect(!mgrs.contains(" m E") && !mgrs.contains(" m N"), "\(mgrs)")
        // No small figure before the two of a line: the hundreds of km (« 7 », « 50 ») are not written.
        let lines = mgrs.components(separatedBy: "\n")
        #expect(!lines.contains { $0 == "7" || $0 == "2" || $0 == "50" }, "\(mgrs)")
        let lambert = try text(.lambert93)
        #expect(!PrintTestText.contains(corner, lambert), "\(lambert)")
        // The degrees keep their corners; the decimal ones in decimal degrees.
        let dms = try text(.wgs84)
        #expect(PrintTestText.contains(corner, dms))
        let decimal = try text(.wgs84Decimal)
        #expect(!PrintTestText.contains(corner, decimal), "\(decimal)")
        #expect(PrintTestText.contains(#"\d+[.,]\d{5}° [NE]"#, decimal), "\(decimal)")
    }

    @Test("the norths box always shows the three angles with their values")
    func norths() throws {
        let sheet = PrintContent(declinationDegrees: 3.2, declinationYear: 2026, declinationAnnualDegrees: 0.13)
        let texts = PrintTexts()
        let p = try plan(PrintTemplate(scale: 25_000.0, dpi: 100, grid: .utm), PrintArea(centerLat: 45.6, centerLon: 5.95))
        let (_, bytes) = try export(p, content: sheet)
        let shownTexts = try PrintTestText.written(PdfProbe(bytes).contentOfPage(0)).components(separatedBy: "\n")
        let frame = try #require(p.pages.first).frame
        let convergence = try #require(GridPlanner.convergenceDegrees(frame, .utm))
        // As written on the page: the prime of the minutes becomes an apostrophe in Windows-1252.
        func angle(_ d: Double) -> String {
            PrintTestText.windows1252(PdfPage.encode(PrintFormat.declination(d, texts.east, texts.west)))
        }
        let rows: [(String, Double)] = [
            (texts.declination, 3.2),
            (texts.gridConvergence, convergence),
            (texts.gridMagneticAngle, 3.2 - convergence),
        ]
        for (label, degrees) in rows {
            // The label as the page writes it (the en dash of « Grid–magnetic » is in Windows-1252).
            let written = PrintTestText.windows1252(PdfPage.encode(label))
            #expect(shownTexts.contains { $0.hasPrefix(written) }, "\(label) missing in \(shownTexts)")
            #expect(shownTexts.contains { $0.hasPrefix(angle(degrees)) }, "\(angle(degrees)) missing")
        }
    }

    @Test("waypoint icons are written once however many waypoints use them")
    func iconsOnce() throws {
        let glyph = try PdfImage.rgba(2, 2, Data(repeating: 0xFF, count: 12), Data([0, 0xFF, 0xFF, 0]))
        let waypoints = (0 ..< 5).map { i in
            PrintWaypoint(name: "P\(i)", lat: 45.0 + Double(i) * 0.002, lon: 6.0, colorArgb: 0xFF19_76D2, iconId: "peak")
        }
        let withIcons = PrintContent(waypoints: waypoints, glyphs: ["peak": glyph])
        let p = try plan(PrintTemplate(scale: 25_000.0, dpi: 100))
        let (_, bytes) = try export(p, content: withIcons)
        let probe = try PdfProbe(bytes)
        var masks = 0
        for number in probe.offsets.keys {
            let body = try probe.body(number)
            if body.contains("/ColorSpace /DeviceGray") { masks += 1 }
        }
        #expect(masks == 1)
        let raster = try #require(p.pages.first).raster
        let images = try PrintTestText.matches(#"/Im\d+ Do"#, probe.contentOfPage(0)).count
        #expect(images == raster.bandCount + 5)
    }

    @Test("an atlas gives an overview page first then one page per sheet")
    func atlas() throws {
        let area = Bounds(minX: 5.95, minY: 44.95, maxX: 6.2, maxY: 45.1)
        let template = PrintTemplate(scale: 50_000.0, dpi: 100, atlas: .grid)
        let p = try plan(template, PrintArea(centerLat: 45.0, centerLon: 6.0, bounds: area))
        let renderer = SyntheticRenderer(missingPerBand: 1)
        let (result, bytes) = try export(p, renderer)
        #expect(result.pages == p.pages.count)
        #expect(result.pages >= 3)
        let probe = try PdfProbe(bytes)
        #expect(try probe.pageNumbers().count == result.pages)
        #expect(renderer.calls.first?.0 == PageKind.overview)
        #expect(result.missingTiles > 0)
        let firstMapPage = try probe.contentOfPage(1)
        let pageText = PrintLocaleFormat.format(PrintLocaleFormat.root, PrintTexts().page, 1, p.mapPages.count)
        #expect(PrintTestText.written(firstMapPage).contains(pageText))
    }

    @Test("a plan with blocking issues is refused and cancellation stops the export")
    func refusedAndCancelled() throws {
        let bad = try plan(PrintTemplate(scale: 50_000.0, atlas: .grid))
        #expect(throws: ToolMapError.self) {
            _ = try export(bad)
        }
        let good = try plan(PrintTemplate(scale: 50_000.0, dpi: 150))
        var calls = 0
        let exporter = PrintExporter(SyntheticRenderer()) {
            calls += 1
            if calls > 2 { throw CancellationError() }
        }
        let content = content
        #expect(throws: CancellationError.self) {
            _ = try exporter.export(good, content, PdfDataOutput())
        }
    }

    @Test("progress goes from zero to one over the pages")
    func progress() throws {
        let p = try plan(PrintTemplate(scale: 50_000.0, dpi: 150))
        var seen: [Double] = []
        _ = try PrintExporter(SyntheticRenderer()).export(p, content, PdfDataOutput()) { seen.append($0.fraction) }
        #expect(seen.first == 0.0)
        #expect(seen.last == 1.0)
        #expect(seen.sorted() == seen)
    }

    @Test("samples for visual checks are written to the build directory")
    func samples() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("print-samples", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let sheet = PrintContent(
            tracks: content.tracks,
            waypoints: content.waypoints,
            attributions: content.attributions,
            layerNames: content.layerNames,
            declinationDegrees: content.declinationDegrees,
            declinationYear: content.declinationYear,
            declinationAnnualDegrees: 0.13,
            dateText: content.dateText
        )
        func write(_ name: String, _ plan: PrintPlan, _ content: PrintContent) throws {
            let file = try PdfFileOutput(url: dir.appendingPathComponent(name))
            _ = try PrintExporter(SyntheticRenderer()).export(plan, content, file)
            try file.close()
        }
        let top25 = PrintTemplate(
            scale: 25_000.0,
            dpi: 200,
            title: "Belledonne \u{2014} travers\u{E9}e",
            subtitle: "\u{C9}tape 2 : col de la Croix",
            grid: .utm
        )
        let p = try plan(top25)
        try write("top25-utm-25000.pdf", p, sheet)
        var mgrs = top25
        mgrs.scale = 50_000.0
        mgrs.grid = .mgrs
        try write("top25-mgrs-50000.pdf", plan(mgrs), sheet)
        // Across zones 31 and 32 at Pontcharra (P24.4), in portrait as the P24.0 preview.
        let pontcharra = PrintArea(centerLat: 45.4274, centerLon: 6.004)
        var straddling = mgrs
        straddling.scale = 25_000.0
        straddling.page = PageSetup(landscape: false)
        try write("top25-mgrs-pontcharra-portrait.pdf", plan(straddling, pontcharra), sheet)
        // The user's sheet, with the French words of the grid box, the longest it gets.
        let french = PrintTexts(
            west: "O",
            gridSquares: "Carreaux de %s",
            zoneEdgeMeridian: "Limite des zones %1$s / %2$s : m\u{E9}ridien %3$s (trait \u{E9}pais)",
            neighbourGrid: "En bleu dans la marge : quadrillage de la zone voisine",
            and: "et",
            squaresOfZones: "Carr\u{E9}s de 100 km : %s",
            westSide: "ouest",
            eastSide: "est",
            gridHeading: "QUADRILLAGE"
        )
        let frenchSheet = PrintContent(attributions: sheet.attributions, dateText: sheet.dateText, texts: french)
        var top25Mgrs = top25
        top25Mgrs.grid = .mgrs
        try write(
            "top25-mgrs-pontcharra.pdf",
            plan(top25Mgrs, PrintArea(centerLat: 45.48625, centerLon: 5.99694)),
            frenchSheet
        )
        var portrait = mgrs
        portrait.page = PageSetup(landscape: false)
        portrait.grid = .wgs84
        try write("top25-wgs84-portrait.pdf", plan(portrait), sheet)
        var atlasTemplate = top25
        atlasTemplate.scale = 50_000.0
        atlasTemplate.atlas = .grid
        atlasTemplate.dpi = 100
        atlasTemplate.grid = .wgs84
        let atlas = try plan(
            atlasTemplate,
            PrintArea(centerLat: 45.0, centerLon: 6.0, bounds: Bounds(minX: 5.95, minY: 44.95, maxX: 6.2, maxY: 45.1))
        )
        try write("atlas.pdf", atlas, sheet)
        let path = dir.appendingPathComponent("top25-utm-25000.pdf").path
        let size = try FileManager.default.attributesOfItem(atPath: path)[.size] as? Int ?? 0
        #expect(size > 1_000)
    }
}
