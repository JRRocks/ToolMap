package fr.toolmap.waypoints

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import fr.toolmap.core.route.RouteMarkers

/** White glyphs of the waypoint icons, drawn on the coloured disc of the map marker (P16.6). */
object WaypointGlyphs {
    /** Size of a glyph in map units; the bitmap is rendered four times larger for sharpness. */
    private const val SCALE = 4
    private const val LOGICAL_SIZE = 14

    /** Height of a letter on the ends of a computed route, in map units (P19). */
    private const val LETTER_SIZE = 11f

    fun render(
        context: Context,
        iconId: String,
    ): Bitmap? {
        if (iconId.startsWith(
                RouteMarkers.LETTER_PREFIX,
            )
        ) {
            return letter(iconId.removePrefix(RouteMarkers.LETTER_PREFIX))
        }
        val res = WaypointIconRes.drawables[iconId] ?: return null
        val drawable = ContextCompat.getDrawable(context, res)?.mutate() ?: return null
        drawable.setTint(Color.WHITE)
        val px = LOGICAL_SIZE * SCALE
        val bitmap = drawable.toBitmap(px, px)
        bitmap.density = SCALE * android.util.DisplayMetrics.DENSITY_DEFAULT
        return bitmap
    }

    /** A white bold letter (or number), centred, for the start and the end of a computed route. */
    private fun letter(text: String): Bitmap {
        val px = LOGICAL_SIZE * SCALE
        val bitmap = createBitmap(px, px)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                textSize = LETTER_SIZE * SCALE * (if (text.length > 1) 0.8f else 1f)
            }
        val y = px / 2f - (paint.descent() + paint.ascent()) / 2f
        Canvas(bitmap).drawText(text, px / 2f, y, paint)
        bitmap.density = SCALE * android.util.DisplayMetrics.DENSITY_DEFAULT
        return bitmap
    }
}
