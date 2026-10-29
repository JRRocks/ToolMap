package fr.toolmap.maps

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import fr.toolmap.core.layers.RasterAdjust
import fr.toolmap.ui.theme.toolMap

/*
 * The sliders of an unfolded line of « Cartes et calques » (opacity and colours) and what happens while one is held
 * (P26.6, docs/previews/p26-teinte.html, validated on 2026-10-29: « T2 - A »). Variant A: the rest of the panel fades
 * away, its background and shadow too, and the slider held stays alone over the map on a pill, its name and value
 * above it, so the map shows what it changes; the panel comes back when the finger lets go. Track T2: « Teinte » has
 * the colour wheel for a track.
 */

/**
 * Which slider of the panel the finger holds: [shown] is how much of the rest of the panel shows (1 while nothing is
 * held, down to 0 while a slider is), [last] the slider held last, which keeps its pill while the panel comes back.
 */
@Stable
internal class PanelFade(
    val held: String?,
    val last: String?,
    val shown: Float,
    val onHold: (key: String, holding: Boolean) -> Unit,
)

@Composable
internal fun rememberPanelFade(): PanelFade {
    var held by remember { mutableStateOf<String?>(null) }
    var last by remember { mutableStateOf<String?>(null) }
    val shown by animateFloatAsState(if (held == null) 1f else 0f, tween(FADE_MILLIS), label = "panelFade")
    return PanelFade(held, last, shown) { key, holding ->
        if (holding) {
            held = key
            last = key
        } else if (held == key) {
            held = null
        }
    }
}

/**
 * A slider of an unfolded line with its [label] above it, in the panel's margins (56 dp at the start, 16 at the end).
 * Held, it tells [fade]; the slider held last stays on a pill of the panel's colour while the rest fades away.
 */
@Composable
internal fun AdjustSlider(
    key: String,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    fade: PanelFade,
    onChange: (Float) -> Unit,
    /** Draws the colour wheel under the slider (« Teinte »). */
    hueTrack: Boolean = false,
    /** What TalkBack reads as the value when the slider's own (a share of its range) would mislead. */
    valueText: String? = null,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val dragged by source.collectIsDraggedAsState()
    val holding = pressed || dragged
    LaunchedEffect(holding) { fade.onHold(key, holding) }
    DisposableEffect(key) { onDispose { fade.onHold(key, false) } }
    val mine = fade.last == key
    // The pill comes as the panel goes, and goes as it comes back.
    val pill = if (mine) 1f - fade.shown else 0f
    val shape = MaterialTheme.shapes.medium
    Column(
        Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .shadow(PILL_ELEVATION * pill, shape, clip = false)
            .background(MaterialTheme.toolMap.panel.copy(alpha = pill), shape)
            .alpha(if (mine) 1f else fade.shown)
            .padding(start = 48.dp, end = 8.dp, top = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        val semantics = valueText?.let { text -> Modifier.semantics { stateDescription = text } } ?: Modifier
        if (hueTrack) {
            Box(contentAlignment = Alignment.CenterStart) {
                HueTrack(fraction = (value - range.start) / (range.endInclusive - range.start))
                Slider(
                    value = value,
                    onValueChange = onChange,
                    modifier = semantics,
                    valueRange = range,
                    colors =
                        SliderDefaults.colors(
                            activeTrackColor = Color.Transparent,
                            inactiveTrackColor = Color.Transparent,
                        ),
                    interactionSource = source,
                )
            }
        } else {
            Slider(
                value = value,
                onValueChange = onChange,
                modifier = semantics,
                valueRange = range,
                interactionSource = source,
            )
        }
    }
}

/**
 * The track of « Teinte »: the colour red takes at each place of the slider (cyan at both ends, red in the middle),
 * shaped like the track of the other sliders: 16 dp high, round at its ends, with a gap on each side of the thumb.
 * Material places the thumb's centre at half a thumb from the start plus [fraction] of the rest.
 */
@Composable
private fun HueTrack(fraction: Float) {
    Canvas(Modifier.fillMaxWidth().height(TRACK_HEIGHT)) {
        val thumb = THUMB_WIDTH.toPx()
        val start = thumb / 2
        val end = size.width - thumb / 2
        if (end <= start) return@Canvas
        val centre = start + fraction.coerceIn(0f, 1f) * (end - start)
        val gap = THUMB_WIDTH.toPx() / 2 + THUMB_GAP.toPx()
        val outer = CornerRadius(size.height / 2)
        val inner = CornerRadius(INNER_CORNER.toPx())
        val brush = Brush.horizontalGradient(HUE_COLORS, startX = start, endX = end)

        fun part(
            left: Float,
            right: Float,
            leftCorner: CornerRadius,
            rightCorner: CornerRadius,
        ) {
            if (right - left < 1f) return
            val path = Path()
            path.addRoundRect(
                RoundRect(
                    left = left,
                    top = 0f,
                    right = right,
                    bottom = size.height,
                    topLeftCornerRadius = leftCorner,
                    topRightCornerRadius = rightCorner,
                    bottomRightCornerRadius = rightCorner,
                    bottomLeftCornerRadius = leftCorner,
                ),
            )
            drawPath(path, brush)
        }
        part(start, centre - gap, outer, inner)
        part(centre + gap, end, inner, outer)
    }
}

/** What red becomes from -180° to +180°: hues in steps of 30°, a little softened. */
private val HUE_COLORS =
    (-180..180 step 30).map { degrees ->
        Color.hsv(((degrees % 360) + 360f) % 360f, HUE_SATURATION, HUE_VALUE)
    }

/** The range of « Teinte », in degrees. */
internal val HUE_RANGE = -RasterAdjust.MAX_HUE..RasterAdjust.MAX_HUE

private const val FADE_MILLIS = 150
private val PILL_ELEVATION = 4.dp
private const val HUE_SATURATION = 0.7f
private const val HUE_VALUE = 0.85f

/** Material's slider (track height, thumb width and the gap beside it, inner corners), to draw the same track. */
private val TRACK_HEIGHT = 16.dp
private val THUMB_WIDTH = 4.dp
private val THUMB_GAP = 6.dp
private val INNER_CORNER = 2.dp
