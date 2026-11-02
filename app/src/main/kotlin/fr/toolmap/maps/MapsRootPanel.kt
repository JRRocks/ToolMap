package fr.toolmap.maps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.layers.FavoriteChange
import fr.toolmap.core.layers.FavoriteChanges
import fr.toolmap.core.layers.Favorites
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.layers.LayerElevation
import fr.toolmap.core.layers.LayerField
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
import fr.toolmap.ui.theme.FilledTonalButton
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
    /** The « Partager » icon of the band: « Partager des favoris », the favourite applied ticked (P25.2). */
    val onShareFavorites: (applied: String?) -> Unit = {},
    /** A favourite was just updated: the screen offers « Annuler », which puts back [previous] (P25.9). */
    val onFavoriteUpdated: (previous: Workspace) -> Unit = {},
)

/*
 * « Cartes et calques » since P29.3 (« Strates », docs/previews/design-03-cartes.html, variant C): the layer stack is
 * the panel itself. The band says what is shown and how many maps are drawn at the zoom of the map; the favourites are
 * a row of chips; each layer is a line (drag handle, thumbnail, name and state, eye) that unfolds in place with its
 * opacity, its order, its conditions, its colours and the rest of its commands; at the foot, the offline mode and the
 * coverage as two switches, then the four ways to the catalogue, the zone, the downloads and the storage. While a
 * slider of the unfolded line is held, everything else fades away so that the map shows what it changes (P26.6).
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
    var showUpdate by remember { mutableStateOf(false) }
    val byId = remember(sources) { sources.associateBy { it.id } }
    val layers = state.stack.topToBottom()
    val applied = Favorites.applied(favorites, state.favorite, state.stack)
    // The favourite the stack came from, changed since: « Mettre à jour » replaces it (P25.9, variant B).
    val modified = Favorites.modified(favorites, state.favorite, state.stack)
    val changes = remember(modified, state.stack) { modified?.let { FavoriteChanges.of(it, state.stack) }.orEmpty() }
    val drawn = layers.count { it.visible && it.opacity > 0f && LayerElevation.isDrawnAt(it, currentZoom) }
    val fade = rememberPanelFade()

    BarPanel(
        maxHeight = maxHeight,
        modifier = modifier,
        containerAlpha = fade.shown,
        header = {
            Box(Modifier.alpha(fade.shown)) {
                MapsBand(
                    favorite = applied?.name ?: modified?.let { stringResource(R.string.maps_band_modified, it.name) },
                    single = layers.singleOrNull()?.let { byId[it.sourceId]?.name },
                    count = layers.size,
                    drawn = drawn,
                    zoom = floor(currentZoom).toInt(),
                    onShare = { actions.onShareFavorites(applied?.id) },
                    onSave = { showSave = true },
                    onHelp = actions.onHelp,
                )
            }
        },
    ) {
        Column(Modifier.alpha(fade.shown)) {
            FavoriteChips(
                favorites = favorites,
                applied = applied,
                modified = modified,
                hiddenBuiltins = hiddenBuiltins.size,
                byId = byId,
                thumbnails = maps.thumbnails,
                onApply = vm::applyWorkspace,
                onDelete = { favorite ->
                    vm.deleteWorkspace(favorite.id)
                    actions.onFavoriteDeleted(favorite)
                },
                onRestoreOriginal = { vm.restoreOriginalWorkspace(it.id) },
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
        }
        StackRows(
            layers = layers,
            byId = byId,
            maps = maps,
            vm = vm,
            currentZoom = currentZoom,
            expanded = expanded,
            onExpand = maps::setExpanded,
            fade = fade,
            changes =
                changes
                    .mapNotNull { change ->
                        when (change) {
                            is FavoriteChange.Changed -> change.layerId to change
                            is FavoriteChange.Added -> change.layerId to change
                            else -> null
                        }
                    }.toMap(),
        )
        Column(Modifier.alpha(fade.shown)) {
            if (layers.isNotEmpty() && modified != null) {
                UpdateButtons(onUpdate = { showUpdate = true }, onNew = { showSave = true })
            } else if (layers.isNotEmpty() && applied == null) {
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
    if (showUpdate && modified != null) {
        UpdateFavoriteDialog(
            name = modified.name,
            lines = changeLines(changes, state.stack.layers.associateBy { it.id }, byId),
            onConfirm = {
                vm.updateFavorite(modified)
                actions.onFavoriteUpdated(modified)
                showUpdate = false
            },
            onDismiss = { showUpdate = false },
        )
    }
}

/**
 * Under the stack changed from a favourite (P25.9, variant B): « Mettre à jour » (tonal, the free width), which asks
 * first, and « Nouveau… », the « Nouveau favori » dialog of before.
 */
