package fr.toolmap.map

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * Where the floating parts of the map go so that none covers another, at any width (V2): the arithmetic, in pixels,
 * of [MapTopChrome] and [MapBottomChrome].
 */
object ChromePlacement {
    /** A rectangle of the screen, in pixels. */
    data class Area(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    /**
     * The panels of the top (recording, target, following...) stand beside the info boxes when they fit in the width
     * left of the screen, else below them.
     */
    fun panelsBesideBoxes(
        width: Int,
        boxesWidth: Int,
        panelsWidth: Int,
        gap: Int,
    ): Boolean = boxesWidth == 0 || panelsWidth <= width - boxesWidth - gap

    /**
     * The chip of the top right corner (the recording, P29.1) stays on the line of the boxes when both fit there, else
     * it goes under them, still on the right.
     */
    fun cornerBesideBoxes(
        width: Int,
        boxesWidth: Int,
        cornerWidth: Int,
        gap: Int,
    ): Boolean = cornerWidth == 0 || boxesWidth == 0 || boxesWidth + gap + cornerWidth <= width

    /** The left of the panels: centred on the screen when they can be, else just right of the boxes. */
    fun panelsX(
        width: Int,
        boxesWidth: Int,
        panelsWidth: Int,
        gap: Int,
        beside: Boolean,
    ): Int {
        val centred = ((width - panelsWidth) / 2).coerceAtLeast(0)
        return if (beside && boxesWidth > 0) max(centred, boxesWidth + gap) else centred
    }

    /** The top of the panels: level with the boxes beside them, else below them (and below the corner chip). */
    fun panelsY(
        boxesHeight: Int,
        gap: Int,
        beside: Boolean,
    ): Int = if (beside || boxesHeight == 0) 0 else boxesHeight + gap

    /** The buttons of the right (zoom, base swap) go above the bar when both do not fit on one line. */
    fun endAboveBar(
        width: Int,
        barWidth: Int,
        endWidth: Int,
        gap: Int,
    ): Boolean = endWidth > 0 && barWidth + gap + endWidth > width

    /** The credits of the maps wrap before the buttons of the right, which stand at their height. */
    fun attributionMaxWidth(
        width: Int,
        endWidth: Int,
        gap: Int,
    ): Int = if (endWidth == 0) width else (width - endWidth - gap).coerceAtLeast(0)

    /**
     * The part of the screen ([width] × [height]) that the info boxes, ending at [boxesRight] × [boxesBottom], leave
     * free: below them or right of them, whichever is larger. A zone being selected starts there, so that its handles
     * are not under the boxes.
     */
    fun freeOfBoxes(
        width: Int,
        height: Int,
        boxesRight: Int,
        boxesBottom: Int,
    ): Area {
        if (boxesRight <= 0 || boxesBottom <= 0) return Area(0, 0, width, height)
        val below = width.toLong() * (height - boxesBottom).coerceAtLeast(0)
        val right = (width - boxesRight).coerceAtLeast(0).toLong() * height
        return if (below >= right) Area(0, boxesBottom, width, height) else Area(boxesRight, 0, width, height)
    }
}

/**
 * Lets a part reach past the margins around it, to the edges of the screen: it is measured [start] + [end] wider and
 * [bottom] taller and drawn so, while its parent places it at the size it has without them. The bottom bar of P29.0
 * goes so through the padding of the chrome, to the sides of the screen and under the system bar.
 */
fun Modifier.bleed(
    start: Dp,
    end: Dp,
    bottom: Dp,
): Modifier =
    layout { measurable, constraints ->
        val s = start.roundToPx()
        val b = bottom.roundToPx()
        val width = constraints.maxWidth + s + end.roundToPx()
        val maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + b else Constraints.Infinity
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width, maxHeight = maxHeight))
        layout(constraints.maxWidth, (placeable.height - b).coerceAtLeast(0)) { placeable.placeRelative(-s, 0) }
    }

/** The gap between two floating parts of the map. */
private val CHROME_GAP = 8.dp

/** The gap between the credits and the bar under them. */
private val ATTRIBUTION_GAP = 6.dp

/**
 * The top of the map: the info boxes in the left corner, the [corner] chip (recording) in the right one, and the
 * panels (target, following...) beside the boxes or below them, never over them (V2).
 */
@Composable
fun MapTopChrome(
    boxes: @Composable () -> Unit,
    panels: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    corner: @Composable () -> Unit = {},
    gap: Dp = CHROME_GAP,
) {
    Layout(
        contents = listOf({ Box { boxes() } }, { Box { panels() } }, { Box { corner() } }),
        modifier = modifier,
    ) { slots, constraints ->
        val panelsSlot = slots[1].first()
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val g = gap.roundToPx()
        val b = slots[0].first().measure(loose)
        val c = slots[2].first().measure(loose)
        val cornerBeside = ChromePlacement.cornerBesideBoxes(width, b.width, c.width, g)
        val cornerY = if (cornerBeside || b.height == 0) 0 else b.height + g
        // The panels share the line of the boxes with what the corner leaves of it, else they go under both.
        val line = if (c.width > 0) width - c.width - g else width
        val wanted = panelsSlot.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(width)
        val beside =
            if (c.width == 0) {
                ChromePlacement.panelsBesideBoxes(width, b.width, wanted, g)
            } else {
                cornerBeside && wanted <= line - if (b.width > 0) b.width + g else 0
            }
        val room =
            if (beside && b.width > 0) {
                line - b.width - g
            } else if (beside) {
                line
            } else {
                width
            }
        val p = panelsSlot.measure(loose.copy(maxWidth = room.coerceAtLeast(0)))
        val x = ChromePlacement.panelsX(if (beside) line else width, b.width, p.width, g, beside)
        val above = max(b.height, if (c.height > 0) cornerY + c.height else 0)
        val y = ChromePlacement.panelsY(above, g, beside)
        layout(width, max(above, y + p.height).coerceAtMost(constraints.maxHeight)) {
            b.place(0, 0)
            c.place(width - c.width, cornerY)
            p.place(x, y)
        }
    }
}

/**
 * The bottom of the map: the bar in the left corner with the credits above it, the buttons of the right (zoom, base
 * swap) beside the bar or above it when the screen is too narrow; the credits wrap before those buttons (V2). It fills
 * the height it is given.
 */
@Composable
fun MapBottomChrome(
    attribution: @Composable () -> Unit,
    bar: @Composable () -> Unit,
    end: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    gap: Dp = CHROME_GAP,
) {
    Layout(
        contents = listOf({ Box { attribution() } }, { Box { bar() } }, { Box { end() } }),
        modifier = modifier,
    ) { slots, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val g = gap.roundToPx()
        val e = slots[2].first().measure(loose)
        val b = slots[1].first().measure(loose)
        val stacked = ChromePlacement.endAboveBar(width, b.width, e.width, g)
        val a =
            slots[0].first().measure(
                loose.copy(maxWidth = ChromePlacement.attributionMaxWidth(width, e.width, g)),
            )
        val barTop = height - b.height
        val aboveBar = if (b.height > 0 && a.height > 0) ATTRIBUTION_GAP.roundToPx() else 0
        layout(width, height) {
            b.place(0, barTop)
            e.place(width - e.width, if (stacked) barTop - g - e.height else height - e.height)
            a.place(0, barTop - aboveBar - a.height)
        }
    }
}
