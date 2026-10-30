import CoreGraphics
import Foundation
import ImageIO
import ToolMapCore
import UniformTypeIdentifiers

/// Draws a strip of a printed map with CoreGraphics (Android `AndroidBandRenderer`): the tiles of each layer of the page
/// plan are read several at a time, cut to the layer's elevation band if it has one, given its colour settings, then
/// drawn at their exact pixel place (`PrintRaster.placements`) with the layer's opacity; tiles that could not be read
/// are hatched on the bottom layer. The strip comes out as a JPEG for the core's `PdfWriter`, so the PDF is the same as
/// on Android and the scale is the one of the plan: one pixel of the strip is `frameWidthMm / widthPx` of paper.
final class PrintBandRenderer: BandRenderer {
    static let jpegQuality = 0.88

    private let fetcher: PrintTileFetcher
    private let dems = PrintDemCache()

    init(fetcher: PrintTileFetcher = PrintTileFetcher()) {
        self.fetcher = fetcher
    }

    func render(_ page: PrintPagePlan, _ band: Band) throws -> RenderedBand {
        let width = band.widthPx
        let height = band.heightPx
        guard width > 0, height > 0, let context = PrintImages.opaqueContext(width, height) else {
            throw ToolMapError.invalidArgument("cannot draw a strip of \(width) x \(height) pixels")
        }
        context.setFillColor(red: 1, green: 1, blue: 1, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        context.interpolationQuality = .high
        var missing = 0
        for (index, layer) in page.layers.layers.enumerated() {
            let placements = try page.raster.placements(layer.tileZoom, band)
            let drawn = self.images(layer, placements)
            let opacity = CGFloat(Swift.min(Swift.max(layer.layer.opacity, 0), 1))
            for (i, placement) in placements.enumerated() {
                // The bitmap counts rows from the top, CoreGraphics from the bottom.
                let rect = CGRect(
                    x: placement.left,
                    y: height - placement.bottom,
                    width: placement.right - placement.left,
                    height: placement.bottom - placement.top
                )
                switch drawn[i] {
                case let .image(image):
                    context.saveGState()
                    context.setAlpha(opacity)
                    context.draw(image, in: rect)
                    context.restoreGState()
                case .empty:
                    // The source has no tile there: nothing to draw, nothing missing.
                    break
                case .unavailable:
                    missing += 1
                    if index == 0 { PrintImages.hatch(context, rect, page.dpi) }
                }
            }
        }
        guard let image = context.makeImage(), let jpeg = PrintImages.jpeg(image, quality: PrintBandRenderer.jpegQuality)
        else {
            throw ToolMapError.invalidArgument("cannot compress a strip of \(width) x \(height) pixels")
        }
        return RenderedBand(image: PdfImage.jpeg(width, height, jpeg), missingTiles: missing)
    }

    /// A tile ready to be drawn.
    enum TileImage {
        case image(CGImage)
        case empty
        case unavailable
    }

    /// The tiles of `placements`, read and prepared in parallel (about one per core: the network waits are short).
    private func images(_ layer: PrintLayer, _ placements: [TilePlacement]) -> [TileImage] {
        let results = Results(count: placements.count)
        let matrix = RasterAdjust.colorMatrix(layer.layer)
        DispatchQueue.concurrentPerform(iterations: placements.count) { i in
            results.set(i, prepared(layer, placements[i].tile, matrix))
        }
        return results.values
    }

    private func prepared(_ layer: PrintLayer, _ tile: TileId, _ matrix: [Float]?) -> TileImage {
        switch fetch(layer, tile) {
        case let .image(image):
            if let matrix, let adjusted = PrintImages.adjusted(image, matrix) {
                return .image(adjusted)
            }
            return .image(image)
        case .empty:
            return .empty
        case .unavailable:
            return .unavailable
        }
    }

    /// A tile of `layer` as the screen draws it (P20): in an elevation band, nothing where the band covers none of it,
    /// the plain tile where it covers all of it, else the tile cut out along the contour. Without the elevations the
    /// tile is missing.
    private func fetch(_ layer: PrintLayer, _ tile: TileId) -> TileImage {
        guard let band = layer.band else { return decoded(fetcher.fetch(layer.source, tile)) }
        let window = DemWindow.of(tile.z, tile.x, tile.y)
        guard let dem = dems.dem(window, band.dem, fetcher) else { return .unavailable }
        let range = MaskRaster.range(dem.values, dem.size, window)
        switch band.mask.coverage(range.0, range.1) {
        case .none:
            return .empty
        case .all:
            return decoded(fetcher.fetch(layer.source, tile))
        case .part:
            let plain = decoded(fetcher.fetch(layer.source, tile))
            guard case let .image(image) = plain else { return plain }
            guard let masked = PrintImages.masked(image, dem: dem, window: window, mask: band.mask) else { return .unavailable }
            return .image(masked)
        }
    }

    private func decoded(_ fetched: PrintTileFetch) -> TileImage {
        switch fetched {
        case let .found(data):
            guard let image = PrintImages.decode(data) else { return .unavailable }
            return .image(image)
        case .empty:
            return .empty
        case .unavailable:
            return .unavailable
        }
    }

    /// The results of the parallel reads, by index.
    final class Results: @unchecked Sendable {
        private let lock = NSLock()
        private var items: [TileImage]

        init(count: Int) {
            items = Array(repeating: .unavailable, count: count)
        }

        func set(_ index: Int, _ value: TileImage) {
            lock.lock()
            items[index] = value
            lock.unlock()
        }

        var values: [TileImage] {
            lock.lock()
            defer { lock.unlock() }
            return items
        }
    }
}

/// The elevation tiles of the bands, decoded once per export.
final class PrintDemCache: @unchecked Sendable {
    struct Dem {
        let values: [Float]
        let size: Int
    }

