// Port of core/src/main/kotlin/fr/toolmap/core/layers/LayerElevation.kt (LayerElevation, ElevationBar, StyleSources).

/// A layer's elevation band (P20) as the map draws it.
public enum LayerElevation {
    /// The elevation map the bands of the layers are read from (`builtin-sources.aqx`).
    public static let demSourceId = "terrarium"

    /// Highest tile zoom an open zoom range stands for in a mask.
    private static let maxTileZoom = 30

    /// The mask of `layer`'s tiles, or nil when they are drawn whole (no elevation band).
    public static func maskOf(_ layer: LayerConfig) -> ElevationMask? {
        if !layer.hasElevationRange { return nil }
        var whole: IntRange?
        if layer.rangeLogic == .or, layer.hasZoomRange {
            whole = IntRange(layer.displayMinZoom ?? 0, layer.displayMaxZoom ?? maxTileZoom)
        }
        return ElevationMask(layer.displayMinElevation, layer.displayMaxElevation, whole)
    }

    /// Whether the layer is hidden outside its zoom range: always, except when « OU » joins the range to an elevation
    /// band (the band is then drawn at the other zooms).
    public static func limitsZoom(_ layer: LayerConfig) -> Bool {
        !(layer.hasElevationRange && layer.rangeLogic == .or)
    }

    /// True when `layer` is drawn (whole or in its band) at the displayed zoom `displayedZoom`.
    public static func isDrawnAt(_ layer: LayerConfig, _ displayedZoom: Double) -> Bool {
        !limitsZoom(layer) || LayerZoom.isActiveAt(layer, displayedZoom)
    }
}

/// The elevation bar of « Conditions d'affichage » (P20): 0 to `maxMeters` in `step` m steps, positions counted in
/// steps so that the handles move like those of `ZoomBar` (with `max` = `steps`). A handle at an end means no limit.
public enum ElevationBar {
    public static let maxMeters = 4800
    public static let step = 50
    public static let steps = maxMeters / step

    /// The bar position of a layer band: an unset bound sits at the end of the bar.
    public static func rangeOf(_ min: Int?, _ max: Int?) -> ZoomBar.Range {
        ZoomBar.Range(min.map(stepOf) ?? 0, max.map(stepOf) ?? steps)
    }

    /// Back to layer bounds in metres, nil for a handle at the end of the bar.
    public static func toBounds(_ range: ZoomBar.Range) -> (Int?, Int?) {
        (range.min > 0 ? range.min * step : nil, range.max < steps ? range.max * step : nil)
    }

    /// Position on the bar (in steps, fractions allowed) of an elevation, for the mark of the map centre.
    public static func positionOf(_ meters: Double) -> Double {
        let position = meters / Double(step)
        if position < 0 { return 0 }
        if position > Double(steps) { return Double(steps) }
        return position
    }

    private static func stepOf(_ meters: Int) -> Int {
        ZoomBar.clamp(ZoomBar.roundFloat(Float(meters) / Float(step)), 0, steps)
    }
}

/// Ids of the sources of the map style: a map's own id, or, for a layer drawn in an elevation band, its id followed by
/// the band's URL query (`opensnowmap?alt=1500,`), so that the same map can be shown plain and masked, and a new band is
/// a new source whose tiles MapLibre asks for again.
public enum StyleSources {
    private static let query: Character = "?"

    public static func id(_ sourceId: String, _ mask: ElevationMask?) -> String {
        guard let band = mask else { return sourceId }
        return "\(sourceId)\(query)\(band.query())"
    }

    /// The map a style source shows.
    public static func sourceOf(_ styleId: String) -> String {
        styleId.substringBefore(query)
    }

    /// The URL query of its tiles, or nil for a plain map.
    public static func queryOf(_ styleId: String) -> String? {
        styleId.substringAfter(query, missing: "").nilIfEmpty
    }
}
