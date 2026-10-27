package fr.toolmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.toolmap.ui.theme.toolMap

/*
 * The grouped lists of the screens (P29.0, « Crête »): the rows of a group make one white block on the grey of the
 * screen, rounded at its ends, with a thin line between two rows; a small grey caption may name the group.
 */

/** The corner of the white block of a group: the theme's medium shape. */
val GROUP_RADIUS = 14.dp

/** Where a row stands in its group: rounded at the top of the first row and the bottom of the last. */
fun groupedShape(
    first: Boolean,
    last: Boolean,
    radius: Dp = GROUP_RADIUS,
): Shape =
    RoundedCornerShape(
        topStart = if (first) radius else 0.dp,
        topEnd = if (first) radius else 0.dp,
        bottomStart = if (last) radius else 0.dp,
        bottomEnd = if (last) radius else 0.dp,
    )

/** One white block of rows, each [rows] entry a row, with a thin line between two of them. */
@Composable
fun GroupBlock(
    modifier: Modifier = Modifier,
    rows: List<@Composable () -> Unit>,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.toolMap.panel),
    ) {
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            row()
        }
    }
}

/** The small grey caption over a group: it names it, it is not a title. */
@Composable
fun GroupCaption(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
    )
}
