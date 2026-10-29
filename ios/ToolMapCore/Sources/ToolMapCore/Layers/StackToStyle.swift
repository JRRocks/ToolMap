// Port of core/src/main/kotlin/fr/toolmap/core/layers/StackToStyle.kt.

/// Translation of the user's `LayerStack` into the `AppliedLayer`s the style should hold.
public enum StackToStyle {
    /// `mapping` turns the displayed zoom ranges of the layers into camera zooms (ADR-016, ADR-032). `darkening`, from
    /// 0 to 1, lowers the brightness of every map for the night mode (F14): the maps only, the tracks are other layers.
    public static func desired(
        _ stack: LayerStack,
        mapping: ZoomMapping = .standard,
        darkening: Float = 0,
        isKnownSource: (String) -> Bool
    ) -> [AppliedLayer] {
        let keep = 1 - clamp(darkening, 0, 1)
        return stack.layers
            .filter { isKnownSource($0.sourceId) }
            .map { (layer: LayerConfig) -> AppliedLayer in
                let brightness = RasterAdjust.brightnessRange(layer.brightness, inverted: layer.inverted)
                let range = LayerElevation.limitsZoom(layer)
                    ? LayerZoom.cameraRange(layer.displayMinZoom, layer.displayMaxZoom, mapping)
                    : LayerZoom.CameraRange(nil, nil)
                let props = LayerProps(
                    visible: layer.visible,
                    opacity: clamp(layer.opacity, 0, 1),
                    contrast: clamp(layer.contrast, -1, 1),
                    brightnessMin: brightness.min * keep,
                    brightnessMax: brightness.max * keep,
                    saturation: clamp(layer.saturation, -1, 1),
                    hueRotate: clamp(layer.hue, -RasterAdjust.maxHue, RasterAdjust.maxHue),
                    minZoom: range.minZoom,
                    maxZoom: range.maxZoom
                )
                // A layer drawn in an elevation band has its own source: its tiles carry the band (P20).
                return AppliedLayer(
                    id: layer.id,
                    sourceId: StyleSources.id(layer.sourceId, LayerElevation.maskOf(layer)),
                    props: props
                )
            }
    }

    /// Kotlin `coerceIn`: a NaN stays NaN.
    private static func clamp(_ value: Float, _ low: Float, _ high: Float) -> Float {
        if value < low { return low }
        if value > high { return high }
        return value
    }
}
