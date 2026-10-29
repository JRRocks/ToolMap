// Port of core/src/main/kotlin/fr/toolmap/core/layers/LayerZoom.kt (LayerZoom and ZoomBar).

import Foundation

/// Per-layer display range (feature N3). A range is written in the zoom shown to the user (the `z` of the 256 px tiles
/// on screen, `ZoomMapping`), both bounds **inclusive**: "8 to 16" means the layer is visible from zoom 8.0 up to (but
/// excluding) 17.0, so two layers "1–7" and "8–16" meet without a gap at zoom 7.5.
public enum LayerZoom {
    /// MapLibre's `minzoom` / `maxzoom` of a raster layer, in camera zoom; nil = not set.
    public struct CameraRange: Equatable, Hashable, Sendable {
        public let minZoom: Float?
        public let maxZoom: Float?

        public init(_ minZoom: Float?, _ maxZoom: Float?) {
            self.minZoom = minZoom
            self.maxZoom = maxZoom
        }
    }

    /// MapLibre hides a layer below `minzoom` and from `maxzoom` on (exclusive). With the camera at displayed zoom
    /// − offset (`ZoomMapping`, 1 with MapLibre's default tile size): `minzoom = min − offset` and
    /// `maxzoom = max + 1 − offset`; a bound below camera zoom 0 means "no minimum" or "never shown".
    public static func cameraRange(
        _ displayMin: Int?,
        _ displayMax: Int?,
        _ mapping: ZoomMapping = .standard
    ) -> CameraRange {
        var minZoom: Float?
        if let low = displayMin {
            let camera = Float(mapping.toCameraZoom(Double(low)))
            minZoom = camera > 0 ? camera : nil
        }
        var maxZoom: Float?
        if let high = displayMax {
            maxZoom = Swift.max(Float(mapping.toCameraZoom(Double(high) + 1.0)), 0)
        }
        return CameraRange(minZoom, maxZoom)
    }

    /// True when a layer with this range is drawn at the displayed zoom `displayedZoom` (fractions allowed).
    public static func isActiveAt(_ displayMin: Int?, _ displayMax: Int?, _ displayedZoom: Double) -> Bool {
        let aboveMin = displayMin.map { displayedZoom >= Double($0) } ?? true
        let belowMax = displayMax.map { displayedZoom < Double($0 + 1) } ?? true
        return aboveMin && belowMax
    }

    public static func isActiveAt(_ layer: LayerConfig, _ displayedZoom: Double) -> Bool {
        isActiveAt(layer.displayMinZoom, layer.displayMaxZoom, displayedZoom)
    }
}

/// Geometry and rules of the two-handle bars of « Conditions d'affichage »: the zoom bar (0...`max`) and the elevation
/// bar (`ElevationBar`, in steps, through the `max` parameters); pure so it can be tested.
public enum ZoomBar {
    /// Highest position of the zoom bar (Kotlin `ZoomBar.MAX`).
    public static let max = 20

    public enum Handle: String, Equatable, Hashable, Sendable {
        case min = "MIN"
        case max = "MAX"
    }

    public struct Range: Equatable, Hashable, Sendable {
        public let min: Int
        public let max: Int

        public init(_ min: Int, _ max: Int) {
            self.min = min
            self.max = max
        }
    }

    /// The bar position of a layer range: an unset bound sits at the end of the bar.
    public static func rangeOf(_ displayMin: Int?, _ displayMax: Int?) -> Range {
        Range(clamp(displayMin ?? 0, 0, ZoomBar.max), clamp(displayMax ?? ZoomBar.max, 0, ZoomBar.max))
    }

    /// The integer position (zoom) under the horizontal position `x` of a bar `width` pixels wide.
    public static func zoomAt(_ x: Float, _ width: Float, _ max: Int = ZoomBar.max) -> Int {
        if width <= 0 { return 0 }
        return clamp(roundFloat(x / width * Float(max)), 0, max)
    }

    /// Horizontal fraction (0..1) of the position `zoom` on the bar.
    public static func fractionOf(_ zoom: Double, _ max: Int = ZoomBar.max) -> Float {
        let fraction = Float(zoom / Double(max))
        if fraction < 0 { return 0 }
        if fraction > 1 { return 1 }
        return fraction
    }

    /// The handle a touch at `x` grabs: the closest one, the outer side of a shared position.
    public static func nearestHandle(_ x: Float, _ width: Float, _ range: Range, _ max: Int = ZoomBar.max) -> Handle {
        let minX = Float(range.min) / Float(max) * width
        let maxX = Float(range.max) / Float(max) * width
        let dMin = abs(x - minX)
        let dMax = abs(x - maxX)
        if dMin < dMax { return .min }
        if dMax < dMin { return .max }
        if x < minX { return .min }
        return .max
    }

    /// Moves `handle` to `zoom` without letting the handles cross.
    public static func moved(_ range: Range, _ handle: Handle, _ zoom: Int, _ max: Int = ZoomBar.max) -> Range {
        switch handle {
        case .min: return Range(clamp(zoom, 0, range.max), range.max)
        case .max: return Range(range.min, clamp(zoom, range.min, max))
        }
    }

    /// Back to layer bounds: a handle at the end of the bar means "no limit" (nil).
    public static func toBounds(_ range: Range) -> (Int?, Int?) {
        (range.min > 0 ? range.min : nil, range.max < ZoomBar.max ? range.max : nil)
    }

    /// Kotlin `coerceIn` on integers (the low bound wins when they cross, as `coerceIn` refuses them).
    static func clamp(_ value: Int, _ low: Int, _ high: Int) -> Int {
        if value < low { return low }
        if value > high { return high }
        return value
    }

    /// Java `Math.round(float)`: `floor(x + 0.5)`, saturated to the 32-bit range, 0 for NaN.
    static func roundFloat(_ value: Float) -> Int {
        kotlinToInt(Double((value + 0.5).rounded(.down)))
    }
}
