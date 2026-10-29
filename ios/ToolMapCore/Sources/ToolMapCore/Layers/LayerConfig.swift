// Port of core/src/main/kotlin/fr/toolmap/core/layers/LayerConfig.kt (LayerConfig, RangeLogic, BrightnessRange,
// RasterAdjust).

import Foundation

/// One entry of the layer stack: a tile source displayed with its own opacity and color adjustments.
/// `displayMinZoom` / `displayMaxZoom` (inclusive, in tile zoom, nil = unbounded) are the per-layer display range of
/// feature N3 (phase P3). `displayMinElevation` / `displayMaxElevation` (inclusive, metres, nil = unbounded) are the
/// elevation band the layer is drawn in (P20); `rangeLogic` says how the two combine when both are set. `hue` turns the
/// colours round the colour wheel (P26.6).
///
/// JSON: the keys and defaults of the Kotlin `@Serializable` class (a missing key takes its default value).
public struct LayerConfig: Equatable, Hashable, Sendable, Codable {
    public var id: String
    public var sourceId: String
    public var visible: Bool
    /// 0 (transparent) .. 1 (opaque).
    public var opacity: Float
    /// -1 .. 1, 0 = unchanged.
    public var contrast: Float
    /// -1 .. 1, 0 = unchanged.
    public var brightness: Float
    /// -1 .. 1, 0 = unchanged (-1 = grayscale).
    public var saturation: Float
    public var displayMinZoom: Int?
    public var displayMaxZoom: Int?
    /// Negative image: every colour channel is flipped (AlpineQuest's "Inverted colors").
    public var inverted: Bool
    public var displayMinElevation: Int?
    public var displayMaxElevation: Int?
    public var rangeLogic: RangeLogic
    /// Hue rotation in degrees, -180 .. 180, 0 = unchanged (MapLibre's `raster-hue-rotate`).
    public var hue: Float

    public init(
        id: String,
        sourceId: String,
        visible: Bool = true,
        opacity: Float = 1,
        contrast: Float = 0,
        brightness: Float = 0,
        saturation: Float = 0,
        displayMinZoom: Int? = nil,
        displayMaxZoom: Int? = nil,
        inverted: Bool = false,
        displayMinElevation: Int? = nil,
        displayMaxElevation: Int? = nil,
        rangeLogic: RangeLogic = .and,
        hue: Float = 0
    ) {
        self.id = id
        self.sourceId = sourceId
        self.visible = visible
        self.opacity = opacity
        self.contrast = contrast
        self.brightness = brightness
        self.saturation = saturation
        self.displayMinZoom = displayMinZoom
        self.displayMaxZoom = displayMaxZoom
        self.inverted = inverted
        self.displayMinElevation = displayMinElevation
        self.displayMaxElevation = displayMaxElevation
        self.rangeLogic = rangeLogic
        self.hue = hue
    }

    /// True when a color adjustment differs from the neutral value.
    public var hasAdjustments: Bool {
        contrast != 0 || brightness != 0 || saturation != 0 || hue != 0 || inverted
    }

    public var hasZoomRange: Bool { displayMinZoom != nil || displayMaxZoom != nil }

    public var hasElevationRange: Bool { displayMinElevation != nil || displayMaxElevation != nil }

    private enum CodingKeys: String, CodingKey {
        case id, sourceId, visible, opacity, contrast, brightness, saturation, displayMinZoom, displayMaxZoom, inverted
        case displayMinElevation, displayMaxElevation, rangeLogic, hue
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        sourceId = try c.decode(String.self, forKey: .sourceId)
        visible = try c.decodeIfPresent(Bool.self, forKey: .visible) ?? true
        opacity = try c.decodeIfPresent(Float.self, forKey: .opacity) ?? 1
        contrast = try c.decodeIfPresent(Float.self, forKey: .contrast) ?? 0
        brightness = try c.decodeIfPresent(Float.self, forKey: .brightness) ?? 0
        saturation = try c.decodeIfPresent(Float.self, forKey: .saturation) ?? 0
        displayMinZoom = try c.decodeIfPresent(Int.self, forKey: .displayMinZoom)
        displayMaxZoom = try c.decodeIfPresent(Int.self, forKey: .displayMaxZoom)
        inverted = try c.decodeIfPresent(Bool.self, forKey: .inverted) ?? false
        displayMinElevation = try c.decodeIfPresent(Int.self, forKey: .displayMinElevation)
        displayMaxElevation = try c.decodeIfPresent(Int.self, forKey: .displayMaxElevation)
        rangeLogic = try c.decodeIfPresent(RangeLogic.self, forKey: .rangeLogic) ?? .and
        hue = try c.decodeIfPresent(Float.self, forKey: .hue) ?? 0
    }

