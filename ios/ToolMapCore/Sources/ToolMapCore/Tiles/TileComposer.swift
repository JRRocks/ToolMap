// Port of data/src/main/kotlin/fr/toolmap/data/tiles/TileComposer.kt (BitmapTileComposer) on CoreGraphics.

#if canImport(CoreGraphics) && canImport(ImageIO)
    import CoreGraphics
    import Foundation
    import ImageIO
    import UniformTypeIdentifiers

    /// Draws a tile that no server serves as such (ADR-031): part of a coarser tile enlarged, or finer tiles shrunk
    /// together, following a `TileRecipe` of `TileResolver`. The result is never stored: it is rebuilt from the stored
    /// tiles whenever the map asks for it. Missing parts stay transparent.
    public struct TileComposer: Sendable {
        public static let defaultTileSize = 256

        private let tileSize: Int

        public init(tileSize: Int = TileComposer.defaultTileSize) {
            self.tileSize = tileSize
        }

        /// The image of `recipe` (PNG bytes), or nil when none of its tiles is available. `leaf` gives the bytes of a
        /// real tile, or nil when it is missing.
        public func compose(_ recipe: TileRecipe, leaf: (TileRecipe.Leaf) -> Data?) -> Data? {
            guard let image = render(recipe, tileSize, leaf) else { return nil }
            return TileComposer.png(image)
        }

        private func render(_ recipe: TileRecipe, _ size: Int, _ leaf: (TileRecipe.Leaf) -> Data?) -> CGImage? {
            switch recipe {
            case .none:
                return nil
            case let .direct(source, level, tile):
                guard let data = leaf(TileRecipe.Leaf(source: source, level: level, tile: tile)) else { return nil }
                return TileComposer.decode(data)
            case let .enlarge(from, depth, col, row):
                // Only the part of the coarse tile that is enlarged matters: rendered at full size, then cropped.
                guard let coarse = render(from, tileSize, leaf) else { return nil }
                return crop(coarse, depth: depth, col: col, row: row, size: size)
            case let .shrink(parts, depth):
                return shrink(parts, depth: depth, size: size, leaf)
            }
        }

        private func crop(_ coarse: CGImage, depth: Int, col: Int, row: Int, size: Int) -> CGImage? {
            let side = 1 << depth
            let part = Swift.max(coarse.width / side, 1)
            let left = Swift.min(col * coarse.width / side, coarse.width - part)
            let top = Swift.min(row * coarse.height / side, coarse.height - part)
            guard let piece = coarse.cropping(to: CGRect(x: left, y: top, width: part, height: part)),
                  let context = TileComposer.context(size)
            else { return nil }
            context.draw(piece, in: CGRect(x: 0, y: 0, width: size, height: size))
            return context.makeImage()
        }

        private func shrink(_ parts: [TileRecipe], depth: Int, size: Int, _ leaf: (TileRecipe.Leaf) -> Data?) -> CGImage? {
            let side = 1 << depth
            let cell = Swift.max(size / side, 1)
            var context: CGContext?
            for (index, part) in parts.enumerated() {
                guard let image = render(part, cell, leaf) else { continue }
                if context == nil { context = TileComposer.context(size) }
                let col = index % side
                let row = index / side
                let x0 = col * size / side
                let x1 = (col + 1) * size / side
                let y0 = row * size / side
                let y1 = (row + 1) * size / side
                // CoreGraphics counts rows from the bottom.
                context?.draw(image, in: CGRect(x: x0, y: size - y1, width: x1 - x0, height: y1 - y0))
            }
            return context?.makeImage()
        }

        static func context(_ size: Int) -> CGContext? {
            let context = CGContext(
                data: nil,
                width: size,
                height: size,
                bitsPerComponent: 8,
                bytesPerRow: 0,
                space: CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            )
            context?.interpolationQuality = .high
            return context
        }

        static func decode(_ data: Data) -> CGImage? {
            guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
            return CGImageSourceCreateImageAtIndex(source, 0, nil)
        }

        static func png(_ image: CGImage) -> Data? {
            let out = NSMutableData()
            guard let destination = CGImageDestinationCreateWithData(out as CFMutableData, UTType.png.identifier as CFString, 1, nil)
            else { return nil }
            CGImageDestinationAddImage(destination, image, nil)
            guard CGImageDestinationFinalize(destination) else { return nil }
            return out as Data
        }
    }
#endif
