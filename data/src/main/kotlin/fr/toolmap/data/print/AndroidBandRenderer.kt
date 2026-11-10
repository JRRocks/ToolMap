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
import fr.toolmap.core.layers.Blend
import fr.toolmap.core.layers.LayerConfig
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
        val width = band.widthPx
        val bitmap = Bitmap.createBitmap(width, band.heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        var missing = 0
        page.layers.layers.forEachIndexed { index, layer ->
            val placements = page.raster.placements(layer.tileZoom, band)
            val fetched = fetchAll(layer, placements)
            // A blended layer (P26.5) is drawn apart, then mixed with what is under it as the map tiles are.
            val blended = layer.layer.isBlended
            val own = if (blended) Bitmap.createBitmap(width, band.heightPx, Bitmap.Config.ARGB_8888) else bitmap
            val target = if (blended) Canvas(own) else canvas
            val paint = paintOf(layer, opaque = blended)
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
                            target.drawBitmap(tile, null, dst, paint)
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
            if (blended) {
                mix(bitmap, own, layer.layer)
                own.recycle()
            }
        }
        val out = ByteArrayOutputStream(maxOf(MIN_BUFFER, width * band.heightPx / COMPRESSION_GUESS))
        bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
        bitmap.recycle()
        return RenderedBand(PdfImage.jpeg(width, band.heightPx, out.toByteArray()), missing)
    }

    /**
     * The tiles of [placements], read in parallel (never more at once than the source's server policy allows).
     *
     * The few tiles that could not be read are asked once more: on a phone network a connection goes silent now and
     * then, its tiles wait for nothing until their time runs out, and a new connection gets them at once. When most of
     * the strip failed there is no network to speak of (or the map is not stored and must not be fetched): asking again
     * would only double the wait.
     */
    private fun fetchAll(
        layer: PrintLayer,
        placements: List<TilePlacement>,
    ): Map<TileId, TileFetch> {
        if (placements.isEmpty()) return emptyMap()
        val tiles = placements.map { it.tile }.distinct()
        val first = fetchPass(layer, tiles)
        val failed = tiles.filter { first[it] is TileFetch.Unavailable }
        if (failed.isEmpty() || failed.size * RETRY_SHARE > tiles.size) return first
        return first + fetchPass(layer, failed)
    }

    private fun fetchPass(
        layer: PrintLayer,
        tiles: List<TileId>,
    ): Map<TileId, TileFetch> {
        val width = minOf(MAX_FETCH_THREADS, BulkPolicy.limitsFor(layer.source).maxThreads).coerceAtLeast(1)
        val gate = Semaphore(width)
        val tasks =
            tiles.map { tile ->
                Callable {
                    gate.acquire()
                    try {
                        tile to fetch(layer, tile)
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

    /** The paint of [layer]: its colours, and its opacity unless it is [opaque] (a blended layer gets it in [mix]). */
    private fun paintOf(
        layer: PrintLayer,
        opaque: Boolean = false,
    ): Paint =
        Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            alpha = Math.round((if (opaque) 1f else layer.layer.opacity.coerceIn(0f, 1f)) * MAX_ALPHA)
            RasterAdjust.colorMatrix(layer.layer)?.let { colorFilter = ColorMatrixColorFilter(ColorMatrix(it)) }
        }

    /**
     * Draws [own], a blended layer drawn alone, over [below] with the tint, blend mode and opacity of [layer]: the same
     * formulas as the tiles of the map (`BitmapTileBlender`, ADR-111).
     */
    private fun mix(
        below: Bitmap,
        own: Bitmap,
        layer: LayerConfig,
    ) {
        val width = below.width
        val height = below.height
        val under = IntArray(width * height)
        val above = IntArray(width * height)
        below.getPixels(under, 0, width, 0, 0, width, height)
        own.getPixels(above, 0, width, 0, 0, width, height)
        layer.tint?.let { Blend.tint(above, it, layer.tintStrength) }
        Blend.composite(under, above, layer.blend, layer.opacity)
        below.setPixels(under, 0, width, 0, 0, width, height)
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

        /** Failed tiles are asked again when they are at most one in [RETRY_SHARE] of the strip. */
        private const val RETRY_SHARE = 2
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
