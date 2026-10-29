package fr.toolmap.core.print

import fr.toolmap.core.elevation.ElevationMask
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.layers.LayerElevation
import fr.toolmap.core.source.TileSource
import kotlin.math.floor

/** A layer that will be drawn on the page, with the tile zoom it is read at. */
data class PrintLayer(
    val layer: LayerConfig,
    val source: TileSource,
    val tileZoom: Int,
    val tileSize: Int,
    /** 1.0 when sharp; above, the tiles are enlarged by this factor to reach the printed resolution. */
    val oversampling: Double,
    /** The elevation band its tiles are cut to, as on the screen (P20); `null` when they are drawn whole. */
    val band: PrintBand? = null,
)

/** A layer drawn in an elevation band: its pixels outside [mask] are left out, the elevations read from [dem]. */
data class PrintBand(
    val mask: ElevationMask,
    val dem: TileSource,
)

sealed interface PrintWarning {
    /** The source stops below the zoom the page needs: its tiles are enlarged. */
    data class Oversampled(
        val sourceId: String,
        val sourceName: String,
        val factor: Double,
        val usedZoom: Int,
        val wantedZoom: Int,
    ) : PrintWarning

    /** The layer's display range (N3) does not reach this scale: it is left out. */
    data class OutsideRange(
        val sourceId: String,
        val sourceName: String,
    ) : PrintWarning

    /** The layer cannot be drawn: unknown, unsupported or without any zoom. */
    data class Unavailable(
        val sourceId: String,
    ) : PrintWarning
}

/** What the page will contain and at which zoom. */
class PrintLayerPlan(
    val layers: List<PrintLayer>,
    val warnings: List<PrintWarning>,
    /** The fractional zoom whose pixels equal the printed ones (what the layer display ranges are compared to). */
    val equivalentZoom: Double,
    /** The zoom the page would ideally be read at. */
    val idealZoom: Int,
)

/** Chooses, for each layer of the stack, whether it is printed and at which tile zoom (spec §2 and §3). */
object PrintLayerPlanner {
    /**
     * [stack] is ordered bottom to top, like the map. A layer is printed when it is visible, not transparent, and — if
     * [respectRanges] — the screen draws it at the zoom the page is equivalent to (its display range contains that
     * zoom, or « OU » joins the range to an elevation band). A layer with an elevation band is printed in that band,
     * except where « OU » draws it whole: the page shows what the screen shows at that zoom.
     */
    fun plan(
        stack: List<LayerConfig>,
        source: (String) -> TileSource?,
        scale: Double,
        dpi: Int,
        latitude: Double,
        respectRanges: Boolean,
        lighter: Boolean,
    ): PrintLayerPlan {
        val equivalent = PrintMath.equivalentZoom(scale, dpi, latitude)
        val ideal = PrintMath.tileZoom(scale, dpi, latitude, lighter)
        val layers = ArrayList<PrintLayer>()
        val warnings = ArrayList<PrintWarning>()
        for (layer in stack) {
            if (!layer.visible || layer.opacity <= 0f) continue
            val src = source(layer.sourceId)
            if (src == null || !src.isSupported) {
                warnings += PrintWarning.Unavailable(layer.sourceId)
                continue
            }
            if (respectRanges && !LayerElevation.isDrawnAt(layer, equivalent)) {
                warnings += PrintWarning.OutsideRange(src.id, src.name)
                continue
            }
            val available = src.levels.flatMap { it.zooms.toList() }.distinct().sorted()
            val used = PrintMath.nativeZoom(ideal, available)
            if (used == null) {
                warnings += PrintWarning.Unavailable(src.id)
                continue
            }
            val level = src.levelFor(used)
            val tileSize = level?.tileSize ?: DEFAULT_TILE_SIZE
            val oversampling = PrintMath.oversampling(used, scale, dpi, latitude, tileSize)
            if (oversampling > 1.0) {
                warnings += PrintWarning.Oversampled(src.id, src.name, oversampling, used, ideal)
            }
            layers += PrintLayer(layer, src, used, tileSize, oversampling, bandOf(layer, equivalent, source))
        }
        return PrintLayerPlan(layers, warnings, equivalent, ideal)
    }

    /** Like the screen: without the elevation map, the layer is drawn whole. */
    private fun bandOf(
        layer: LayerConfig,
        equivalent: Double,
        source: (String) -> TileSource?,
    ): PrintBand? {
        val mask = LayerElevation.maskOf(layer)?.takeUnless { it.isWholeAt(floor(equivalent).toInt()) } ?: return null
        val dem = source(LayerElevation.DEM_SOURCE_ID)?.takeIf { it.isSupported } ?: return null
        return PrintBand(mask, dem)
    }

    private const val DEFAULT_TILE_SIZE = 256
}
