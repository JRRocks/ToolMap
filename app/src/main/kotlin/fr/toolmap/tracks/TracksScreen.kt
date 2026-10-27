package fr.toolmap.tracks

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.settings.LibraryViewSettings
import fr.toolmap.core.track.LibraryFilter
import fr.toolmap.core.track.LibraryKey
import fr.toolmap.core.track.LibraryKind
import fr.toolmap.core.track.LibraryOrder
import fr.toolmap.core.track.LibrarySort
import fr.toolmap.core.track.PathStyle
import fr.toolmap.data.tracks.FolderEntity
import fr.toolmap.data.tracks.ItemKind
import fr.toolmap.data.tracks.ItemRef
import fr.toolmap.data.tracks.RouteEntity
import fr.toolmap.data.tracks.TrackEntity
import fr.toolmap.data.tracks.TrackLibrary
import fr.toolmap.data.tracks.TrackSelection
import fr.toolmap.data.tracks.WaypointEntity
import fr.toolmap.map.MenuIcons
import fr.toolmap.maps.MapsIcons
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.minTouchTarget
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One line of the library list. */
private sealed interface LibraryRow {
    val depth: Int
    val key: String

    data class Folder(
        val folder: FolderEntity,
        override val depth: Int,
        val itemCount: Int,
        val expanded: Boolean,
    ) : LibraryRow {
        override val key get() = "f${folder.id}"
    }

    /** A track, a route or a waypoint: what the explorer sorts, filters and selects (F7). */
    sealed interface Item : LibraryRow {
        val ref: ItemRef
        val orderKey: LibraryKey
    }

    data class Track(
        val track: TrackEntity,
        override val depth: Int,
        val shown: Boolean,
    ) : Item {
        override val key get() = "t${track.id}"
        override val ref get() = ItemRef(ItemKind.TRACK, track.id)
        override val orderKey
            get() =
                LibraryKey(
                    LibraryKind.TRACK,
                    track.name,
                    track.startTimeMillis ?: track.createdAt,
                    track.distanceMeters,
                )
    }

    data class Route(
        val route: RouteEntity,
        override val depth: Int,
        val shown: Boolean,
    ) : Item {
        override val key get() = "r${route.id}"
        override val ref get() = ItemRef(ItemKind.ROUTE, route.id)
        override val orderKey get() = LibraryKey(LibraryKind.ROUTE, route.name, route.createdAt, route.distanceMeters)
    }

    data class Waypoint(
        val waypoint: WaypointEntity,
        override val depth: Int,
        val shown: Boolean,
    ) : Item {
        override val key get() = "w${waypoint.id}"
        override val ref get() = ItemRef(ItemKind.WAYPOINT, waypoint.id)
        override val orderKey
            get() = LibraryKey(LibraryKind.WAYPOINT, waypoint.name, waypoint.timeMillis ?: waypoint.createdAt, null)
    }
}

/**
 * The lines of the explorer: the folders on top, then the items of each level in the chosen order and of the chosen
 * kind (F7). A search lists the matching items of every folder, without the folders.
 */
private fun rows(
    lib: TrackLibrary,
    expanded: Set<Long>,
    view: LibraryViewSettings,
    query: String,
): List<LibraryRow> {
    if (query.isNotBlank()) return itemRows(lib, 0, view, query) { true }
    val out = ArrayList<LibraryRow>()

    fun visit(
        parent: Long?,
        depth: Int,
    ) {
        for (f in lib.folders.filter { it.parentId == parent }) {
            val inside = lib.descendants(f.id)
            val count =
                lib.tracks.count { it.folderId in inside } + lib.routes.count { it.folderId in inside } +
                    lib.waypoints.count { it.folderId in inside }
            val open = f.id in expanded
            out += LibraryRow.Folder(f, depth, count, open)
            if (open) visit(f.id, depth + 1)
        }
        out += itemRows(lib, depth, view, "") { it == parent }
    }
    visit(null, 0)
    return out
}

