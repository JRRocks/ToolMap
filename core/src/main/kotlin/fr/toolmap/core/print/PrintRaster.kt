package fr.toolmap.core.print

import fr.toolmap.core.tiles.TileCoord
import fr.toolmap.core.tiles.TileId
import fr.toolmap.core.tiles.TileMath
import kotlin.math.ceil
import kotlin.math.floor

/** A horizontal strip of the output image: [heightPx] rows from row [y0]. */
data class Band(
    val index: Int,
    val y0: Int,
    val heightPx: Int,
)

/** Where a tile lands in the bitmap of a band, in whole pixels (shared edges are computed once: no seam). */
data class TilePlacement(
    val tile: TileId,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * The output image of a page at [dpi]: its size, its cut into bands of [bandHeightPx] rows (memory stays bounded
 * whatever the paper: a wide sheet gets thinner bands), and which tiles fall in each band.
 */
class PrintRaster(
    val frame: FrameProjection,
    val dpi: Int,
    val bandHeightPx: Int = bandHeightFor(PrintMath.pixels(frame.frameWidthMm, dpi)),
) {
    init {
        require(dpi > 0 && bandHeightPx > 0) { "invalid raster" }
    }

    val widthPx: Int = PrintMath.pixels(frame.frameWidthMm, dpi)
    val heightPx: Int = PrintMath.pixels(frame.frameHeightMm, dpi)

    /** Mercator meters under one output pixel along each axis (equal up to the rounding of the pixel counts). */
    private val mercatorPerPixelX = (frame.mercatorBounds.maxX - frame.mercatorBounds.minX) / widthPx
    private val mercatorPerPixelY = (frame.mercatorBounds.maxY - frame.mercatorBounds.minY) / heightPx

    val pixels: Long get() = widthPx.toLong() * heightPx

    val bandCount: Int get() = (heightPx + bandHeightPx - 1) / bandHeightPx

    fun band(index: Int): Band {
        require(index in 0 until bandCount) { "band $index of $bandCount" }
        val y0 = index * bandHeightPx
        return Band(index, y0, minOf(bandHeightPx, heightPx - y0))
    }

    /** Pixel column (from the frame's left edge) of a Mercator x. */
    private fun colOf(mx: Double): Int = Math.round((mx - frame.mercatorBounds.minX) / mercatorPerPixelX).toInt()

    /** Pixel row (from the frame's top edge) of a Mercator y. */
    private fun rowOf(my: Double): Int = Math.round((frame.mercatorBounds.maxY - my) / mercatorPerPixelY).toInt()

    /** The tile range covering the whole frame at zoom [z] (empty ranges when the frame is off the map). */
    fun tileRange(z: Int): Pair<IntRange, IntRange> = tileRange(z, frame.mercatorBounds.minY, frame.mercatorBounds.maxY)

    private fun tileRange(
        z: Int,
        minY: Double,
        maxY: Double,
    ): Pair<IntRange, IntRange> {
        val n = TileMath.tilesPerAxis(z)
        val size = 2 * TileMath.ORIGIN_SHIFT_M / n
        val b = frame.mercatorBounds
        val x0 = floor((b.minX + TileMath.ORIGIN_SHIFT_M) / size).toLong().coerceIn(0, n - 1).toInt()
        val x1 = ceil((b.maxX + TileMath.ORIGIN_SHIFT_M) / size).toLong().minus(1).coerceIn(0, n - 1).toInt()
        val y0 = floor((TileMath.ORIGIN_SHIFT_M - maxY) / size).toLong().coerceIn(0, n - 1).toInt()
        val y1 = ceil((TileMath.ORIGIN_SHIFT_M - minY) / size).toLong().minus(1).coerceIn(0, n - 1).toInt()
        return x0..x1 to y0..y1
    }

    /** Every tile the page needs at zoom [z]. */
    fun tiles(z: Int): List<TileId> {
        val (xs, ys) = tileRange(z)
        return ys.flatMap { y -> xs.map { x -> TileId(z, x, y) } }
    }

    fun tileCount(z: Int): Long {
        val (xs, ys) = tileRange(z)
        return (xs.last - xs.first + 1L) * (ys.last - ys.first + 1L)
    }

    /** The tiles of zoom [z] that intersect [band], placed in the band's bitmap. */
    fun placements(
        z: Int,
        band: Band,
    ): List<TilePlacement> {
        val b = frame.mercatorBounds
        val top = b.maxY - band.y0 * mercatorPerPixelY
        val bottom = b.maxY - (band.y0 + band.heightPx) * mercatorPerPixelY
        val (xs, ys) = tileRange(z, bottom, top)
        val out = ArrayList<TilePlacement>((xs.last - xs.first + 1) * (ys.last - ys.first + 1))
        for (y in ys) {
            for (x in xs) {
                val m = TileMath.boundsMercator(x, y, z)
                out +=
                    TilePlacement(
                        TileId(z, x, y),
                        left = colOf(m.minX),
                        top = rowOf(m.maxY) - band.y0,
                        right = colOf(m.maxX),
                        bottom = rowOf(m.minY) - band.y0,
                    )
            }
        }
        return out
    }

    /** Column and row of the tile containing the frame centre, mostly for tests and diagnostics. */
    fun centerTile(z: Int): TileCoord = TileMath.tileAt(frame.centerLon, frame.centerLat, z)

    companion object {
        const val MAX_BAND_HEIGHT = 512
        const val MIN_BAND_HEIGHT = 16

        /** The pixels of a band at most: 512 rows of a sheet 8 192 px wide, a 16 MB bitmap however wide the sheet. */
        const val MAX_BAND_PIXELS = MAX_BAND_HEIGHT * 8_192L

        /** Rows of a band of a page [widthPx] wide: [MAX_BAND_HEIGHT] up to 8 192 px, fewer beyond. */
        fun bandHeightFor(widthPx: Int): Int =
            (MAX_BAND_PIXELS / maxOf(widthPx, 1)).coerceIn(MIN_BAND_HEIGHT.toLong(), MAX_BAND_HEIGHT.toLong()).toInt()
    }
}
