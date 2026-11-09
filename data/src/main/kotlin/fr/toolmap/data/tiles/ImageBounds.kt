package fr.toolmap.data.tiles

import android.graphics.BitmapFactory

/** The size an image announces in its first bytes, read without decoding it. */
object ImageBounds {
    /** Width and height in pixels; `null` when [bytes] are no image Android reads. */
    fun of(bytes: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }
}
