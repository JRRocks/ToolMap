package fr.toolmap.maps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.layers.Workspace
import fr.toolmap.core.state.WorkState
import fr.toolmap.map.BarPanel
import fr.toolmap.map.MapViewModel
import fr.toolmap.map.MenuIcons
import fr.toolmap.map.MenuRow
import fr.toolmap.map.PANEL_INSET
import fr.toolmap.map.PanelCaption
import fr.toolmap.map.PanelChevron
import fr.toolmap.map.PanelSwitch
import fr.toolmap.map.StateCard
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.map.PanelHeader as BarPanelHeader

/** What the root panel can ask the map screen to do (screens it cannot open itself). */
class MapsRootActions(
    val onOpenAvailable: () -> Unit,
    val onSaveArea: () -> Unit,
    val onImport: () -> Unit,
    val onOpenDownloads: () -> Unit,
    val onOpenStorage: () -> Unit,
    /** The « ? »: the help page at its « Cartes et calques » section (R5, F11). */
    val onHelp: () -> Unit = {},
)

/**
 * The « Cartes et calques » panel (P14.10), above the bar like the other panels of the bar: a tinted block with the
 * maps shown (it opens « Cartes affichées »), the catalogue and the saving of a zone, the favourites (the first tile
 * saves the display as a new one), the offline mode, and the less used options as chips.
 */
@Composable
fun MapsRootPanel(
    state: WorkState,
    maps: MapsViewModel,
    vm: MapViewModel,
    actions: MapsRootActions,
    onDismiss: () -> Unit,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val hiddenBuiltins by vm.hiddenBuiltins.collectAsStateWithLifecycle()
    val offline by vm.offline.collectAsStateWithLifecycle()
    val coverage by vm.coverage.collectAsStateWithLifecycle()
    val sources by maps.sources.collectAsStateWithLifecycle()
    var showSave by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Workspace?>(null) }
    val sourceName = { id: String -> sources.firstOrNull { it.id == id }?.name ?: id }
    val shown = state.stack.topToBottom().map { it.sourceId to sourceName(it.sourceId) }

    BarPanel(
        maxHeight = maxHeight,
        modifier = modifier,
        header = { BarPanelHeader(stringResource(R.string.maps_title), onHelp = actions.onHelp) },
    ) {
        StateCard(
            overline = stringResource(R.string.maps_displayed),
            title = shown.joinToString(" + ") { it.second }.ifEmpty { stringResource(R.string.menu_display_none) },
            subtitle = pluralStringResource(R.plurals.maps_displayed_count, shown.size, shown.size),
            leading = { StackThumbnails(shown, maps.thumbnails) },
            trailing = { PanelChevron() },
            onClick = { maps.open(MapsPage.DISPLAYED) },
        )
        MenuRow(stringResource(R.string.maps_available), icon = MapsIcons.Map, trailing = { PanelChevron() }) {
            actions.onOpenAvailable()
        }
        MenuRow(stringResource(R.string.maps_save_area), icon = MapsIcons.CropFree, trailing = { PanelChevron() }) {
            actions.onSaveArea()
        }
        PanelCaption(stringResource(R.string.maps_favorites))
        LazyRow(
            contentPadding = PaddingValues(horizontal = PANEL_INSET),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "add") { AddFavoriteTile(onClick = { showSave = true }) }
            items(favorites, key = { it.id }) { favorite ->
                FavoriteTile(
                    favorite = favorite,
                    selected = favorite.matches(state.stack),
                    sourceName = sourceName,
                    thumbnails = maps.thumbnails,
                    onApply = {
                        vm.applyWorkspace(favorite)
                        onDismiss()
                    },
                    onLongPress = { toDelete = favorite },
                )
            }
        }
        MenuRow(
            stringResource(R.string.maps_option_offline),
            icon = MenuIcons.CloudOff,
            subtitle = stringResource(R.string.maps_option_offline_sub),
            trailing = { PanelSwitch(checked = offline) },
        ) {
            vm.setOffline(!offline)
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = PANEL_INSET),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = coverage,
                onClick = { vm.setCoverage(!coverage) },
                label = { Text(stringResource(R.string.maps_chip_coverage)) },
                leadingIcon =
                    if (coverage) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    },
            )
            AssistChip(
                onClick = actions.onImport,
                label = { Text(stringResource(R.string.maps_chip_import)) },
                leadingIcon = {
                    Icon(MenuIcons.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
            AssistChip(onClick = actions.onOpenDownloads, label = { Text(stringResource(R.string.menu_downloads)) })
            AssistChip(onClick = actions.onOpenStorage, label = { Text(stringResource(R.string.maps_chip_storage)) })
            if (hiddenBuiltins.isNotEmpty()) {
                AssistChip(
                    onClick = vm::restoreBuiltinWorkspaces,
                    label = { Text(stringResource(R.string.maps_favorite_restore, hiddenBuiltins.size)) },
                )
            }
        }
    }
    if (showSave) {
        SaveFavoriteDialog(
            empty = state.stack.isEmpty,
            onSave = {
                vm.saveWorkspace(it)
                showSave = false
            },
            onDismiss = { showSave = false },
        )
    }
    toDelete?.let { favorite ->
        DeleteFavoriteDialog(
            favorite = favorite,
            onDelete = {
                vm.deleteWorkspace(favorite.id)
                toDelete = null
            },
            onDismiss = { toDelete = null },
        )
    }
}

