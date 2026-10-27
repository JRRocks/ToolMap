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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.source.TileSource
import fr.toolmap.settings.SettingsIcons
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap

private const val PERCENT = 100f

/** The map icon of a picture-less thumbnail, as a share of its side. */
private const val PLACEHOLDER_ICON = 0.5f

/**
 * The picture of a map (a tile of it), or, while there is none, the map icon on the tile colour of the theme (P29.3:
 * never letters on a pastel in place of a picture). [shape] is 8 dp corners, a circle in the favourites' chips.
 */
@Composable
fun MapThumbnail(
    sourceId: String,
    name: String,
    thumbnails: MapThumbnails,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    shape: Shape = RoundedCornerShape(8.dp),
    /** The icon shown until a tile is had (or when none can be): the type of the map (P29.6), never its initials. */
    placeholder: ImageVector = MapsIcons.Map,
) {
    val image by produceState<ImageBitmap?>(null, sourceId) { value = thumbnails.load(sourceId) }
    Box(
        modifier =
            modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.toolMap.tile),
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
            Icon(
                placeholder,
                contentDescription = null,
                tint = MaterialTheme.toolMap.onTile,
                modifier = Modifier.size(size * PLACEHOLDER_ICON),
            )
        }
    }
}

/** The icon of the kind of a map: relief data, an overlay, an aerial image, a shaded relief, a map. */
fun typeIcon(source: TileSource): ImageVector =
    when {
        source.type == "dem" -> SettingsIcons.Terrain
        source.isLayer -> MapsIcons.Layers
        source.type == "satellite" || source.type == "hybrid" -> MapsIcons.SatelliteAlt
        source.type == "hillshade" -> SettingsIcons.Terrain
        else -> MapsIcons.Map
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
    title: String = stringResource(R.string.maps_help_title),
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
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
