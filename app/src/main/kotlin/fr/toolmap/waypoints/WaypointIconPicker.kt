package fr.toolmap.waypoints

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.track.WaypointCatalog
import fr.toolmap.ui.theme.TextButton

/** The label of an icon, in the user's language. */
@Composable
fun iconLabel(icon: WaypointCatalog.Icon): String =
    WaypointIconRes.labels[icon.id]?.let { stringResource(it) } ?: icon.key

/** A white glyph on the disc of the category colour: how the icon looks on the map. */
@Composable
fun WaypointIconBadge(
    icon: WaypointCatalog.Icon,
    size: Dp,
) {
    Box(Modifier.size(size).clip(CircleShape).background(Color(icon.colorArgb)), contentAlignment = Alignment.Center) {
        WaypointIconRes.drawables[icon.id]?.let {
            Icon(
                painterResource(it),
                contentDescription = null,
                tint = Color.White,
                modifier =
                    Modifier.size(size * 0.62f),
            )
        }
    }
}

/**
 * Picks the icon of a waypoint among every icon of AlpineQuest's categories (P16.6): categories fold and unfold, and
 * the search field filters on the label (a search unfolds every category that has a match).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WaypointIconPicker(
    selectedId: String,
    onSelect: (WaypointCatalog.Icon) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val unfolded = remember { mutableStateListOf<String>() }
    val labels = WaypointIconRes.labels.mapValues { stringResource(it.value) }
    val needle = query.trim().lowercase()
    val groups =
        WaypointCatalog.CATEGORIES.mapNotNull { category ->
            val icons =
                if (needle.isEmpty()) {
                    category.icons
                } else {
                    category.icons.filter { (labels[it.id] ?: it.key).lowercase().contains(needle) }
                }
            if (icons.isEmpty()) null else category to icons
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.waypoint_symbol)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.waypoint_icon_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (groups.isEmpty()) Text(stringResource(R.string.waypoint_icon_no_result))
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(groups, key = { it.first.id }) { (category, icons) ->
                        val open = needle.isNotEmpty() || category.id in unfolded
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (category.id in
                                            unfolded
                                        ) {
                                            unfolded.remove(category.id)
                                        } else {
                                            unfolded.add(category.id)
                                        }
                                    }.padding(vertical = 10.dp),
                        ) {
                            Box(Modifier.size(12.dp).background(Color(category.colorArgb), CircleShape))
                            Text(
                                stringResource(WaypointIconRes.categoryLabels.getValue(category.id)),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f).padding(start = 10.dp),
                            )
                            Text(icons.size.toString(), style = MaterialTheme.typography.labelMedium)
                            Icon(
                                if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                            )
                        }
                        if (open) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(bottom = 8.dp),
                            ) {
                                icons.forEach { icon ->
                                    val background =
                                        if (icon.id ==
                                            selectedId
                                        ) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            Color.Transparent
                                        }
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier =
                                            Modifier
                                                .width(64.dp)
                                                .clip(MaterialTheme.shapes.small)
                                                .background(background)
                                                .clickable { onSelect(icon) }
                                                .padding(vertical = 4.dp),
                                    ) {
                                        WaypointIconBadge(icon, 36.dp)
                                        Text(
                                            labels[icon.id] ?: icon.key,
                                            style = MaterialTheme.typography.labelSmall,
                                            textAlign = TextAlign.Center,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