/** The items whose folder passes [inFolder], of the kind of [view] and matching [query], in the order of [view]. */
private fun itemRows(
    lib: TrackLibrary,
    depth: Int,
    view: LibraryViewSettings,
    query: String,
    inFolder: (Long?) -> Boolean,
): List<LibraryRow.Item> {
    val items = ArrayList<LibraryRow.Item>()
    lib.tracks.filter { inFolder(it.folderId) }.mapTo(items) {
        LibraryRow.Track(it, depth, lib.isShown(it.folderId, it.visible))
    }
    lib.routes.filter { inFolder(it.folderId) }.mapTo(items) {
        LibraryRow.Route(it, depth, lib.isShown(it.folderId, it.visible))
    }
    lib.waypoints.filter { inFolder(it.folderId) }.mapTo(items) {
        LibraryRow.Waypoint(it, depth, lib.isShown(it.folderId, it.visible))
    }
    val order = LibraryOrder.comparator(view.sort)
    return items
        .filter { LibraryOrder.accepts(view.filter, it.orderKey.kind) && LibraryOrder.matches(it.orderKey.name, query) }
        .sortedWith(compareBy(order) { it.orderKey })
}

/** The « visible » box of an item, `null` when the item is gone. */
private fun TrackLibrary.visibleOf(ref: ItemRef): Boolean? =
    when (ref.kind) {
        ItemKind.TRACK -> tracks.firstOrNull { it.id == ref.id }?.visible
        ItemKind.ROUTE -> routes.firstOrNull { it.id == ref.id }?.visible
        ItemKind.WAYPOINT -> waypoints.firstOrNull { it.id == ref.id }?.visible
    }

private fun TrackLibrary.folderOf(ref: ItemRef): Long? =
    when (ref.kind) {
        ItemKind.TRACK -> tracks.firstOrNull { it.id == ref.id }?.folderId
        ItemKind.ROUTE -> routes.firstOrNull { it.id == ref.id }?.folderId
        ItemKind.WAYPOINT -> waypoints.firstOrNull { it.id == ref.id }?.folderId
    }

/** What a dialog is open for. */
private sealed interface Dialog {
    data class Rename(
        val ref: ItemRef?,
        val folderId: Long?,
        val current: String,
    ) : Dialog

    data class Style(
        val ref: ItemRef,
        val current: PathStyle,
    ) : Dialog

    data class Delete(
        val ref: ItemRef?,
        val folderId: Long?,
        val name: String,
    ) : Dialog

    /** A new folder under [parentId] (`null` = top of the library). */
    data class NewFolder(
        val parentId: Long?,
    ) : Dialog

    /** Move an item ([ref]) or a folder ([folderId]) currently inside [parentId]. */
    data class Move(
        val ref: ItemRef?,
        val folderId: Long?,
        val name: String,
        val parentId: Long?,
    ) : Dialog

    /** Move the items picked in the explorer (F7). */
    data class MoveMany(
        val refs: List<ItemRef>,
    ) : Dialog

    /** Delete the items picked in the explorer, after a confirmation (F7; F12 keeps it). */
    data class DeleteMany(
        val refs: List<ItemRef>,
    ) : Dialog
}

/** The top of the library and every folder that can take what is moved, in tree order. */
private fun moveTargets(
    lib: TrackLibrary,
    movedFolder: Long?,
    current: Long?,
    topLabel: String,
): List<MoveTarget> {
    // A folder cannot go into itself or into what it holds.
    val excluded = movedFolder?.let { lib.descendants(it) }.orEmpty()
    val out = arrayListOf(MoveTarget(null, topLabel, 0, current == null))

    fun visit(
        parent: Long?,
        depth: Int,
    ) {
        for (f in lib.folders.filter { it.parentId == parent && it.id !in excluded }) {
            out += MoveTarget(f.id, f.name, depth, current == f.id)
            visit(f.id, depth + 1)
        }
    }
    visit(null, 1)
    return out
}

