// Port of core/src/main/kotlin/fr/toolmap/core/print/PrintRaster.kt.

import Foundation

/// A piece of the output image: `heightPx` rows from row `y0`, `widthPx` columns from column `x0`. A band of the
/// raster spans the whole width; a page wider than `PrintRaster.maxPieceWidth` has its bands cut into pieces side by
/// side.
public struct Band: Equatable, Hashable, Sendable {
    public let index: Int
    public let y0: Int
    public let heightPx: Int
    public let x0: Int
    public let widthPx: Int

    public init(index: Int, y0: Int, heightPx: Int, x0: Int = 0, widthPx: Int) {
        self.index = index
        self.y0 = y0
        self.heightPx = heightPx
        self.x0 = x0
        self.widthPx = widthPx
    }
}

/// Where a tile lands in the bitmap of a band or piece, in whole pixels (shared edges are computed once: no seam).
public struct TilePlacement: Equatable, Hashable, Sendable {
    public let tile: TileId
    public let left: Int
    public let top: Int
    public let right: Int
    public let bottom: Int

    public init(tile: TileId, left: Int, top: Int, right: Int, bottom: Int) {
        self.tile = tile
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom
    }
}

/// The output image of a page at `dpi`: its size, its cut into bands of `bandHeightPx` rows and, on a wide page, into
/// pieces of `maxPieceWidth` columns at most (memory stays bounded whatever the paper and the resolution, and no image
/// is wider than JPEG allows), and which tiles fall in each piece.
public struct PrintRaster: Equatable, Sendable {
    public static let maxBandHeight = 512

    /// The widest piece: with `maxBandHeight` rows, a 16 MB bitmap at most whatever the page (ADR-097).
    public static let maxPieceWidth = 8_192

    public let frame: FrameProjection
    public let dpi: Int
    public let bandHeightPx: Int
    public let widthPx: Int
    public let heightPx: Int

    /// Mercator meters under one output pixel along each axis (equal up to the rounding of the pixel counts).
    private let mercatorPerPixelX: Double
    private let mercatorPerPixelY: Double

    /// Throws `ToolMapError.invalidArgument` for a resolution or a band height that is not positive.
    public init(_ frame: FrameProjection, _ dpi: Int, bandHeightPx: Int = PrintRaster.maxBandHeight) throws {
        try require(dpi > 0 && bandHeightPx > 0, "invalid raster")
        self.frame = frame
        self.dpi = dpi
        self.bandHeightPx = bandHeightPx
        let w = PrintMath.pixels(frame.frameWidthMm, dpi)
        let h = PrintMath.pixels(frame.frameHeightMm, dpi)
        widthPx = w
        heightPx = h
        mercatorPerPixelX = (frame.mercatorBounds.maxX - frame.mercatorBounds.minX) / Double(w)
        mercatorPerPixelY = (frame.mercatorBounds.maxY - frame.mercatorBounds.minY) / Double(h)
    }

    public var pixels: Int { widthPx * heightPx }

    public var bandCount: Int { (heightPx + bandHeightPx - 1) / bandHeightPx }

    public func band(_ index: Int) throws -> Band {
        try require(index >= 0 && index < bandCount, "band \(index) of \(bandCount)")
        let y0 = index * bandHeightPx
        return Band(index: index, y0: y0, heightPx: Swift.min(bandHeightPx, heightPx - y0), widthPx: widthPx)
    }

    /// Pieces side by side in a band: one on a page up to `maxPieceWidth` wide.
    public var pieceCount: Int { (widthPx + PrintRaster.maxPieceWidth - 1) / PrintRaster.maxPieceWidth }

    /// `band` cut into `pieceCount` pieces from left to right, each `maxPieceWidth` wide but the last.
    public func pieces(_ band: Band) -> [Band] {
        var out: [Band] = []
        for k in stride(from: 0, to: pieceCount, by: 1) {
            let x0 = k * PrintRaster.maxPieceWidth
            out.append(
                Band(
                    index: band.index,
                    y0: band.y0,
                    heightPx: band.heightPx,
                    x0: x0,
                    widthPx: Swift.min(PrintRaster.maxPieceWidth, widthPx - x0)
                )
            )
        }
        return out
    }

