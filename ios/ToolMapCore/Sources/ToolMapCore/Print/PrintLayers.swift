// Port of core/src/main/kotlin/fr/toolmap/core/print/PrintLayers.kt.

import Foundation

/// A layer that will be drawn on the page, with the tile zoom it is read at.
public struct PrintLayer: Equatable, Sendable {
    public let layer: LayerConfig
    public let source: TileSource
    public let tileZoom: Int
    public let tileSize: Int
    /// 1.0 when sharp; above, the tiles are enlarged by this factor to reach the printed resolution.
    public let oversampling: Double
    /// The elevation band its tiles are cut to, as on the screen (P20); nil when they are drawn whole.
    public let band: PrintBand?

    public init(
        layer: LayerConfig,
        source: TileSource,
        tileZoom: Int,
        tileSize: Int,
        oversampling: Double,
        band: PrintBand? = nil
    ) {
        self.layer = layer
        self.source = source
        self.tileZoom = tileZoom
        self.tileSize = tileSize
        self.oversampling = oversampling
        self.band = band
    }
}

/// A layer drawn in an elevation band: its pixels outside `mask` are left out, the elevations read from `dem`.
public struct PrintBand: Equatable, Sendable {
    public let mask: ElevationMask
    public let dem: TileSource

    public init(mask: ElevationMask, dem: TileSource) {
        self.mask = mask
        self.dem = dem
    }
}

public enum PrintWarning: Equatable, Sendable {
    /// The source stops below the zoom the page needs: its tiles are enlarged.
    case oversampled(sourceId: String, sourceName: String, factor: Double, usedZoom: Int, wantedZoom: Int)

    /// The layer's display range (N3) does not reach this scale: it is left out.
    case outsideRange(sourceId: String, sourceName: String)

    /// The layer cannot be drawn: unknown, unsupported or without any zoom.
    case unavailable(sourceId: String)

    /// A heavy PDF (P17.9): above `PrintTemplate.highDpi`, or a page of more than `PrintPlanner.heavyPixels`. It is
    /// still made, but it may take long or fail for want of memory or room; `pixelsPerPage` is the largest page's.
    case heavy(dpi: Int, pixelsPerPage: Int)
}

/// What the page will contain and at which zoom.
public struct PrintLayerPlan: Equatable, Sendable {
    public let layers: [PrintLayer]
    public let warnings: [PrintWarning]
    /// The fractional zoom whose pixels equal the printed ones (what the layer display ranges are compared to).
    public let equivalentZoom: Double
    /// The zoom the page would ideally be read at.
    public let idealZoom: Int

    public init(layers: [PrintLayer], warnings: [PrintWarning], equivalentZoom: Double, idealZoom: Int) {
        self.layers = layers
        self.warnings = warnings
        self.equivalentZoom = equivalentZoom
        self.idealZoom = idealZoom
    }
}

/// Chooses, for each layer of the stack, whether it is printed and at which tile zoom (spec §2 and §3).
public enum PrintLayerPlanner {
    private static let defaultTileSize = 256

    /// `stack` is ordered bottom to top, like the map. A layer is printed when it is visible, not transparent, and, if
    /// `respectRanges`, the screen draws it at the zoom the page is equivalent to (its display range contains that
    /// zoom, or « OU » joins the range to an elevation band). A layer with an elevation band is printed in that band,
    /// except where « OU » draws it whole: the page shows what the screen shows at that zoom.
    public static func plan(
        stack: [LayerConfig],
        source: (String) -> TileSource?,
        scale: Double,
        dpi: Int,
        latitude: Double,
        respectRanges: Bool,
        lighter: Bool
    ) -> PrintLayerPlan {
        let equivalent = PrintMath.equivalentZoom(scale, dpi, latitude)
        let ideal = PrintMath.tileZoom(scale, dpi, latitude, lighter: lighter)
        var layers: [PrintLayer] = []
        var warnings: [PrintWarning] = []
        for layer in stack {
            if !layer.visible || layer.opacity <= 0 { continue }
            guard let src = source(layer.sourceId), src.isSupported else {
                warnings.append(.unavailable(sourceId: layer.sourceId))
                continue
            }
            if respectRanges, !LayerElevation.isDrawnAt(layer, equivalent) {
                warnings.append(.outsideRange(sourceId: src.id, sourceName: src.name))
                continue
            }
            let available = Array(Set(src.levels.flatMap { $0.zooms.toList() })).sorted()
            guard let used = PrintMath.nativeZoom(ideal, available) else {
                warnings.append(.unavailable(sourceId: src.id))
                continue
            }
            let tileSize = src.levelFor(used)?.tileSize ?? defaultTileSize
            let oversampling = PrintMath.oversampling(used, scale, dpi, latitude, tileSize: tileSize)
            if oversampling > 1.0 {
                warnings.append(
                    .oversampled(
                        sourceId: src.id,
                        sourceName: src.name,
                        factor: oversampling,
                        usedZoom: used,
                        wantedZoom: ideal
                    )
                )
            }
            layers.append(
                PrintLayer(
                    layer: layer,
                    source: src,
                    tileZoom: used,
                    tileSize: tileSize,
                    oversampling: oversampling,
                    band: bandOf(layer, equivalent, source)
                )
            )
        }
        return PrintLayerPlan(layers: layers, warnings: warnings, equivalentZoom: equivalent, idealZoom: ideal)
    }

    /// Like the screen: without the elevation map, the layer is drawn whole.
    private static func bandOf(
        _ layer: LayerConfig,
        _ equivalent: Double,
        _ source: (String) -> TileSource?
    ) -> PrintBand? {
        guard let mask = LayerElevation.maskOf(layer), !mask.isWholeAt(kotlinToInt(floor(equivalent))) else {
            return nil
        }
        guard let dem = source(LayerElevation.demSourceId), dem.isSupported else { return nil }
        return PrintBand(mask: mask, dem: dem)
    }
}
