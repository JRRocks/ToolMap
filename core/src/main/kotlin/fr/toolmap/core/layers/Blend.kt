package fr.toolmap.core.layers

import fr.toolmap.core.compute.Compute
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How a layer mixes with everything under it (P26), the modes of W3C Compositing and Blending Level 1. [key] is the
 * name in the tile URLs and the files (stable: never renamed).
 */
@Serializable
enum class BlendMode(
    val key: String,
) {
    NORMAL("normal"),
    MULTIPLY("multiply"),
    SCREEN("screen"),
    OVERLAY("overlay"),
    DARKEN("darken"),
    LIGHTEN("lighten"),
    COLOR_DODGE("color-dodge"),
    COLOR_BURN("color-burn"),
    HARD_LIGHT("hard-light"),
    SOFT_LIGHT("soft-light"),
    DIFFERENCE("difference"),
    EXCLUSION("exclusion"),
    HUE("hue"),
    SATURATION("saturation"),
    COLOR("color"),
    LUMINOSITY("luminosity"),
    ;

    /** True for the modes computed channel by channel; the last four work on the whole colour. */
    val isSeparable: Boolean get() = ordinal < HUE.ordinal

    companion object {
        fun fromKey(key: String): BlendMode? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The pixel side of the blend modes (P26): the same formulas for the tiles of the map and for the print. Pixels are
 * ARGB ints with straight (not premultiplied) colours, as `Bitmap.getPixels` gives them.
 */
object Blend {
    /** The blend function `B(cb, cs)` of a separable [mode], channels in 0..1 (W3C Compositing § 10.1). */
    fun channel(
        mode: BlendMode,
        cb: Float,
        cs: Float,
    ): Float =
        when (mode) {
            BlendMode.NORMAL -> {
                cs
            }

            BlendMode.MULTIPLY -> {
                cb * cs
            }

            BlendMode.SCREEN -> {
                screen(cb, cs)
            }

            BlendMode.OVERLAY -> {
                hardLight(cs, cb)
            }

            BlendMode.DARKEN -> {
                min(cb, cs)
            }

            BlendMode.LIGHTEN -> {
                max(cb, cs)
            }

            BlendMode.COLOR_DODGE -> {
                if (cb == 0f) {
                    0f
                } else if (cs >= 1f) {
                    1f
                } else {
                    min(1f, cb / (1f - cs))
                }
            }

            BlendMode.COLOR_BURN -> {
                if (cb >= 1f) {
                    1f
                } else if (cs == 0f) {
                    0f
                } else {
                    1f - min(1f, (1f - cb) / cs)
                }
            }

            BlendMode.HARD_LIGHT -> {
                hardLight(cb, cs)
            }

            BlendMode.SOFT_LIGHT -> {
                softLight(cb, cs)
            }

            BlendMode.DIFFERENCE -> {
                abs(cb - cs)
            }

            BlendMode.EXCLUSION -> {
                cb + cs - 2f * cb * cs
            }

            else -> {
                throw IllegalArgumentException("$mode is not separable")
            }
        }

    /**
     * The blended colour `B(Cb, Cs)` of any [mode], colours in 0..1, written into [out] (W3C Compositing § 10.2 for the
     * non-separable modes).
     */
    fun color(
        mode: BlendMode,
        cb: FloatArray,
        cs: FloatArray,
        out: FloatArray,
    ) {
        if (mode.isSeparable) {
            for (i in 0 until CHANNELS) out[i] = channel(mode, cb[i], cs[i])
            return
        }
        when (mode) {
            BlendMode.HUE -> {
                setSat(cs, sat(cb), out)
                setLum(out, lum(cb), out)
            }

            BlendMode.SATURATION -> {
                setSat(cb, sat(cs), out)
                setLum(out, lum(cb), out)
            }

            BlendMode.COLOR -> {
                setLum(cs, lum(cb), out)
            }

            else -> {
                setLum(cb, lum(cs), out)
            }
        }
    }

    /**
     * Draws [above] over [below] (same size), in place in [below]: [mode] blends the colours where both are present,
     * [opacity] (0..1) scales the alpha of [above], and the result is "source over" (W3C Compositing § 5.8).
     * Done by the [Compute.accelerator] when there is one (same pixels, ADR-119), else by [compositeReference].
     */
    fun composite(
        below: IntArray,
        above: IntArray,
        mode: BlendMode,
        opacity: Float = 1f,
    ) {
        require(below.size == above.size) { "pixel arrays of different sizes" }
        if (Compute.accelerator?.composite(below, above, mode.ordinal, opacity) == true) return
        compositeReference(below, above, mode, opacity)
    }

    /** [composite] in Kotlin: the reference the accelerator is checked against. */
    fun compositeReference(
        below: IntArray,
        above: IntArray,
        mode: BlendMode,
        opacity: Float = 1f,
    ) {
        require(below.size == above.size) { "pixel arrays of different sizes" }
        val gain = opacity.coerceIn(0f, 1f)
        val cb = FloatArray(CHANNELS)
        val cs = FloatArray(CHANNELS)
        val mixed = FloatArray(CHANNELS)
        for (i in below.indices) {
            val s = above[i]
            val alphaS = (s ushr ALPHA_SHIFT) / MAX * gain
            if (alphaS <= 0f) continue
            val b = below[i]
            val alphaB = (b ushr ALPHA_SHIFT) / MAX
            unpack(s, cs)
            unpack(b, cb)
            if (alphaB <= 0f || mode == BlendMode.NORMAL) {
                mixed[0] = cs[0]
                mixed[1] = cs[1]
                mixed[2] = cs[2]
            } else {
                color(mode, cb, cs, mixed)
                // Where the backdrop is partly transparent the source keeps part of its own colour.
                for (c in 0 until CHANNELS) mixed[c] = (1f - alphaB) * cs[c] + alphaB * mixed[c]
            }
            val alphaO = alphaS + alphaB * (1f - alphaS)
            var argb = pack(alphaO) shl ALPHA_SHIFT
            for (c in 0 until CHANNELS) {
                val premultiplied = alphaS * mixed[c] + alphaB * (1f - alphaS) * cb[c]
                argb = argb or (pack(premultiplied / alphaO) shl SHIFTS[c])
            }
            below[i] = argb
        }
    }

    /**
     * Gives [pixels] a [tint] colour (`0xRRGGBB`), in place: each pixel takes the hue and saturation of the tint and
     * keeps its own luminosity (the « Couleur » mode), mixed with its own colour by [intensity] (0..1). Alpha stays.
     */
    fun tint(
        pixels: IntArray,
        tint: Int,
        intensity: Float,
    ) {
        if (Compute.accelerator?.tint(pixels, tint, intensity) == true) return
        tintReference(pixels, tint, intensity)
    }

    /** [tint] in Kotlin: the reference the accelerator is checked against. */
    fun tintReference(
        pixels: IntArray,
        tint: Int,
        intensity: Float,
    ) {
        val k = intensity.coerceIn(0f, 1f)
        if (k <= 0f) return
        val ct = FloatArray(CHANNELS)
        unpack(tint, ct)
        val c = FloatArray(CHANNELS)
        val tinted = FloatArray(CHANNELS)
        for (i in pixels.indices) {
            val p = pixels[i]
            unpack(p, c)
            setLum(ct, lum(c), tinted)
            var argb = p and ALPHA_MASK
            for (ch in 0 until CHANNELS) argb = argb or (pack(c[ch] + (tinted[ch] - c[ch]) * k) shl SHIFTS[ch])
            pixels[i] = argb
        }
    }

    /**
     * Applies a colour [matrix] (4 × 5, Android's layout, offsets in 0..255: [RasterAdjust.colorMatrix]) to [pixels],
     * in place. Alpha stays.
     */
    fun adjust(
        pixels: IntArray,
        matrix: FloatArray,
    ) {
        require(matrix.size == MATRIX_SIZE) { "a colour matrix has $MATRIX_SIZE entries" }
        if (Compute.accelerator?.adjust(pixels, matrix) == true) return
        adjustReference(pixels, matrix)
    }

    /** [adjust] in Kotlin: the reference the accelerator is checked against. */
    fun adjustReference(
        pixels: IntArray,
        matrix: FloatArray,
    ) {
        require(matrix.size == MATRIX_SIZE) { "a colour matrix has $MATRIX_SIZE entries" }
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr RED_SHIFT) and BYTE).toFloat()
            val g = ((p shr GREEN_SHIFT) and BYTE).toFloat()
            val b = (p and BYTE).toFloat()
            var argb = p and ALPHA_MASK
            for (row in 0 until CHANNELS) {
                val v = matrix[row * 5] * r + matrix[row * 5 + 1] * g + matrix[row * 5 + 2] * b + matrix[row * 5 + 4]
                argb = argb or ((v + HALF).toInt().coerceIn(0, BYTE) shl SHIFTS[row])
            }
            pixels[i] = argb
        }
    }

    private fun screen(
        cb: Float,
        cs: Float,
    ): Float = cb + cs - cb * cs

    private fun hardLight(
        cb: Float,
        cs: Float,
    ): Float = if (cs <= HALF) cb * 2f * cs else screen(cb, 2f * cs - 1f)

    private fun softLight(
        cb: Float,
        cs: Float,
    ): Float {
        if (cs <= HALF) return cb - (1f - 2f * cs) * cb * (1f - cb)
        val d = if (cb <= QUARTER) ((16f * cb - 12f) * cb + 4f) * cb else sqrt(cb)
        return cb + (2f * cs - 1f) * (d - cb)
    }

    internal fun lum(c: FloatArray): Float = LUM_R * c[0] + LUM_G * c[1] + LUM_B * c[2]

    private fun sat(c: FloatArray): Float = max(c[0], max(c[1], c[2])) - min(c[0], min(c[1], c[2]))

    /** `SetLum(c, l)`: [c] moved to luminosity [l], then brought back into 0..1 (`ClipColor`). [out] may be [c]. */
    private fun setLum(
        c: FloatArray,
        l: Float,
        out: FloatArray,
    ) {
        val d = l - lum(c)
        for (i in 0 until CHANNELS) out[i] = c[i] + d
        val lo = lum(out)
        val n = min(out[0], min(out[1], out[2]))
        val x = max(out[0], max(out[1], out[2]))
        for (i in 0 until CHANNELS) {
            if (n < 0f) out[i] = lo + (out[i] - lo) * lo / (lo - n)
            if (x > 1f) out[i] = lo + (out[i] - lo) * (1f - lo) / (x - lo)
        }
    }

    /** `SetSat(c, s)`: [c] with saturation [s], its hue kept. [out] must not be [c]. */
    private fun setSat(
        c: FloatArray,
        s: Float,
        out: FloatArray,
    ) {
        val hi = max(c[0], max(c[1], c[2]))
        val lo = min(c[0], min(c[1], c[2]))
        for (i in 0 until CHANNELS) {
            out[i] =
                when {
                    hi <= lo -> 0f
                    c[i] == hi -> s
                    c[i] == lo -> 0f
                    else -> (c[i] - lo) * s / (hi - lo)
                }
        }
    }

    private fun unpack(
        argb: Int,
        out: FloatArray,
    ) {
        out[0] = ((argb shr RED_SHIFT) and BYTE) / MAX
        out[1] = ((argb shr GREEN_SHIFT) and BYTE) / MAX
        out[2] = (argb and BYTE) / MAX
    }

    private fun pack(v: Float): Int = (v * MAX + HALF).toInt().coerceIn(0, BYTE)

    private const val CHANNELS = 3
    private const val MATRIX_SIZE = 20
    private const val BYTE = 0xFF
    private const val MAX = 255f
    private const val HALF = 0.5f
    private const val QUARTER = 0.25f
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val ALPHA_MASK = -0x1000000
    private val SHIFTS = intArrayOf(RED_SHIFT, GREEN_SHIFT, 0)
    private const val LUM_R = 0.3f
    private const val LUM_G = 0.59f
    private const val LUM_B = 0.11f
}
