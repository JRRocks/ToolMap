package fr.toolmap.maps

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.layers.FavoriteChange
import fr.toolmap.core.layers.FavoriteChanges
import fr.toolmap.core.layers.Favorites
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.layers.LayerElevation
import fr.toolmap.core.layers.LayerField
import fr.toolmap.core.layers.LayerZoom
import fr.toolmap.core.layers.Workspace
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.state.WorkState
import fr.toolmap.map.BarPanel
import fr.toolmap.map.MapViewModel
import fr.toolmap.map.MenuIcons
import fr.toolmap.map.PANEL_INSET
import fr.toolmap.map.PanelBand
import fr.toolmap.map.PanelCaption
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.FilledTonalButton
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap

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
    /** A favourite was just renamed [name]: the screen offers « Annuler », which puts back [previous] (P25.5). */
    val onFavoriteRenamed: (previous: Workspace, name: String) -> Unit = { _, _ -> },
)

/*
 * « Cartes et calques » since P29.3 (« Strates »), lightened by P34 (style 4 « Fiche du calque », docs/previews/
 * p34-cartes-onglets.html, band 4): the layer stack is the panel itself. The band says what is shown and how many maps
 * are drawn at the zoom of the map; the favourites are a row of chips; each layer is a line (drag handle, thumbnail,
 * name, eye, ›) whose touch opens its sheet in the panel ([LayerSheet], ← back to the list), the sheet opening its
 * « Couleurs » page ([ColorsPage]); at the foot, « Cartes disponibles » and « Hors ligne… », which opens the « Hors
 * ligne » page ([OfflinePage]; docs/previews/p34-complements.html, A, D, F and G validated on 2026-11-05). While a
 * slider of a sheet or of its colours is held, everything else fades away so that the map shows what it changes
 * (P26.6).
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
    val view by maps.view.collectAsStateWithLifecycle()
    var showSave by remember { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }
    // The favourite whose sheet is open, and the one being renamed (P25.5), by id.
    var sheetFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    val byId = remember(sources) { sources.associateBy { it.id } }
    val layers = state.stack.topToBottom()
    val applied = Favorites.applied(favorites, state.favorite, state.stack)
    // The favourite the stack came from, changed since: « Mettre à jour » replaces it (P25.9, variant B).
    val modified = Favorites.modified(favorites, state.favorite, state.stack)
    val changes = remember(modified, state.stack) { modified?.let { FavoriteChanges.of(it, state.stack) }.orEmpty() }
    val drawn = layers.count { it.visible && it.opacity > 0f && LayerElevation.isDrawnAt(it, currentZoom) }
    val fade = rememberPanelFade()
    val favoriteName = applied?.name ?: modified?.let { stringResource(R.string.maps_band_modified, it.name) }
    // The layer whose sheet is open (P34); gone from the stack, the list comes back.
    val sheet = view.layerId?.let { id -> layers.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
    LaunchedEffect(view.layerId, sheet) { if (view.layerId != null && sheet == null) maps.show(RootView()) }
    BackHandler(enabled = view != RootView()) { maps.show(view.back()) }
    val offlinePage = sheet == null && view.page == RootPage.OFFLINE
    val sheetName =
        sheet?.let { layers[it].sourceId }?.let { byId[it]?.name ?: stringResource(R.string.layers_missing_source, it) }

    BarPanel(
        maxHeight = maxHeight,
        modifier = modifier,
        containerAlpha = fade.shown,
        header = {
            Box(Modifier.alpha(fade.shown)) {
                if (sheet != null && view.page == RootPage.COLORS) {
                    BackBand(
                        title = stringResource(R.string.maps_cmd_colors),
                        subtitle = sheetName.orEmpty(),
                        onBack = { maps.show(RootView(layers[sheet].id)) },
                        onClose = maps::close,
                    )
                } else if (sheet != null) {
                    LayerSheetBand(
                        name = sheetName.orEmpty(),
                        favorite = favoriteName,
                        rank = sheet + 1,
                        count = layers.size,
                        onBack = { maps.show(RootView()) },
                        onClose = maps::close,
                    )
                } else if (offlinePage) {
                    BackBand(
                        title = stringResource(R.string.maps_offline_short),
                        subtitle = stringResource(R.string.maps_offline_page_sub),
                        onBack = { maps.show(RootView()) },
                        onClose = maps::close,
                    )
                } else {
                    MapsBand(
                        favorite = favoriteName,
                        single = layers.singleOrNull()?.let { byId[it.sourceId]?.name },
                        count = layers.size,
                        drawn = drawn,
                        zoom = LayerZoom.shownZoom(currentZoom),
                        offline = offline,
                        onShare = { actions.onShareFavorites(applied?.id) },
                        onSave = { showSave = true },
                        onHelp = actions.onHelp,
                    )
                }
            }
        },
    ) {
        if (sheet != null && view.page == RootPage.COLORS) {
            ColorsPage(
                layer = layers[sheet],
                name = sheetName.orEmpty(),
                below = layers.drop(sheet + 1),
                thumbnails = maps.thumbnails,
                fade = fade,
                vm = vm,
            )
        } else if (sheet != null) {
            LayerSheetContent(
                layer = layers[sheet],
                source = byId[layers[sheet].sourceId],
                name = sheetName.orEmpty(),
                rank = sheet + 1,
                count = layers.size,
                maps = maps,
                vm = vm,
                currentZoom = currentZoom,
                fade = fade,
            )
        } else if (offlinePage) {
            val jobs by maps.jobs.collectAsStateWithLifecycle()
            val figures by maps.storageFigures.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { maps.refreshStorage() }
            OfflinePage(
                offline = offline,
                coverage = coverage,
                jobs = jobs,
                figures = figures,
                onOffline = vm::setOffline,
                onCoverage = vm::setCoverage,
                onSaveArea = actions.onSaveArea,
                onDownloads = actions.onOpenDownloads,
                onStorage = actions.onOpenStorage,
            )
        } else {
            StackList(
                layers = layers,
                favorites = favorites,
                applied = applied,
                modified = modified,
                changes = changes,
                hiddenBuiltins = hiddenBuiltins.size,
                byId = byId,
                maps = maps,
                vm = vm,
                actions = actions,
                currentZoom = currentZoom,
                offline = offline,
                onFavorite = { sheetFor = it.id },
                onSave = { showSave = true },
                onUpdate = { showUpdate = true },
            )
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
    favorites.firstOrNull { it.id == sheetFor }?.let { favorite ->
        val cover = coverOf(favorite, byId)
        FavoriteSheet(
            favorite = favorite,
            modified = favorite.id == modified?.id,
            cover = cover,
            coverName = cover?.let { byId[it]?.name } ?: favorite.name,
            thumbnails = maps.thumbnails,
            actions =
                FavoriteSheetActions(
                    onApply = vm::applyWorkspace,
                    onRename = { renaming = it.id },
                    onShare = { actions.onShareFavorites(it.id) },
                    onRestoreOriginal = { vm.restoreOriginalWorkspace(it.id) },
                    onDelete = {
                        vm.deleteWorkspace(it.id)
                        actions.onFavoriteDeleted(it)
                    },
                ),
            onClose = { sheetFor = null },
        )
    }
    favorites.firstOrNull { it.id == renaming }?.let { favorite ->
        RenameFavoriteDialog(
            favorite = favorite,
            favorites = favorites,
            onRename = { name ->
                vm.renameFavorite(favorite, name)
                actions.onFavoriteRenamed(favorite, name)
                renaming = null
            },
            onDismiss = { renaming = null },
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
 * The list of the panel (P34, band 4): the favourites, « Du dessus vers le dessous » and one line per map, what keeps
 * the stack as a favourite, then two links, « Cartes disponibles » and « Hors ligne… ».
 */
