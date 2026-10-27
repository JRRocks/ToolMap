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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.maps.CatalogEntry
import fr.toolmap.core.maps.GroupKind
import fr.toolmap.core.maps.MapCatalog
import fr.toolmap.core.maps.MapGroup
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.wizard.SourceDraft
import fr.toolmap.download.formatBytes
import fr.toolmap.draw.RouteIcons
import fr.toolmap.map.MapViewModel
import fr.toolmap.map.MenuIcons
import fr.toolmap.settings.SettingsIcons
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.groupedShape
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.Switch
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap
import fr.toolmap.wizard.WizardKind
import kotlinx.coroutines.launch

/** What the catalogue asks the activity to open (the wizard, the file picker, the storage screen). */
class AvailableMapsActions(
    val onBack: () -> Unit,
    /** The wizard on an existing map (modified, or copied when it is provided with the app). */
    val onOpenWizard: (sourceId: String?) -> Unit,
    /** « Importer un fichier (.aqx, .mbtiles) »: one picker, the extension says which import it is. */
    val onImport: () -> Unit,
    val onOpenStorage: () -> Unit,
    /** Multi-zoom maps (ADR-031): a new one, an existing one, or one built from a map of the catalogue. */
    val onNewMultiZoom: () -> Unit = {},
    val onEditMultiZoom: (sourceId: String) -> Unit = {},
    val onDeriveMultiZoom: (sourceId: String) -> Unit = {},
    /** « Télécharger une zone… » (F4): back to the map, in « Sélectionner et enregistrer une zone » with this map. */
    val onSaveZone: (sourceId: String) -> Unit = {},
    /** A new map of the kind chosen in the sheet « Ajouter une carte » (P29.6): the wizard starts at its address. */
    val onNewMap: (WizardKind) -> Unit = {},
)

/** `.aqx` files have no registered type: the picker is asked for plain bytes, as in the wizard. */
private const val MIME_AQX = "application/octet-stream"

/** A map hidden from the list, shown greyed while « Afficher » is on (F3). */
private const val HIDDEN_ALPHA = 0.55f

/** The two segments of the screen (P29.6): the base maps, of which one is shown, and the layers, any number. */
private enum class Segment { BASES, LAYERS }

