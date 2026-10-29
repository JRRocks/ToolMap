// Port of core/src/main/kotlin/fr/toolmap/core/print/PrintExporter.kt.

import Foundation

/// One strip of a page's map, as an image ready for the PDF, and how many of its tiles could not be read.
public struct RenderedBand: Sendable {
    public let image: PdfImage
    public let missingTiles: Int

    public init(image: PdfImage, missingTiles: Int) {
        self.image = image
        self.missingTiles = missingTiles
    }
}

/// What the platform does for the export: turn the tiles of a strip into pixels. The app reads the tiles from local
/// storage, composes the layers of the page plan with their opacity and colour settings, hatches what is missing and
/// compresses the result (JPEG); tests plug in a drawing of their own.
public protocol BandRenderer {
    /// Renders `band` of `page`'s raster (`Band.widthPx` columns from `Band.x0`, `Band.heightPx` rows from `Band.y0`).
    func render(_ page: PrintPagePlan, _ band: Band) throws -> RenderedBand
}

/// How far the export has come, for a progress bar.
public struct PrintProgress: Equatable, Hashable, Sendable {
    public let pageIndex: Int
    public let pageCount: Int
    public let bandIndex: Int
    public let bandCount: Int

    public init(pageIndex: Int, pageCount: Int, bandIndex: Int, bandCount: Int) {
        self.pageIndex = pageIndex
        self.pageCount = pageCount
        self.bandIndex = bandIndex
        self.bandCount = bandCount
    }

    /// 0 to 1 over the whole job, every page weighing the same.
    public var fraction: Double {
        if pageCount == 0 { return 1.0 }
        return (Double(pageIndex) + Double(bandIndex) / Double(Swift.max(bandCount, 1))) / Double(pageCount)
    }
}

public struct PrintResult: Equatable, Hashable, Sendable {
    public let pages: Int
    public let bytes: Int
    public let missingTiles: Int

    public init(pages: Int, bytes: Int, missingTiles: Int) {
        self.pages = pages
        self.bytes = bytes
        self.missingTiles = missingTiles
    }
}

/// Writes the PDF of a `PrintPlan` page after page, a strip of the map at a time (so memory is bounded whatever the
/// paper), with the vector furniture of `PageComposer` over each. Running it on a background thread is the caller's
/// business; `ensureActive` is called between strips and should throw to cancel.
public final class PrintExporter {
    /// Rows (and columns) by which a piece is rendered beyond its own, to be covered by the next one.
    public static let seamRows = 2

    private let renderer: any BandRenderer
    private let ensureActive: () throws -> Void

    public init(_ renderer: any BandRenderer, ensureActive: @escaping () throws -> Void = {}) {
        self.renderer = renderer
        self.ensureActive = ensureActive
    }

    /// Throws `ToolMapError.invalidArgument` when the plan cannot be exported, and whatever the renderer, the output or
    /// `ensureActive` throws.
    public func export(
        _ plan: PrintPlan,
        _ content: PrintContent,
        _ output: any PdfOutput,
        onProgress: (PrintProgress) -> Void = { _ in }
    ) throws -> PrintResult {
        try require(plan.canExport, "the plan cannot be exported: \(plan.issues)")
        let template = plan.template
        let setup = template.page
        let counted = CountingOutput(output)
        let composer = PageComposer(plan, content)
        var missing = 0
        let writer = try PdfWriter(
            counted,
            info: PdfInfo(title: template.title, subject: PrintFormat.scale(template.scale, content.texts))
        )
        do {
            for (index, pp) in plan.pages.enumerated() {
                let page = try writer.beginPage(setup.widthMm, setup.heightMm)
                let raster = pp.raster
                let mmPerPixelX = setup.frameWidthMm / Double(raster.widthPx)
                let mmPerPixelY = setup.frameHeightMm / Double(raster.heightPx)
                var pageMissing = 0
                for i in stride(from: 0, to: raster.bandCount, by: 1) {
                    try ensureActive()
                    onProgress(
                        PrintProgress(
                            pageIndex: index,
                            pageCount: plan.pages.count,
                            bandIndex: i,
                            bandCount: raster.bandCount
                        )
                    )
                    let band = try raster.band(i)
                    for piece in raster.pieces(band) {
                        // Each piece reaches into the next one below and to its right: a hairline between two images
                        // never shows.
                        let extended = Band(
                            index: piece.index,
                            y0: piece.y0,
                            heightPx: Swift.min(piece.heightPx + PrintExporter.seamRows, raster.heightPx - piece.y0),
                            x0: piece.x0,
                            widthPx: Swift.min(piece.widthPx + PrintExporter.seamRows, raster.widthPx - piece.x0)
                        )
                        let rendered = try renderer.render(pp, extended)
                        pageMissing += rendered.missingTiles
                        page.save()
                        page.clipRect(setup.frameLeftMm, setup.frameTopMm, setup.frameWidthMm, setup.frameHeightMm)
                        try page.image(
                            rendered.image,
                            setup.frameLeftMm + Double(piece.x0) * mmPerPixelX,
                            setup.frameTopMm + Double(piece.y0) * mmPerPixelY,
                            Double(extended.widthPx) * mmPerPixelX,
                            Double(extended.heightPx) * mmPerPixelY
                        )
                        page.restore()
                    }
                }
                try ensureActive()
                try composer.drawOverlay(page, pp, plan.mapPages.count, pageMissing)
                missing += pageMissing
                try writer.endPage()
            }
            onProgress(PrintProgress(pageIndex: plan.pages.count, pageCount: plan.pages.count, bandIndex: 0, bandCount: 1))
        } catch {
            // As Kotlin's `use`: the document is closed, then the failure goes on.
            try? writer.close()
            throw error
        }
        try writer.close()
        return PrintResult(pages: plan.pages.count, bytes: counted.count, missingTiles: missing)
    }

    private final class CountingOutput: PdfOutput {
        private let target: any PdfOutput
        private(set) var count = 0

        init(_ target: any PdfOutput) {
            self.target = target
        }

        func write(_ data: Data) throws {
            try target.write(data)
            count += data.count
        }

        func flush() throws {
            try target.flush()
        }
    }
}
