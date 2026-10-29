package fr.toolmap.data.print

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import fr.toolmap.core.elevation.DemWindow
import fr.toolmap.core.elevation.ElevationMask
import fr.toolmap.core.elevation.MaskRaster
import fr.toolmap.core.layers.RasterAdjust
import fr.toolmap.core.pdf.PdfImage
import fr.toolmap.core.print.Band
import fr.toolmap.core.print.BandRenderer
import fr.toolmap.core.print.PrintLayer
import fr.toolmap.core.print.PrintPagePlan
import fr.toolmap.core.print.RenderedBand
import fr.toolmap.core.print.TilePlacement
import fr.toolmap.core.tiles.TileId
import fr.toolmap.data.download.BulkPolicy
import fr.toolmap.data.tiles.BitmapElevationMasker
import fr.toolmap.data.tiles.ElevationMasker
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * Draws a strip of a printed map with Android's graphics: the tiles of each layer of the page plan are read (several at
 * a time), cut to the layer's elevation band if it has one, decoded and drawn at their exact pixel place with the
 * layer's opacity and colour settings; tiles that could not be read are hatched. The strip comes out as a JPEG for the
 * PDF.
 */
class AndroidBandRenderer(
    private val fetcher: PrintTileFetcher,
    private val jpegQuality: Int = DEFAULT_JPEG_QUALITY,
    private val masker: ElevationMasker = BitmapElevationMasker(),
) : BandRenderer,
    Closeable {
    private val pool: ExecutorService by lazy {
        val counter = AtomicInteger()
        Executors.newFixedThreadPool(MAX_FETCH_THREADS) { r ->
            Thread(r, "print-tiles-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
    }

    override fun close() {
        pool.shutdownNow()
    }

    override fun render(
        page: PrintPagePlan,
        band: Band,
    ): RenderedBand {
        val width = page.raster.widthPx
        val bitmap = Bitmap.createBitmap(width, band.heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        var missing = 0
        page.layers.layers.forEachIndexed { index, layer ->
            val placements = page.raster.placements(layer.tileZoom, band)
            val fetched = fetchAll(layer, placements)
            val paint = paintOf(layer)
            for (placement in placements) {
                val result = fetched[placement.tile]
                val dst = Rect(placement.left, placement.top, placement.right, placement.bottom)
                when (result) {
                    is TileFetch.Found -> {
                        val tile = BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)
                        if (tile == null) {
                            missing++
                            if (index == 0) hatch(canvas, dst, page.dpi)
                        } else {
                            canvas.drawBitmap(tile, null, dst, paint)
                            tile.recycle()
                        }
                    }

                    // The source has no tile there: nothing to draw, nothing missing.
                    TileFetch.Empty -> {}

                    else -> {
                        missing++
                        if (index == 0) hatch(canvas, dst, page.dpi)
                    }
                }
            }
        }
        val out = ByteArrayOutputStream(maxOf(MIN_BUFFER, width * band.heightPx / COMPRESSION_GUESS))
        bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
        bitmap.recycle()
        return RenderedBand(PdfImage.jpeg(width, band.heightPx, out.toByteArray()), missing)
    }

    /** The tiles of [placements], read in parallel (never more at once than the source's server policy allows). */
    private fun fetchAll(
        layer: PrintLayer,
        placements: List<TilePlacement>,
    ): Map<TileId, TileFetch> {
        if (placements.isEmpty()) return emptyMap()
        val width = minOf(MAX_FETCH_THREADS, BulkPolicy.limitsFor(layer.source).maxThreads).coerceAtLeast(1)
        val gate = Semaphore(width)
        val tasks =
            placements.map { p ->
                Callable {
                    gate.acquire()
                    try {
                        p.tile to fetch(layer, p.tile)
                    } finally {
                        gate.release()
                    }
                }
            }
        return pool.invokeAll(tasks).associate { it.get() }
    }

    /**
     * A tile of [layer] as the screen draws it (P20, `TileInterceptor.masked`): in an elevation band, nothing where the
     * band covers none of it, the plain tile where it covers all of it, else the tile cut out along the contour.
     * Without the elevations (offline and not stored) the tile is missing.
     */
    private fun fetch(
        layer: PrintLayer,
        tile: TileId,
    ): TileFetch {
        val band = layer.band ?: return fetcher.fetch(layer.source, tile)
        val window = DemWindow.of(tile.z, tile.x, tile.y)
        val dem =
            masker.dem("${window.demZ}/${window.demX}/${window.demY}") {
                (fetcher.fetch(band.dem, TileId(window.demZ, window.demX, window.demY)) as? TileFetch.Found)?.bytes
            } ?: return TileFetch.Unavailable
        val (low, high) = MaskRaster.range(dem.elevations, dem.size, window)
        return when (band.mask.coverage(low, high)) {
            ElevationMask.Coverage.NONE -> {
                TileFetch.Empty
            }

            ElevationMask.Coverage.ALL -> {
                fetcher.fetch(layer.source, tile)
            }

            ElevationMask.Coverage.PART -> {
                when (val plain = fetcher.fetch(layer.source, tile)) {
                    is TileFetch.Found -> {
                        masker.mask(plain.bytes, dem, window, band.mask)?.let(TileFetch::Found) ?: TileFetch.Unavailable
                    }

                    else -> {
                        plain
                    }
                }
            }
        }
    }

    private fun paintOf(layer: PrintLayer): Paint =
        Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            alpha = Math.round(layer.layer.opacity.coerceIn(0f, 1f) * MAX_ALPHA)
            RasterAdjust.colorMatrix(layer.layer)?.let { colorFilter = ColorMatrixColorFilter(ColorMatrix(it)) }
        }

    /** A light grey patch with diagonal lines about every 1.6 mm, so a missing tile is plain on the paper. */
    private fun hatch(
        canvas: Canvas,
        area: Rect,
        dpi: Int,
    ) {
        val fill = Paint().apply { color = Color.rgb(HATCH_FILL, HATCH_FILL, HATCH_FILL) }
        val line =
            Paint().apply {
                color = Color.rgb(HATCH_LINE, HATCH_LINE, HATCH_LINE)
                strokeWidth = maxOf(1f, dpi / MM_PER_INCH * HATCH_WIDTH_MM)
                isAntiAlias = true
            }
        val spacing = maxOf(MIN_HATCH_PX, Math.round(dpi / MM_PER_INCH * HATCH_SPACING_MM))
        canvas.save()
        canvas.clipRect(area)
        canvas.drawRect(area, fill)
        var offset = -area.height()
        while (offset < area.width()) {
            canvas.drawLine(
                (area.left + offset).toFloat(),
                area.bottom.toFloat(),
                (area.left + offset + area.height()).toFloat(),
                area.top.toFloat(),
                line,
            )
            offset += spacing
        }
        canvas.restore()
    }

    companion object {
        const val DEFAULT_JPEG_QUALITY = 88
        private const val MAX_FETCH_THREADS = 6
        private const val MAX_ALPHA = 255f
        private const val MIN_BUFFER = 64 * 1024
        private const val COMPRESSION_GUESS = 8
        private const val HATCH_FILL = 236
        private const val HATCH_LINE = 170
        private const val HATCH_WIDTH_MM = 0.12f
        private const val HATCH_SPACING_MM = 1.6f
        private const val MIN_HATCH_PX = 4
        private const val MM_PER_INCH = 25.4f
    }
}