    /// Pixel column (from the frame's left edge) of a Mercator x.
    private func colOf(_ mx: Double) -> Int {
        kotlinRound((mx - frame.mercatorBounds.minX) / mercatorPerPixelX)
    }

    /// Pixel row (from the frame's top edge) of a Mercator y.
    private func rowOf(_ my: Double) -> Int {
        kotlinRound((frame.mercatorBounds.maxY - my) / mercatorPerPixelY)
    }

    /// The tile range covering the whole frame at zoom `z` (empty ranges when the frame is off the map), as (columns,
    /// rows). Throws for a zoom outside 0...30.
    public func tileRange(_ z: Int) throws -> (IntRange, IntRange) {
        let b = frame.mercatorBounds
        return try tileRange(z, b.minX, b.maxX, b.minY, b.maxY)
    }

    private func tileRange(
        _ z: Int,
        _ minX: Double,
        _ maxX: Double,
        _ minY: Double,
        _ maxY: Double
    ) throws -> (IntRange, IntRange) {
        let n = try TileMath.tilesPerAxis(z)
        let origin = TileMath.originShiftM
        let size = 2 * origin / Double(n)
        func clamp(_ value: Double) -> Int {
            Swift.min(Swift.max(kotlinToLong(value), 0), n - 1)
        }
        let x0 = clamp(floor((minX + origin) / size))
        let x1 = clamp(ceil((maxX + origin) / size) - 1)
        let y0 = clamp(floor((origin - maxY) / size))
        let y1 = clamp(ceil((origin - minY) / size) - 1)
        return (IntRange(x0, x1), IntRange(y0, y1))
    }

    /// Every tile the page needs at zoom `z`.
    public func tiles(_ z: Int) throws -> [TileId] {
        let (xs, ys) = try tileRange(z)
        var out: [TileId] = []
        for y in stride(from: ys.first, through: ys.last, by: 1) {
            for x in stride(from: xs.first, through: xs.last, by: 1) {
                out.append(TileId(z, x, y))
            }
        }
        return out
    }

    public func tileCount(_ z: Int) throws -> Int {
        let (xs, ys) = try tileRange(z)
        return (xs.last - xs.first + 1) * (ys.last - ys.first + 1)
    }

    /// The tiles of zoom `z` that intersect `band` (a band or a piece of one), placed in its bitmap.
    public func placements(_ z: Int, _ band: Band) throws -> [TilePlacement] {
        let b = frame.mercatorBounds
        let top = b.maxY - Double(band.y0) * mercatorPerPixelY
        let bottom = b.maxY - Double(band.y0 + band.heightPx) * mercatorPerPixelY
        let left = b.minX + Double(band.x0) * mercatorPerPixelX
        let right = b.minX + Double(band.x0 + band.widthPx) * mercatorPerPixelX
        let (xs, ys) = try tileRange(z, left, right, bottom, top)
        var out: [TilePlacement] = []
        for y in stride(from: ys.first, through: ys.last, by: 1) {
            for x in stride(from: xs.first, through: xs.last, by: 1) {
                let m = try TileMath.boundsMercator(x, y, z)
                out.append(
                    TilePlacement(
                        tile: TileId(z, x, y),
                        left: colOf(m.minX) - band.x0,
                        top: rowOf(m.maxY) - band.y0,
                        right: colOf(m.maxX) - band.x0,
                        bottom: rowOf(m.minY) - band.y0
                    )
                )
            }
        }
        return out
    }

    /// Column and row of the tile containing the frame centre, mostly for tests and diagnostics.
    public func centerTile(_ z: Int) throws -> TileCoord {
        try TileMath.tileAt(frame.centerLon, frame.centerLat, z)
    }
}
