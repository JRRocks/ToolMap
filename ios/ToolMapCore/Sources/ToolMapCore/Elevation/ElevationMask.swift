// Port of core/src/main/kotlin/fr/toolmap/core/elevation/ElevationMask.kt (ElevationMask, DemWindow, MaskRaster).

import Foundation

/// The elevation band a layer is drawn in (P20): its pixels below `min` or above `max` metres (inclusive bounds, nil =
/// unbounded) are transparent, so the map under it shows there. `whole` are the tile zooms where the layer is drawn
/// whole whatever the elevation (a zoom range joined by « OU »); nil = never.
///
/// The band travels in the query of the layer's tile URL (`query()`, `parse(_:)`): the tile interceptor reads it, and
/// a new band is a new URL, so MapLibre asks for the tiles again.
public struct ElevationMask: Equatable, Hashable, Sendable {
    public let min: Int?
    public let max: Int?
    public let whole: IntRange?

    private static let alt = "alt"
    private static let wholeKey = "whole"

    public enum Coverage: String, Equatable, Hashable, Sendable {
        case none = "NONE"
        case part = "PART"
        case all = "ALL"
    }

    public init(_ min: Int?, _ max: Int?, _ whole: IntRange? = nil) {
        self.min = min
        self.max = max
        self.whole = whole
    }

    /// True when the tiles of zoom `z` are drawn whole, without looking at the elevations.
    public func isWholeAt(_ z: Int) -> Bool {
        if min == nil, max == nil { return true }
        guard let zooms = whole else { return false }
        return zooms.contains(z)
    }

    /// `alt=1500,` / `alt=,1200&whole=8,16`: the URL query that carries this band.
    public func query() -> String {
        let low = min.map { String($0) } ?? ""
        let high = max.map { String($0) } ?? ""
        var text = "\(ElevationMask.alt)=\(low),\(high)"
        if let zooms = whole {
            text += "&\(ElevationMask.wholeKey)=\(zooms.first),\(zooms.last)"
        }
        return text
    }

    /// Whether a tile (or the part of a DEM tile under it), whose elevations go from `low` to `high`, is shown.
    public func coverage(_ low: Float, _ high: Float) -> Coverage {
        let above = min.map { Float($0) } ?? -Float.infinity
        let below = max.map { Float($0) } ?? Float.infinity
        if high < above || low > below { return .none }
        if low >= above, high <= below { return .all }
        return .part
    }

    /// The band in a tile URL `query`, or nil when the query has none (a plain layer).
    public static func parse(_ query: String?) -> ElevationMask? {
        guard let text = query, !text.isEmpty else { return nil }
        var params: [String: String] = [:]
        for part in kotlinSplit(text, "&") {
            guard let eq = part.firstIndex(of: "="), eq != part.startIndex else { continue }
            params[String(part[..<eq])] = String(part[part.index(after: eq)...])
        }
        guard let altText = params[alt] else { return nil }
        let bounds = kotlinSplit(altText, ",")
        if bounds.count != 2 { return nil }
        var low: Int?
        if !bounds[0].isEmpty {
            guard let value = kotlinInt(bounds[0]) else { return nil }
            low = value
        }
        var high: Int?
        if !bounds[1].isEmpty {
            guard let value = kotlinInt(bounds[1]) else { return nil }
            high = value
        }
        var wholeRange: IntRange?
        if let wholeText = params[wholeKey] {
            let zooms = kotlinSplit(wholeText, ",")
            guard zooms.count >= 1, let a = kotlinInt(zooms[0]) else { return nil }
            guard zooms.count >= 2, let b = kotlinInt(zooms[1]) else { return nil }
            wholeRange = IntRange(a, b)
        }
        return ElevationMask(low, high, wholeRange)
    }
}

/// Where the elevations of tile (`z`, x, y) are read: the DEM tile (`demZ`, `demX`, `demY`) at zoom
/// `min(z, maxDemZoom)`, and, when the tile is finer than the DEM, the cell (`col`, `row`) of a 2^`depth` × 2^`depth`
/// grid of it.
public struct DemWindow: Equatable, Hashable, Sendable {
    /// Elevation tiles read for the masks. 13 is about 13 m per pixel at 45° N, finer than the data itself (SRTM /
    /// EU-DEM, ≈ 30 m), and the zoom the elevations of the tracks already use (`ElevationCompleter`): tiles stored for
    /// a profile serve the masks too.
    public static let maxDemZoom = 13

    public let demZ: Int
    public let demX: Int
    public let demY: Int
    public let depth: Int
    public let col: Int
    public let row: Int

    public init(demZ: Int, demX: Int, demY: Int, depth: Int, col: Int, row: Int) {
        self.demZ = demZ
        self.demX = demX
        self.demY = demY
        self.depth = depth
        self.col = col
        self.row = row
    }

    public static func of(_ z: Int, _ x: Int, _ y: Int, maxDemZoom: Int = DemWindow.maxDemZoom) -> DemWindow {
        let demZ = Swift.min(z, maxDemZoom)
        let depth = z - demZ
        let demX = x >> depth
        let demY = y >> depth
        return DemWindow(demZ: demZ, demX: demX, demY: demY, depth: depth, col: x - (demX << depth), row: y - (demY << depth))
    }
}

