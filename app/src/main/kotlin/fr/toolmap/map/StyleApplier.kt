package fr.toolmap.map

import fr.toolmap.core.geo.ZoomMapping
import fr.toolmap.core.layers.AppliedLayer
import fr.toolmap.core.layers.LayerProps
import fr.toolmap.core.layers.StyleOp
import fr.toolmap.core.layers.StyleSources
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.tiles.ScalingPolicy
import fr.toolmap.data.text.GlyphInterceptor
import fr.toolmap.data.tiles.CoverageHost
import fr.toolmap.data.tiles.TileHost
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/** Executes [StyleOp]s on a MapLibre [Style]: the only place that knows how raster layers are built. */
object StyleApplier {
    /** An invisible layer kept above every raster layer: new raster layers are inserted below it. */
    const val ANCHOR_LAYER = "tm-top"
    const val LAYER_PREFIX = "tm-l-"

    /**
     * The previous drawing of a layer, kept below it while its new elevation band loads (P20). Not `tm-g-`: that is the
     * grid's, and [clear] would take the grid away with the maps.
     */
    private const val GHOST_PREFIX = "tm-ghost-"
    private const val OWN_PREFIX = "tm-"
    private const val LOCATION_PREFIX = "mapbox-"
    const val COVERAGE_SOURCE = "tm-coverage"
    const val COVERAGE_LAYER = "tm-coverage-layer"
    private const val COVERAGE_OPACITY = 0.7f
    private const val TILEJSON_VERSION = "2.2.0"
    private const val COVERAGE_MAX_ZOOM = 22f
    private const val COVERAGE_TILE_PIXELS = 256
    private const val LAYER_MAX_ZOOM = 24f

    /** Style JSON the map starts from: a background plus the anchor, and the glyphs made on the device (ADR-062). */
    const val BASE_STYLE =
        """{"version":8,"glyphs":"${GlyphInterceptor.STYLE_URL}","sources":{},"layers":[""" +
            """{"id":"bg","type":"background","paint":{"background-color":"#d9d9d9"}},""" +
            """{"id":"$ANCHOR_LAYER","type":"background","paint":{"background-opacity":0}}]}"""

    fun layerId(id: String): String = LAYER_PREFIX + id

    fun ghostId(id: String): String = GHOST_PREFIX + id

    /**
     * Puts a copy of [layer] (its source, its look) just below it under its own id: when [layer] is rebuilt on another
     * source, the copy keeps its drawing on screen until the new tiles are there (P20).
     */
    fun addGhost(
        style: Style,
        layer: AppliedLayer,
    ) {
        val ghost = RasterLayer(ghostId(layer.id), layer.sourceId)
        ghost.setProperties(*properties(layer.props))
        applyZoomRange(ghost, layer.props)
        style.addLayerBelow(ghost, layerId(layer.id))
    }

    fun apply(
        style: Style,
        ops: List<StyleOp>,
        find: (String) -> TileSource?,
        scaling: ScalingPolicy = ScalingPolicy(),
        mapping: ZoomMapping = ZoomMapping.STANDARD,
    ) {
        for (op in ops) {
            when (op) {
                is StyleOp.RemoveLayer -> {
                    style.removeLayer(layerId(op.id))
                }

                is StyleOp.RemoveSource -> {
                    style.removeSource(op.sourceId)
                }

                is StyleOp.AddSource -> {
                    find(StyleSources.sourceOf(op.sourceId))?.let {
                        style.addSource(rasterSource(op.sourceId, it, scaling, mapping))
                    }
                }

                is StyleOp.AddLayer -> {
                    val layer = RasterLayer(layerId(op.layer.id), op.layer.sourceId)
                    layer.setProperties(*properties(op.layer.props))
                    applyZoomRange(layer, op.layer.props)
                    style.addLayerBelow(layer, op.belowId?.let(::layerId) ?: ANCHOR_LAYER)
                }

                is StyleOp.UpdateLayer -> {
                    style.getLayerAs<RasterLayer>(layerId(op.id))?.let { layer ->
                        layer.setProperties(*properties(op.props))
                        applyZoomRange(layer, op.props)
                    }
                }
            }
        }
    }

    /** A layer of a map or its previous drawing: what [clear] removes. The overlays (grid, tracks…) are not. */
    fun isMapLayer(id: String): Boolean = id.startsWith(LAYER_PREFIX) || id.startsWith(GHOST_PREFIX)