/**
 * "Available maps": every map of the catalogue by family, and the way to show it. Since P29.6 (« Bascule ») two
 * segments: « Fonds de carte », where a radio says which base is shown (the lowest of the stack, the one a tap
 * replaces), and « Calques », where a switch says whether a layer is shown; a tap does the same, with « Annuler » in
 * its message. The bar says what the map shows (« Fond : IGN Cartes · 1 calque »), its loupe searches the list and
 * the online catalogue, its ⋮ leads to the storage and the hidden maps. The online catalogue opens over the screen, so
 * that the sections left folded here stay as they are.
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
            Surface(modifier = Modifier.fillMaxSize()) {
                OnlineCatalogScreen(maps, onShow = { id -> showMap(vm, id) }) { showCatalog = false }
            }
        }
    }
}

/** Shows [sourceId]: a base replaces the lowest base of the stack, a layer goes on top. */
private fun showMap(
    vm: MapViewModel,
    sourceId: String,
) {
    if (vm.isBase(sourceId)) vm.replaceBase(sourceId) else vm.addLayer(sourceId)
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
    val added by maps.catalogAdded.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val figures by maps.storageFigures.collectAsStateWithLifecycle()
    val sources by maps.sources.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var segment by rememberSaveable { mutableStateOf(Segment.BASES) }
    var collapsed by remember { mutableStateOf(emptySet<GroupKind>()) }
    var toDelete by remember { mutableStateOf<TileSource?>(null) }
    var addSheet by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var toClear by remember { mutableStateOf<TileSource?>(null) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var askingKey by remember { mutableStateOf<CatalogEntry?>(null) }
    val resources = LocalResources.current
    LaunchedEffect(Unit) { maps.refreshStorage() }
    LaunchedEffect(maps) { maps.notices.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }
    val bottomBase = state.stack.baseLayer(vm::isBase)?.sourceId
    val shownIds = state.stack.layers.map { it.sourceId }.toSet()
    val searchText = query.takeIf { searching }.orEmpty()
    val listed =
        remember(groups, searchText, segment, bottomBase, shownIds) {
            val found = MapCatalog.search(groups, searchText) { resources.getString(groupTitle(it)) }
            if (searchText.isNotBlank()) {
                found
            } else {
                found.mapNotNull { group ->
                    val kept = group.sources.filter { inSegment(it, segment, vm.isBase(it.id), bottomBase, shownIds) }
                    if (kept.isEmpty()) null else group.copy(sources = kept)
                }
            }
        }
    val catalogFound =
        remember(searchText) { if (searchText.isBlank()) emptyList() else maps.catalogMatches(searchText) }
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

    // « Carte affichée : X », with « Annuler », which puts the stack back as it was.
    fun showWithUndo(source: TileSource) {
        val before = vm.state.value.stack
        showMap(vm, source.id)
        scope.launch {
            val answer =
                snackbar.showSnackbar(
                    resources.getString(R.string.am_shown, source.name),
                    actionLabel = resources.getString(R.string.am_shown_undo),
                    duration = SnackbarDuration.Short,
                )
            if (answer == SnackbarResult.ActionPerformed) vm.restoreStack(before)
        }
    }

    fun addFromCatalog(
        entry: CatalogEntry,
        keys: Map<String, String>,
    ) {
        maps.addFromCatalog(entry, keys)
        // « Afficher », as in the online catalogue.
        scope.launch {
            val answer =
                snackbar.showSnackbar(
                    resources.getString(R.string.catalog_added_message, entry.source.name),
                    actionLabel = resources.getString(R.string.catalog_show),
                    duration = SnackbarDuration.Short,
                )
            if (answer == SnackbarResult.ActionPerformed) showMap(vm, entry.source.id)
        }
    }

    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            if (searching) {
                SearchBar(
                    query = query,
                    onChange = { query = it },
                    onClose = {
                        searching = false
                        query = ""
                    },
                )
            } else {
                ToolMapTopBar(
                    title = stringResource(R.string.am_title),
                    subtitle = statusLine(state.stack.layers.map { it.sourceId }, bottomBase, sources),
                    onBack = actions.onBack,
                    actions = {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.am_search_open))
                        }
                        BarMenu(
                            figures = figures,
                            hiddenCount = hidden.size,
                            showHidden = showHidden,
                            onStorage = actions.onOpenStorage,
                            onShowHidden = { maps.setShowHidden(!showHidden) },
                        )
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { addSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.am_add)) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            val searchingNow = searchText.isNotBlank()
            if (!searching) {
                SingleChoiceSegmentedButtonRow(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                ) {
                    listOf(Segment.BASES to R.string.am_segment_bases, Segment.LAYERS to R.string.am_segment_layers)
                        .forEachIndexed { index, (option, label) ->
                            SegmentedButton(
                                selected = segment == option,
                                onClick = { segment = option },
                                shape = SegmentedButtonDefaults.itemShape(index, 2),
                                label = { Text(stringResource(label), maxLines = 1) },
                            )
                        }
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            ) {
                if (listed.isEmpty() && catalogFound.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            if (searchingNow) {
                                stringResource(R.string.am_search_none, query.trim())
                            } else {
                                stringResource(R.string.am_empty)
                            },
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
                for (group in listed) {
                    // The results of a search are never folded away.
                    val open = searchingNow || group.kind !in collapsed
                    item(key = "group-${group.kind}") {
                        GroupHeader(group, open, figures) {
                            collapsed = if (open) collapsed + group.kind else collapsed - group.kind
                        }
                    }
                    if (open) {
                        itemsIndexed(group.sources, key = { _, it -> "${group.kind}-${it.id}" }) { index, source ->
                            val base = vm.isBase(source.id)
                            // In « Calques », a base shown above the lowest one is a layer like the others.
                            val asBase = base && (segment == Segment.BASES || searchingNow)
                            val displayed = source.id in shownIds
                            MapRow(
                                source = source,
                                maps = maps,
                                control =
                                    if (asBase) {
                                        RowControl.Radio(source.id == bottomBase)
                                    } else {
                                        RowControl.Toggle(displayed)
                                    },
                                base = base,
                                displayed = displayed,
                                stored = figures?.perSource?.get(source.id),
                                hidden = source.id in hidden,
                                actions =
                                    MapRowActions(
                                        onPick = {
                                            when {
                                                asBase && source.id == bottomBase -> Unit
                                                asBase -> showWithUndo(source)
                                                displayed -> vm.hideSource(source.id)
                                                else -> showWithUndo(source)
                                            }
                                        },
                                        onAddLayer = {
                                            vm.addLayer(source.id)
                                            scope.launch {
                                                snackbar.showSnackbar(
                                                    resources.getString(R.string.am_shown, source.name),
                                                )
                                            }
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
                // The search also finds the maps to add, in the online catalogue (P29.6).
                if (catalogFound.isNotEmpty()) {
                    item(key = "catalog-head") {
                        Text(
                            stringResource(R.string.am_search_catalog),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 2.dp, top = 14.dp, bottom = 6.dp),
                        )
                    }
                    itemsIndexed(catalogFound, key = { _, it -> "catalog-${it.source.id}" }) { index, entry ->
                        CatalogEntryRow(
                            entry = entry,
                            added = entry.source.id in added,
                            onAdd = {
                                if (entry.keyParams.isEmpty()) addFromCatalog(entry, emptyMap()) else askingKey = entry
                            },
                            first = index == 0,
                            last = index == catalogFound.lastIndex,
                        )
                    }
                }
                if (searchingNow && listed.isNotEmpty()) {
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
    if (addSheet) {
        AddMapSheet(
            onDismiss = { addSheet = false },
            onCatalog = {
                addSheet = false
                onOpenCatalog()
            },
            onKind = { kind ->
                addSheet = false
                actions.onNewMap(kind)
            },
            onMultiZoom = {
                addSheet = false
                actions.onNewMultiZoom()
            },
            onImport = {
                addSheet = false
                actions.onImport()
            },
        )
    }
    askingKey?.let { entry ->
        CatalogKeyDialog(
            entry = entry,
            onConfirm = { keys ->
                askingKey = null
                addFromCatalog(entry, keys)
            },
            onDismiss = { askingKey = null },
        )
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

/**
 * Whether [source] goes in [segment]: the bases in « Fonds de carte »; in « Calques » the layers and the bases shown
 * above the lowest one (they are layers of the stack).
 */
private fun inSegment(
    source: TileSource,
    segment: Segment,
    base: Boolean,
    bottomBase: String?,
    shown: Set<String>,
): Boolean =
    when (segment) {
        Segment.BASES -> base
        Segment.LAYERS -> !base || (source.id in shown && source.id != bottomBase)
    }

/** « Fond : IGN Cartes · 1 calque »: what the map shows, in the words of the two segments. */
@Composable
private fun statusLine(
    stack: List<String>,
    bottomBase: String?,
    sources: List<TileSource>,
): String {
    val layers = stack.count { it != bottomBase }
    val count =
        if (layers == 0) {
            stringResource(R.string.am_layers_none)
        } else {
            pluralStringResource(R.plurals.am_layers_count, layers, layers)
        }
    val baseName = bottomBase?.let { id -> sources.firstOrNull { it.id == id }?.name }
    return if (baseName != null) {
        stringResource(R.string.am_status, baseName, count)
    } else {
        stringResource(R.string.am_status_no_base, count)
    }
}

/** The ⋮ of the bar: the storage (with its figures) and the hidden maps, which had their gauge and chip before. */
@Composable
private fun BarMenu(
    figures: StorageFigures?,
    hiddenCount: Int,
    showHidden: Boolean,
    onStorage: () -> Unit,
    onShowHidden: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.am_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.am_storage_menu))
                        if (figures != null) {
                            Text(
                                stringResource(
                                    R.string.am_storage_menu_sub,
                                    formatBytes(figures.tilesBytes),
                                    formatBytes(figures.freeBytes),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                leadingIcon = { Icon(SettingsIcons.Storage, contentDescription = null) },
                onClick = {
                    open = false
                    onStorage()
                },
            )
            if (hiddenCount > 0) {
                DropdownMenuItem(
                    text = { Text(pluralStringResource(R.plurals.am_show_hidden, hiddenCount, hiddenCount)) },
                    leadingIcon = { Icon(MapsIcons.VisibilityOff, contentDescription = null) },
                    trailingIcon = { if (showHidden) Icon(Icons.Filled.Check, contentDescription = null) },
                    onClick = {
                        open = false
                        onShowHidden()
                    },
                )
            }
        }
    }
}

/** The loupe of the bar opened: « Rechercher une carte » in place of the title, the arrow closing the search. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchBar(
    query: String,
    onChange: (String) -> Unit,
    onClose: () -> Unit,
    placeholder: String = stringResource(R.string.am_search),
) {
    val tm = MaterialTheme.toolMap
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onChange,
                placeholder = { Text(placeholder, color = tm.onShellVariant) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onChange("") }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.am_search_clear),
                                tint = tm.onShell,
                            )
                        }
                    }
                },
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = tm.onShell,
                        unfocusedTextColor = tm.onShell,
                        cursorColor = tm.onShell,
                    ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.am_search_close))
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = tm.shell,
                scrolledContainerColor = tm.shell,
                navigationIconContentColor = tm.onShell,
                titleContentColor = tm.onShell,
                actionIconContentColor = tm.onShell,
            ),
    )
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
                Text(it, style = MaterialTheme.typography.bodySmall, color = muted)
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

/** The control at the end of a row: the radio of a base, or the switch of a layer. */
private sealed interface RowControl {
    data class Radio(
        val selected: Boolean,
    ) : RowControl

    data class Toggle(
        val on: Boolean,
    ) : RowControl
}

/** What the commands of a row and of its menu do. */
private class MapRowActions(
    val onPick: () -> Unit,
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
private fun MapRow(
    source: TileSource,
    maps: MapsViewModel,
    control: RowControl,
    base: Boolean,
    displayed: Boolean,
    stored: Pair<Long, Long>?,
    hidden: Boolean,
    actions: MapRowActions,
    first: Boolean,
    last: Boolean,
) {
    val supported = source.isSupported
    val imported = maps.isImported(source.id)
    val enabled = supported && !hidden
    // A row of the white block of its group (P29.0): the rows of a section are one list, not one card each.
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(groupedShape(first, last))
                .background(MaterialTheme.toolMap.panel),
    ) {
        if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .alpha(if (hidden) HIDDEN_ALPHA else 1f)
                    .then(
                        // The row carries the state of its control, as TalkBack reads it.
                        when (control) {
                            is RowControl.Radio -> {
                                Modifier.selectable(
                                    selected = control.selected,
                                    enabled = enabled,
                                    role = Role.RadioButton,
                                    onClick = actions.onPick,
                                )
                            }

                            is RowControl.Toggle -> {
                                Modifier.toggleable(
                                    value = control.on,
                                    enabled = enabled,
                                    role = Role.Switch,
                                    onValueChange = { actions.onPick() },
                                )
                            }
                        },
                    ).padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MapThumbnail(source.id, source.name, maps.thumbnails, size = 48.dp, placeholder = typeIcon(source))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    source.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        hidden -> stringResource(R.string.am_hidden_note)
                        supported -> stringResource(R.string.am_zooms, source.minZoom, source.maxZoom)
                        else -> stringResource(R.string.source_unsupported, source.unsupportedReason.orEmpty())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!hidden && stored != null && stored.first > 0) {
                    Text(
                        stringResource(R.string.am_stored, formatBytes(stored.second)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // The control only says the state: the whole row toggles it, TalkBack reads the row.
            when (control) {
                is RowControl.Radio -> {
                    RadioButton(selected = control.selected, onClick = null, enabled = enabled)
                }

                is RowControl.Toggle -> {
                    Switch(checked = control.on, onCheckedChange = null, enabled = enabled)
                }
            }
            RowMenu(source, imported, base, displayed, stored, hidden, actions)
        }
    }
}

/** The ⋮ of a row: eight commands at most, the showing being the row's own control. */
@Composable
private fun RowMenu(
    source: TileSource,
    imported: Boolean,
    base: Boolean,
    displayed: Boolean,
    stored: Pair<Long, Long>?,
    hidden: Boolean,
    actions: MapRowActions,
) {
    var menu by remember { mutableStateOf(false) }
    val supported = source.isSupported
    val resources = LocalResources.current
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
                return@DropdownMenu
            }
            // A base keeps « Ajouter comme calque » and « Ne plus afficher »: its radio only replaces the base.
            if (base) {
                item(R.string.am_add_layer, supported, icon = MapsIcons.Layers, action = actions.onAddLayer)
                if (displayed) item(R.string.am_hide, icon = MapsIcons.VisibilityOff, action = actions.onHide)
            }
            if (source.isMultiZoom) {
                item(
                    if (imported) R.string.am_edit_zooms else R.string.am_copy_zooms,
                    icon = Icons.Filled.Edit,
                    action = actions.onEdit,
                )
            } else {
                if (SourceDraft.fromSource(source) != null) {
                    item(
                        if (imported) R.string.add_source_edit else R.string.add_source_copy,
                        icon = Icons.Filled.Edit,
                        action = actions.onEdit,
                    )
                }
                if (supported) {
                    item(
                        R.string.am_derive_multizoom,
                        icon = SettingsIcons.StackedChart,
                        action = actions.onDerive,
                    )
                }
            }
            // F4: the zone, the stored tiles and the file of this map.
            val canSave = supported && !source.isLocalOnly
            val storedTiles = stored?.takeIf { it.first > 0 && !source.isMultiZoom }?.first
            val canExport = !source.isMultiZoom && !source.isLocalOnly
            if (canSave || storedTiles != null || canExport) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                if (canSave) {
                    item(R.string.am_save_zone, icon = MapsIcons.Download, action = actions.onSaveZone)
                }
                if (storedTiles != null) {
                    item(
                        R.string.am_clear_tiles,
                        icon = RouteIcons.DeleteSweep,
                        trailing = tilesText(resources, storedTiles),
                        action = actions.onClear,
                    )
                }
                if (canExport) {
                    item(R.string.am_export_aqx, icon = MapsIcons.IosShare, action = actions.onExport)
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            if (imported) {
                item(R.string.am_delete, icon = RouteIcons.DeleteSweep, action = actions.onDelete)
            } else {
                // F3: a provided map cannot be deleted, it leaves the list.
                item(R.string.am_hide_from_list, icon = MapsIcons.VisibilityOff, action = actions.onHideFromList)
            }
        }
    }
}

/**
 * « Ajouter une carte » (P29.6): every way to add a map in one sheet, each with what it is for. The kind of a new map
 * is chosen here, so that the wizard starts at its address; the two imports are one picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMapSheet(
    onDismiss: () -> Unit,
    onCatalog: () -> Unit,
    onKind: (WizardKind) -> Unit,
    onMultiZoom: () -> Unit,
    onImport: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                stringResource(R.string.am_add_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            AddRow(SettingsIcons.Public, R.string.am_add_catalog, R.string.am_add_catalog_desc, onCatalog)
            AddRow(MapsIcons.Map, R.string.wizard_type_xyz, R.string.wizard_type_xyz_desc) { onKind(WizardKind.XYZ) }
            AddRow(MapsIcons.Layers, R.string.wizard_type_wmts, R.string.wizard_type_wmts_desc) {
                onKind(WizardKind.WMTS)
            }
            AddRow(MapsIcons.Layers, R.string.wizard_type_wms, R.string.wizard_type_wms_desc) { onKind(WizardKind.WMS) }
            AddRow(SettingsIcons.StackedChart, R.string.am_new_multizoom, R.string.am_add_multizoom_desc, onMultiZoom)
            AddRow(MenuIcons.UploadFile, R.string.am_add_import, R.string.am_add_import_desc, onImport)
        }
    }
}

@Composable
private fun AddRow(
    icon: ImageVector,
    title: Int,
    description: Int,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.toolMap.tile),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.toolMap.onTile, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