/// Elevation masks computed on a decoded DEM tile (`dem`, `demSize` × `demSize` metres, row after row).
public enum MaskRaster {
    private static let minStep: Float = 0.01
    private static let opaque: Float = 255

    /// Lowest and highest elevations under `window`, one DEM pixel beyond its edges (the pixels its interpolated
    /// elevations are made of).
    public static func range(_ dem: [Float], _ demSize: Int, _ window: DemWindow) -> (Float, Float) {
        let side = 1 << window.depth
        let x0 = Swift.max(window.col * demSize / side - 1, 0)
        let y0 = Swift.max(window.row * demSize / side - 1, 0)
        let x1 = Swift.min((window.col + 1) * demSize / side, demSize - 1)
        let y1 = Swift.min((window.row + 1) * demSize / side, demSize - 1)
        var low = Float.infinity
        var high = -Float.infinity
        if y0 <= y1, x0 <= x1 {
            for y in y0 ... y1 {
                let line = y * demSize
                for x in x0 ... x1 {
                    let v = dem[line + x]
                    if v < low { low = v }
                    if v > high { high = v }
                }
            }
        }
        return (low, high)
    }

    /// The elevation of each pixel of a `size` × `size` tile under `window`, interpolated between the four nearest DEM
    /// pixel centres; beyond the DEM tile's edge its border pixels are repeated (at most half a DEM pixel off).
    public static func elevations(_ dem: [Float], _ demSize: Int, _ window: DemWindow, _ size: Int) -> [Float] {
        let side = Double(1 << window.depth)
        var out = [Float](repeating: 0, count: size * size)
        // DEM pixel coordinate of each output column / row, computed once.
        var xs = [Double](repeating: 0, count: size)
        var ys = [Double](repeating: 0, count: size)
        for i in 0 ..< size {
            let fraction = (Double(i) + 0.5) / Double(size)
            xs[i] = (Double(window.col) + fraction) / side * Double(demSize) - 0.5
            ys[i] = (Double(window.row) + fraction) / side * Double(demSize) - 0.5
        }
        let last = demSize - 1
        for j in 0 ..< size {
            let py = ys[j]
            let y0 = kotlinToInt(floor(py))
            let ty = Float(py - Double(y0))
            let r0 = clamp(y0, 0, last) * demSize
            let r1 = clamp(y0 + 1, 0, last) * demSize
            for i in 0 ..< size {
                let px = xs[i]
                let x0 = kotlinToInt(floor(px))
                let tx = Float(px - Double(x0))
                let c0 = clamp(x0, 0, last)
                let c1 = clamp(x0 + 1, 0, last)
                let top = dem[r0 + c0] + (dem[r0 + c1] - dem[r0 + c0]) * tx
                let bottom = dem[r1 + c0] + (dem[r1 + c1] - dem[r1 + c0]) * tx
                out[j * size + i] = top + (bottom - top) * ty
            }
        }
        return out
    }

    /// The opacity (0..255) of each pixel of a `size` × `size` grid of `elevations` for `mask`: 255 inside the band,
    /// 0 outside, and a one-pixel soft edge along the contour (the local slope says how many metres a pixel spans), so
    /// the limit is not jagged.
    public static func alphas(_ elevations: [Float], _ size: Int, _ mask: ElevationMask) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: size * size)
        let low = mask.min.map { Float($0) }
        let high = mask.max.map { Float($0) }
        let last = size - 1
        for j in 0 ..< size {
            let up = Swift.max(j - 1, 0) * size
            let down = Swift.min(j + 1, last) * size
            let line = j * size
            for i in 0 ..< size {
                let e = elevations[line + i]
                // Metres per pixel along the steeper axis (central differences), never zero.
                let gx = abs(elevations[line + Swift.min(i + 1, last)] - elevations[line + Swift.max(i - 1, 0)]) * 0.5
                let gy = abs(elevations[down + i] - elevations[up + i]) * 0.5
                let step = javaMax(javaMax(gx, gy), minStep)
                var a: Float = 1
                if let lowBound = low {
                    a = javaMin(a, clamp((e - lowBound) / step + 0.5, 0, 1))
                }
                if let highBound = high {
                    a = javaMin(a, clamp((highBound - e) / step + 0.5, 0, 1))
                }
                let level = kotlinToInt(Double(a * opaque + 0.5))
                out[line + i] = UInt8(truncatingIfNeeded: level)
            }
        }
        return out
    }

    private static func clamp(_ value: Int, _ low: Int, _ high: Int) -> Int {
        Swift.min(Swift.max(value, low), high)
    }

    /// Kotlin `coerceIn`: a NaN stays NaN.
    private static func clamp(_ value: Float, _ low: Float, _ high: Float) -> Float {
        if value < low { return low }
        if value > high { return high }
        return value
    }

    /// Java `Math.min`: NaN when either value is NaN.
    private static func javaMin(_ a: Float, _ b: Float) -> Float {
        if a.isNaN || b.isNaN { return Float.nan }
        return Swift.min(a, b)
    }

    /// Java `Math.max`: NaN when either value is NaN.
    private static func javaMax(_ a: Float, _ b: Float) -> Float {
        if a.isNaN || b.isNaN { return Float.nan }
        return Swift.max(a, b)
    }
}
