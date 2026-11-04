package fr.toolmap.sketch

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import fr.toolmap.core.sketch.SketchFill
import fr.toolmap.core.sketch.SketchFills
import fr.toolmap.core.sketch.XY
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The tiles of the fill patterns (P28.4), drawn once per pattern and colour from [SketchFills]: repeated by MapLibre
 * under a saved zone (their density makes one dp of the pattern one dp of the screen) and by the sketch tool under the
 * zone being placed.
 */
object SketchPatterns {
    private val cache = HashMap<String, Bitmap>()

    /** The name of the image in the style of the map. */
    fun id(
        fill: SketchFill,
        color: Int,
    ): String = String.format(Locale.ROOT, "tm-sketch-%s-%06X", fill.code, color and RGB)

    /** The tile of [fill] in [color] at the density of the screen; `null` for a fill without a pattern. */
    fun bitmap(
        fill: SketchFill,
        color: Int,
        density: Float = Resources.getSystem().displayMetrics.density,
    ): Bitmap? {
        val tile = SketchFills.tile(fill) ?: return null
        return synchronized(cache) {
            cache.getOrPut("${id(fill, color)}@$density") { draw(fill, color, tile, density) }
        }
    }

    private fun draw(
        fill: SketchFill,
        color: Int,
        tile: XY,
        density: Float,
    ): Bitmap {
        val w = max(2, (tile.x * density).roundToInt())
        val h = max(2, (tile.y * density).roundToInt())
        // One dp of the pattern, so that its marks repeat exactly over the width of the tile.
        val unit = w / tile.x
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val marks = SketchFills.marks(fill, XY(0.0, 0.0), XY(w.toDouble(), h.toDouble()), unit)
        val rgb = color and RGB
        if (marks.groundAlpha > 0) canvas.drawColor(withAlpha(rgb, marks.groundAlpha))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = withAlpha(rgb, fill.opacity) }
        if (marks.lines.isNotEmpty()) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = marks.lineWidth.toFloat()
            paint.strokeCap = Paint.Cap.BUTT
            paint.strokeJoin = Paint.Join.ROUND
            for (line in marks.lines) {
                val path = Path()
                path.moveTo(line[0].x.toFloat(), line[0].y.toFloat())
                for (i in 1 until line.size) path.lineTo(line[i].x.toFloat(), line[i].y.toFloat())
                canvas.drawPath(path, paint)
            }
        }
        if (marks.dots.isNotEmpty()) {
            paint.style = Paint.Style.FILL
            for (d in marks.dots) canvas.drawCircle(d.x.toFloat(), d.y.toFloat(), marks.dotRadius.toFloat(), paint)
        }
        return bitmap
    }

    private fun withAlpha(
        rgb: Int,
        opacity: Double,
    ): Int = ((opacity * OPAQUE).roundToInt().coerceIn(0, OPAQUE) shl ALPHA_SHIFT) or rgb

    private const val RGB = 0xFFFFFF
    private const val OPAQUE = 255
    private const val ALPHA_SHIFT = 24
}