/** The library: every imported or recorded track, route and waypoint, in the folders they came in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TracksScreen(
    vm: TracksViewModel,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onShowOnMap: (MapRequest) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val library by vm.library.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val view by vm.libraryView.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Open folders and the scroll position live in the view model: kept back from a detail and from one opening to
    // the next while the app runs (P14.11).
    val expanded by vm.expandedFolders.collectAsStateWithLifecycle()
    val listState = rememberLazyListState(vm.libraryScroll.first, vm.libraryScroll.second)
    DisposableEffect(listState) {
        onDispose { vm.libraryScroll = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
    }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var pendingExport by remember { mutableStateOf<TrackSelection?>(null) }
    // F7: the search by name, and the items picked after a long press (the selection mode while not empty).
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<ItemRef>()) }
    val selecting = selected.isNotEmpty()

    LaunchedEffect(vm) {
        vm.messages.collect { snackbar.showSnackbar(resources.getString(it.resId, *it.args.toTypedArray())) }
    }
    // An item deleted meanwhile leaves the selection.
    LaunchedEffect(library) {
        val lib = library ?: return@LaunchedEffect
        selected = selected.filterTo(HashSet()) { lib.visibleOf(it) != null }
    }
    BackHandler(enabled = selecting) { selected = emptySet() }
    BackHandler(enabled = !selecting && searchOpen) {
        searchOpen = false
        query = ""
    }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(TrackSharing.GPX_MIME)) { uri ->
            val selection = pendingExport
            if (uri != null && selection != null) vm.exportTo(context.contentResolver, uri, selection)
            pendingExport = null
        }
    val export: (TrackSelection, String?) -> Unit = { selection, name ->
        pendingExport = selection
        exportLauncher.launch(TrackSharing.fileName(name))
    }
    val share: (TrackSelection, String?) -> Unit = { selection, name ->
        scope.launch {
            vm.exportToCache(TrackSharing.directory(context), TrackSharing.fileName(name), selection)?.let {
                TrackSharing.share(context, it, resources.getString(R.string.tracks_share))
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (selecting) {
                SelectionBar(
                    count = selected.size,
                    onClose = { selected = emptySet() },
                    onVisibility = {
                        // Shows them all when one is hidden, hides them all otherwise.
                        val lib = library
                        if (lib != null) {
                            val show = selected.any { lib.visibleOf(it) == false }
                            selected.forEach { vm.setVisible(it, show) }
                        }
                    },
                    onMove = { dialog = Dialog.MoveMany(selected.toList()) },
                    onExport = {
                        val refs = selected
                        export(
                            TrackSelection(
                                trackIds = refs.filter { it.kind == ItemKind.TRACK }.map { it.id },
                                routeIds = refs.filter { it.kind == ItemKind.ROUTE }.map { it.id },
                                waypointIds = refs.filter { it.kind == ItemKind.WAYPOINT }.map { it.id },
                            ),
                            resources.getString(R.string.tracks_selection_file),
                        )
                        selected = emptySet()
                    },
                    onDelete = { dialog = Dialog.DeleteMany(selected.toList()) },
                )
            } else {
                ToolMapTopBar(
                    title = stringResource(R.string.tracks_title),
                    onBack = onBack,
                    actions = {
                        IconButton(onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) query = ""
                        }) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.tracks_find))
                        }
                        IconButton(onClick = onImport) {
                            Icon(MenuIcons.UploadFile, contentDescription = stringResource(R.string.tracks_import))
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).safeDrawingPadding()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val lib = library
            if (searchOpen) SearchField(query) { query = it }
            if (lib != null && !lib.isEmpty) {
                ViewChips(view, onSort = vm::setLibrarySort, onFilter = vm::setLibraryFilter)
            }
            if (!selecting) {
                TextButton(
                    onClick = { dialog = Dialog.NewFolder(null) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.tracks_new_folder), modifier = Modifier.padding(start = 6.dp))
                }
            }
            if (lib == null || lib.isEmpty) {
                Text(
                    stringResource(R.string.tracks_empty),
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                val list = remember(lib, expanded, view, query) { rows(lib, expanded, view, query) }
                if (list.isEmpty()) {
                    Text(
                        if (query.isNotBlank()) {
                            stringResource(R.string.tracks_search_none, query.trim())
                        } else {
                            stringResource(R.string.tracks_filter_none)
                        },
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(list, key = { it.key }) { row ->
                        LibraryRowView(
                            row = row,
                            vm = vm,
                            selection = if (selecting) selected else null,
                            onSelect = { ref -> selected = if (ref in selected) selected - ref else selected + ref },
                            onToggleFolder = vm::toggleFolder,
                            onOpenDetail = onOpenDetail,
                            onShowOnMap = onShowOnMap,
                            onDialog = { dialog = it },
                            onExport = export,
                            onShare = share,
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    dialog?.let { d ->
        when (d) {
            is Dialog.Rename -> {
                RenameDialog(
                    title = stringResource(R.string.tracks_rename),
                    initial = d.current,
                    onConfirm = { name ->
                        d.ref?.let { vm.rename(it, name) }
                        d.folderId?.let { vm.renameFolder(it, name) }
                        dialog = null
                    },
                    onDismiss = { dialog = null },
                )
            }

            is Dialog.Style -> {
                StyleDialog(
                    initial = d.current,
                    onConfirm = {
                        vm.setStyle(d.ref, it)
                        dialog = null
                    },
                    onDismiss = { dialog = null },
                )
            }

            is Dialog.NewFolder -> {
                RenameDialog(
                    title =
                        stringResource(
                            if (d.parentId ==
                                null
                            ) {
                                R.string.tracks_new_folder
                            } else {
                                R.string.tracks_new_subfolder
                            },
                        ),
                    initial = "",
                    onConfirm = { name ->
                        vm.createFolder(d.parentId, name)
                        d.parentId?.let(vm::expandFolder)
                        dialog = null
                    },
                    onDismiss = { dialog = null },
                )
            }

            is Dialog.Move -> {
                val lib = library
                if (lib != null) {
                    MoveDialog(
                        title = stringResource(R.string.tracks_move_title, d.name),
                        targets = moveTargets(lib, d.folderId, d.parentId, stringResource(R.string.tracks_move_top)),
                        onPick = { target ->
                            d.ref?.let { vm.moveItem(it, target) }
                            d.folderId?.let { vm.moveFolder(it, target) }
                            target?.let(vm::expandFolder)
                            dialog = null
                        },
                        onDismiss = { dialog = null },
                    )
                }
            }

            is Dialog.MoveMany -> {
                val lib = library
                if (lib != null) {
                    // The folder they are all in is marked « actuel »; none is when they come from several.
                    val parents = d.refs.map { lib.folderOf(it) }.distinct()
                    MoveDialog(
                        title = pluralStringResource(R.plurals.tracks_move_many_title, d.refs.size, d.refs.size),
                        targets =
                            moveTargets(
                                lib,
                                null,
                                if (parents.size == 1) parents[0] else SEVERAL_FOLDERS,
                                stringResource(R.string.tracks_move_top),
                            ),
                        onPick = { target ->
                            d.refs.forEach { vm.moveItem(it, target) }
                            target?.let(vm::expandFolder)
                            selected = emptySet()
                            dialog = null
                        },
                        onDismiss = { dialog = null },
                    )
                }
            }

            is Dialog.Delete -> {
                ConfirmDialog(
                    title = stringResource(R.string.tracks_delete),
                    message =
                        stringResource(
                            if (d.folderId !=
                                null
                            ) {
                                R.string.tracks_delete_folder_ask
                            } else {
                                R.string.tracks_delete_ask
                            },
                            d.name,
                        ),
                    confirmLabel = stringResource(R.string.tracks_delete),
                    onConfirm = {
                        d.ref?.let { vm.delete(it) }
                        d.folderId?.let { vm.deleteFolder(it) }
                        dialog = null
                    },
                    onDismiss = { dialog = null },
                )
            }

            is Dialog.DeleteMany -> {
                ConfirmDialog(
                    title = stringResource(R.string.tracks_delete),
                    message = pluralStringResource(R.plurals.tracks_delete_many_ask, d.refs.size, d.refs.size),
                    confirmLabel = stringResource(R.string.tracks_delete),
                    onConfirm = {
                        d.refs.forEach { vm.delete(it) }
                        selected = emptySet()
                        dialog = null
                    },
                    onDismiss = { dialog = null },
                )
            }
        }
    }
}

/** The bar of the selection mode (F7): « ✕ N sélectionnés », then what can be done to all of them at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onVisibility: () -> Unit,
    onMove: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        title = { Text(pluralStringResource(R.plurals.tracks_selected, count, count)) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.tracks_selection_close))
            }
        },
        actions = {
            IconButton(onClick = onVisibility) {
                Icon(MapsIcons.Visibility, contentDescription = stringResource(R.string.tracks_selection_visibility))
            }
            IconButton(onClick = onMove) {
                Icon(MenuIcons.DriveFileMove, contentDescription = stringResource(R.string.tracks_move))
            }
            IconButton(onClick = onExport) {
                Icon(MenuIcons.FileSave, contentDescription = stringResource(R.string.tracks_selection_export))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.tracks_delete))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    )
}

/** « Rechercher par nom » (F7), under the bar: it searches the items of every folder. */
@Composable
private fun SearchField(
    query: String,
    onChange: (String) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus),
        placeholder = { Text(stringResource(R.string.tracks_search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.tracks_search_clear))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(percent = 50),
    )
}