@Composable
private fun StackList(
    layers: List<LayerConfig>,
    favorites: List<Workspace>,
    applied: Workspace?,
    modified: Workspace?,
    changes: List<FavoriteChange>,
    hiddenBuiltins: Int,
    byId: Map<String, TileSource>,
    maps: MapsViewModel,
    vm: MapViewModel,
    actions: MapsRootActions,
    currentZoom: Double,
    offline: Boolean,
    onFavorite: (Workspace) -> Unit,
    onSave: () -> Unit,
    onUpdate: () -> Unit,
) {
    FavoriteChips(
        favorites = favorites,
        applied = applied,
        modified = modified,
        hiddenBuiltins = hiddenBuiltins,
        byId = byId,
        thumbnails = maps.thumbnails,
        onApply = vm::applyWorkspace,
        onOpen = onFavorite,
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
        onOpen = { maps.show(RootView(it)) },
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
    if (layers.isNotEmpty() && modified != null) {
        UpdateButtons(onUpdate = onUpdate, onNew = onSave)
    } else if (layers.isNotEmpty() && applied == null) {
        // The stack matches no favourite: the way to keep it, next to it.
        TextButton(onClick = onSave, modifier = Modifier.padding(start = 8.dp)) {
            Icon(MapsIcons.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.maps_save_hint_button), modifier = Modifier.padding(start = 8.dp))
        }
    }
    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = actions.onOpenAvailable) {
            Icon(MapsIcons.Map, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.maps_available), modifier = Modifier.padding(start = 8.dp))
        }
        // On, the link says it in the accent colour (choice A, P34.1).
        val accent = MaterialTheme.toolMap.accent
        TextButton(
            onClick = { maps.show(RootView(page = RootPage.OFFLINE)) },
            colors =
                if (offline) {
                    ButtonDefaults.textButtonColors(
                        contentColor = accent,
                    )
                } else {
                    ButtonDefaults.textButtonColors()
                },
        ) {
            Icon(MapsIcons.CloudOff, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                stringResource(if (offline) R.string.maps_offline_link_on else R.string.maps_offline_link),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * The sheet of [layer] (P34): [LayerSheet] on the view models, with the window where its opacity is typed and the
 * « Détails » of « Plus ».
 */
@Composable
private fun LayerSheetContent(
    layer: LayerConfig,
    source: TileSource?,
    name: String,
    rank: Int,
    count: Int,
    maps: MapsViewModel,
    vm: MapViewModel,
    currentZoom: Double,
    fade: PanelFade,
) {
    var typing by remember(layer.id) { mutableStateOf(false) }
    var details by remember(layer.id) { mutableStateOf(false) }
    val forced by maps.forced.collectAsStateWithLifecycle()
    val isForced = layer.sourceId in forced
    LayerSheet(
        layer = layer,
        name = name,
        rank = rank,
        count = count,
        thumbnails = maps.thumbnails,
        currentZoom = currentZoom,
        fade = fade,
        onVisible = { vm.setVisible(layer.id, it) },
        onOpacity = { vm.setOpacity(layer.id, it) },
        onTypeOpacity = { typing = true },
        onConditions = { maps.openConditions(layer.id) },
        onColors = { maps.show(RootView(layer.id, RootPage.COLORS)) },
        onUp = { vm.moveLayerUp(layer.id) },
        onDown = { vm.moveLayerDown(layer.id) },
        more =
            MoreActions(
                forced = isForced,
                onDetails = { details = true },
                onForce = { maps.setForced(layer.sourceId, !isForced) },
                onRemoveOthers = { vm.removeOtherLayers(layer.id) },
                // The layer gone, the panel comes back to the list.
                onRemove = { vm.removeLayer(layer.id) },
            ),
    )
    if (typing) AdjustDialog(Adjust.OPACITY, layer, vm) { typing = false }
    if (details) DetailsDialog(layer, source, maps, isForced) { details = false }
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
    /** The offline mode is on: the second line says it, instead of the maps drawn (choice A, P34.1). */
    offline: Boolean,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onHelp: () -> Unit,
) {
    val maps = pluralStringResource(R.plurals.maps_band_count, count, count)
    val offlineLine = stringResource(R.string.maps_band_offline)
    val subtitle =
        if (count == 0) {
            stringResource(R.string.maps_band_empty) + if (offline) "\n$offlineLine" else ""
        } else {
            val what = (favorite ?: single)?.let { "$it · $maps" } ?: maps
            val now =
                when {
                    offline -> offlineLine
                    drawn == 0 -> stringResource(R.string.maps_band_none_drawn, zoom)
                    else -> pluralStringResource(R.plurals.maps_band_drawn, drawn, drawn, zoom)
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
 * long press opens its sheet ([FavoriteSheet], P25.5). « Rétablir (n) » last brings back the provided ones deleted.
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
    onOpen: (Workspace) -> Unit,
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
                onOpen = { onOpen(favorite) },
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
    onOpen: () -> Unit,
) {
    ChipFrame(selected = selected, modified = modified, onClick = onApply, onLongClick = onOpen) {
        MapThumbnail(cover.orEmpty(), coverName, thumbnails, size = 24.dp, shape = CircleShape)
        Text(favorite.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        if (modified) Box(Modifier.size(8.dp).background(MaterialTheme.toolMap.accent, CircleShape))
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
