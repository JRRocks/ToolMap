package fr.toolmap.tracks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
 * the map can show the matching point; [cursorMeters] is where the cursor currently stands. The reading stands above,
 * the length below (the profile of a planned route).
 */
@Composable
fun ElevationProfileView(
    profile: ElevationProfile,
    cursorMeters: Double?,
    onCursor: (Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fmt = LocalFormats.current
    if (profile.minElevation == null || profile.maxElevation == null) return
    Column(modifier) {
        Text(
            text = profileReading(profile, cursorMeters),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        ProfileCanvas(profile, cursorMeters, onCursor, PROFILE_HEIGHT)
        Text(
            text = fmt.distance(profile.totalDistanceMeters.coerceAtLeast(1.0)),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}

/**
 * The profile of a track's detail (P29.4, « Topo »): the curve with its cursor, the axis from « 0 » to the length,
 * and the legend of the five slope colours, so that the colours are read and not guessed. The reading goes on the
 * line of the title, given by [profileReading].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ElevationProfileChart(
    profile: ElevationProfile,
    cursorMeters: Double?,
    onCursor: (Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fmt = LocalFormats.current
    if (profile.minElevation == null || profile.maxElevation == null) return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        ProfileCanvas(profile, cursorMeters, onCursor, CHART_HEIGHT)
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(AXIS_START, style = MaterialTheme.typography.bodySmall, color = muted)
            Text(
                fmt.distance(profile.totalDistanceMeters.coerceAtLeast(1.0)),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                textAlign = TextAlign.End,
            )
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SLOPE_LEGEND.forEach { (band, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(gradeColor(band), CircleShape))
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

/** A small profile without cursor, in the sheet of a track touched on the map (P29.4, « Repère »). */
@Composable
fun MiniProfile(
    profile: ElevationProfile,
    modifier: Modifier = Modifier,
) {
    if (profile.minElevation == null || profile.maxElevation == null) return
    Box(modifier) { ProfileCanvas(profile, cursorMeters = null, onCursor = null, height = MINI_HEIGHT) }
}

/** « 3,20 km · 612 m · pente 18 % » under the cursor, « De 278 m à 1 020 m » without it. */
@Composable
fun profileReading(
    profile: ElevationProfile,
    cursorMeters: Double?,
): String {
    val fmt = LocalFormats.current
    val sample = cursorMeters?.let(profile::sampleAt)
    return if (sample != null) {
        stringResource(
            R.string.tracks_profile_cursor,
            fmt.distance(sample.distanceMeters),
            fmt.elevation(sample.elevationMeters),
            fmt.slope(sample.gradePercent),
        )
    } else {
        stringResource(
            R.string.tracks_profile_range,
            fmt.elevation(profile.minElevation ?: 0.0),
            fmt.elevation(profile.maxElevation ?: 0.0),
        )
    }
}

/**
 * The curve itself; [onCursor] `null` takes no touch. TalkBack reads the range and the length, which the colours
 * alone would not give.
 */
@Composable
private fun ProfileCanvas(
    profile: ElevationProfile,
    cursorMeters: Double?,
    onCursor: ((Double?) -> Unit)?,
    height: Dp,
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
    val spoken =
        stringResource(
            R.string.tracks_profile_description,
            fmt.elevation(minEle),
            fmt.elevation(maxEle),
            fmt.distance(total),
        )
    val touch =
        if (onCursor == null) {
            Modifier
        } else {
            Modifier
                .pointerInput(total) {
                    detectTapGestures { at -> onCursor((at.x / width * total).coerceIn(0.0, total)) }
                }.pointerInput(total) {
                    detectDragGestures(
                        onDragStart = { at -> onCursor((at.x / width * total).coerceIn(0.0, total)) },
                        onDrag = { change, _ -> onCursor((change.position.x / width * total).coerceIn(0.0, total)) },
                    )
                }
        }
    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = spoken }
                .then(touch),
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
        val line = if (height < CHART_HEIGHT) MINI_LINE_WIDTH else LINE_WIDTH
        samples.zipWithNext().forEach { (a, b) ->
            drawLine(
                color = gradeColor(GradeBand.of(b.gradePercent)),
                start = Offset(x(a.distanceMeters), y(a.elevationMeters)),
                end = Offset(x(b.distanceMeters), y(b.elevationMeters)),
                strokeWidth = line,
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
}

/** The five slope bands and their names, in the order of the legend. */
private val SLOPE_LEGEND =
    listOf(
        GradeBand.FLAT to R.string.tracks_slope_flat,
        GradeBand.GENTLE to R.string.tracks_slope_gentle,
        GradeBand.MODERATE to R.string.tracks_slope_moderate,
        GradeBand.STEEP to R.string.tracks_slope_steep,
        GradeBand.VERY_STEEP to R.string.tracks_slope_very_steep,
    )

private const val AXIS_START = "0"
private val PROFILE_HEIGHT = 160.dp
private val CHART_HEIGHT = 140.dp
private val MINI_HEIGHT = 56.dp
private const val MIN_SPAN_METERS = 10.0
private const val FILL_ALPHA = 0.12f
private const val USABLE_HEIGHT = 0.85
private const val BOTTOM_MARGIN = 0.06
private const val LINE_WIDTH = 5f
private const val MINI_LINE_WIDTH = 3.5f
private const val CURSOR_WIDTH = 3f
private const val CURSOR_RADIUS = 9f