    /** Removes every raster layer and source added by [apply]; used to recover from a failed update. */
    fun clear(style: Style) {
        style.layers
            .map { it.id }
            .filter(::isMapLayer)
            .forEach { style.removeLayer(it) }
        style.sources
            .map { it.id }
            .filter { it != COVERAGE_SOURCE && !it.startsWith(OWN_PREFIX) && !it.startsWith(LOCATION_PREFIX) }
            .forEach { style.removeSource(it) }
    }

    /**
     * Shows the stored coverage of [sourceId] above everything else, or removes the overlay when `null`. Its tiles
     * are declared like those of the maps ([mapping]) so that it shows the coverage of the zoom on screen.
     */
    fun setCoverage(
        style: Style,
        sourceId: String?,
        mapping: ZoomMapping = ZoomMapping.STANDARD,
    ) {
        style.removeLayer(COVERAGE_LAYER)
        style.removeSource(COVERAGE_SOURCE)
        if (sourceId == null) return
        val tileSet = TileSet(TILEJSON_VERSION, CoverageHost.templateFor(sourceId))
        tileSet.setMinZoom(0f)
        tileSet.setMaxZoom(COVERAGE_MAX_ZOOM)
        style.addSource(RasterSource(COVERAGE_SOURCE, tileSet, mapping.declaredTileSize(COVERAGE_TILE_PIXELS)))
        // After the anchor, so layers inserted later (below the anchor) stay under the overlay, and under the tracks.
        val layer = RasterLayer(COVERAGE_LAYER, COVERAGE_SOURCE)
        layer.setProperties(PropertyFactory.rasterOpacity(COVERAGE_OPACITY))
        if (style.getLayer(TrackOverlay.CASING_LAYER) != null) {
            style.addLayerBelow(layer, TrackOverlay.CASING_LAYER)
        } else {
            style.addLayer(layer)
        }
    }

    /**
     * Tile zoom range given to MapLibre for [source]: beyond the maximum MapLibre stretches the last level itself
     * (over-zoom); below the minimum it would show nothing, so the range starts [ScalingPolicy.shrinkLevels] lower
     * and the tile interceptor draws those zooms from the first one ("never show an empty map", ADR-031).
     */
    fun zoomRange(
        source: TileSource,
        scaling: ScalingPolicy,
    ): IntRange = (source.minZoom - scaling.shrinkLevels).coerceAtLeast(0)..source.maxZoom

    /** What MapLibre was given for [source]: when it changes, its style source must be rebuilt. */
    fun signature(
        source: TileSource,
        scaling: ScalingPolicy,
        mapping: ZoomMapping = ZoomMapping.STANDARD,
    ): String = "${zoomRange(source, scaling)}|${tileSize(source, mapping)}|${source.levels.hashCode()}"

    /**
     * The `tileSize` MapLibre gets for [source]: smaller than the real tiles on a dense screen, so that it takes
     * tiles deep enough to put about one tile pixel on one screen pixel, like AlpineQuest (ADR-032).
     */
    fun tileSize(
        source: TileSource,
        mapping: ZoomMapping,
    ): Int = mapping.declaredTileSize(source.levels.first().tileSize)

    /** The style source [styleId] of [source]: its tile URLs carry the elevation band of a masked layer (P20). */
    private fun rasterSource(
        styleId: String,
        source: TileSource,
        scaling: ScalingPolicy,
        mapping: ZoomMapping,
    ): RasterSource {
        val template = TileHost.templateFor(source) + (StyleSources.queryOf(styleId)?.let { "?$it" } ?: "")
        val tileSet = TileSet(TILEJSON_VERSION, template)
        val range = zoomRange(source, scaling)
        tileSet.setMinZoom(range.first.toFloat())
        tileSet.setMaxZoom(range.last.toFloat())
        source.copyright?.let { tileSet.attribution = it }
        return RasterSource(styleId, tileSet, tileSize(source, mapping))
    }

    /** Per-layer display range (N3); an unset bound goes back to MapLibre's defaults (0 and 24). */
    private fun applyZoomRange(
        layer: RasterLayer,
        props: LayerProps,
    ) {
        layer.setMinZoom(props.minZoom ?: 0f)
        layer.setMaxZoom(props.maxZoom ?: LAYER_MAX_ZOOM)
    }

    private fun properties(props: LayerProps) =
        arrayOf(
            PropertyFactory.visibility(if (props.visible) Property.VISIBLE else Property.NONE),
            PropertyFactory.rasterOpacity(props.opacity),
            PropertyFactory.rasterContrast(props.contrast),
            PropertyFactory.rasterSaturation(props.saturation),
            PropertyFactory.rasterHueRotate(props.hueRotate),
            PropertyFactory.rasterBrightnessMin(props.brightnessMin),
            PropertyFactory.rasterBrightnessMax(props.brightnessMax),
        )
}
