package fr.toolmap.data.tiles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import fr.toolmap.core.elevation.DemWindow
import fr.toolmap.core.elevation.ElevationMask
import fr.toolmap.core.elevation.MaskRaster
import fr.toolmap.core.elevation.TerrariumTile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** A decoded elevation tile: [size] × [size] metres, row after row. */
class DemGrid(
    val size: Int,
    val elevations: FloatArray,
)

/**
 * Draws a layer's tiles in an elevation band (P20): the pixels outside the band become transparent. Decoded elevation
 * tiles are kept in memory, so a new band only redraws the map tiles.
 */
interface ElevationMasker {
    /**
     * The elevation tile [key] decoded, from memory or from the bytes [load] gives (`null`: not available). Requests
     * for the same tile at the same time share one [load].
     */
    fun dem(
        key: String,
        load: () -> ByteArray?,
    ): DemGrid?

    /** [tile] (PNG or JPEG bytes) with its pixels outside [mask] made transparent, as a PNG; `null` if undecodable. */
    fun mask(
        tile: ByteArray,
        dem: DemGrid,
        window: DemWindow,
        mask: ElevationMask,
    ): ByteArray?
}

/** [ElevationMasker] on Android bitmaps. [log] receives one timing line per masked tile (measures only). */
class BitmapElevationMasker(
    cachedTiles: Int = CACHED_DEM_TILES,
    private val log: ((String) -> Unit)? = null,
) : ElevationMasker {
    private val cache = LruCache<String, DemGrid>(cachedTiles)
    private val loading = ConcurrentHashMap<String, FutureTask<DemGrid?>>()

    override fun dem(
        key: String,
        load: () -> ByteArray?,
    ): DemGrid? {
        cache.get(key)?.let { return it }
        while (true) {
            val mine = FutureTask { load()?.let(::decodeDem)?.also { cache.put(key, it) } }
            val running = loading.putIfAbsent(key, mine)
            if (running == null) {
                try {
                    mine.run()
                    return result(mine)
                } finally {
                    loading.remove(key, mine)
                }
            }
            try {
                return running.get()
            } catch (_: ExecutionException) {
                // The request that was loading it failed (cancelled by MapLibre): load it for this one.
                continue
            }
        }
    }

    override fun mask(
        tile: ByteArray,
        dem: DemGrid,
        window: DemWindow,
        mask: ElevationMask,
    ): ByteArray? {
        val start = SystemClock.elapsedRealtimeNanos()
        val options =
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
        val bitmap = BitmapFactory.decodeByteArray(tile, 0, tile.size, options) ?: return null
        val decoded = SystemClock.elapsedRealtimeNanos()
        try {
            if (bitmap.width != bitmap.height) return null
            val size = bitmap.width
            val alphas = MaskRaster.alphas(MaskRaster.elevations(dem.elevations, dem.size, window, size), size, mask)
            val computed = SystemClock.elapsedRealtimeNanos()
            // Each pixel's own opacity scaled by the mask's (colours unpremultiplied, as getPixels gives them).
            val pixels = IntArray(size * size)
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
            for (i in pixels.indices) {
                val p = pixels[i]
                val a = (p ushr ALPHA) * (alphas[i].toInt() and BYTE) / BYTE
                pixels[i] = (p and RGB) or (a shl ALPHA)
            }
            // A JPEG tile has no alpha channel: give it one before cutting it out.
            bitmap.setHasAlpha(true)
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            val drawn = SystemClock.elapsedRealtimeNanos()
            val png =
                ByteArrayOutputStream(ENCODE_BUFFER).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                    out.toByteArray()
                }
            log?.invoke(
                "masked size=$size decode_ms=${ms(decoded - start)} mask_ms=${ms(computed - decoded)} " +
                    "draw_ms=${ms(drawn - computed)} encode_ms=${ms(SystemClock.elapsedRealtimeNanos() - drawn)} " +
                    "bytes=${png.size}",
            )
            return png
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeDem(bytes: ByteArray): DemGrid? {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        try {
            if (bitmap.width != bitmap.height) return null
            val size = bitmap.width
            val pixels = IntArray(size * size)
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
            val elevations =
                FloatArray(pixels.size) { i ->
                    val p = pixels[i]
                    TerrariumTile
                        .decodeElevation((p shr RED) and BYTE, (p shr GREEN) and BYTE, p and BYTE)
                        .toFloat()
                }
            return DemGrid(size, elevations)
        } finally {
            bitmap.recycle()
        }
    }

    /** The task's result, its own failure (a cancelled call) thrown again as it was. */
    private fun result(task: FutureTask<DemGrid?>): DemGrid? =
        try {
            task.get()
        } catch (e: ExecutionException) {
            when (val cause = e.cause) {
                is IOException -> throw cause
                is RuntimeException -> throw cause
                else -> throw IllegalStateException(cause)
            }
        }

    private fun ms(nanos: Long): String = "%.1f".format(java.util.Locale.ROOT, nanos / NANOS_PER_MILLI)

    private companion object {
        /** About 256 KB each: a screen at zoom 13 or finer reads a few of them, coarser zooms one per tile. */
        const val CACHED_DEM_TILES = 64
        const val PNG_QUALITY = 100
        const val ENCODE_BUFFER = 64 * 1024
        const val ALPHA = 24
        const val RED = 16
        const val GREEN = 8
        const val BYTE = 0xFF
        const val RGB = 0x00FFFFFF
        const val NANOS_PER_MILLI = 1_000_000.0
    }
}
