package fr.toolmap.maps

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.layers.FavoriteImport
import fr.toolmap.core.source.TileSource
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.TextButton

/**
 * « Ajouter N favoris ? » (P25.3, common to the variants of `docs/previews/p25-partage-sauvegarde.html`): the sheet over
 * the map when a `.toolmap` file is opened. One line per favorite of the file with its box and what adding it does;
 * nothing is applied, the favorites join the chips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveFavoritesSheet(
    incoming: IncomingFavorites,
    sources: List<TileSource>,
    thumbnails: MapThumbnails,
    onToggle: (Int) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ReceiveFavoritesContent(incoming, sources.associateBy { it.id }, thumbnails, onToggle, onAdd, onDismiss)
    }
}

@Composable
internal fun ReceiveFavoritesContent(
    incoming: IncomingFavorites,
    byId: Map<String, TileSource>,
    thumbnails: MapThumbnails,
    onToggle: (Int) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val items = incoming.plan.items
    val resources = LocalResources.current
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp)) {
            Text(
                pluralStringResource(R.plurals.fav_import_title, items.size, items.size),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                incoming.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 72.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                // The maps the favorite brings are not stored yet: their names and kinds come from the file.
                val maps = byId + item.added.associateBy { it.id }
                IncomingRow(
                    item = item,
                    status = statusOf(item, maps, resources),
                    checked = index in incoming.checked,
                    cover = coverOf(item.favorite, maps),
                    maps = maps,
                    thumbnails = thumbnails,
                    onToggle = { onToggle(index) },
                )
            }
        }
        Text(
            stringResource(R.string.fav_import_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            val count = incoming.checked.size
            Button(onClick = onAdd, enabled = count > 0) {
                Text(pluralStringResource(R.plurals.fav_import_button, count, count), fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** A favorite of the file: its picture, its name, what adding it does, its box. */
@Composable
private fun IncomingRow(
    item: FavoriteImport,
    status: String,
    checked: Boolean,
    cover: String?,
    maps: Map<String, TileSource>,
    thumbnails: MapThumbnails,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MapThumbnail(
            cover.orEmpty(),
            maps[cover]?.name ?: item.favorite.name,
            thumbnails,
            size = 40.dp,
            shape = RoundedCornerShape(10.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.originalName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
    }
}

/**
 * What adding [item] does, as the sheet says it: « Nom déjà pris : ajouté en « Rando (2) » », « 1 carte ajoutée
 * (SnowBase Map) · clé à saisir », « 1 carte remise dans la liste (OpenTopoMap) »; else its number of maps.
 */
internal fun statusOf(
    item: FavoriteImport,
    maps: Map<String, TileSource>,
    resources: Resources,
): String {
    val parts =
        buildList {
            if (item.renamed) add(resources.getString(R.string.fav_import_renamed, item.favorite.name))
            if (item.added.isNotEmpty()) {
                val names = item.added.joinToString(", ") { it.name }
                add(resources.getQuantityString(R.plurals.fav_import_added, item.added.size, item.added.size, names))
            }
            if (item.needKey.isNotEmpty()) add(resources.getString(R.string.fav_import_need_key))
            if (item.unhidden.isNotEmpty()) {
                val names = item.unhidden.joinToString(", ") { maps[it]?.name ?: it }
                add(
                    resources.getQuantityString(
                        R.plurals.fav_import_unhidden,
                        item.unhidden.size,
                        item.unhidden.size,
                        names,
                    ),
                )
            }
        }
    if (parts.isNotEmpty()) return parts.joinToString(" · ")
    val count = item.favorite.layers.size
    return resources.getQuantityString(R.plurals.fav_share_maps, count, count)
}
