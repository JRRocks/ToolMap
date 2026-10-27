package fr.toolmap.core.layers

import fr.toolmap.core.elevation.ElevationMask

/** A layer's elevation band (P20) as the map draws it. */
object LayerElevation {
    /** Highest tile zoom an open zoom range stands for in a mask. */
    private const val MAX_TILE_ZOOM = 30

    /** The mask of [layer]'s tiles, or `null` when they are drawn whole (no elevation band). */
    fun maskOf(layer: LayerConfig): ElevationMask? {
        if (!layer.hasElevationRange) return null
        val whole =
            if (layer.rangeLogic == RangeLogic.OR && layer.hasZoomRange) {
                (layer.displayMinZoom ?: 0)..(layer.displayMaxZoom ?: MAX_TILE_ZOOM)
            } else {
                null
            }
        return ElevationMask(layer.displayMinElevation, layer.displayMaxElevation, whole)
    }

    /**
     * Whether the layer is hidden outside its zoom range: always, except when « OU » joins the range to an elevation
     * band (the band is then drawn at the other zooms).
     */
    fun limitsZoom(layer: LayerConfig): Boolean = !(layer.hasElevationRange && layer.rangeLogic == RangeLogic.OR)

    /** True when [layer] is drawn (whole or in its band) at the displayed zoom [displayedZoom]. */
    fun isDrawnAt(
        layer: LayerConfig,
        displayedZoom: Double,
    ): Boolean = !limitsZoom(layer) || LayerZoom.isActiveAt(layer, displayedZoom)
}

/**
 * The elevation bar of « Conditions d'affichage » (P20): 0 to [MAX_METERS] in [STEP] m steps, positions counted in
 * steps so that the handles move like those of [ZoomBar] (with `max` = [STEPS]). A handle at an end means no limit.
 */
object ElevationBar {
    const val MAX_METERS = 4800
    const val STEP = 50
    const val STEPS = MAX_METERS / STEP

    /** The bar position of a layer band: an unset bound sits at the end of the bar. */
    fun rangeOf(
        min: Int?,
        max: Int?,
    ): ZoomBar.Range = ZoomBar.Range(min?.let(::stepOf) ?: 0, max?.let(::stepOf) ?: STEPS)

    /** Back to layer bounds in metres, `null` for a handle at the end of the bar. */
    fun toBounds(range: ZoomBar.Range): Pair<Int?, Int?> =
        range.min.takeIf { it > 0 }?.times(STEP) to range.max.takeIf { it < STEPS }?.times(STEP)

    /** Position on the bar (in steps, fractions allowed) of an elevation, for the mark of the map centre. */
    fun positionOf(meters: Double): Double = (meters / STEP).coerceIn(0.0, STEPS.toDouble())

    private fun stepOf(meters: Int): Int = Math.round(meters.toFloat() / STEP).coerceIn(0, STEPS)
}

/**
 * Ids of the sources of the map style: a map's own id, or, for a layer drawn in an elevation band, its id followed by
 * the band's URL query (`opensnowmap?alt=1500,`), so that the same map can be shown plain and masked, and a new band is
 * a new source whose tiles MapLibre asks for again.
 */
object StyleSources {
    private const val QUERY = '?'

    fun id(
        sourceId: String,
        mask: ElevationMask?,
    ): String = if (mask == null) sourceId else "$sourceId$QUERY${mask.query()}"

    /** The map a style source shows. */
    fun sourceOf(styleId: String): String = styleId.substringBefore(QUERY)

    /** The URL query of its tiles, or `null` for a plain map. */
    fun queryOf(styleId: String): String? = styleId.substringAfter(QUERY, "").takeIf { it.isNotEmpty() }
}
