package fr.toolmap.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import fr.toolmap.core.geo.GridFigureLine
import fr.toolmap.core.state.CameraState

/**
 * Where the figures of the grid may go, in pixels of the map view: [left] is the column of the northings, [bottom] the
 * line the eastings stand on (the top of the bar), [top] the bottom of the boxes. A figure that runs into one of
 * [blocked] (the buttons over the map) is left out.
 */
class GridFigureFrame(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val blocked: List<Rect>,
)

/**
 * The figures of the UTM grid at the edge of the screen (variant A of `docs/previews/p24-utm-reperes.html`, validated
 * on 2026-10-28): the value in km of each kilometre line where it leaves the screen, the last two figures larger, as
 * on a paper map; the eastings at the bottom, just right of their line, the northings on the left, just above theirs.
 * A figure that would run into another, a box or a button is left out; the camera is a key only, as for the pages of
 * a print: the lines are projected again when the map moves.
 */
@Composable
fun GridFigures(
    camera: CameraState,
    lines: List<GridFigureLine>,
    project: (lat: Double, lon: Double) -> Offset?,
    frame: GridFigureFrame,
) {
    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer()
    val texts =
        remember(lines, measurer, density) {
            lines.associateWith { line ->
                val (small, big) = line.figures
                val text =
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontSize = SMALL_SIZE)) { append(small) }
                        withStyle(SpanStyle(fontSize = BIG_SIZE, fontWeight = FontWeight.Bold)) { append(big) }
                    }
                measurer.measure(text, TextStyle(color = TEXT_COLOR, fontSize = SMALL_SIZE))
            }
        }
    val screens =
        remember(camera, lines) {
            lines.associateWith { l ->
                l.points.mapNotNull { project(it.lat, it.lon) }
            }
        }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val placed = ArrayList<Rect>()
        val gap = GAP_DP * density
        val clear = CLEAR_DP * density

        fun place(
            text: TextLayoutResult,
            topLeft: Offset,
        ) {
            val box = Rect(topLeft, Size(text.size.width.toFloat(), text.size.height.toFloat()))
            val inside =
                box.left >= frame.left - 1f && box.right <= frame.right && box.top >= frame.top &&
                    box.bottom <= frame.bottom
            val free =
                (placed + frame.blocked).none { !it.isEmpty && it.inflate(clear).overlaps(box) }
            if (!inside || !free) return
            placed += box
            drawText(
                text,
                color = HALO_COLOR,
                topLeft = topLeft,
                drawStyle = Stroke(HALO_DP * density, join = StrokeJoin.Round),
            )
            // Fill again explicitly: the stroke of the halo would otherwise stay on the paint.
            drawText(text, color = TEXT_COLOR, topLeft = topLeft, drawStyle = Fill)
        }
        // The northings first, on the left: the eastings give way in the bottom left corner.
        for (line in lines.filter { !it.easting }) {
            val text = texts.getValue(line)
            val y = crossing(screens.getValue(line), frame.left, vertical = true) ?: continue
            place(text, Offset(frame.left, y - gap - text.firstBaseline))
        }
        val row = frame.bottom - ROW_DP * density
        for (line in lines.filter { it.easting }) {
            val text = texts.getValue(line)
            val x = crossing(screens.getValue(line), row, vertical = false) ?: continue
            place(text, Offset(x + gap, row - text.firstBaseline))
        }
    }
}

/**
 * Where the line [points] crosses the vertical x = [at] (its y) or the horizontal y = [at] (its x), the first
 * crossing along it; `null` when it does not cross.
 */
internal fun crossing(
    points: List<Offset>,
    at: Float,
    vertical: Boolean,
): Float? {
    for (i in 0 until points.lastIndex) {
        val a = points[i]
        val b = points[i + 1]
        val da = (if (vertical) a.x else a.y) - at
        val db = (if (vertical) b.x else b.y) - at
        if (da == 0f) return if (vertical) a.y else a.x
        if (da * db < 0f) {
            val t = da / (da - db)
            return if (vertical) a.y + (b.y - a.y) * t else a.x + (b.x - a.x) * t
        }
    }
    return null
}

// As in the validated preview: dark grey with a white halo, 11 and 15 px on a phone 300 px wide (360 dp).
private val TEXT_COLOR = Color(0xFF1B2B31)
private val HALO_COLOR = Color.White
private val SMALL_SIZE = 13.sp
private val BIG_SIZE = 18.sp
private const val HALO_DP = 3f

/** Between a line and its figure. */
private const val GAP_DP = 3f

/** The baseline of the eastings above the bar. */
private const val ROW_DP = 8f

/** Around a figure, a box or a button, so that two of them never touch. */
private const val CLEAR_DP = 3f