/** « Date ↓ » and its sort menu, then the kinds of item to show (F7); both are kept in the settings. */
@Composable
private fun ViewChips(
    view: LibraryViewSettings,
    onSort: (LibrarySort) -> Unit,
    onFilter: (LibraryFilter) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FilterChip(
                selected = true,
                onClick = { menu = true },
                label = { Text(stringResource(sortChipLabel(view.sort))) },
                leadingIcon = {
                    Icon(
                        MenuIcons.Sort,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                LibrarySort.entries.forEach { sort ->
                    DropdownMenuItem(
                        text = { Text(stringResource(sortLabel(sort))) },
                        leadingIcon = {
                            if (sort == view.sort) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                            } else {
                                Spacer(Modifier.size(24.dp))
                            }
                        },
                        onClick = {
                            menu = false
                            onSort(sort)
                        },
                    )
                }
            }
        }
        LibraryFilter.entries.forEach { filter ->
            FilterChip(
                selected = view.filter == filter,
                onClick = { onFilter(filter) },
                label = { Text(stringResource(filterLabel(filter))) },
            )
        }
    }
}

private fun sortChipLabel(sort: LibrarySort): Int =
    when (sort) {
        LibrarySort.DATE -> R.string.tracks_sort_chip_date
        LibrarySort.NAME -> R.string.tracks_sort_name
        LibrarySort.DISTANCE -> R.string.tracks_sort_chip_distance
    }

