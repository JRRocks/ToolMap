package fr.toolmap.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import fr.toolmap.R
import fr.toolmap.core.altitude.AltitudeSource
import fr.toolmap.core.geo.ScaleBar
import fr.toolmap.core.geo.ScreenGridIssue
import fr.toolmap.core.geo.ScreenGridPlan
import fr.toolmap.core.geo.ZoomMapping
import fr.toolmap.core.settings.AltitudeMode
import fr.toolmap.core.state.CameraState
import fr.toolmap.data.altitude.AltitudeStatus
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.minTouchTarget
import fr.toolmap.ui.theme.toolMap
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

private val OverlayShape = RoundedCornerShape(6.dp)

/**
 * A round button of the map (P29.1, « Pastilles »): a disc of the panel colour 4 dp narrower than the touch target of
 * the mode (44 dp, 60 dp with gloves), its icon in the primary colour. Zoom and compass are drawn so.
 */
@Composable
fun MapRoundButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(minTouchTarget() - 4.dp),
        shape = CircleShape,
        color = MaterialTheme.toolMap.panel,
        contentColor = MaterialTheme.colorScheme.primary,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/** Compass needle rotating against the map bearing; hidden while the map points north. */
@Composable
fun CompassButton(
    bearing: Double,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (kotlin.math.abs(bearing) < 0.5) return
    val compassLabel = stringResource(R.string.a11y_compass)
    MapRoundButton(onClick = onClick, modifier = modifier.semantics { contentDescription = compassLabel }) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(26.dp).rotate((-bearing).toFloat())) {
                val w = size.width
                val h = size.height
                val north =
                    Path().apply {
                        moveTo(w / 2, 0f)
                        lineTo(w * 0.8f, h / 2)
                        lineTo(w * 0.2f, h / 2)
                        close()
                    }
                val south =
                    Path().apply {
                        moveTo(w / 2, h)
                        lineTo(w * 0.8f, h / 2)
                        lineTo(w * 0.2f, h / 2)
                        close()
                    }
                drawPath(north, Color(0xFFD32F2F))
                drawPath(south, COMPASS_SOUTH)
                drawCircle(Color.White, radius = w * 0.07f, center = Offset(w / 2, h / 2), style = Stroke(1.5f))
            }
        }
    }
}

/**
 * Mandatory credits of the maps on screen, on one line above the bar (variant E of `docs/previews/p19-reticule.html`,
 * 2026-10-28): shown for [CREDITS_SHOWN_MILLIS] each time the maps change, then faded out, so that they never cover
 * the centre point for long. The full texts are in « Aide et à propos ».
 */
@Composable
fun AttributionText(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    var shown by remember(text) { mutableStateOf(true) }
    LaunchedEffect(text) {
        delay(CREDITS_SHOWN_MILLIS)
        shown = false
    }
    AnimatedVisibility(visible = shown, enter = fadeIn(), exit = fadeOut(tween(CREDITS_FADE_MILLIS))) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                modifier
                    .background(MaterialTheme.toolMap.panel.copy(alpha = 0.75f), OverlayShape)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

private const val CREDITS_SHOWN_MILLIS = 4_000L
private const val CREDITS_FADE_MILLIS = 600

/**
 * The point the coordinates refer to, at the centre of the map (F1, ADR-043): a small white circle, which a tap opens
 * the quick menu of when [menuEnabled]. The circle sits in the middle of a 48 dp target. [menu] is shown above it.
 */
@Composable
fun CenterPoint(
    menuEnabled: Boolean,
    menuOpen: Boolean,
    onTap: (dx: Float, dy: Float) -> Unit,
    onMenu: () -> Unit,
    onDismiss: () -> Unit,
    menu: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** « Poser : Point d'intérêt » of a route (P30.1): the point is a pin whose tip is the centre. */
    pin: Boolean = false,
) {
    val label = stringResource(R.string.center_point)
    val menuLabel = stringResource(R.string.center_point_menu)
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onMenu)
    // The tap is passed on with its offset from the middle, so that a waypoint drawn under the point stays tappable;
    // a long press always opens the menu. Disabled, the point lets every touch through to the map.
    val gestures =
        if (menuEnabled) {
            Modifier
                .semantics {
                    role = Role.Button
                    onClick {
                        tap(0f, 0f)
                        true
                    }
                    onLongClick(menuLabel) {
                        longPress()
                        true
                    }
                }.pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { tap(it.x - size.width / 2f, it.y - size.height / 2f) },
                        onLongPress = { longPress() },
                    )
                }
        } else {
            Modifier.semantics { disabled() }
        }
    Box(
        modifier
            .size(TOUCH_TARGET)
            .semantics { contentDescription = label }
            .then(gestures),
        contentAlignment = Alignment.Center,
    ) {
        val accent = MaterialTheme.toolMap.accent
        Canvas(Modifier.size(POINT_SIZE)) {
            val radius = POINT_RADIUS.toPx()
            if (pin) {
                // The head of the pin above the centre and its tip on it; drawn past the canvas, which does not clip.
                val head = Offset(center.x, center.y - radius * PIN_HEAD_RISE)
                // The two sides leave the head where they touch it: at ± alpha from straight down.
                val alpha = Math.toDegrees(acos(1.0 / PIN_HEAD_RISE)).toFloat()
                val start = 90f - alpha
                val pinPath =
                    Path().apply {
                        moveTo(center.x, center.y)
                        val a = Math.toRadians(start.toDouble())
                        lineTo(head.x + radius * cos(a).toFloat(), head.y + radius * sin(a).toFloat())
                        arcTo(Rect(head, radius), start, -(360f - 2 * alpha), false)
                        close()
                    }
                drawPath(pinPath, POINT_HALO, style = Stroke((POINT_RING + 2.5.dp).toPx()))
                drawPath(pinPath, accent, style = Stroke(POINT_RING.toPx()))
                drawCircle(accent, POINT_RING.toPx(), head)
            } else {
                // A turquoise ring on a white halo (P29.1, « Pastilles »): it shows on the white and on the dark maps.
                drawCircle(POINT_HALO, radius, style = Stroke((POINT_RING + 2.5.dp).toPx()))
                drawCircle(accent, radius, style = Stroke(POINT_RING.toPx()))
            }
        }
        if (menuOpen && menuEnabled) {
            val gap = with(LocalDensity.current) { POINT_MENU_GAP.roundToPx() }
            val position = remember(gap) { AboveAnchor(gap) }
            Popup(
                popupPositionProvider = position,
                onDismissRequest = onDismiss,
                properties = PopupProperties(focusable = true),
            ) {
                menu()
            }
        }
    }
}

/** Puts a popup above its anchor, centred on it, and keeps it inside the window. */
private class AboveAnchor(
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.center.x - popupContentSize.width / 2
        val y = anchorBounds.top - gapPx - popupContentSize.height
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceAtLeast(0),
        )
    }
}

private val TOUCH_TARGET = 48.dp

/** The head of the pin of « Point d'intérêt » sits this many radii above its tip (P30.1). */
private const val PIN_HEAD_RISE = 1.7f
private val POINT_SIZE = 24.dp
private val POINT_RADIUS = 8.dp
private val POINT_RING = 2.5.dp
private val POINT_MENU_GAP = 8.dp
private val POINT_HALO = Color.White.copy(alpha = 0.95f)

/** The south half of the needle, grey on the white disc. */
private val COMPASS_SOUTH = Color(0xFF8A979D)