    /// Every key is written, nulls included, like the Kotlin codecs (`encodeDefaults = true`).
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(sourceId, forKey: .sourceId)
        try c.encode(visible, forKey: .visible)
        try c.encode(opacity, forKey: .opacity)
        try c.encode(contrast, forKey: .contrast)
        try c.encode(brightness, forKey: .brightness)
        try c.encode(saturation, forKey: .saturation)
        try c.encode(displayMinZoom, forKey: .displayMinZoom)
        try c.encode(displayMaxZoom, forKey: .displayMaxZoom)
        try c.encode(inverted, forKey: .inverted)
        try c.encode(displayMinElevation, forKey: .displayMinElevation)
        try c.encode(displayMaxElevation, forKey: .displayMaxElevation)
        try c.encode(rangeLogic, forKey: .rangeLogic)
        try c.encode(hue, forKey: .hue)
    }
}

/// How a layer's zoom range and elevation band combine when both are set (P20): `and` draws it where both hold (the
/// band, at the zooms of the range), `or` where either holds (whole at the zooms of the range, the band elsewhere).
public enum RangeLogic: String, Codable, CaseIterable, Sendable {
    case and = "AND"
    case or = "OR"
}

/// MapLibre brightness range (`raster-brightness-min` / `-max`) for a user value in -1..1.
public struct BrightnessRange: Equatable, Hashable, Sendable {
    public let min: Float
    public let max: Float

    public init(_ min: Float, _ max: Float) {
        self.min = min
        self.max = max
    }
}

public enum RasterAdjust {
    /// Largest hue rotation either way, in degrees (P26.6).
    public static let maxHue: Float = 180

    private static let maxContrast: Float = 0.99
    private static let matrixSize = 20
    private static let alphaGain = 18

    /// Brighter (> 0) lifts the black point, darker (< 0) lowers the white point; 0 gives the identity range (0..1).
    /// A negative image (`inverted`) swaps the two ends: the shader mixes `min` to `max` over the channel value, so a
    /// range running from 1 down to 0 flips black and white.
    public static func brightnessRange(_ brightness: Float, inverted: Bool = false) -> BrightnessRange {
        let b = clamp(brightness, -1, 1)
        let range = b >= 0 ? BrightnessRange(b, 1) : BrightnessRange(0, 1 + b)
        return inverted ? BrightnessRange(range.max, range.min) : range
    }

    /// The colour matrix (4 × 5, row after row, Android's layout: the last column is an offset in 0..255) that gives a
    /// pixel the same hue, contrast, saturation and brightness as the map shows with `layer`'s settings, or nil when
    /// the layer has none. It follows the raster shader of the map: hue rotation, saturation, then contrast around
    /// mid-grey, then the brightness range.
    public static func colorMatrix(_ layer: LayerConfig) -> [Float]? {
        if !layer.hasAdjustments { return nil }
        let s = saturationFactor(clamp(layer.saturation, -1, 1))
        let k = contrastFactor(clamp(layer.contrast, -1, maxContrast))
        let range = brightnessRange(layer.brightness, inverted: layer.inverted)
        let span = range.max - range.min
        let spin = hueWeights(clamp(layer.hue, -maxHue, maxHue))
        // Saturation moves each channel towards the average (weights d on itself, o on the others), which the
        // rotation keeps: row i of saturation × rotation is (d - o) × the rotation's row + o.
        let d: Float = 1 - s + s / 3
        let o: Float = s / 3
        let middle: Float = span * 0.5 * (1 - k)
        let offset: Float = (range.min + middle) * 255
        var matrix = [Float](repeating: 0, count: matrixSize)
        for row in 0 ..< 3 {
            for col in 0 ..< 3 {
                let weight: Float = (d - o) * spin[(col - row + 3) % 3] + o
                matrix[row * 5 + col] = weight * k * span
            }
            matrix[row * 5 + 4] = offset
        }
        matrix[alphaGain] = 1
        return matrix
    }

    /// MapLibre's `raster-hue-rotate` weights for `degrees`: the new red is `w0 r + w1 g + w2 b`, green and blue take
    /// the same weights shifted by one (`w2 r + w0 g + w1 b`, `w1 r + w2 g + w0 b`): a turn about the grey axis.
    static func hueWeights(_ degrees: Float) -> [Float] {
        let a = Angle.radians(Double(degrees))
        let c = cos(a)
        let s = 3.0.squareRoot() * sin(a)
        let w0 = (2 * c + 1) / 3
        let w1 = (1 - c - s) / 3
        let w2 = (1 - c + s) / 3
        return [Float(w0), Float(w1), Float(w2)]
    }

    /// MapLibre's `raster-saturation` factor: how far each channel moves towards the average.
    static func saturationFactor(_ saturation: Float) -> Float {
        if saturation >= 0 {
            let denominator: Float = 1.001 - saturation
            return 1 - 1 / denominator
        }
        return -saturation
    }

    /// MapLibre's `raster-contrast` factor: the slope around mid-grey.
    static func contrastFactor(_ contrast: Float) -> Float {
        contrast > 0 ? 1 / (1 - contrast) : 1 + contrast
    }

    /// Kotlin `coerceIn`: a NaN stays NaN.
    private static func clamp(_ value: Float, _ low: Float, _ high: Float) -> Float {
        if value < low { return low }
        if value > high { return high }
        return value
    }
}
