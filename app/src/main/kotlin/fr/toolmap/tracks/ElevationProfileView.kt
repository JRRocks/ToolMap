package fr.toolmap.tracks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.track.ElevationProfile
import fr.toolmap.core.track.GradeBand
import fr.toolmap.ui.LocalFormats

/** Colour of a stretch of the profile from its steepness, from easy (blue-green) to very steep (red). */
fun gradeColor(band: GradeBand): Color =
    when (band) {
        GradeBand.FLAT -> Color(0xFF26A69A)
        GradeBand.GENTLE -> Color(0xFF66BB6A)
        GradeBand.MODERATE -> Color(0xFFFFCA28)
        GradeBand.STEEP -> Color(0xFFFB8C00)
        GradeBand.VERY_STEEP -> Color(0xFFE53935)
    }

/**
 * Altitude against distance, coloured by slope. Touching or dragging moves a cursor, reported to the caller so that
 * the map can show the matching point; [cursorMeters] is where the cursor currently stands.
 */
@Composable
fun ElevationProfileView(
    profile: ElevationProfile,
    cursorMeters: Double?,
    onCursor: (Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fmt = LocalFormats.current
    val minEle = profile.minElevation ?: return
    val maxEle = profile.maxElevation ?: return
    val span = (maxEle - minEle).coerceAtLeast(MIN_SPAN_METERS)
    val total = profile.totalDistanceMeters.coerceAtLeast(1.0)
    var width by remember { mutableFloatStateOf(1f) }
    val axisColor = MaterialTheme.colorScheme.outline
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = FILL_ALPHA)
    val cursorColor = MaterialTheme.colorScheme.onSurface
    val sample = cursorMeters?.let(profile::sampleAt)

    Column(modifier) {
        Text(
            text =
                if (sample != null) {
                    stringResource(
                        R.string.tracks_profile_cursor,
                        fmt.distance(sample.distanceMeters),
                        fmt.elevation(sample.elevationMeters),
                        fmt.slope(sample.gradePercent),
                    )
                } else {
                    stringResource(
                        R.string.tracks_profile_range,
                        fmt.elevation(minEle),
                        fmt.elevation(maxEle),
                    )
                },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(PROFILE_HEIGHT)
                    .pointerInput(total) {
                        detectTapGestures { at -> onCursor((at.x / width * total).coerceIn(0.0, total)) }
                    }.pointerInput(total) {
                        detectDragGestures(
                            onDragStart = { at -> onCursor((at.x / width * total).coerceIn(0.0, total)) },
                            onDrag = {
                                change,
                                _,
                                ->
                                onCursor((change.position.x / width * total).coerceIn(0.0, total))
                            },
                        )
                    },
        ) {
            width = size.width
            val h = size.height

            fun x(d: Double) = (d / total * size.width).toFloat()

            fun y(e: Double) = (h - ((e - minEle) / span * (h * USABLE_HEIGHT) + h * BOTTOM_MARGIN)).toFloat()
            val samples = profile.samples

            val area =
                Path().apply {
                    moveTo(x(samples.first().distanceMeters), h)
                    samples.forEach { lineTo(x(it.distanceMeters), y(it.elevationMeters)) }
                    lineTo(x(samples.last().distanceMeters), h)
                    close()
                }
            drawPath(area, fillColor)
            samples.zipWithNext().forEach { (a, b) ->
                drawLine(
                    color = gradeColor(GradeBand.of(b.gradePercent)),
                    start = Offset(x(a.distanceMeters), y(a.elevationMeters)),
                    end = Offset(x(b.distanceMeters), y(b.elevationMeters)),
                    strokeWidth = LINE_WIDTH,
                )
            }
            drawLine(axisColor, Offset(0f, h), Offset(size.width, h), strokeWidth = 1f)
            if (sample != null) {
                val cx = x(sample.distanceMeters)
                drawLine(cursorColor, Offset(cx, 0f), Offset(cx, h), strokeWidth = CURSOR_WIDTH)
                drawCircle(
                    cursorColor,
                    radius = CURSOR_RADIUS,
                    center = Offset(cx, y(sample.elevationMeters)),
                    style = Stroke(CURSOR_WIDTH),
                )
            }
        }
        Text(
            text = fmt.distance(total),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}

private val PROFILE_HEIGHT = 160.dp
private const val MIN_SPAN_METERS = 10.0
private const val FILL_ALPHA = 0.12f
private const val USABLE_HEIGHT = 0.85
private const val BOTTOM_MARGIN = 0.06
private const val LINE_WIDTH = 5f
private const val CURSOR_WIDTH = 3f
private const val CURSOR_RADIUS = 9f
