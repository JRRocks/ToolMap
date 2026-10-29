// Port of core/src/main/kotlin/fr/toolmap/core/elevation/TerrariumTile.kt.

import Foundation

/// A decoded Terrarium elevation tile (AWS `elevation-tiles-prod`, Mapzen format): each pixel holds
/// `red * 256 + green + blue / 256 - 32768` meters.
public struct TerrariumTile: Sendable {
    public let size: Int
    private let elevations: [Float]

    public init(size: Int, elevations: [Float]) throws {
        try require(elevations.count == size * size, "expected \(size * size) pixels, got \(elevations.count)")
        self.size = size
        self.elevations = elevations
    }

    /// Elevation of the pixel at (`x`, `y`).
    public func pixel(_ x: Int, _ y: Int) -> Double {
        let row = Swift.min(Swift.max(y, 0), size - 1)
        let col = Swift.min(Swift.max(x, 0), size - 1)
        return Double(elevations[row * size + col])
    }

    /// Elevation at a fraction (`fx`, `fy`) of the tile, each 0..1 from the top-left corner, interpolated between the
    /// four nearest pixel centres. Beyond the edge the border pixels are repeated (an error under one pixel).
    public func elevationAt(_ fx: Double, _ fy: Double) -> Double {
        let px = fx * Double(size) - 0.5
        let py = fy * Double(size) - 0.5
        let x0 = kotlinToInt(floor(px))
        let y0 = kotlinToInt(floor(py))
        let tx = px - Double(x0)
        let ty = py - Double(y0)
        let top = pixel(x0, y0) * (1 - tx) + pixel(x0 + 1, y0) * tx
        let bottom = pixel(x0, y0 + 1) * (1 - tx) + pixel(x0 + 1, y0 + 1) * tx
        return top * (1 - ty) + bottom * ty
    }

    public static func decodeElevation(_ red: Int, _ green: Int, _ blue: Int) -> Double {
        Double(red) * 256.0 + Double(green) + Double(blue) / 256.0 - 32768.0
    }

    /// Throws `PngError` when `png` is not a PNG this decoder reads, or is not square.
    public static func fromPng(_ png: Data) throws -> TerrariumTile {
        let image = try PngDecoder.decode(png)
        if image.width != image.height { throw PngError("Elevation tile is not square") }
        if image.channels < 3 { throw PngError("Elevation tile has no colour channels") }
        var values = [Float](repeating: 0, count: image.width * image.height)
        for y in 0 ..< image.height {
            for x in 0 ..< image.width {
                let elevation = decodeElevation(image.sample(x, y, 0), image.sample(x, y, 1), image.sample(x, y, 2))
                values[y * image.width + x] = Float(elevation)
            }
        }
        return try TerrariumTile(size: image.width, elevations: values)
    }

    /// Fractions (`fx`, `fy`) of the tile (x, y) at `zoom` where a WGS84 position falls. Throws for a zoom outside
    /// 0...30, like `TileMath.tilesPerAxis`.
    public static func fractionIn(_ lon: Double, _ lat: Double, _ zoom: Int, _ x: Int, _ y: Int) throws -> (Double, Double) {
        let tiles = try TileMath.tilesPerAxis(zoom)
        let n = Double(tiles)
        let worldX = (lon + 180.0) / 360.0 * n
        let clamped = Swift.min(Swift.max(lat, -TileMath.maxLatitude), TileMath.maxLatitude)
        let sinLat = sin(Angle.radians(clamped))
        let worldY = (0.5 - log((1 + sinLat) / (1 - sinLat)) / (4 * Double.pi)) * n
        return (worldX - Double(x), worldY - Double(y))
    }
}