/** The thumbnails of the maps shown ([layers]: id and name, top first), overlapping, at most three. */
@Composable
fun StackThumbnails(
    layers: List<Pair<String, String>>,
    thumbnails: MapThumbnails,
) {
    val shown = layers.take(MAX_THUMBNAILS)
    if (shown.isEmpty()) {
        Box(Modifier.size(THUMBNAIL), contentAlignment = Alignment.Center) {
            Icon(MapsIcons.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        return
    }
    Box(Modifier.width(THUMBNAIL + THUMBNAIL_STEP * (shown.size - 1))) {
        // The bottom map first, so that the top one is drawn over it, at the start.
        shown.asReversed().forEachIndexed { index, (id, name) ->
            MapThumbnail(
                sourceId = id,
                name = name,
                thumbnails = thumbnails,
                size = THUMBNAIL,
                modifier = Modifier.offset(x = THUMBNAIL_STEP * (shown.size - 1 - index)),
            )
        }
    }
}

/** The first tile of the favourites: a dashed frame with a « + », which saves what is shown as a new favourite. */
@Composable
private fun AddFavoriteTile(onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier =
            Modifier.width(FAVORITE_WIDTH).drawBehind {
                drawRoundRect(
                    color = outline,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style =
                        Stroke(
                            width = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                        ),
                )
            },
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                stringResource(R.string.maps_favorite_add_tile),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                minLines = 2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A favorite: the picture of its base map, its name; a tap shows it, a long press offers to delete it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteTile(
    favorite: Workspace,
    selected: Boolean,
    sourceName: (String) -> String,
    thumbnails: MapThumbnails,
    onApply: () -> Unit,
    onLongPress: () -> Unit,
) {
    val cover = favorite.layers.firstOrNull()?.sourceId
    Surface(
        shape = RoundedCornerShape(12.dp),
        color =
            if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.width(FAVORITE_WIDTH).combinedClickable(onClick = onApply, onLongClick = onLongPress),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MapThumbnail(
                sourceId = cover.orEmpty(),
                name = cover?.let(sourceName) ?: favorite.name,
                thumbnails = thumbnails,
                size = 64.dp,
            )
            // Two lines, broken between words: the tile is wide enough for the longest word of the names given.
            Text(
                favorite.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SaveFavoriteDialog(
    empty: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.maps_favorite_new_title)) },
        text = {
            if (empty) {
                Text(stringResource(R.string.maps_favorite_empty))
            } else {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.maps_favorite_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = !empty && name.isNotBlank()) {
                Text(stringResource(R.string.maps_favorite_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun DeleteFavoriteDialog(
    favorite: Workspace,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.maps_favorite_delete_title)) },
        text = {
            Text(
                stringResource(
                    if (favorite.isBuiltin) R.string.maps_favorite_builtin else R.string.maps_favorite_delete_text,
                    favorite.name,
                ),
            )
        },
        confirmButton = { TextButton(onClick = onDelete) { Text(stringResource(R.string.layers_remove)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Wide enough for « OpenStreetMap » and « Multi-Zoom » on one line each, so that no word is cut. */
private val FAVORITE_WIDTH = 108.dp
private val THUMBNAIL = 40.dp
private val THUMBNAIL_STEP = 14.dp
private const val MAX_THUMBNAILS = 3
