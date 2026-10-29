package fr.toolmap.core.print

import fr.toolmap.core.tiles.TileCoord
import fr.toolmap.core.tiles.TileId
import fr.toolmap.core.tiles.TileMath
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A piece of the output image: [heightPx] rows from row [y0], [widthPx] columns from column [x0]. A band of the raster
 * spans the whole width; a page wider than [PrintRaster.MAX_PIECE_WIDTH] has its bands cut into pieces side by side.
 */
data class Band(
    val index: Int,
    val y0: Int,
    val heightPx: Int,
    val x0: Int = 0,
    val widthPx: Int,
)

/** Where a tile lands in the bitmap of a band or piece, in whole pixels (shared edges are computed once: no seam). */
data class TilePlacement(
    val tile: TileId,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * The output image of a page at [dpi]: its size, its cut into bands of [bandHeightPx] rows and, on a wide page, into
 * pieces of [MAX_PIECE_WIDTH] columns at most (memory stays bounded whatever the paper and the resolution, and no image
 * is wider than JPEG allows), and which tiles fall in each piece.
 */
class PrintRaster(
    val frame: FrameProjection,
    val dpi: Int,
    val bandHeightPx: Int = MAX_BAND_HEIGHT,
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
        return Band(index, y0, minOf(bandHeightPx, heightPx - y0), widthPx = widthPx)
    }

    /** Pieces side by side in a band: one on a page up to [MAX_PIECE_WIDTH] wide. */
    val pieceCount: Int get() = (widthPx + MAX_PIECE_WIDTH - 1) / MAX_PIECE_WIDTH

    /** [band] cut into [pieceCount] pieces from left to right, each [MAX_PIECE_WIDTH] wide but the last. */
    fun pieces(band: Band): List<Band> =
        List(pieceCount) { k ->
            val x0 = k * MAX_PIECE_WIDTH
            band.copy(x0 = x0, widthPx = minOf(MAX_PIECE_WIDTH, widthPx - x0))
        }

    /** Pixel column (from the frame's left edge) of a Mercator x. */
    private fun colOf(mx: Double): Int = Math.round((mx - frame.mercatorBounds.minX) / mercatorPerPixelX).toInt()

    /** Pixel row (from the frame's top edge) of a Mercator y. */
    private fun rowOf(my: Double): Int = Math.round((frame.mercatorBounds.maxY - my) / mercatorPerPixelY).toInt()

    /** The tile range covering the whole frame at zoom [z] (empty ranges when the frame is off the map). */
    fun tileRange(z: Int): Pair<IntRange, IntRange> {
        val b = frame.mercatorBounds
        return tileRange(z, b.minX, b.maxX, b.minY, b.maxY)
    }

    private fun tileRange(
        z: Int,
        minX: Double,
        maxX: Double,
        minY: Double,
        maxY: Double,
    ): Pair<IntRange, IntRange> {
        val n = TileMath.tilesPerAxis(z)
        val size = 2 * TileMath.ORIGIN_SHIFT_M / n
        val x0 = floor((minX + TileMath.ORIGIN_SHIFT_M) / size).toLong().coerceIn(0, n - 1).toInt()
        val x1 = ceil((maxX + TileMath.ORIGIN_SHIFT_M) / size).toLong().minus(1).coerceIn(0, n - 1).toInt()
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

    /** The tiles of zoom [z] that intersect [band] (a band or a piece of one), placed in its bitmap. */
    fun placements(
        z: Int,
        band: Band,
    ): List<TilePlacement> {
        val b = frame.mercatorBounds
        val top = b.maxY - band.y0 * mercatorPerPixelY
        val bottom = b.maxY - (band.y0 + band.heightPx) * mercatorPerPixelY
        val left = b.minX + band.x0 * mercatorPerPixelX
        val right = b.minX + (band.x0 + band.widthPx) * mercatorPerPixelX
        val (xs, ys) = tileRange(z, left, right, bottom, top)
        val out = ArrayList<TilePlacement>((xs.last - xs.first + 1) * (ys.last - ys.first + 1))
        for (y in ys) {
            for (x in xs) {
                val m = TileMath.boundsMercator(x, y, z)
                out +=
                    TilePlacement(
                        TileId(z, x, y),
                        left = colOf(m.minX) - band.x0,
                        top = rowOf(m.maxY) - band.y0,
                        right = colOf(m.maxX) - band.x0,
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

        /** The widest piece: with [MAX_BAND_HEIGHT] rows, a 16 MB bitmap at most whatever the page (ADR-097). */
        const val MAX_PIECE_WIDTH = 8_192
    }
}
