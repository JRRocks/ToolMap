package fr.toolmap.core.text

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The signed distance field MapLibre draws its text with (ADR-062): one byte per pixel, 191 on the edge of the glyph,
 * more inside, less outside, down to 0 at [RADIUS] pixels away. Same maths as Mapbox's TinySDF (an exact Euclidean
 * distance transform, Felzenszwalb and Huttenlocher), so that the glyphs look like the ones of a hosted font.
 */
object SignedDistance {
    /** Pixels of empty margin around each glyph bitmap, the value MapLibre expects (`border` of its glyph atlas). */
    const val BUFFER = 3

    /** Distance, in pixels, over which the field goes from the edge to nothing. */
    const val RADIUS = 8.0

    /** Where the edge sits on the 0..1 scale (0.25 → byte 191, the `gamma` centre of MapLibre's text shader). */
    const val CUTOFF = 0.25

    private const val INF = 1e20
    private const val OPAQUE = 255.0

    /**
     * The field of a coverage image: [alpha] holds [width] × [height] bytes (0 = empty, 255 = ink), row by row. The
     * result has the same size.
     */
    fun of(
        alpha: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray {
        require(alpha.size == width * height) { "alpha has ${alpha.size} bytes, expected ${width * height}" }
        val size = width * height
        val outer = DoubleArray(size)
        val inner = DoubleArray(size)
        for (i in 0 until size) {
            val a = (alpha[i].toInt() and 0xFF) / OPAQUE
            when {
                a >= 1.0 -> {
                    outer[i] = 0.0
                    inner[i] = INF
                }

                a <= 0.0 -> {
                    outer[i] = INF
                    inner[i] = 0.0
                }

                else -> {
                    // A partly covered pixel: the edge crosses it, at a distance given by its coverage.
                    val d = 0.5 - a
                    outer[i] = if (d > 0) d * d else 0.0
                    inner[i] = if (d < 0) d * d else 0.0
                }
            }
        }
        transform(outer, width, height)
        transform(inner, width, height)
        val out = ByteArray(size)
        for (i in 0 until size) {
            val d = sqrt(outer[i]) - sqrt(inner[i])
            val v = (OPAQUE - OPAQUE * (d / RADIUS + CUTOFF)).roundToInt().coerceIn(0, OPAQUE.toInt())
            out[i] = v.toByte()
        }
        return out
    }

    /** Squared Euclidean distance transform of [grid] in place: columns, then rows. */
    private fun transform(
        grid: DoubleArray,
        width: Int,
        height: Int,
    ) {
        val n = max(width, height)
        val f = DoubleArray(n)
        val v = IntArray(n)
        val z = DoubleArray(n + 1)
        for (x in 0 until width) line(grid, x, width, height, f, v, z)
        for (y in 0 until height) line(grid, y * width, 1, width, f, v, z)
    }

    /** One-dimensional transform of the [length] values of [grid] from [offset], [stride] apart (lower envelope). */
    private fun line(
        grid: DoubleArray,
        offset: Int,
        stride: Int,
        length: Int,
        f: DoubleArray,
        v: IntArray,
        z: DoubleArray,
    ) {
        for (q in 0 until length) f[q] = grid[offset + q * stride]
        var k = 0
        v[0] = 0
        z[0] = -INF
        z[1] = INF
        for (q in 1 until length) {
            var s: Double
            while (true) {
                val r = v[k]
                s = (f[q] - f[r] + q.toDouble() * q - r.toDouble() * r) / (q - r) / 2.0
                // The parabola of q hides the one of r: drop r and compare with the one before.
                if (s > z[k]) break
                k--
                if (k < 0) break
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = INF
        }
        k = 0
        for (q in 0 until length) {
            while (z[k + 1] < q) k++
            val r = v[k]
            val dq = (q - r).toDouble()
            grid[offset + q * stride] = dq * dq + f[r]
        }
    }
}