    private static let maxEntries = 64

    private let lock = NSLock()
    private var cache: [String: Dem?] = [:]

    func dem(_ window: DemWindow, _ source: TileSource, _ fetcher: PrintTileFetcher) -> Dem? {
        let key = "\(window.demZ)/\(window.demX)/\(window.demY)"
        lock.lock()
        let known = cache[key]
        lock.unlock()
        if let known { return known }
        let made = load(TileId(window.demZ, window.demX, window.demY), source, fetcher)
        lock.lock()
        if cache.count >= PrintDemCache.maxEntries { cache.removeAll() }
        cache[key] = .some(made)
        lock.unlock()
        return made
    }

    private func load(_ tile: TileId, _ source: TileSource, _ fetcher: PrintTileFetcher) -> Dem? {
        guard case let .found(data) = fetcher.fetch(source, tile), let terrarium = try? TerrariumTile.fromPng(data)
        else { return nil }
        let size = terrarium.size
        var values = [Float](repeating: 0, count: size * size)
        for y in 0 ..< size {
            for x in 0 ..< size {
                values[y * size + x] = Float(terrarium.pixel(x, y))
            }
        }
        return Dem(values: values, size: size)
    }
}

/// Bitmap helpers of the print: decoding, colour settings, elevation masks, hatching and JPEG.
enum PrintImages {
    static let colorSpace: CGColorSpace = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()

    /// Premultiplied RGBA bytes, top row first.
    struct Pixels {
        var bytes: [UInt8]
        let width: Int
        let height: Int
    }

    /// A white RGB bitmap without alpha: what a JPEG holds.
    static func opaqueContext(_ width: Int, _ height: Int) -> CGContext? {
        CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        )
    }