private fun sortLabel(sort: LibrarySort): Int =
    when (sort) {
        LibrarySort.DATE -> R.string.tracks_sort_date
        LibrarySort.NAME -> R.string.tracks_sort_name
        LibrarySort.DISTANCE -> R.string.tracks_sort_distance
    }

private fun filterLabel(filter: LibraryFilter): Int =
    when (filter) {
        LibraryFilter.ALL -> R.string.tracks_filter_all
        LibraryFilter.TRACKS -> R.string.tracks_filter_tracks
        LibraryFilter.ROUTES -> R.string.tracks_filter_routes
        LibraryFilter.WAYPOINTS -> R.string.tracks_filter_waypoints
    }

/**
 * One line. In the selection mode ([selection] not `null`) a tap picks or drops an item, and the items show a ring in
 * place of their colour dot, without their box and their menu; a long press starts that mode (F7).
 */
@Composable
private fun LibraryRowView(
    row: LibraryRow,
    vm: TracksViewModel,
    selection: Set<ItemRef>?,
    onSelect: (ItemRef) -> Unit,
    onToggleFolder: (Long) -> Unit,
    onOpenDetail: (Long) -> Unit,
    onShowOnMap: (MapRequest) -> Unit,
    onDialog: (Dialog) -> Unit,
    onExport: (TrackSelection, String?) -> Unit,
    onShare: (TrackSelection, String?) -> Unit,
) {
    val unnamed = stringResource(R.string.tracks_unnamed)
    var menu by remember { mutableStateOf(false) }
    val indent = (row.depth * INDENT_DP).dp
    val selecting = selection != null
    val picked = row is LibraryRow.Item && selection?.contains(row.ref) == true
    val background = if (picked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent

    Row(
        modifier = Modifier.fillMaxWidth().background(background).padding(start = indent, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (row) {
            is LibraryRow.Folder -> {
                val f = row.folder
                Icon(
                    if (row.expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onToggleFolder(f.id) }
                        .heightIn(min = minTouchTarget())
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(f.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        pluralStringResource(R.plurals.tracks_folder_items, row.itemCount, row.itemCount),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!selecting) {
                    Checkbox(checked = f.visible, onCheckedChange = { vm.setFolderVisible(f.id, it) })
                    val folderSelection = TrackSelection(folderIds = listOf(f.id))
                    ItemMenu(
                        menu,
                        { menu = it },
                        listOf(
                            R.string.tracks_rename to { onDialog(Dialog.Rename(null, f.id, f.name)) },
                            R.string.tracks_new_subfolder to { onDialog(Dialog.NewFolder(f.id)) },
                            R.string.tracks_move to { onDialog(Dialog.Move(null, f.id, f.name, f.parentId)) },
                            R.string.tracks_export to { onExport(folderSelection, f.name) },
                            R.string.tracks_share to { onShare(folderSelection, f.name) },
                            R.string.tracks_delete to { onDialog(Dialog.Delete(null, f.id, f.name)) },
                        ),
                    )
                }
            }

            is LibraryRow.Track -> {
                val t = row.track
                val ref = row.ref
                ItemColumn(
                    color = Color(t.colorArgb),
                    title = t.name ?: unnamed,
                    subtitle = itemSubtitle(row),
                    dim = !row.shown,
                    selected = selection?.let { ref in it },
                    onClick = { if (selecting) onSelect(ref) else onOpenDetail(t.id) },
                    onLongClick = { onSelect(ref) },
                    modifier = Modifier.weight(1f),
                )
                if (!selecting) {
                    Checkbox(checked = t.visible, onCheckedChange = { vm.setVisible(ref, it) })
                    ItemMenu(
                        menu,
                        { menu = it },
                        listOf(
                            R.string.tracks_details to { onOpenDetail(t.id) },
                            R.string.tracks_show_on_map to
                                { onShowOnMap(MapRequest(t.minLat, t.minLon, t.maxLat, t.maxLon)) },
                            R.string.tracks_rename to { onDialog(Dialog.Rename(ref, null, t.name.orEmpty())) },
                            R.string.tracks_move to
                                { onDialog(Dialog.Move(ref, null, t.name ?: unnamed, t.folderId)) },
                            R.string.tracks_style to
                                { onDialog(Dialog.Style(ref, PathStyle(t.colorArgb, t.widthDp, t.dashed, t.arrows))) },
                            R.string.tracks_export to { onExport(TrackSelection(trackIds = listOf(t.id)), t.name) },
                            R.string.tracks_share to { onShare(TrackSelection(trackIds = listOf(t.id)), t.name) },
                            R.string.tracks_delete to { onDialog(Dialog.Delete(ref, null, t.name ?: unnamed)) },
                        ),
                    )
                }
            }

            is LibraryRow.Route -> {
                val r = row.route
                val ref = row.ref
                val fit = MapRequest(r.minLat, r.minLon, r.maxLat, r.maxLon)
                ItemColumn(
                    color = Color(r.colorArgb),
                    title = r.name ?: unnamed,
                    subtitle = itemSubtitle(row),
                    dim = !row.shown,
                    selected = selection?.let { ref in it },
                    onClick = { if (selecting) onSelect(ref) else onShowOnMap(fit) },
                    onLongClick = { onSelect(ref) },
                    modifier = Modifier.weight(1f),
                )
                if (!selecting) {
                    Checkbox(checked = r.visible, onCheckedChange = { vm.setVisible(ref, it) })
                    ItemMenu(
                        menu,
                        { menu = it },
                        listOf(
                            R.string.tracks_show_on_map to { onShowOnMap(fit) },
                            R.string.tracks_rename to { onDialog(Dialog.Rename(ref, null, r.name.orEmpty())) },
                            R.string.tracks_move to
                                { onDialog(Dialog.Move(ref, null, r.name ?: unnamed, r.folderId)) },
                            R.string.tracks_style to
                                { onDialog(Dialog.Style(ref, PathStyle(r.colorArgb, r.widthDp, r.dashed, r.arrows))) },
                            R.string.tracks_export to { onExport(TrackSelection(routeIds = listOf(r.id)), r.name) },
                            R.string.tracks_share to { onShare(TrackSelection(routeIds = listOf(r.id)), r.name) },
                            R.string.tracks_delete to { onDialog(Dialog.Delete(ref, null, r.name ?: unnamed)) },
                        ),
                    )
                }
            }

            is LibraryRow.Waypoint -> {
                val w = row.waypoint
                val ref = row.ref
                val fit = MapRequest(w.lat, w.lon, w.lat, w.lon)
                ItemColumn(
                    color = Color(WAYPOINT_COLOR),
                    title = w.name ?: unnamed,
                    subtitle = itemSubtitle(row),
                    dim = !row.shown,
                    selected = selection?.let { ref in it },
                    onClick = { if (selecting) onSelect(ref) else onShowOnMap(fit) },
                    onLongClick = { onSelect(ref) },
                    modifier = Modifier.weight(1f),
                )
                if (!selecting) {
                    Checkbox(checked = w.visible, onCheckedChange = { vm.setVisible(ref, it) })
                    ItemMenu(
                        menu,
                        { menu = it },
                        listOf(
                            R.string.tracks_show_on_map to { onShowOnMap(fit) },
                            R.string.tracks_rename to { onDialog(Dialog.Rename(ref, null, w.name.orEmpty())) },
                            R.string.tracks_move to
                                { onDialog(Dialog.Move(ref, null, w.name ?: unnamed, w.folderId)) },
                            R.string.tracks_export to
                                { onExport(TrackSelection(waypointIds = listOf(w.id)), w.name) },
                            R.string.tracks_share to { onShare(TrackSelection(waypointIds = listOf(w.id)), w.name) },
                            R.string.tracks_delete to { onDialog(Dialog.Delete(ref, null, w.name ?: unnamed)) },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemMenu(
    expanded: Boolean,
    setExpanded: (Boolean) -> Unit,
    entries: List<Pair<Int, () -> Unit>>,
) {
    Box {
        IconButton(onClick = { setExpanded(true) }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { setExpanded(false) }) {
            entries.forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        setExpanded(false)
                        action()
                    },
                )
            }
        }
    }
}

/** Title and subtitle of an item, after its colour dot; [selected] not `null` shows the ring of the selection mode. */
@Composable
private fun ItemColumn(
    color: Color,
    title: String,
    subtitle: String,
    dim: Boolean,
    selected: Boolean?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                    onLongClickLabel = stringResource(R.string.tracks_select),
                ).semantics { if (selected != null) this.selected = selected }
                .heightIn(min = minTouchTarget())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (selected) {
            null -> {
                Box(Modifier.size(14.dp).background(color, CircleShape))
            }

            true -> {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }

            false -> {
                Icon(MenuIcons.RadioUnchecked, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
            }
        }
        Column {
            val alpha = if (dim) DIM_ALPHA else 1f
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
    }
}

/** « Trace · 11.9 km · 18 oct. », « Waypoint · 2 130 m · 15 oct. » (F7): the kind, the length or height, the date. */
@Composable
private fun itemSubtitle(row: LibraryRow.Item): String {
    val fmt = LocalFormats.current
    val key = row.orderKey
    val parts = ArrayList<String>()
    parts +=
        stringResource(
            when (row) {
                is LibraryRow.Track -> R.string.tracks_kind_track
                is LibraryRow.Route -> R.string.tracks_kind_route
                is LibraryRow.Waypoint -> R.string.tracks_waypoint
            },
        )
    key.distanceMeters?.let { parts += fmt.distance(it) }
    (row as? LibraryRow.Waypoint)?.waypoint?.ele?.let { parts += fmt.elevation(it) }
    parts += shortDate(key.timeMillis)
    return parts.joinToString(" · ")
}

/** « 18 oct. », with the year when it is not this one. */
private fun shortDate(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    return date.format(if (date.year == LocalDate.now(zone).year) DAY_MONTH else DAY_MONTH_YEAR)
}

private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)
private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.FRENCH)

private const val INDENT_DP = 20
private const val DIM_ALPHA = 0.45f
private const val WAYPOINT_COLOR = 0xFFE53935

/** No folder is the current one: the items to move come from several. */
private const val SEVERAL_FOLDERS = -1L
