package fr.toolmap.core.elevation

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The elevation band a layer is drawn in (P20): its pixels below [min] or above [max] metres (inclusive bounds, `null`
 * = unbounded) are transparent, so the map under it shows there. [whole] are the tile zooms where the layer is drawn
 * whole whatever the elevation (a zoom range joined by « OU »); `null` = never.
 *
 * The band travels in the query of the layer's tile URL ([query], [parse]): the tile interceptor reads it, and a new
 * band is a new URL, so MapLibre asks for the tiles again.
 */
data class ElevationMask(
    val min: Int?,
    val max: Int?,
    val whole: IntRange? = null,
) {
    /** True when the tiles of zoom [z] are drawn whole, without looking at the elevations. */
    fun isWholeAt(z: Int): Boolean = (min == null && max == null) || (whole != null && z in whole)

    /** `alt=1500,` / `alt=,1200&whole=8,16`: the URL query that carries this band. */
    fun query(): String =
        buildString {
            append(ALT).append('=').append(min ?: "").append(',').append(max ?: "")
            whole?.let { append('&').append(WHOLE).append('=').append(it.first).append(',').append(it.last) }
        }

    /** Whether a tile (or the part of a DEM tile under it), whose elevations go from [low] to [high], is shown. */
    fun coverage(
        low: Float,
        high: Float,
    ): Coverage {
        val above = min?.toFloat() ?: Float.NEGATIVE_INFINITY
        val below = max?.toFloat() ?: Float.POSITIVE_INFINITY
        return when {
            high < above || low > below -> Coverage.NONE
            low >= above && high <= below -> Coverage.ALL
            else -> Coverage.PART
        }
    }

    enum class Coverage { NONE, PART, ALL }

    companion object {
        private const val ALT = "alt"
        private const val WHOLE = "whole"

        /** The band in a tile URL [query], or `null` when the query has none (a plain layer). */
        fun parse(query: String?): ElevationMask? {
            if (query.isNullOrEmpty()) return null
            val params =
                query.split('&').mapNotNull { part ->
                    val eq = part.indexOf('=')
                    if (eq <= 0) null else part.substring(0, eq) to part.substring(eq + 1)
                }.toMap()
            val alt = params[ALT]?.split(',') ?: return null
            if (alt.size != 2) return null
            val low = alt[0].takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: return null }
            val high = alt[1].takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: return null }
            val whole =
                params[WHOLE]?.split(',')?.let { zooms ->
                    val a = zooms.getOrNull(0)?.toIntOrNull() ?: return null
                    val b = zooms.getOrNull(1)?.toIntOrNull() ?: return null
                    a..b
                }
            return ElevationMask(low, high, whole)
        }
    }
}

/**
 * Where the elevations of tile ([z], x, y) are read: the DEM tile ([demZ], [demX], [demY]) at zoom `min(z, maxDemZoom)`,
 * and, when the tile is finer than the DEM, the cell ([col], [row]) of a 2^[depth] × 2^[depth] grid of it.
 */
data class DemWindow(
    val demZ: Int,
    val demX: Int,
    val demY: Int,
    val depth: Int,
    val col: Int,
    val row: Int,
) {
    companion object {
        /**
         * Elevation tiles read for the masks. 13 is about 13 m per pixel at 45° N, finer than the data itself
         * (SRTM / EU-DEM, ≈ 30 m), and the zoom the elevations of the tracks already use (`ElevationCompleter`):
         * tiles stored for a profile serve the masks too.
         */
        const val MAX_DEM_ZOOM = 13

        fun of(
            z: Int,
            x: Int,
            y: Int,
            maxDemZoom: Int = MAX_DEM_ZOOM,
        ): DemWindow {
            val demZ = min(z, maxDemZoom)
            val depth = z - demZ
            val demX = x shr depth
            val demY = y shr depth
            return DemWindow(demZ, demX, demY, depth, x - (demX shl depth), y - (demY shl depth))
        }
    }
}

