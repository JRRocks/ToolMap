package fr.toolmap.layers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import fr.toolmap.core.layers.ZoomBar

private val HANDLE_RADIUS = 11.dp
private val TRACK_HEIGHT = 6.dp
private val BAR_HEIGHT = 40.dp
private val CURSOR_WIDTH = 2.dp

/**
 * A two-handle bar of 0 to [max] integer positions (the zoom bar of a layer, or its elevation bar in steps): the
 * coloured segment is where the layer is shown, the vertical cursor marks where the map is right now ([cursor], in bar
 * positions; `null`: unknown, no cursor). The bar is grayed when the layer is not drawn now ([activeNow]).
 * [onChange] follows the finger; [onRelease] comes when it lifts (after a tap too), the range being the last one
 * [onChange] gave. All geometry rules live in [ZoomBar] (tested on the JVM).
 */
@Composable
fun RangeBar(
    range: ZoomBar.Range,
    max: Int,
    cursor: Double?,
    activeNow: Boolean,
    description: String,
    stateText: String,
    onChange: (ZoomBar.Range) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val trackColor = colors.surfaceVariant
    val segmentColor = if (activeNow) colors.primary else colors.outline
    val handleColor = if (activeNow) colors.primary else colors.outline
    val cursorColor = colors.tertiary
    val latestRange by rememberUpdatedState(range)
    val latestOnChange by rememberUpdatedState(onChange)
    val latestOnRelease by rememberUpdatedState(onRelease)

    // Touch position -> bar position, ignoring the margin that keeps the end handles inside the bar.
    fun grab(
        x: Float,
        width: Float,
        pad: Float,
        handle: ZoomBar.Handle?,
    ): ZoomBar.Handle {
        val usable = width - 2 * pad
        val picked = handle ?: ZoomBar.nearestHandle(x - pad, usable, latestRange, max)
        val moved = ZoomBar.moved(latestRange, picked, ZoomBar.zoomAt(x - pad, usable, max), max)
        if (moved != latestRange) latestOnChange(moved)
        return picked
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .semantics {
                contentDescription = description
                stateDescription = stateText
            }.pointerInput(max) {
                val pad = HANDLE_RADIUS.toPx()
                detectTapGestures { offset ->
                    grab(offset.x, size.width.toFloat(), pad, null)
                    latestOnRelease()
                }
            }.pointerInput(max) {
                val pad = HANDLE_RADIUS.toPx()
                var handle: ZoomBar.Handle? = null
                detectDragGestures(
                    onDragStart = { offset -> handle = grab(offset.x, size.width.toFloat(), pad, null) },
                    onDragEnd = {
                        handle = null
                        latestOnRelease()
                    },
                    onDragCancel = {
                        handle = null
                        latestOnRelease()
                    },
                ) { change, _ ->
                    change.consume()
                    handle = grab(change.position.x, size.width.toFloat(), pad, handle)
                }
            },
    ) {
        val pad = HANDLE_RADIUS.toPx()
        val usable = size.width - 2 * pad
        val centerY = size.height / 2
        val track = TRACK_HEIGHT.toPx()

        fun xOf(position: Double) = pad + usable * ZoomBar.fractionOf(position, max)

        drawBar(pad, centerY, usable, track, trackColor)
        val left = xOf(range.min.toDouble())
        val right = xOf(range.max.toDouble())
        drawBar(left, centerY, right - left, track, segmentColor)
        if (cursor != null) {
            val cursorX = xOf(cursor)
            drawLine(cursorColor, Offset(cursorX, 0f), Offset(cursorX, size.height), strokeWidth = CURSOR_WIDTH.toPx())
        }
        drawCircle(handleColor, HANDLE_RADIUS.toPx(), Offset(left, centerY))
        drawCircle(handleColor, HANDLE_RADIUS.toPx(), Offset(right, centerY))
    }
}

private fun DrawScope.drawBar(
    x: Float,
    centerY: Float,
    width: Float,
    height: Float,
    color: androidx.compose.ui.graphics.Color,
) {
    drawRoundRect(
        color,
        topLeft = Offset(x, centerY - height / 2),
        size = Size(width.coerceAtLeast(0f), height),
        cornerRadius = CornerRadius(height / 2),
    )
}
