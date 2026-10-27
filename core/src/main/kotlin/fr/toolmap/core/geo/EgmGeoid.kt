package fr.toolmap.core.geo

import java.nio.ByteBuffer

/**
 * The EGM96 geoid (NGA's `WW15MGH.DAC`, public domain): the height of the geoid above the WGS84 ellipsoid, on a grid of
 * 15 arc-minutes. The GNSS gives heights above the ellipsoid; the maps, the barometer and the track files use heights
 * above the geoid (sea level), so the altitude of a fix is `ellipsoidal − undulation` (ADR-036).
 *
 * The file holds 721 rows from 90° N to 90° S, of 1 440 values from 0° to 359.75° east, big-endian signed 16-bit
 * integers in centimetres. Values between the nodes are bilinear: the error is a few centimetres.
 */
class EgmGeoid private constructor(
    private val centimetres: ShortArray,
) {
    /** The undulation of the geoid at a position, in metres (negative where the geoid lies under the ellipsoid). */
    fun undulation(
        lat: Double,
        lon: Double,
    ): Double {
        val row = ((90.0 - lat.coerceIn(-90.0, 90.0)) / STEP).coerceIn(0.0, (ROWS - 1).toDouble())
        val col = (((lon % 360.0) + 360.0) % 360.0) / STEP
        val r0 = row.toInt().coerceAtMost(ROWS - 2)
        val c0 = col.toInt() % COLS
        val fr = row - r0
        val fc = col - col.toInt()
        val c1 = (c0 + 1) % COLS
        val top = value(r0, c0) * (1 - fc) + value(r0, c1) * fc
        val bottom = value(r0 + 1, c0) * (1 - fc) + value(r0 + 1, c1) * fc
        return top * (1 - fr) + bottom * fr
    }

    /** The height above sea level of a height above the ellipsoid (the GNSS altitude) at a position. */
    fun orthometric(
        ellipsoidalMeters: Double,
        lat: Double,
        lon: Double,
    ): Double = ellipsoidalMeters - undulation(lat, lon)

    private fun value(
        row: Int,
        col: Int,
    ): Double = centimetres[row * COLS + col] / 100.0

    companion object {
        const val ROWS = 721
        const val COLS = 1440
        const val STEP = 0.25
        const val FILE_BYTES = ROWS * COLS * 2

        /** Reads the grid. Throws when the bytes are not a whole EGM96 grid: the caller keeps its own altitude. */
        fun parse(bytes: ByteArray): EgmGeoid {
            require(bytes.size == FILE_BYTES) { "Geoid grid of ${bytes.size} bytes, expected $FILE_BYTES" }
            val values = ShortArray(ROWS * COLS)
            ByteBuffer.wrap(bytes).asShortBuffer().get(values)
            return EgmGeoid(values)
        }
    }
}
