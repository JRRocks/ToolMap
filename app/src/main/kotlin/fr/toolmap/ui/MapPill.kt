package fr.toolmap.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import fr.toolmap.ui.theme.toolMap

/** How much of the map shows through a pill: barely, as the pills of the map (P29.1). */
private const val PILL_ALPHA = 0.94f

/**
 * What stays over the map, in the dress of the pills of P29.1 (P29.10): the white of the panels, barely see-through,
 * 12 dp corners, a light shadow. The banners of the target and of the followed track, the figures of a route and the
 * band of the print frame wear it; a banner that warns takes its own [color].
 */
@Composable
fun MapPill(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.toolMap.panel.copy(alpha = PILL_ALPHA),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = color,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 2.dp,
        content = content,
    )
}
