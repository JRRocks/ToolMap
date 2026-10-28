package fr.toolmap.maps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.layers.LayerElevation
import fr.toolmap.core.layers.Workspace
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.state.WorkState
import fr.toolmap.map.BarPanel
import fr.toolmap.map.MapViewModel
import fr.toolmap.map.MenuIcons
import fr.toolmap.map.PANEL_INSET
import fr.toolmap.map.PanelBand
import fr.toolmap.map.PanelCaption
import fr.toolmap.settings.SettingsIcons
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.DropdownMenu
import fr.toolmap.ui.theme.Switch
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap
import kotlin.math.floor

/** What the root panel can ask the map screen to do (screens it cannot open itself). */
class MapsRootActions(
    val onOpenAvailable: () -> Unit,
    val onSaveArea: () -> Unit,
    val onOpenDownloads: () -> Unit,
    val onOpenStorage: () -> Unit,
    /** The « ? »: the help page at its « Cartes et calques » section (R5, F11). */
    val onHelp: () -> Unit = {},
    /** A favourite was just deleted: the screen offers « Annuler » for a while (P29.3). */
    val onFavoriteDeleted: (Workspace) -> Unit = {},
)

/*
 * « Cartes et calques » since P29.3 (« Strates », docs/previews/design-03-cartes.html, variant C): the layer stack is
 * the panel itself. The band says what is shown and how many maps are drawn at the zoom of the map; the favourites are
 * a row of chips; each layer is a line (drag handle, thumbnail, name and state, eye) that unfolds in place with its
 * opacity, its order, its conditions, its colours and the rest of its commands; at the foot, the offline mode and the
 * coverage as two switches, then the four ways to the catalogue, the zone, the downloads and the storage.
 */
