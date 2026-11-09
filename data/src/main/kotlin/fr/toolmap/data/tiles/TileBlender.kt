package fr.toolmap.data.tiles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import fr.toolmap.core.compute.Compute
import fr.toolmap.core.layers.Blend
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.layers.RasterAdjust
import java.io.ByteArrayOutputStream

/**
 * Draws a blended layer's tile (P26): the tiles of the layers under it and its own, bottom first, each with its colours,
 * tint, opacity and blend mode ([fr.toolmap.core.layers.BlendRecipe]). Never stored: rebuilt from the stored tiles.
 */
fun interface TileBlender {
    /** The tile (PNG bytes) of [layers], bottom first with their image bytes (`null`: none here), or `null`. */
    fun blend(layers: List<Pair<LayerConfig, ByteArray?>>): ByteArray?
}

/** [TileBlender] on Android bitmaps, at the size of the top tile. [log] receives one timing line per tile. */
class BitmapTileBlender(
    private val log: ((String) -> Unit)? = null,
) : TileBlender {
    override fun blend(layers: List<Pair<LayerConfig, ByteArray?>>): ByteArray? {
        val start = SystemClock.elapsedRealtimeNanos()
        val top = layers.lastOrNull()?.second ?: return null
        val size = sizeOf(top) ?: return null
        val canvas = IntArray(size * size)
        var decodeNanos = 0L
        var blendNanos = 0L
        for ((part, bytes) in layers) {
            if (bytes == null) continue
            val t0 = SystemClock.elapsedRealtimeNanos()
            val pixels = pixels(bytes, size) ?: continue
            val t1 = SystemClock.elapsedRealtimeNanos()
            RasterAdjust.colorMatrix(part)?.let { Blend.adjust(pixels, it) }
            part.tint?.let { Blend.tint(pixels, it, part.tintStrength) }
            Blend.composite(canvas, pixels, part.blend, part.opacity)
            decodeNanos += t1 - t0
            blendNanos += SystemClock.elapsedRealtimeNanos() - t1
        }
        val drawn = SystemClock.elapsedRealtimeNanos()
        // The tile is shown and never stored: written fast by the native engine when it is there (ADR-119).
        val png = Compute.fastPng(canvas, size, size) ?: androidPng(canvas, size)
        log?.invoke(
            "blended size=$size parts=${layers.count { it.second != null }} decode_ms=${ms(decodeNanos)} " +
                "blend_ms=${ms(blendNanos)} encode_ms=${ms(SystemClock.elapsedRealtimeNanos() - drawn)} " +
                "total_ms=${ms(SystemClock.elapsedRealtimeNanos() - start)} bytes=${png.size}",
        )
        return png
    }

    private fun androidPng(
        pixels: IntArray,
        size: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        try {
            return ByteArrayOutputStream(ENCODE_BUFFER).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun sizeOf(bytes: ByteArray): Int? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth.takeIf { it > 0 && it == bounds.outHeight }
    }

    /** The pixels of [bytes] at [size] × [size] (scaled when the map's tiles have another size). */
    private fun pixels(
        bytes: ByteArray,
        size: Int,
    ): IntArray? {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val bitmap =
            if (decoded.width == size && decoded.height == size) {
                decoded
            } else {
                Bitmap.createScaledBitmap(decoded, size, size, true).also { decoded.recycle() }
            }
        try {
            val pixels = IntArray(size * size)
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
            return pixels
        } finally {
            bitmap.recycle()
        }
    }

    private fun ms(nanos: Long): String = "%.1f".format(java.util.Locale.ROOT, nanos / NANOS_PER_MILLI)

    private companion object {
        const val PNG_QUALITY = 100
        const val ENCODE_BUFFER = 64 * 1024
        const val NANOS_PER_MILLI = 1_000_000.0
    }
}
