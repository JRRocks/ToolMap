package fr.toolmap.core.layers

import kotlinx.serialization.Serializable

/**
 * One entry of the layer stack: a tile source displayed with its own opacity and color adjustments.
 * [displayMinZoom] / [displayMaxZoom] (inclusive, in tile zoom, `null` = unbounded) are the per-layer
 * display range of feature N3 (phase P3). [displayMinElevation] / [displayMaxElevation] (inclusive, metres, `null` =
 * unbounded) are the elevation band the layer is drawn in (P20); [rangeLogic] says how the two combine when both are
 * set.
 */
@Serializable
data class LayerConfig(
    val id: String,
    val sourceId: String,
    val visible: Boolean = true,
    /** 0 (transparent) .. 1 (opaque). */
    val opacity: Float = 1f,
    /** -1 .. 1, 0 = unchanged. */
    val contrast: Float = 0f,
    /** -1 .. 1, 0 = unchanged. */
    val brightness: Float = 0f,
    /** -1 .. 1, 0 = unchanged (-1 = grayscale). */
    val saturation: Float = 0f,
    val displayMinZoom: Int? = null,
    val displayMaxZoom: Int? = null,
    /** Negative image: every colour channel is flipped (AlpineQuest's "Inverted colors"). */
    val inverted: Boolean = false,
    val displayMinElevation: Int? = null,
    val displayMaxElevation: Int? = null,
    val rangeLogic: RangeLogic = RangeLogic.AND,
) {
    /** True when a color adjustment differs from the neutral value. */
    val hasAdjustments: Boolean get() = contrast != 0f || brightness != 0f || saturation != 0f || inverted

    val hasZoomRange: Boolean get() = displayMinZoom != null || displayMaxZoom != null

    val hasElevationRange: Boolean get() = displayMinElevation != null || displayMaxElevation != null
}

/**
 * How a layer's zoom range and elevation band combine when both are set (P20): [AND] draws it where both hold (the
 * band, at the zooms of the range), [OR] where either holds (whole at the zooms of the range, the band elsewhere).
 */
@Serializable
enum class RangeLogic { AND, OR }

/** MapLibre brightness range (`raster-brightness-min` / `-max`) for a user value in -1..1. */
data class BrightnessRange(
    val min: Float,
    val max: Float,
)

object RasterAdjust {
    /**
     * Brighter (> 0) lifts the black point, darker (< 0) lowers the white point; 0 gives the identity range
     * (0..1). A negative image ([inverted]) swaps the two ends: the shader mixes `min` to `max` over the channel
     * value, so a range running from 1 down to 0 flips black and white.
     */
    fun brightnessRange(
        brightness: Float,
        inverted: Boolean = false,
    ): BrightnessRange {
        val b = brightness.coerceIn(-1f, 1f)
        val range = if (b >= 0f) BrightnessRange(b, 1f) else BrightnessRange(0f, 1f + b)
        return if (inverted) BrightnessRange(range.max, range.min) else range
    }

    /**
     * The colour matrix (4 × 5, row after row, Android's layout: the last column is an offset in 0..255) that gives a
     * pixel the same contrast, saturation and brightness as the map shows with [layer]'s settings, or `null` when the
     * layer has none. It follows the raster shader of the map: saturation, then contrast around mid-grey, then the
     * brightness range.
     */
    fun colorMatrix(layer: LayerConfig): FloatArray? {
        if (!layer.hasAdjustments) return null
        val s = saturationFactor(layer.saturation.coerceIn(-1f, 1f))
        val k = contrastFactor(layer.contrast.coerceIn(-1f, MAX_CONTRAST))
        val range = brightnessRange(layer.brightness, layer.inverted)
        val span = range.max - range.min
        val diagonal = (1f - s + s / 3f) * k * span
        val other = s / 3f * k * span
        val offset = (range.min + span * 0.5f * (1f - k)) * 255f
        return floatArrayOf(
            diagonal,
            other,
            other,
            0f,
            offset,
            other,
            diagonal,
            other,
            0f,
            offset,
            other,
            other,
            diagonal,
            0f,
            offset,
            0f,
            0f,
            0f,
            1f,
            0f,
        )
    }

    /** MapLibre's `raster-saturation` factor: how far each channel moves towards the average. */
    internal fun saturationFactor(saturation: Float): Float =
        if (saturation >= 0f) 1f - 1f / (1.001f - saturation) else -saturation

    /** MapLibre's `raster-contrast` factor: the slope around mid-grey. */
    internal fun contrastFactor(contrast: Float): Float = if (contrast > 0f) 1f / (1f - contrast) else 1f + contrast

    private const val MAX_CONTRAST = 0.99f
}