@Composable
fun MapsRootPanel(
    state: WorkState,
    maps: MapsViewModel,
    vm: MapViewModel,
    actions: MapsRootActions,
    /** The zoom of the tiles on screen, which says which layers are drawn now. */
    currentZoom: Double,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val hiddenBuiltins by vm.hiddenBuiltins.collectAsStateWithLifecycle()
    val offline by vm.offline.collectAsStateWithLifecycle()
    val coverage by vm.coverage.collectAsStateWithLifecycle()
    val sources by maps.sources.collectAsStateWithLifecycle()
    val expanded by maps.expandedLayer.collectAsStateWithLifecycle()
    var showSave by remember { mutableStateOf(false) }
    val byId = remember(sources) { sources.associateBy { it.id } }
    val layers = state.stack.topToBottom()
    val applied = favorites.firstOrNull { it.matches(state.stack) }

    BarPanel(
        maxHeight = maxHeight,
        modifier = modifier,
        header = {
            MapsBand(
                favorite = applied?.name,
                single = layers.singleOrNull()?.let { byId[it.sourceId]?.name },
                count = layers.size,
                drawn = layers.count { it.visible && it.opacity > 0f && LayerElevation.isDrawnAt(it, currentZoom) },
                zoom = floor(currentZoom).toInt(),
                onSave = { showSave = true },
                onHelp = actions.onHelp,
            )
        },
    ) {
        FavoriteChips(
            favorites = favorites,
            applied = applied,
            hiddenBuiltins = hiddenBuiltins.size,
            byId = byId,
            thumbnails = maps.thumbnails,
            onApply = vm::applyWorkspace,
            onDelete = { favorite ->
                vm.deleteWorkspace(favorite.id)
                actions.onFavoriteDeleted(favorite)
            },
            onRestore = vm::restoreBuiltinWorkspaces,
        )
        PanelCaption(stringResource(R.string.maps_stack_caption))
        if (layers.isEmpty()) {
            Text(
                stringResource(R.string.maps_displayed_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        StackRows(
            layers = layers,
            byId = byId,
            maps = maps,
            vm = vm,
            currentZoom = currentZoom,
            expanded = expanded,
            onExpand = maps::setExpanded,
        )
        if (layers.isNotEmpty() && applied == null) {
            // The stack matches no favourite: the way to keep it, next to it.
            TextButton(onClick = { showSave = true }, modifier = Modifier.padding(start = 8.dp)) {
                Icon(MapsIcons.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.maps_save_hint_button), modifier = Modifier.padding(start = 8.dp))
            }
        }
        SwitchCells(
            offline = offline,
            coverage = coverage,
            onOffline = { vm.setOffline(!offline) },
            onCoverage = { vm.setCoverage(!coverage) },
        )
        NavTiles(actions)
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
}

/**
 * The band of the panel: its title, then what is shown (the favourite applied, else the only map, with the number of
 * maps) and how many of them the map draws at its zoom; « Enregistrer l'affichage » and the « ? » at the end.
 */
@Composable
private fun MapsBand(
    favorite: String?,
    single: String?,
    count: Int,
    drawn: Int,
    zoom: Int,
    onSave: () -> Unit,
    onHelp: () -> Unit,
) {
    val maps = pluralStringResource(R.plurals.maps_band_count, count, count)
    val subtitle =
        if (count == 0) {
            stringResource(R.string.maps_band_empty)
        } else {
            val what = (favorite ?: single)?.let { "$it · $maps" } ?: maps
            val now =
                if (drawn == 0) {
                    stringResource(R.string.maps_band_none_drawn, zoom)
                } else {
                    pluralStringResource(R.plurals.maps_band_drawn, drawn, drawn, zoom)
                }
            "$what\n$now"
        }
    PanelBand(title = stringResource(R.string.maps_title), subtitle = subtitle, subtitleLines = 2) {
        IconButton(onClick = onSave) {
            Icon(MapsIcons.BookmarkAdd, contentDescription = stringResource(R.string.maps_favorite_add_tile))
        }
        IconButton(onClick = onHelp) {
            Icon(MenuIcons.Help, contentDescription = stringResource(R.string.menu_help))
        }
    }
}

/**
 * The favourites as chips: the round picture of the top base map of each, its name; the one applied is filled. A tap
 * applies it (the panel stays open); a long press offers « Appliquer » and « Supprimer ». « Rétablir (n) » last brings
 * back the provided ones deleted.
 */
@Composable
private fun FavoriteChips(
    favorites: List<Workspace>,
    applied: Workspace?,
    hiddenBuiltins: Int,
    byId: Map<String, TileSource>,
    thumbnails: MapThumbnails,
    onApply: (Workspace) -> Unit,
    onDelete: (Workspace) -> Unit,
    onRestore: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = PANEL_INSET),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 2.dp),
    ) {
        items(favorites, key = { it.id }) { favorite ->
            // The top map that is not an overlay: what the favourite shows first.
            val cover =
                favorite.layers.asReversed().firstOrNull { byId[it.sourceId]?.isLayer == false }?.sourceId
                    ?: favorite.layers.lastOrNull()?.sourceId
            FavoriteChip(
                favorite = favorite,
                selected = favorite.id == applied?.id,
                cover = cover,
                coverName = cover?.let { byId[it]?.name } ?: favorite.name,
                thumbnails = thumbnails,
                onApply = { onApply(favorite) },
                onDelete = { onDelete(favorite) },
            )
        }
        if (hiddenBuiltins > 0) {
            item(key = "restore") {
                ChipFrame(selected = false, onClick = onRestore) {
                    Icon(
                        MapsIcons.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 4.dp).size(18.dp),
                    )
                    Text(
                        stringResource(R.string.maps_favorite_restore_short, hiddenBuiltins),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteChip(
    favorite: Workspace,
    selected: Boolean,
    cover: String?,
    coverName: String,
    thumbnails: MapThumbnails,
    onApply: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ChipFrame(selected = selected, onClick = onApply, onLongClick = { menu = true }) {
            MapThumbnail(cover.orEmpty(), coverName, thumbnails, size = 24.dp, shape = CircleShape)
            Text(favorite.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.maps_favorite_apply)) },
                onClick = {
                    menu = false
                    onApply()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.layers_remove)) },
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
    }
}

/** A chip of 32 dp (a 48 dp touch target): filled with the secondary container when selected, outlined otherwise. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChipFrame(
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) scheme.secondaryContainer else MaterialTheme.toolMap.panel,
        contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
        border = if (selected) null else BorderStroke(1.dp, scheme.outline),
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .height(32.dp)
                .semantics { this.selected = selected }
                .clip(MaterialTheme.shapes.small)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                    onLongClickLabel = onLongClick?.let { stringResource(R.string.maps_favorite_actions) },
                ),
    ) {
        Row(
            Modifier.padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** « Hors ligne » and « Couverture », two switches of the same kind side by side. */
@Composable
private fun SwitchCells(
    offline: Boolean,
    coverage: Boolean,
    onOffline: () -> Unit,
    onCoverage: () -> Unit,
) {
    val offlineSpoken =
        stringResource(R.string.maps_option_offline) + ", " + stringResource(R.string.maps_option_offline_sub)
    Row(
        Modifier.fillMaxWidth().padding(start = PANEL_INSET, end = PANEL_INSET, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SwitchCell(stringResource(R.string.maps_offline_short), offline, onOffline, spoken = offlineSpoken)
        SwitchCell(stringResource(R.string.maps_chip_coverage), coverage, onCoverage)
    }
}

@Composable
private fun RowScope.SwitchCell(
    label: String,
    on: Boolean,
    onToggle: () -> Unit,
    spoken: String? = null,
) {
    Row(
        Modifier
            .weight(1f)
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.toolMap.tile)
            .toggleable(value = on, role = Role.Switch, onValueChange = { onToggle() })
            .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.toolMap.onTile,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = on, onCheckedChange = null, modifier = Modifier.offset(x = (-2).dp))
    }
}

/** The four ways out of the panel, in a 2 × 2 grid: icon and name on one line (four do not fit in a row). */
@Composable
private fun NavTiles(actions: MapsRootActions) {
    val rows =
        listOf(
            listOf(
                Triple(MapsIcons.Map, R.string.maps_available, actions.onOpenAvailable),
                Triple(MapsIcons.CropFree, R.string.maps_tile_save_area, actions.onSaveArea),
            ),
            listOf(
                Triple(MapsIcons.Download, R.string.menu_downloads, actions.onOpenDownloads),
                Triple(SettingsIcons.Storage, R.string.maps_chip_storage, actions.onOpenStorage),
            ),
        )
    rows.forEach { row ->
        Row(
            Modifier.fillMaxWidth().padding(start = PANEL_INSET, end = PANEL_INSET, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            row.forEach { (icon, label, onClick) -> NavTile(icon, stringResource(label), onClick) }
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun RowScope.NavTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.toolMap.tile,
        contentColor = MaterialTheme.toolMap.onTile,
        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            // 13 sp, so that « Téléchargements » holds on one line in half the width of a phone.
            Text(
                label,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, lineHeight = 17.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
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

private val THUMBNAIL = 40.dp
private val THUMBNAIL_STEP = 14.dp
private const val MAX_THUMBNAILS = 3