@Composable
private fun UpdateButtons(
    onUpdate: () -> Unit,
    onNew: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = PANEL_INSET, end = PANEL_INSET, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = onUpdate,
            shape = CircleShape,
            colors =
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = scheme.primaryContainer,
                    contentColor = scheme.onPrimaryContainer,
                ),
            modifier = Modifier.weight(1f).height(40.dp),
        ) {
            Icon(MapsIcons.Save, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.maps_favorite_update),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        TextButton(onClick = onNew, shape = CircleShape) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.maps_favorite_new_short), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** « Mettre à jour « Rando été » ? » and what changes, one line per map, nothing else (validated 2026-11-02). */
@Composable
private fun UpdateFavoriteDialog(
    name: String,
    lines: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // The name holds on one line, as « Rando été » in the preview.
        title = { Text(stringResource(R.string.maps_favorite_update_title, name.replace(' ', NBSP))) },
        text = { Text(lines.joinToString("\n")) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.maps_favorite_update)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** One line per change, as the dialog says them: « IGN Pentes : 31 % → 50 % ». */
@Composable
internal fun changeLines(
    changes: List<FavoriteChange>,
    layers: Map<String, LayerConfig>,
    byId: Map<String, TileSource>,
): List<String> {
    fun nameOf(sourceId: String) = byId[sourceId]?.name ?: sourceId
    return changes.map { change ->
        when (change) {
            is FavoriteChange.Changed -> {
                val now = layers[change.layerId]
                val was = change.was
                val what =
                    change.fields.sorted().map { field ->
                        when (field) {
                            LayerField.VISIBILITY -> {
                                stringResource(
                                    if (now?.visible ==
                                        false
                                    ) {
                                        R.string.fav_change_hidden
                                    } else {
                                        R.string.fav_change_shown
                                    },
                                )
                            }

                            LayerField.OPACITY -> {
                                stringResource(
                                    R.string.fav_change_opacity,
                                    percentOf(was.opacity),
                                    percentOf(now?.opacity ?: was.opacity),
                                )
                            }

                            LayerField.ZOOM -> {
                                stringResource(
                                    R.string.fav_change_zoom,
                                    zoomLineText(was.displayMinZoom, was.displayMaxZoom),
                                    zoomLineText(now?.displayMinZoom, now?.displayMaxZoom),
                                )
                            }

                            LayerField.ELEVATION -> {
                                stringResource(R.string.fav_change_elevation)
                            }

                            LayerField.COLOURS -> {
                                stringResource(R.string.fav_change_colours)
                            }
                        }
                    }
                stringResource(R.string.fav_change_line, nameOf(change.sourceId), what.joinToString(", "))
            }

            is FavoriteChange.Added -> {
                stringResource(
                    R.string.fav_change_line,
                    nameOf(change.sourceId),
                    stringResource(R.string.fav_change_added),
                )
            }

            is FavoriteChange.Removed -> {
                stringResource(
                    R.string.fav_change_line,
                    nameOf(change.was.sourceId),
                    stringResource(R.string.fav_change_removed),
                )
            }

            FavoriteChange.Reordered -> {
                stringResource(R.string.fav_change_order)
            }
        }
    }
}

private const val NBSP = ' '

/**
 * The band of the panel: its title, then what is shown (the favourite applied, else the only map, with the number of
 * maps) and how many of them the map draws at its zoom; « Partager » (favourites, P25.2), « Enregistrer l'affichage »
 * and the « ? » at the end.
 */
@Composable
private fun MapsBand(
    favorite: String?,
    single: String?,
    count: Int,
    drawn: Int,
    zoom: Int,
    onShare: () -> Unit,
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
        IconButton(onClick = onShare) {
            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.fav_share_title))
        }
        IconButton(onClick = onSave) {
            Icon(MapsIcons.BookmarkAdd, contentDescription = stringResource(R.string.maps_favorite_add_tile))
        }
        IconButton(onClick = onHelp) {
            Icon(MenuIcons.Help, contentDescription = stringResource(R.string.menu_help))
        }
    }
}

