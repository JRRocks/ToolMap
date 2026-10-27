package fr.toolmap.data.tiles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import fr.toolmap.core.tiles.TileRecipe
import java.io.ByteArrayOutputStream

/**
 * Draws a tile that no server serves as such (ADR-031): part of a coarser tile enlarged, or finer tiles shrunk
 * together, following a [TileRecipe] of [fr.toolmap.core.tiles.TileResolver]. The result is never stored: it is
 * rebuilt from the stored tiles whenever MapLibre asks for it.
 */
fun interface TileComposer {
    /**
     * The image of [recipe] (PNG bytes), or `null` when none of its tiles is available. [leaf] gives the bytes of
     * a real tile, or `null` when it is missing.
     */
    fun compose(
        recipe: TileRecipe,
        leaf: (TileRecipe.Direct) -> ByteArray?,
    ): ByteArray?
}

/** [TileComposer] on Android bitmaps; missing parts stay transparent. */
class BitmapTileComposer(
    private val tileSize: Int = DEFAULT_TILE_SIZE,
) : TileComposer {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    override fun compose(
        recipe: TileRecipe,
        leaf: (TileRecipe.Direct) -> ByteArray?,
    ): ByteArray? {
        val bitmap = render(recipe, tileSize, leaf) ?: return null
        return try {
            ByteArrayOutputStream(ENCODE_BUFFER).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** [recipe] as a [size] × [size] bitmap, or `null` when nothing of it is available. */
    private fun render(
        recipe: TileRecipe,
        size: Int,
        leaf: (TileRecipe.Direct) -> ByteArray?,
    ): Bitmap? =
        when (recipe) {
            TileRecipe.None -> {
                null
            }

            is TileRecipe.Direct -> {
                leaf(recipe)?.let { decode(it, size) }
            }

            is TileRecipe.Enlarge -> {
                // Only the part of the coarse tile that is enlarged matters: rendered at full size, then cropped.
                render(recipe.from, tileSize, leaf)?.let { coarse -> crop(coarse, recipe, size) }
            }

            is TileRecipe.Shrink -> {
                shrink(recipe, size, leaf)
            }
        }

    private fun crop(
        coarse: Bitmap,
        recipe: TileRecipe.Enlarge,
        size: Int,
    ): Bitmap {
        val side = 1 shl recipe.depth
        val part = (coarse.width / side).coerceAtLeast(1)
        val left = (recipe.col * coarse.width / side).coerceAtMost(coarse.width - part)
        val top = (recipe.row * coarse.height / side).coerceAtMost(coarse.height - part)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(coarse, Rect(left, top, left + part, top + part), Rect(0, 0, size, size), paint)
        coarse.recycle()
        return out
    }

    private fun shrink(
        recipe: TileRecipe.Shrink,
        size: Int,
        leaf: (TileRecipe.Direct) -> ByteArray?,
    ): Bitmap? {
        val side = 1 shl recipe.depth
        val cell = (size / side).coerceAtLeast(1)
        var out: Bitmap? = null
        recipe.parts.forEachIndexed { index, part ->
            val image = render(part, cell, leaf) ?: return@forEachIndexed
            val target = out ?: Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { out = it }
            val col = index % side
            val row = index / side
            val dst = Rect(col * size / side, row * size / side, (col + 1) * size / side, (row + 1) * size / side)
            Canvas(target).drawBitmap(image, null, dst, paint)
            image.recycle()
        }
        return out
    }

    /** Decodes [bytes] for display at [size] pixels, subsampled when that is much smaller than the tile. */
    private fun decode(
        bytes: ByteArray,
        size: Int,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && sample < MAX_SAMPLE) sample *= 2
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private companion object {
        const val DEFAULT_TILE_SIZE = 256
        const val PNG_QUALITY = 100
        const val ENCODE_BUFFER = 64 * 1024
        const val MAX_SAMPLE = 64
    }
}