    static func decode(_ data: Data) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        return CGImageSourceCreateImageAtIndex(source, 0, nil)
    }

    static func pixels(_ image: CGImage) -> Pixels? {
        let width = image.width
        let height = image.height
        guard width > 0, height > 0 else { return nil }
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        let drawn: Bool = bytes.withUnsafeMutableBytes { raw in
            guard let context = CGContext(
                data: raw.baseAddress,
                width: width,
                height: height,
                bitsPerComponent: 8,
                bytesPerRow: width * 4,
                space: colorSpace,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        return drawn ? Pixels(bytes: bytes, width: width, height: height) : nil
    }

    static func makeImage(_ rgba: Pixels) -> CGImage? {
        guard let provider = CGDataProvider(data: Data(rgba.bytes) as CFData) else { return nil }
        return CGImage(
            width: rgba.width,
            height: rgba.height,
            bitsPerComponent: 8,
            bitsPerPixel: 32,
            bytesPerRow: rgba.width * 4,
            space: colorSpace,
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
            provider: provider,
            decode: nil,
            shouldInterpolate: true,
            intent: .defaultIntent
        )
    }

    /// The tile with the hue, contrast, saturation and brightness of its layer: `matrix` is the 4 × 5 colour matrix of
    /// `RasterAdjust.colorMatrix` (Android's layout, offsets in 0..255).
    static func adjusted(_ image: CGImage, _ matrix: [Float]) -> CGImage? {
        guard matrix.count >= 20, var rgba = PrintImages.pixels(image) else { return nil }
        let count = rgba.width * rgba.height
        let m = matrix
        rgba.bytes.withUnsafeMutableBufferPointer { buffer in
            for i in 0 ..< count {
                let o = i * 4
                let alpha = Float(buffer[o + 3])
                if alpha <= 0 { continue }
                let red = Float(buffer[o]) * 255 / alpha
                let green = Float(buffer[o + 1]) * 255 / alpha
                let blue = Float(buffer[o + 2]) * 255 / alpha
                let r = m[0] * red + m[1] * green + m[2] * blue + m[3] * alpha + m[4]
                let g = m[5] * red + m[6] * green + m[7] * blue + m[8] * alpha + m[9]
                let b = m[10] * red + m[11] * green + m[12] * blue + m[13] * alpha + m[14]
                let a = clamped(m[15] * red + m[16] * green + m[17] * blue + m[18] * alpha + m[19])
                buffer[o] = UInt8(clamped(r) * a / 255)
                buffer[o + 1] = UInt8(clamped(g) * a / 255)
                buffer[o + 2] = UInt8(clamped(b) * a / 255)
                buffer[o + 3] = UInt8(a)
            }
        }
        return PrintImages.makeImage(rgba)
    }

    private static func clamped(_ value: Float) -> Float {
        Swift.min(Swift.max(value.rounded(), 0), 255)
    }

    /// The tile cut to an elevation band: each pixel keeps the opacity `MaskRaster.alphas` gives it (P20).
    static func masked(_ image: CGImage, dem: PrintDemCache.Dem, window: DemWindow, mask: ElevationMask) -> CGImage? {
        guard image.width == image.height, var rgba = PrintImages.pixels(image) else { return nil }
        let size = image.width
        let elevations = MaskRaster.elevations(dem.values, dem.size, window, size)
        let alphas = MaskRaster.alphas(elevations, size, mask)
        rgba.bytes.withUnsafeMutableBufferPointer { buffer in
            for i in 0 ..< size * size {
                let a = Int(alphas[i])
                if a == 255 { continue }
                for k in 0 ..< 4 {
                    buffer[i * 4 + k] = UInt8((Int(buffer[i * 4 + k]) * a + 127) / 255)
                }
            }
        }
        return PrintImages.makeImage(rgba)
    }

    /// A light grey patch with diagonal lines about every 1.6 mm, so a missing tile is plain on the paper.
    static func hatch(_ context: CGContext, _ area: CGRect, _ dpi: Int) {
        let pixelsPerMm = Double(dpi) / 25.4
        let spacing = Swift.max(4, Int((pixelsPerMm * 1.6).rounded()))
        context.saveGState()
        context.clip(to: area)
        context.setFillColor(red: 236 / 255, green: 236 / 255, blue: 236 / 255, alpha: 1)
        context.fill(area)
        context.setStrokeColor(red: 170 / 255, green: 170 / 255, blue: 170 / 255, alpha: 1)
        context.setLineWidth(CGFloat(Swift.max(1, pixelsPerMm * 0.12)))
        context.setShouldAntialias(true)
        var offset = -Int(area.height.rounded())
        let span = Int(area.width.rounded())
        while offset < span {
            let x = area.minX + CGFloat(offset)
            context.move(to: CGPoint(x: x, y: area.minY))
            context.addLine(to: CGPoint(x: x + area.height, y: area.maxY))
            offset += spacing
        }
        context.strokePath()
        context.restoreGState()
    }

    static func jpeg(_ image: CGImage, quality: Double) -> Data? {
        let out = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            out as CFMutableData,
            UTType.jpeg.identifier as CFString,
            1,
            nil
        ) else { return nil }
        let options = [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary
        CGImageDestinationAddImage(destination, image, options)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return out as Data
    }
}
