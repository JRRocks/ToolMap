package fr.toolmap.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import fr.toolmap.core.p2p.MemberLook
import fr.toolmap.core.p2p.MemberPalette
import fr.toolmap.share.MemberPicture
import fr.toolmap.share.memberIconRes

/**
 * The map marker of a member, like a game token on a minimap: a ring of its colour around a contrasting disc, with
 * its silhouette or initials in its colour, or its own picture inside the ring.
 */
internal object MemberMarkerBitmap {
    private const val SIZE_DP = 32f
    private const val RING_DP = 3f
    private const val ICON_SHARE = 0.56f
    private const val TEXT_SHARE = 0.36f
    private const val SHADOW = 0x66000000

    fun draw(
        context: Context,
        look: Int,
        name: String,
        picture: ByteArray? = null,
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val size = (SIZE_DP * density).toInt()
        val ring = RING_DP * density
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val c = size / 2f
        val argb = MemberLook.argb(look)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = SHADOW
        canvas.drawCircle(c, c, c, paint)
        paint.color = argb
        canvas.drawCircle(c, c, c - density * 0.75f, paint)
        paint.color = MemberLook.backdrop(argb)
        val inner = c - ring
        canvas.drawCircle(c, c, inner, paint)
        val icon = MemberLook.icon(look)
        val photo = picture?.takeIf { icon == MemberLook.PICTURE }?.let(MemberPicture::decode)
        val silhouette = memberIconRes(icon)?.let { ContextCompat.getDrawable(context, it)?.mutate() }
        when {
            photo != null -> {
                val shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val scale = inner * 2 / minOf(photo.width, photo.height)
                shader.setLocalMatrix(
                    Matrix().apply {
                        postScale(scale, scale)
                        postTranslate(c - photo.width * scale / 2, c - photo.height * scale / 2)
                    },
                )
                canvas.drawCircle(c, c, inner, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
            }

            silhouette != null -> {
                val half = (size * ICON_SHARE / 2).toInt()
                silhouette.setBounds(c.toInt() - half, c.toInt() - half, c.toInt() + half, c.toInt() + half)
                silhouette.setTint(argb)
                silhouette.draw(canvas)
            }

            else -> {
                paint.color = argb
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = size * TEXT_SHARE
                val baseline = c - (paint.descent() + paint.ascent()) / 2
                canvas.drawText(MemberPalette.initials(name), c, baseline, paint)
            }
        }
        return bitmap
    }
}