/**
 * The favourites as chips: the round picture of the top base map of each, its name; the one applied is filled, the
 * one the stack came from and changed since is dashed with a dot (P25.9). A tap applies it (the panel stays open); a
 * long press offers « Appliquer », « Rétablir l'original » for a provided one the user updated, and « Supprimer ».
 * « Rétablir (n) » last brings back the provided ones deleted.
 */
@Composable
private fun FavoriteChips(
    favorites: List<Workspace>,
    applied: Workspace?,
    modified: Workspace?,
    hiddenBuiltins: Int,
    byId: Map<String, TileSource>,
    thumbnails: MapThumbnails,
    onApply: (Workspace) -> Unit,
    onDelete: (Workspace) -> Unit,
    onRestoreOriginal: (Workspace) -> Unit,
    onRestore: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = PANEL_INSET),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 2.dp),
    ) {
        items(favorites, key = { it.id }) { favorite ->
            val cover = coverOf(favorite, byId)
            FavoriteChip(
                favorite = favorite,
                selected = favorite.id == applied?.id,
                modified = favorite.id == modified?.id,
                cover = cover,
                coverName = cover?.let { byId[it]?.name } ?: favorite.name,
                thumbnails = thumbnails,
                onApply = { onApply(favorite) },
                onDelete = { onDelete(favorite) },
                onRestoreOriginal = { onRestoreOriginal(favorite) }.takeIf { Favorites.isOverridden(favorite) },
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

/** The top map of [favorite] that is not an overlay: what it shows first, and its picture. */
internal fun coverOf(
    favorite: Workspace,
    byId: Map<String, TileSource>,
): String? =
    favorite.layers.asReversed().firstOrNull { byId[it.sourceId]?.isLayer == false }?.sourceId
        ?: favorite.layers.lastOrNull()?.sourceId

@Composable
private fun FavoriteChip(
    favorite: Workspace,
    selected: Boolean,
    modified: Boolean,
    cover: String?,
    coverName: String,
    thumbnails: MapThumbnails,
    onApply: () -> Unit,
    onDelete: () -> Unit,
    onRestoreOriginal: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ChipFrame(selected = selected, modified = modified, onClick = onApply, onLongClick = { menu = true }) {
            MapThumbnail(cover.orEmpty(), coverName, thumbnails, size = 24.dp, shape = CircleShape)
            Text(favorite.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            if (modified) Box(Modifier.size(8.dp).background(MaterialTheme.toolMap.accent, CircleShape))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.maps_favorite_apply)) },
                onClick = {
                    menu = false
                    onApply()
                },
            )
            if (onRestoreOriginal != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.maps_favorite_restore_original)) },
                    onClick = {
                        menu = false
                        onRestoreOriginal()
                    },
                )
            }
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

/**
 * A chip of 32 dp (a 48 dp touch target): filled with the secondary container when selected, outlined otherwise;
 * [modified] (P25.9): dashed in the accent colour, 1.5 dp.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChipFrame(
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modified: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    val accent = MaterialTheme.toolMap.accent
    val modifiedState = stringResource(R.string.maps_favorite_modified_state)
    Surface(
        shape = shape,
        color = if (selected) scheme.secondaryContainer else MaterialTheme.toolMap.panel,
        contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
        border = if (selected || modified) null else BorderStroke(1.dp, scheme.outline),
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .height(32.dp)
                .semantics {
                    this.selected = selected
                    if (modified) stateDescription = modifiedState
                }.then(if (modified) Modifier.dashedBorder(1.5.dp, accent, shape) else Modifier)
                .clip(shape)
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

/** A dashed outline of [width] inside the bounds, over the content (the chip of a modified favourite). */
private fun Modifier.dashedBorder(
    width: Dp,
    color: Color,
    shape: Shape,
) = drawWithContent {
    drawContent()
    val stroke = width.toPx()
    inset(stroke / 2) {
        drawOutline(
            shape.createOutline(size, layoutDirection, this),
            color,
            style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
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
