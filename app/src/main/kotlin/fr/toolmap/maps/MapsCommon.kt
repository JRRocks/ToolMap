package fr.toolmap.maps

import android.content.res.Resources
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.ui.theme.TextButton

private const val PERCENT = 100f
private const val HUE_RANGE = 360
private const val PLACEHOLDER_SATURATION = 0.28f
private const val PLACEHOLDER_VALUE = 0.9f

/** Up to two capital letters for the placeholder of a map without a picture ("IGN topo" gives "IT"). */
internal fun initialsOf(name: String): String =
    name
        .split(' ', '-', '_', '\'', '’')
        .filter { word -> word.any(Char::isLetterOrDigit) }
        .take(2)
        .joinToString("") { word -> word.first { it.isLetterOrDigit() }.uppercase() }
        .ifEmpty { "?" }

/** A stable pastel colour per map, so a picture-less card is still recognisable. */
internal fun placeholderColor(sourceId: String): Color =
    Color.hsv(
        hue = Math.floorMod(sourceId.hashCode(), HUE_RANGE).toFloat(),
        saturation = PLACEHOLDER_SATURATION,
        value = PLACEHOLDER_VALUE,
    )

/** The picture of a map (a tile of it), or its initials on a coloured square while there is none. */
@Composable
fun MapThumbnail(
    sourceId: String,
    name: String,
    thumbnails: MapThumbnails,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val image by produceState<ImageBitmap?>(null, sourceId) { value = thumbnails.load(sourceId) }
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(8.dp)).background(placeholderColor(sourceId)),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(initialsOf(name), style = MaterialTheme.typography.titleMedium, color = Color.Black.copy(alpha = 0.6f))
        }
    }
}

/** Title line of a panel: [title], the help button and (when given) a leading [navigation] button. */
@Composable
fun PanelHeader(
    title: String,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
    navigation: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        navigation?.invoke()
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onHelp) {
            Icon(MapsIcons.Help, contentDescription = stringResource(R.string.maps_help))
        }
        trailing?.invoke()
    }
}

@Composable
fun HelpDialog(
    text: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.maps_help_title)) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

/** "1 tuile", "12 tuiles": the count with the right plural. */
fun tilesText(
    resources: Resources,
    count: Long,
): String {
    val n = count.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    return resources.getQuantityString(R.plurals.maps_tiles, n, n)
}

/** "40 %" of a 0..1 fraction. */
internal fun percentOf(fraction: Float): Int = (fraction * PERCENT).toInt()
