// Port of core/src/main/kotlin/fr/toolmap/core/print/PrintMath.kt.

import Foundation

/// The maths of printing a map at an exact scale (docs/specs/print-pdf.md §2). Everything is Web Mercator with 256 px
/// tiles unless said otherwise; the scale is exact at the latitude of the frame centre.
public enum PrintMath {
    /// Meters per pixel of a 256 px tile at zoom 0, at the equator.
    public static let equatorResolution = 156_543.03392804097

    public static let mmPerInch = 25.4

    private static let ln2 = log(2.0)
    private static let epsilon = 1e-9
    private static let maxZoom = 24
    private static let earthRadiusM = 6_371_000.0
    private static let lowBytesPerPixel = 0.15
    private static let highBytesPerPixel = 2.0

    /// Ground meters under one printed pixel: scale 1:`scale` at `dpi`.
    public static func groundResolution(_ scale: Double, _ dpi: Int) -> Double {
        scale * mmPerInch / 1000.0 / Double(dpi)
    }

    /// Ground meters covered by `mm` of paper at 1:`scale`.
    public static func groundMeters(_ mm: Double, _ scale: Double) -> Double {
        mm * scale / 1000.0
    }

    /// Web Mercator stretches distances by 1 / cos(latitude): meters of the projection per ground meter.
    public static func mercatorFactor(_ latitude: Double) -> Double {
        1.0 / cos(Angle.radians(latitude))
    }

    /// Pixels of `mm` of paper at `dpi`, at most `Int32.max` (a sheet that wide is refused by the plan).
    public static func pixels(_ mm: Double, _ dpi: Int) -> Int {
        let rounded = kotlinRound(mm / mmPerInch * Double(dpi))
        return Swift.min(Swift.max(rounded, 0), Int(Int32.max))
    }

    /// Ground resolution of a tile at zoom `z`, at `latitude`; 512 px tiles halve the figure.
    public static func tileResolution(_ z: Int, _ latitude: Double, tileSize: Int = 256) -> Double {
        equatorResolution * cos(Angle.radians(latitude)) / Double(Int64(1) << Int64(z)) * 256.0 / Double(tileSize)
    }

    /// The (fractional) tile zoom whose tiles have exactly the resolution of the printed pixels.
    public static func equivalentZoom(_ scale: Double, _ dpi: Int, _ latitude: Double) -> Double {
        log(equatorResolution * cos(Angle.radians(latitude)) / groundResolution(scale, dpi)) / ln2
    }

    /// The smallest zoom whose tiles are at least as sharp as the printed pixels (`R(z) <= r`): a slight downsampling,
    /// never a blur. `lighter` takes one level less (a lighter file, a touch softer).
    public static func tileZoom(_ scale: Double, _ dpi: Int, _ latitude: Double, lighter: Bool = false) -> Int {
        let z = kotlinToInt(ceil(equivalentZoom(scale, dpi, latitude) - epsilon))
        let chosen = lighter ? z - 1 : z
        return Swift.min(Swift.max(chosen, 0), maxZoom)
    }

    /// The zoom a source with the given native `available` zooms is asked for when `wanted` is the ideal one: the
    /// nearest zoom at or above it, else the highest the source has (then the tiles are enlarged).
    public static func nativeZoom(_ wanted: Int, _ available: [Int]) -> Int? {
        available.first { $0 >= wanted } ?? available.max()
    }

    /// How much the tiles of zoom `used` are enlarged to reach the printed resolution: 1.0 when sharp enough, 3.2 when
    /// a pixel of the paper is 3.2 pixels of the tile. Rounded to 0.1.
    public static func oversampling(
        _ used: Int,
        _ scale: Double,
        _ dpi: Int,
        _ latitude: Double,
        tileSize: Int = 256
    ) -> Double {
        let ratio = tileResolution(used, latitude, tileSize: tileSize) / groundResolution(scale, dpi)
        return ratio <= 1.0 + epsilon ? 1.0 : Double(kotlinRound(ratio * 10.0)) / 10.0
    }

    /// Relative scale error (percent) at the north or south edge of a frame `frameHeightMm` tall, 1:`scale`, centred
    /// at `latitude`: about `tan(lat) * half-height / R`.
    public static func edgeErrorPercent(_ frameHeightMm: Double, _ scale: Double, _ latitude: Double) -> Double {
        let halfHeightMeters = groundMeters(frameHeightMm, scale) / 2.0
        let dPhi = halfHeightMeters / earthRadiusM
        return abs(tan(Angle.radians(latitude))) * dPhi * 100.0
    }

    /// Pages a PDF of `pixelsPerPage` pixels weighs, as a (low, high) figure in bytes: very indicative.
    public static func estimatedBytes(_ pixelsPerPage: Int, _ pages: Int) -> (Int, Int) {
        let pixels = Double(pixelsPerPage &* pages)
        return (kotlinToLong(pixels * lowBytesPerPixel), kotlinToLong(pixels * highBytesPerPixel))
    }
}
