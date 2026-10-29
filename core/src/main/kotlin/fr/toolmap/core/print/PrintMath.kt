package fr.toolmap.core.print

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * The maths of printing a map at an exact scale (docs/specs/print-pdf.md §2). Everything is Web Mercator with 256 px
 * tiles unless said otherwise; the scale is exact at the latitude of the frame centre.
 */
object PrintMath {
    /** Meters per pixel of a 256 px tile at zoom 0, at the equator. */
    const val EQUATOR_RESOLUTION = 156_543.03392804097

    const val MM_PER_INCH = 25.4

    /** Ground meters under one printed pixel: scale 1:[scale] at [dpi]. */
    fun groundResolution(
        scale: Double,
        dpi: Int,
    ): Double = scale * MM_PER_INCH / 1000.0 / dpi

    /** Ground meters covered by [mm] of paper at 1:[scale]. */
    fun groundMeters(
        mm: Double,
        scale: Double,
    ): Double = mm * scale / 1000.0

    /** Web Mercator stretches distances by 1 / cos(latitude): meters of the projection per ground meter. */
    fun mercatorFactor(latitude: Double): Double = 1.0 / cos(Math.toRadians(latitude))

    /** Pixels of [mm] of paper at [dpi], at most [Int.MAX_VALUE] (a sheet that wide is refused by the plan). */
    fun pixels(
        mm: Double,
        dpi: Int,
    ): Int = Math.round(mm / MM_PER_INCH * dpi).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    /** Ground resolution of a tile at zoom [z], at [latitude]; 512 px tiles halve the figure. */
    fun tileResolution(
        z: Int,
        latitude: Double,
        tileSize: Int = 256,
    ): Double = EQUATOR_RESOLUTION * cos(Math.toRadians(latitude)) / (1L shl z).toDouble() * 256.0 / tileSize

    /** The (fractional) tile zoom whose tiles have exactly the resolution of the printed pixels. */
    fun equivalentZoom(
        scale: Double,
        dpi: Int,
        latitude: Double,
    ): Double = ln(EQUATOR_RESOLUTION * cos(Math.toRadians(latitude)) / groundResolution(scale, dpi)) / LN2

    /**
     * The smallest zoom whose tiles are at least as sharp as the printed pixels (`R(z) <= r`): a slight
     * downsampling, never a blur. [lighter] takes one level less (a lighter file, a touch softer).
     */
    fun tileZoom(
        scale: Double,
        dpi: Int,
        latitude: Double,
        lighter: Boolean = false,
    ): Int {
        val z = ceil(equivalentZoom(scale, dpi, latitude) - EPSILON).toInt()
        return (if (lighter) z - 1 else z).coerceIn(0, MAX_ZOOM)
    }

    /**
     * The zoom a source with the given native [available] zooms is asked for when [wanted] is the ideal one: the
     * nearest zoom at or above it, else the highest the source has (then the tiles are enlarged).
     */
    fun nativeZoom(
        wanted: Int,
        available: List<Int>,
    ): Int? = available.firstOrNull { it >= wanted } ?: available.maxOrNull()

    /**
     * How much the tiles of zoom [used] are enlarged to reach the printed resolution: 1.0 when sharp enough, 3.2 when
     * a pixel of the paper is 3.2 pixels of the tile. Rounded to 0.1.
     */
    fun oversampling(
        used: Int,
        scale: Double,
        dpi: Int,
        latitude: Double,
        tileSize: Int = 256,
    ): Double {
        val ratio = tileResolution(used, latitude, tileSize) / groundResolution(scale, dpi)
        return if (ratio <= 1.0 + EPSILON) 1.0 else Math.round(ratio * 10.0) / 10.0
    }

    /**
     * Relative scale error (percent) at the north or south edge of a frame [frameHeightMm] tall, 1:[scale], centred at
     * [latitude]: about `tan(lat) * half-height / R`.
     */
    fun edgeErrorPercent(
        frameHeightMm: Double,
        scale: Double,
        latitude: Double,
    ): Double {
        val halfHeightMeters = groundMeters(frameHeightMm, scale) / 2.0
        val dPhi = halfHeightMeters / EARTH_RADIUS_M
        return abs(tan(Math.toRadians(latitude))) * dPhi * 100.0
    }

    /** Pages a PDF of [pixelsPerPage] pixels weighs, as a (low, high) figure in bytes: very indicative. */
    fun estimatedBytes(
        pixelsPerPage: Long,
        pages: Int,
    ): Pair<Long, Long> =
        (pixelsPerPage * pages * LOW_BYTES_PER_PIXEL).toLong() to
            (pixelsPerPage * pages * HIGH_BYTES_PER_PIXEL).toLong()

    private val LN2 = ln(2.0)
    private const val EPSILON = 1e-9
    private const val MAX_ZOOM = 24
    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val LOW_BYTES_PER_PIXEL = 0.15
    private const val HIGH_BYTES_PER_PIXEL = 2.0
}
