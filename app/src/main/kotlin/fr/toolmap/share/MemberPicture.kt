package fr.toolmap.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import fr.toolmap.core.p2p.MemberLook
import java.io.ByteArrayOutputStream

/**
 * The member's own picture: any image the user picks is cut to its centre square, reduced to
 * [MemberLook.PICTURE_PX] pixels and saved as a JPEG of [MemberLook.MAX_PICTURE_BYTES] at most, so that it goes to the
 * whole group in one small message whatever its original size.
 */
internal object MemberPicture {
    private const val FIRST_QUALITY = 80
    private const val QUALITY_STEP = 10
    private const val LAST_QUALITY = 20

    /** The reduced picture of [uri]; `null` when it cannot be read as an image. */
    fun fromUri(
        context: Context,
        uri: Uri,
    ): ByteArray? =
        runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            // Decode at no more than about four times the final size: a 50-megapixel photo stays cheap.
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MemberLook.PICTURE_PX * 4) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded =
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            val rotation =
                resolver.openInputStream(uri)?.use { degrees(ExifInterface(it)) } ?: 0
            encode(square(decoded, rotation))
        }.getOrNull()

    private fun degrees(exif: ExifInterface): Int =
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    /** [source] cut to its centre square, turned upright by [rotation] degrees, at [MemberLook.PICTURE_PX] pixels. */
    fun square(
        source: Bitmap,
        rotation: Int = 0,
    ): Bitmap {
        val side = minOf(source.width, source.height)
        val scale = MemberLook.PICTURE_PX.toFloat() / side
        val matrix = Matrix().apply { postScale(scale, scale) }
        if (rotation != 0) matrix.postRotate(rotation.toFloat())
        val x = (source.width - side) / 2
        val y = (source.height - side) / 2
        return Bitmap.createBitmap(source, x, y, side, side, matrix, true)
    }

    /** [bitmap] as a JPEG under [MemberLook.MAX_PICTURE_BYTES], lowering the quality until it fits. */
    fun encode(bitmap: Bitmap): ByteArray? {
        var quality = FIRST_QUALITY
        while (quality >= LAST_QUALITY) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val bytes = out.toByteArray()
            if (bytes.size <= MemberLook.MAX_PICTURE_BYTES) return bytes
            quality -= QUALITY_STEP
        }
        return null
    }

    /** The picture received from a member; `null` when the bytes are not an image. */
    fun decode(bytes: ByteArray): Bitmap? =
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
}
