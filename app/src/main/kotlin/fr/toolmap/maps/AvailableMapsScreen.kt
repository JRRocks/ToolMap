package fr.toolmap.maps

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.maps.GroupKind
import fr.toolmap.core.maps.MapCatalog
import fr.toolmap.core.maps.MapGroup
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.wizard.SourceDraft
import fr.toolmap.download.formatBytes
import fr.toolmap.draw.RouteIcons
import fr.toolmap.map.MapViewModel
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap
import kotlinx.coroutines.launch

/** What the catalogue asks the activity to open (the wizard, the file picker, the storage screen). */
class AvailableMapsActions(
    val onBack: () -> Unit,
    val onOpenWizard: (sourceId: String?) -> Unit,
    val onImport: () -> Unit,
    val onOpenStorage: () -> Unit,
    /** Multi-zoom maps (ADR-031): a new one, an existing one, or one built from a map of the catalogue. */
    val onNewMultiZoom: () -> Unit = {},
    val onEditMultiZoom: (sourceId: String) -> Unit = {},
    val onDeriveMultiZoom: (sourceId: String) -> Unit = {},
    /** « Télécharger une zone… » (F4): back to the map, in « Sélectionner et enregistrer une zone » with this map. */
    val onSaveZone: (sourceId: String) -> Unit = {},
)

/** `.aqx` files have no registered type: the picker is asked for plain bytes, as in the wizard. */
private const val MIME_AQX = "application/octet-stream"

/** A map hidden from the list, shown greyed while « Afficher » is on (F3). */
private const val HIDDEN_ALPHA = 0.55f

/**
 * "Available maps": every map of the catalogue by family, with the storage gauge and a card menu per map. The online
 * catalogue (P16.4) opens over it, so that the sections left folded here stay as they are.
 */
