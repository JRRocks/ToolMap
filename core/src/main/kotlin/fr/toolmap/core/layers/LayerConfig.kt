package fr.toolmap.core.layers

import kotlinx.serialization.Serializable
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One entry of the layer stack: a tile source displayed with its own opacity and color adjustments.
 * [displayMinZoom] / [displayMaxZoom] (inclusive, in tile zoom, `null` = unbounded) are the per-layer
 * display range of feature N3 (phase P3). [displayMinElevation] / [displayMaxElevation] (inclusive, metres, `null` =
 * unbounded) are the elevation band the layer is drawn in (P20); [rangeLogic] says how the two combine when both are
 * set. [hue] turns the colours round the colour wheel (P26.6).
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
    /** Hue rotation in degrees, -180 .. 180, 0 = unchanged (MapLibre's `raster-hue-rotate`). */
    val hue: Float = 0f,
) {
    /** True when a color adjustment differs from the neutral value. */
    val hasAdjustments: Boolean
        get() = contrast != 0f || brightness != 0f || saturation != 0f || hue != 0f || inverted

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

    /** Largest hue rotation either way, in degrees (P26.6). */
    const val MAX_HUE = 180f

    /**
     * The colour matrix (4 × 5, row after row, Android's layout: the last column is an offset in 0..255) that gives a
     * pixel the same hue, contrast, saturation and brightness as the map shows with [layer]'s settings, or `null` when
     * the layer has none. It follows the raster shader of the map: hue rotation, saturation, then contrast around
     * mid-grey, then the brightness range.
     */
    fun colorMatrix(layer: LayerConfig): FloatArray? {
        if (!layer.hasAdjustments) return null
        val s = saturationFactor(layer.saturation.coerceIn(-1f, 1f))
        val k = contrastFactor(layer.contrast.coerceIn(-1f, MAX_CONTRAST))
        val range = brightnessRange(layer.brightness, layer.inverted)
        val span = range.max - range.min
        val spin = hueWeights(layer.hue.coerceIn(-MAX_HUE, MAX_HUE))
        // Saturation moves each channel towards the average (weights d on itself, o on the others), which the
        // rotation keeps: row i of saturation × rotation is (d - o) × the rotation's row + o.
        val d = 1f - s + s / 3f
        val o = s / 3f
        val offset = (range.min + span * 0.5f * (1f - k)) * 255f
        val matrix = FloatArray(MATRIX_SIZE)
        for (row in 0 until 3) {
            for (col in 0 until 3) matrix[row * 5 + col] = ((d - o) * spin[(col - row + 3) % 3] + o) * k * span
            matrix[row * 5 + 4] = offset
        }
        matrix[ALPHA_GAIN] = 1f
        return matrix
    }

    /**
     * MapLibre's `raster-hue-rotate` weights for [degrees]: the new red is `w0 r + w1 g + w2 b`, green and blue take
     * the same weights shifted by one (`w2 r + w0 g + w1 b`, `w1 r + w2 g + w0 b`): a turn about the grey axis.
     */
    internal fun hueWeights(degrees: Float): FloatArray {
        val a = Math.toRadians(degrees.toDouble())
        val c = cos(a)
        val s = sqrt(3.0) * sin(a)
        return floatArrayOf(((2 * c + 1) / 3).toFloat(), ((1 - c - s) / 3).toFloat(), ((1 - c + s) / 3).toFloat())
    }

    /** MapLibre's `raster-saturation` factor: how far each channel moves towards the average. */
    internal fun saturationFactor(saturation: Float): Float =
        if (saturation >= 0f) 1f - 1f / (1.001f - saturation) else -saturation

    /** MapLibre's `raster-contrast` factor: the slope around mid-grey. */
    internal fun contrastFactor(contrast: Float): Float = if (contrast > 0f) 1f / (1f - contrast) else 1f + contrast

    private const val MAX_CONTRAST = 0.99f
    private const val MATRIX_SIZE = 20
    private const val ALPHA_GAIN = 18
}
