package fr.toolmap.draw

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.route.AvoidZone
import fr.toolmap.core.settings.UnitSystem
import fr.toolmap.core.state.CameraState
import fr.toolmap.core.track.Geodesy
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.ui.LocalFormats
import kotlin.math.roundToInt

private val HANDLE_TOUCH = 48.dp
private val HANDLE_DOT = 22.dp
private val LABEL_WIDTH = 80.dp
private val ZONE_RED = Color(0xFFD32F2F)

/**
 * The handle that changes the size of the zone to avoid under the crosshair (P30.4, variant C of
 * `docs/previews/p30-itineraires.html`): a white disc ringed with red on the edge of the circle, due east of its centre,
 * joined to it by a thin dashed line, with the size under it. Pulled with the finger, the size follows (50 m to 2 km, in
 * steps of 50 m) and [onPreview] draws the circle at that size; let go, [onResize] gives it to the zone. Only the handle
 * takes touches: the rest of the map stays usable. The circle is fixed on the ground, so the handle is placed again
 * from [camera] each time the map moves.
 */
@Composable
fun ZoneSizeHandle(
    zone: AvoidZone,
    camera: CameraState,
    project: (lat: Double, lon: Double) -> Offset?,
    unproject: (x: Float, y: Float) -> TrackPoint?,
    onPreview: (Int) -> Unit,
    onResize: (Int) -> Unit,
) {
    // The size while the handle is pulled; `null` at rest, where the zone's own size is shown.
    var live by remember(zone) { mutableStateOf<Int?>(null) }
    val radius = live ?: zone.radiusMeters
    // The camera is a key only: when it changes the map moved, and the handle is projected again.
    val centre = remember(zone, camera) { project(zone.lat, zone.lon) }
    val edge =
        remember(zone, radius, camera) {
            val east = zone.east(radius)
            project(east.lat, east.lon)
        }
    if (centre == null || edge == null) return
    val latestEdge by rememberUpdatedState(edge)
    val latestUnproject by rememberUpdatedState(unproject)
    val latestPreview by rememberUpdatedState(onPreview)
    val latestResize by rememberUpdatedState(onResize)
    val sizeText = zoneSize(radius)
    val description = stringResource(R.string.route_zone_handle)
    val grow = stringResource(R.string.route_zone_grow)
    val shrink = stringResource(R.string.route_zone_shrink)
    Canvas(Modifier.fillMaxSize()) {
        drawLine(
            color = ZONE_RED,
            start = centre,
            end = edge,
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
        )
    }
    val fill = Color.White
    Box(
        modifier =
            Modifier
                .offset {
                    val half = HANDLE_TOUCH.toPx() / 2
                    IntOffset((edge.x - half).roundToInt(), (edge.y - half).roundToInt())
                }.size(HANDLE_TOUCH)
                .semantics {
                    contentDescription = description
                    stateDescription = sizeText
                    // TalkBack cannot pull the handle: a step of 50 m either way.
                    customActions =
                        listOf(
                            CustomAccessibilityAction(grow) {
                                latestResize(AvoidZone.resized(zone.radiusMeters + AvoidZone.RESIZE_STEP.toDouble()))
                                true
                            },
                            CustomAccessibilityAction(shrink) {
                                latestResize(AvoidZone.resized(zone.radiusMeters - AvoidZone.RESIZE_STEP.toDouble()))
                                true
                            },
                        )
                }.pointerInput(zone) {
                    var finger = Offset.Zero
                    var pulled = zone.radiusMeters
                    detectDragGestures(
                        onDragStart = {
                            finger = latestEdge
                            pulled = zone.radiusMeters
                        },
                        onDragEnd = {
                            latestResize(pulled)
                            live = null
                        },
                        onDragCancel = {
                            latestResize(zone.radiusMeters)
                            live = null
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            finger += amount
                            val at = latestUnproject(finger.x, finger.y) ?: return@detectDragGestures
                            val r = AvoidZone.resized(Geodesy.distance(zone.lat, zone.lon, at.lat, at.lon))
                            if (r != pulled) {
                                pulled = r
                                live = r
                                latestPreview(r)
                            }
                        },
                    )
                }.drawBehind {
                    val dot = HANDLE_DOT.toPx() / 2
                    drawCircle(fill, dot)
                    drawCircle(ZONE_RED, dot, style = Stroke(width = 3.dp.toPx()))
                },
    )
    // The size under the handle, centred on it.
    Box(
        modifier =
            Modifier
                .offset {
                    IntOffset(
                        (edge.x - LABEL_WIDTH.toPx() / 2).roundToInt(),
                        (edge.y + HANDLE_DOT.toPx() / 2 + 4.dp.toPx()).roundToInt(),
                    )
                }.width(LABEL_WIDTH),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary) {
            Text(
                sizeText,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** A size of zone as the chips write it: « 1 km » rather than « 1,00 km », a round size and not a measure. */
@Composable
fun zoneSize(meters: Int): String {
    val fmt = LocalFormats.current
    return if (fmt.units == UnitSystem.METRIC && meters % 1000 == 0) {
        "${meters / 1000} km"
    } else {
        fmt.distance(meters.toDouble())
    }
}