@Composable
fun AvailableMapsScreen(
    maps: MapsViewModel,
    vm: MapViewModel,
    actions: AvailableMapsActions,
) {
    var showCatalog by rememberSaveable { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
        AvailableMapsContent(maps, vm, actions, onOpenCatalog = { showCatalog = true })
        if (showCatalog) {
            BackHandler { showCatalog = false }
            Surface(modifier = Modifier.fillMaxSize()) { OnlineCatalogScreen(maps) { showCatalog = false } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AvailableMapsContent(
    maps: MapsViewModel,
    vm: MapViewModel,
    actions: AvailableMapsActions,
    onOpenCatalog: () -> Unit,
) {
    val groups by maps.groups.collectAsStateWithLifecycle()
    val hidden by maps.hidden.collectAsStateWithLifecycle()
    val showHidden by maps.showHidden.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val figures by maps.storageFigures.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var collapsed by remember { mutableStateOf(emptySet<GroupKind>()) }
    var toDelete by remember { mutableStateOf<TileSource?>(null) }
    var fabMenu by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var toClear by remember { mutableStateOf<TileSource?>(null) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    val resources = LocalResources.current
    LaunchedEffect(Unit) { maps.refreshStorage() }
    val shownGroups =
        remember(groups, query) { MapCatalog.search(groups, query) { resources.getString(groupTitle(it)) } }
    val exporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_AQX)) { uri ->
            val id = exportId
            exportId = null
            if (uri != null && id != null) {
                scope.launch {
                    val message =
                        maps.exportAqx(id, uri).fold(
                            onSuccess = { resources.getString(R.string.wizard_exported, uri.lastPathSegment ?: id) },
                            onFailure = { e ->
                                resources.getString(R.string.storage_error, e.message ?: e.javaClass.simpleName)
                            },
                        )
                    snackbar.showSnackbar(message)
                }
            }
        }

    fun announce(source: TileSource) {
        scope.launch { snackbar.showSnackbar(resources.getString(R.string.am_shown, source.name)) }
    }

    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            ToolMapTopBar(
                title = stringResource(R.string.am_title),
                subtitle = stringResource(R.string.am_subtitle),
                onBack = actions.onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { fabMenu = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.am_add)) },
                )
                DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.catalog_menu)) },
                        onClick = {
                            fabMenu = false
                            onOpenCatalog()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.add_source_new)) },
                        onClick = {
                            fabMenu = false
                            actions.onOpenWizard(null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.am_new_multizoom)) },
                        onClick = {
                            fabMenu = false
                            actions.onNewMultiZoom()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_import)) },
                        onClick = {
                            fabMenu = false
                            actions.onImport()
                        },
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            SearchField(query) { query = it }
            if (hidden.isNotEmpty()) {
                FilterChip(
                    selected = showHidden,
                    onClick = { maps.setShowHidden(!showHidden) },
                    label = { Text(pluralStringResource(R.plurals.am_hidden_chip, hidden.size, hidden.size)) },
                    leadingIcon = { Icon(MapsIcons.VisibilityOff, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            val searching = query.isNotBlank()
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            ) {
                if (!searching) item(key = "gauge") { StorageGauge(figures, actions.onOpenStorage) }
                if (shownGroups.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            if (searching) {
                                stringResource(R.string.am_search_none, query.trim())
                            } else {
                                stringResource(R.string.am_empty)
                            },
                        )
                    }
                }
                for (group in shownGroups) {
                    // The results of a search are never folded away.
                    val open = searching || group.kind !in collapsed
                    item(key = "group-${group.kind}") {
                        GroupHeader(group, open, figures) {
                            collapsed = if (open) collapsed + group.kind else collapsed - group.kind
                        }
                    }
                    if (open) {
                        itemsIndexed(group.sources, key = { _, it -> "${group.kind}-${it.id}" }) { index, source ->
                            MapCard(
                                source = source,
                                maps = maps,
                                displayed = state.stack.layers.any { it.sourceId == source.id },
                                base = vm.isBase(source.id),
                                stored = figures?.perSource?.get(source.id),
                                hidden = source.id in hidden,
                                actions =
                                    MapCardActions(
                                        onShow = {
                                            if (vm.isBase(source.id)) {
                                                vm.replaceBase(source.id)
                                            } else {
                                                vm.addLayer(source.id)
                                            }
                                            announce(source)
                                        },
                                        onAddLayer = {
                                            vm.addLayer(source.id)
                                            announce(source)
                                        },
                                        onHide = { vm.hideSource(source.id) },
                                        onEdit = {
                                            if (source.isMultiZoom) {
                                                actions.onEditMultiZoom(source.id)
                                            } else {
                                                actions.onOpenWizard(source.id)
                                            }
                                        },
                                        onDerive = { actions.onDeriveMultiZoom(source.id) },
                                        onDelete = { toDelete = source },
                                        onSaveZone = { actions.onSaveZone(source.id) },
                                        onClear = { toClear = source },
                                        onExport = {
                                            exportId = source.id
                                            exporter.launch(maps.aqxFileName(source))
                                        },
                                        onHideFromList = {
                                            vm.hideSource(source.id)
                                            maps.hideFromList(source.id)
                                        },
                                        onShowInList = { maps.showInList(source.id) },
                                    ),
                                first = index == 0,
                                last = index == group.sources.lastIndex,
                            )
                        }
                    }
                }
                if (searching && shownGroups.isNotEmpty()) {
                    item(key = "search-note") {
                        Text(
                            stringResource(R.string.am_search_note),
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    toClear?.let { source ->
        AlertDialog(
            onDismissRequest = { toClear = null },
            title = { Text(stringResource(R.string.am_clear_tiles)) },
            text = { Text(stringResource(R.string.storage_confirm_purge, source.name)) },
            confirmButton = {
                TextButton(onClick = {
                    toClear = null
                    scope.launch {
                        val cleared = maps.clearTiles(source.id)
                        snackbar.showSnackbar(
                            resources.getString(
                                if (cleared) R.string.storage_purged else R.string.storage_error,
                                source.name,
                            ),
                        )
                    }
                }) { Text(stringResource(R.string.storage_purge)) }
            },
            dismissButton = {
                TextButton(onClick = { toClear = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    toDelete?.let { source ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.am_delete_title)) },
            text = { Text(stringResource(R.string.am_delete_text, source.name)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteSource(source.id)
                    toDelete = null
                }) { Text(stringResource(R.string.am_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** How much the phone holds, on a tile-coloured card: used and total on one line, a thin bar, the tiles and « Gérer ». */
@Composable
private fun StorageGauge(
    figures: StorageFigures?,
    onManage: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.toolMap.tile)
                .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
    ) {
        if (figures != null && figures.totalBytes > 0) {
            val used = (figures.totalBytes - figures.freeBytes).coerceAtLeast(0L)
            Row(Modifier.fillMaxWidth().padding(end = 10.dp)) {
                Text(
                    stringResource(R.string.am_storage_used, formatBytes(used)),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.am_storage_of, formatBytes(figures.totalBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
            }
            LinearProgressIndicator(
                progress = { used.toFloat() / figures.totalBytes },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp, end = 10.dp).height(5.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (figures != null) stringResource(R.string.am_storage_tiles, formatBytes(figures.tilesBytes)) else "",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onManage) { Text(stringResource(R.string.am_storage_manage)) }
        }
    }
}

@Composable
private fun GroupHeader(
    group: MapGroup,
    open: Boolean,
    figures: StorageFigures?,
    onToggle: () -> Unit,
) {
    val title = stringResource(groupTitle(group.kind))
    val bytes = figures?.let { f -> group.sources.sumOf { f.perSource[it.id]?.second ?: 0L } } ?: 0L
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 2.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            group.credit?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
        if (bytes > 0) {
            Text(
                stringResource(R.string.am_group_size, formatBytes(bytes)),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
        Icon(
            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = stringResource(if (open) R.string.am_collapse else R.string.am_expand),
            tint = muted,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Where a row stands in its group, for the white block the rows of a group make together (P29.0, « Crête »): rounded
 * at the top of the first row and the bottom of the last, a thin line between two rows.
 */
private fun groupedShape(
    first: Boolean,
    last: Boolean,
    radius: Dp,
): Shape =
    RoundedCornerShape(
        topStart = if (first) radius else 0.dp,
        topEnd = if (first) radius else 0.dp,
        bottomStart = if (last) radius else 0.dp,
        bottomEnd = if (last) radius else 0.dp,
    )

/** The corner of the white block of a group: the theme's medium shape. */
private val GROUP_RADIUS = 14.dp

/** The title of a section of the catalogue, also searched (F5). */
private fun groupTitle(kind: GroupKind): Int =
    when (kind) {
        GroupKind.OPENSTREETMAP -> R.string.am_group_openstreetmap
        GroupKind.IGN -> R.string.am_group_ign
        GroupKind.MULTI_ZOOM -> R.string.am_group_multizoom
        GroupKind.IMPORTED -> R.string.am_group_imported
        GroupKind.LOCAL -> R.string.am_group_local
        GroupKind.OTHER -> R.string.am_group_other
    }

/** « Rechercher une carte » (F5), above the list: it searches the name and the section of each map. */
@Composable
private fun SearchField(
    query: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.am_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.am_search_clear))
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.toolMap.panel,
                unfocusedContainerColor = MaterialTheme.toolMap.panel,
            ),
    )
}

/** What the commands of a card's menu do. */
private class MapCardActions(
    val onShow: () -> Unit,
    val onAddLayer: () -> Unit,
    val onHide: () -> Unit,
    val onEdit: () -> Unit,
    val onDerive: () -> Unit,
    val onDelete: () -> Unit,
    val onSaveZone: () -> Unit,
    val onClear: () -> Unit,
    val onExport: () -> Unit,
    val onHideFromList: () -> Unit,
    val onShowInList: () -> Unit,
)

@Composable
private fun MapCard(
    source: TileSource,
    maps: MapsViewModel,
    displayed: Boolean,
    base: Boolean,
    stored: Pair<Long, Long>?,
    hidden: Boolean,
    actions: MapCardActions,
    first: Boolean,
    last: Boolean,
) {
    var menu by remember { mutableStateOf(false) }
    val supported = source.isSupported
    val imported = maps.isImported(source.id)
    // A row of the white block of its group (P29.0): the rows of a section are one list, not one card each.
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(groupedShape(first, last, GROUP_RADIUS))
                .background(MaterialTheme.toolMap.panel),
    ) {
        if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .alpha(if (hidden) HIDDEN_ALPHA else 1f)
                    .clickable(enabled = supported && !hidden, onClick = actions.onShow)
                    .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MapThumbnail(source.id, source.name, maps.thumbnails, size = 48.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    source.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (hidden) {
                        stringResource(R.string.am_hidden_note)
                    } else if (supported) {
                        stringResource(
                            R.string.add_source_detail,
                            stringResource(if (source.isLayer) R.string.source_overlay else R.string.source_base),
                            source.minZoom,
                            source.maxZoom,
                        )
                    } else {
                        stringResource(R.string.source_unsupported, source.unsupportedReason.orEmpty())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!hidden && stored != null && stored.first > 0) {
                    Text(
                        stringResource(
                            R.string.maps_details_stored,
                            tilesText(LocalResources.current, stored.first),
                            formatBytes(stored.second),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (displayed) {
                    // The badge of a map shown: a turquoise pill.
                    Text(
                        stringResource(R.string.am_displayed_badge),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier =
                            Modifier
                                .padding(top = 4.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 10.dp, vertical = 2.dp),
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.am_card_menu),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    @Composable
                    fun item(
                        label: Int,
                        enabled: Boolean = true,
                        icon: ImageVector? = null,
                        trailing: String? = null,
                        action: () -> Unit,
                    ) = DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        enabled = enabled,
                        leadingIcon = icon?.let { { Icon(it, contentDescription = null) } },
                        trailingIcon =
                            trailing?.let {
                                {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        onClick = {
                            menu = false
                            action()
                        },
                    )
                    if (hidden) {
                        // A hidden map only comes back to the list (F3).
                        item(R.string.am_unhide, icon = MapsIcons.Visibility, action = actions.onShowInList)
                    } else {
                        if (base) item(R.string.am_show_base, supported, action = actions.onShow)
                        item(R.string.am_add_layer, supported, action = actions.onAddLayer)
                        if (displayed) item(R.string.am_hide, action = actions.onHide)
                        if (source.isMultiZoom) {
                            item(
                                if (imported) R.string.am_edit_zooms else R.string.am_copy_zooms,
                                action = actions.onEdit,
                            )
                        } else {
                            if (SourceDraft.fromSource(source) != null) {
                                item(
                                    if (imported) R.string.add_source_edit else R.string.add_source_copy,
                                    action = actions.onEdit,
                                )
                            }
                            if (supported) item(R.string.am_derive_multizoom, action = actions.onDerive)
                        }
                        // F4: the zone, the stored tiles and the file of this map.
                        val canSave = supported && !source.isLocalOnly
                        val storedBytes = stored?.takeIf { it.first > 0 && !source.isMultiZoom }?.second
                        val canExport = !source.isMultiZoom && !source.isLocalOnly
                        if (canSave || storedBytes != null || canExport) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            if (canSave) {
                                item(R.string.am_save_zone, icon = MapsIcons.Download, action = actions.onSaveZone)
                            }
                            if (storedBytes != null) {
                                item(
                                    R.string.am_clear_tiles,
                                    icon = RouteIcons.DeleteSweep,
                                    trailing = formatBytes(storedBytes),
                                    action = actions.onClear,
                                )
                            }
                            if (canExport) {
                                item(R.string.am_export_aqx, icon = MapsIcons.IosShare, action = actions.onExport)
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        if (imported) {
                            item(R.string.am_delete, action = actions.onDelete)
                        } else {
                            // F3: a provided map cannot be deleted, it leaves the list.
                            item(
                                R.string.am_hide_from_list,
                                icon = MapsIcons.VisibilityOff,
                                action = actions.onHideFromList,
                            )
                        }
                    }
                }
            }
        }
    }
}