/** Elevation masks computed on a decoded DEM tile ([dem], [demSize] × [demSize] metres, row after row). */
object MaskRaster {
    /**
     * Lowest and highest elevations under [window], one DEM pixel beyond its edges (the pixels its interpolated
     * elevations are made of).
     */
    fun range(
        dem: FloatArray,
        demSize: Int,
        window: DemWindow,
    ): Pair<Float, Float> {
        val side = 1 shl window.depth
        val x0 = (window.col * demSize / side - 1).coerceAtLeast(0)
        val y0 = (window.row * demSize / side - 1).coerceAtLeast(0)
        val x1 = ((window.col + 1) * demSize / side).coerceAtMost(demSize - 1)
        val y1 = ((window.row + 1) * demSize / side).coerceAtMost(demSize - 1)
        var low = Float.POSITIVE_INFINITY
        var high = Float.NEGATIVE_INFINITY
        for (y in y0..y1) {
            val line = y * demSize
            for (x in x0..x1) {
                val v = dem[line + x]
                if (v < low) low = v
                if (v > high) high = v
            }
        }
        return low to high
    }

    /**
     * The elevation of each pixel of a [size] × [size] tile under [window], interpolated between the four nearest DEM
     * pixel centres; beyond the DEM tile's edge its border pixels are repeated (at most half a DEM pixel off).
     */
    fun elevations(
        dem: FloatArray,
        demSize: Int,
        window: DemWindow,
        size: Int,
    ): FloatArray {
        val side = (1 shl window.depth).toDouble()
        val out = FloatArray(size * size)
        // DEM pixel coordinate of each output column / row, computed once.
        val xs = DoubleArray(size) { i -> (window.col + (i + 0.5) / size) / side * demSize - 0.5 }
        val ys = DoubleArray(size) { j -> (window.row + (j + 0.5) / size) / side * demSize - 0.5 }
        val last = demSize - 1
        for (j in 0 until size) {
            val py = ys[j]
            val y0 = floor(py).toInt()
            val ty = (py - y0).toFloat()
            val r0 = y0.coerceIn(0, last) * demSize
            val r1 = (y0 + 1).coerceIn(0, last) * demSize
            for (i in 0 until size) {
                val px = xs[i]
                val x0 = floor(px).toInt()
                val tx = (px - x0).toFloat()
                val c0 = x0.coerceIn(0, last)
                val c1 = (x0 + 1).coerceIn(0, last)
                val top = dem[r0 + c0] + (dem[r0 + c1] - dem[r0 + c0]) * tx
                val bottom = dem[r1 + c0] + (dem[r1 + c1] - dem[r1 + c0]) * tx
                out[j * size + i] = top + (bottom - top) * ty
            }
        }
        return out
    }

    /**
     * The opacity (0..255) of each pixel of a [size] × [size] grid of [elevations] for [mask]: 255 inside the band,
     * 0 outside, and a one-pixel soft edge along the contour (the local slope says how many metres a pixel spans),
     * so the limit is not jagged.
     */
    fun alphas(
        elevations: FloatArray,
        size: Int,
        mask: ElevationMask,
    ): ByteArray {
        val out = ByteArray(size * size)
        val low = mask.min?.toFloat()
        val high = mask.max?.toFloat()
        val last = size - 1
        for (j in 0 until size) {
            val up = max(j - 1, 0) * size
            val down = min(j + 1, last) * size
            val line = j * size
            for (i in 0 until size) {
                val e = elevations[line + i]
                // Metres per pixel along the steeper axis (central differences), never zero.
                val gx = abs(elevations[line + min(i + 1, last)] - elevations[line + max(i - 1, 0)]) * 0.5f
                val gy = abs(elevations[down + i] - elevations[up + i]) * 0.5f
                val step = max(max(gx, gy), MIN_STEP)
                var a = 1f
                if (low != null) a = min(a, ((e - low) / step + 0.5f).coerceIn(0f, 1f))
                if (high != null) a = min(a, ((high - e) / step + 0.5f).coerceIn(0f, 1f))
                out[line + i] = (a * OPAQUE + 0.5f).toInt().toByte()
            }
        }
        return out
    }

    private const val MIN_STEP = 0.01f
    private const val OPAQUE = 255f
}
