package fr.toolmap.core.layers

import fr.toolmap.core.geo.ZoomMapping

/**
 * Per-layer display range (feature N3). A range is written in the zoom shown to the user (the `z` of the 256 px
 * tiles on screen, [ZoomMapping]), both bounds **inclusive**: "8 to 16" means the layer is visible from zoom 8.0 up to
 * (but excluding) 17.0, so two layers "1–7" and "8–16" meet without a gap at zoom 7.5.
 */
object LayerZoom {
    /** MapLibre's `minzoom` / `maxzoom` of a raster layer, in camera zoom; `null` = not set. */
    data class CameraRange(
        val minZoom: Float?,
        val maxZoom: Float?,
    )

    /**
     * MapLibre hides a layer below `minzoom` and from `maxzoom` on (exclusive). With the camera at displayed zoom
     * − offset ([ZoomMapping], 1 with MapLibre's default tile size): `minzoom = min − offset` and
     * `maxzoom = max + 1 − offset`; a bound below camera zoom 0 means "no minimum" or "never shown".
     */
    fun cameraRange(
        displayMin: Int?,
        displayMax: Int?,
        mapping: ZoomMapping = ZoomMapping.STANDARD,
    ): CameraRange =
        CameraRange(
            minZoom = displayMin?.let { mapping.toCameraZoom(it.toDouble()).toFloat() }?.takeIf { it > 0f },
            maxZoom = displayMax?.let { mapping.toCameraZoom(it + 1.0).toFloat().coerceAtLeast(0f) },
        )

    /** True when a layer with this range is drawn at the displayed zoom [displayedZoom] (fractions allowed). */
    fun isActiveAt(
        displayMin: Int?,
        displayMax: Int?,
        displayedZoom: Double,
    ): Boolean =
        (displayMin == null || displayedZoom >= displayMin) && (displayMax == null || displayedZoom < displayMax + 1)

    fun isActiveAt(
        layer: LayerConfig,
        displayedZoom: Double,
    ): Boolean = isActiveAt(layer.displayMinZoom, layer.displayMaxZoom, displayedZoom)
}

/**
 * Geometry and rules of the two-handle bars of « Conditions d'affichage »: the zoom bar (0..[MAX]) and the elevation
 * bar ([ElevationBar], in steps, through the `max` parameters); pure so it can be tested.
 */
object ZoomBar {
    const val MAX = 20

    /** The highest zoom that can be typed (P23): beyond the bar, its handle stays at the end. */
    const val MAX_TYPED = 24

    enum class Handle { MIN, MAX }

    data class Range(
        val min: Int,
        val max: Int,
    )

    /** The bar position of a layer range: an unset bound sits at the end of the bar. */
    fun rangeOf(
        displayMin: Int?,
        displayMax: Int?,
    ): Range = Range((displayMin ?: 0).coerceIn(0, MAX), (displayMax ?: MAX).coerceIn(0, MAX))

    /** The integer position (zoom) under the horizontal position [x] of a bar [width] pixels wide. */
    fun zoomAt(
        x: Float,
        width: Float,
        max: Int = MAX,
    ): Int = if (width <= 0f) 0 else Math.round(x / width * max).coerceIn(0, max)

    /** Horizontal fraction (0..1) of the position [zoom] on the bar. */
    fun fractionOf(
        zoom: Double,
        max: Int = MAX,
    ): Float = (zoom / max).toFloat().coerceIn(0f, 1f)

    /** The handle a touch at [x] grabs: the closest one, the outer side of a shared position. */
    fun nearestHandle(
        x: Float,
        width: Float,
        range: Range,
        max: Int = MAX,
    ): Handle {
        val minX = range.min.toFloat() / max * width
        val maxX = range.max.toFloat() / max * width
        val dMin = kotlin.math.abs(x - minX)
        val dMax = kotlin.math.abs(x - maxX)
        return when {
            dMin < dMax -> Handle.MIN
            dMax < dMin -> Handle.MAX
            x < minX -> Handle.MIN
            else -> Handle.MAX
        }
    }

    /** Moves [handle] to [zoom] without letting the handles cross. */
    fun moved(
        range: Range,
        handle: Handle,
        zoom: Int,
        max: Int = MAX,
    ): Range =
        when (handle) {
            Handle.MIN -> Range(zoom.coerceIn(0, range.max), range.max)
            Handle.MAX -> Range(range.min, zoom.coerceIn(range.min, max))
        }

    /** Back to layer bounds: a handle at the end of the bar means "no limit" (`null`). */
    fun toBounds(range: Range): Pair<Int?, Int?> = (range.min.takeIf { it > 0 }) to (range.max.takeIf { it < MAX })

    /**
     * The bounds after the bar went from [from] to [to]: a handle that did not move keeps its bound [old], even one
     * typed beyond the bar or between two of its steps; one that moved takes the bound under it ([bounds]).
     */
    fun keptBounds(
        old: Pair<Int?, Int?>,
        from: Range,
        to: Range,
        bounds: (Range) -> Pair<Int?, Int?>,
    ): Pair<Int?, Int?> {
        val (low, high) = bounds(to)
        return (if (to.min == from.min) old.first else low) to (if (to.max == from.max) old.second else high)
    }

    /** True when a typed maximum lies beyond the end of the bar (the handle shows an arrow there). */
    fun isBeyond(max: Int?): Boolean = max != null && max > MAX
}
