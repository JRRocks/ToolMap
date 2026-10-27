package fr.toolmap.tracks

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.track.NameDates
import fr.toolmap.core.track.PathStyle
import fr.toolmap.core.track.TrackFormat
import fr.toolmap.core.track.TrackStats
import fr.toolmap.data.tracks.ItemKind
import fr.toolmap.data.tracks.ItemRef
import fr.toolmap.data.tracks.TrackEntity
import fr.toolmap.data.tracks.TrackLibrary
import fr.toolmap.data.tracks.TrackSelection
import fr.toolmap.map.MenuIcons
import fr.toolmap.map.MenuRow
import fr.toolmap.maps.MapsIcons
import fr.toolmap.settings.SettingsIcons
import fr.toolmap.ui.GroupBlock
import fr.toolmap.ui.GroupCaption
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.TextButton
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Statistics, elevation profile and actions of one track. Since P29.4 (« Topo »): four key figures and the profile,
 * with its axis and the legend of its colours, on the first screen; one filled button; the other figures folded in
 * « Tous les chiffres »; the tools as rows grouped by when they serve; renaming, style, description, moving, export and
 * deletion in the ⋮ of the bar, sharing as its icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailScreen(
    vm: TracksViewModel,
    trackId: Long,
    onBack: () -> Unit,
    onShowOnMap: (MapRequest) -> Unit,
    onDownloadAround: (trackId: Long, radiusMeters: Double) -> Unit,
    /** « Imprimer le long de cette trace… » (F9): the print, with pages chained along the track. */
    onPrintAlong: (trackId: Long) -> Unit,
    onEdit: (trackId: Long) -> Unit,
    onFollow: (trackId: Long) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val library by vm.library.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val cursorPoint by vm.cursor.collectAsStateWithLifecycle()
    var cursorMeters by remember { mutableStateOf<Double?>(null) }
    var ask by remember { mutableStateOf<Ask?>(null) }
    var menu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val entity = library?.tracks?.firstOrNull { it.id == trackId }
    val unnamed = stringResource(R.string.tracks_unnamed)
    val selection = TrackSelection(trackIds = listOf(trackId))

    LaunchedEffect(trackId) { vm.select(trackId) }
    DisposableEffect(trackId) { onDispose { vm.setCursor(null) } }
    LaunchedEffect(vm) {
        vm.messages.collect { snackbar.showSnackbar(resources.getString(it.resId, *it.args.toTypedArray())) }
    }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(TrackSharing.GPX_MIME)) { uri ->
            if (uri != null) vm.exportTo(context.contentResolver, uri, selection)
        }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            ToolMapTopBar(
                title = entity?.name ?: unnamed,
                onBack = onBack,
                subtitle = entity?.let { stringResource(R.string.tracks_detail_subtitle, fullDate(it)) },
                actions = {
                    if (entity != null) {
                        IconButton(onClick = {
                            scope.launch {
                                vm
                                    .exportToCache(
                                        TrackSharing.directory(context),
                                        TrackSharing.fileName(entity.name),
                                        selection,
                                    )?.let {
                                        TrackSharing.share(context, it, resources.getString(R.string.tracks_share))
                                    }
                            }
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.tracks_share))
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                listOf(
                                    R.string.tracks_rename to Ask.RENAME,
                                    R.string.tracks_style to Ask.STYLE,
                                    R.string.tracks_describe to Ask.DESCRIBE,
                                    R.string.tracks_move to Ask.MOVE,
                                    R.string.tracks_export to Ask.EXPORT,
                                    R.string.tracks_delete to Ask.DELETE,
                                ).forEach { (label, what) ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(label)) },
                                        onClick = {
                                            menu = false
                                            if (what == Ask.EXPORT) {
                                                exportLauncher.launch(TrackSharing.fileName(entity.name))
                                            } else {
                                                ask = what
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState()),
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val d = detail
            if (entity == null) {
                Text(stringResource(R.string.tracks_gone), modifier = Modifier.padding(16.dp))
            } else if (d == null || d.trackId != trackId) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
            } else {
                entity.desc?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
                    )
                }
                KeyFigures(d.stats, Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp))

                val profile = d.profile
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        stringResource(R.string.tracks_profile_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (profile != null) {
                        Text(
                            profileReading(profile, cursorMeters),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (profile == null) {
                    Text(
                        stringResource(R.string.tracks_profile_none),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                } else {
                    ElevationProfileChart(
                        profile = profile,
                        cursorMeters = cursorMeters,
                        onCursor = { at ->
                            cursorMeters = at
                            vm.setCursor(at)
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    if (cursorPoint != null) {
                        TextButton(
                            onClick = { cursorPoint?.let { onShowOnMap(MapRequest(it.lat, it.lon, it.lat, it.lon)) } },
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        ) {
                            Icon(Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(
                                stringResource(R.string.tracks_cursor_on_map),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }

                Button(
                    onClick = { onShowOnMap(MapRequest(entity.minLat, entity.minLon, entity.maxLat, entity.maxLon)) },
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                ) {
                    Icon(MapsIcons.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.tracks_show_on_map), modifier = Modifier.padding(start = 8.dp))
                }

                val inset = Modifier.padding(horizontal = 12.dp)
                AllFigures(d.stats, inset)
                GroupCaption(stringResource(R.string.tracks_group_prepare))
                GroupBlock(
                    inset,
                    listOf<@Composable () -> Unit>(
                        {
                            MenuRow(stringResource(R.string.tracks_download_around), icon = MapsIcons.Download) {
                                ask = Ask.RADIUS
                            }
                        },
                        {
                            MenuRow(
                                stringResource(R.string.tracks_print_along),
                                icon = MenuIcons.Print,
                                subtitle = stringResource(R.string.tracks_print_along_hint),
                            ) { onPrintAlong(trackId) }
                        },
                    ),
                )
                GroupCaption(stringResource(R.string.tracks_group_field))
                GroupBlock(
                    inset,
                    listOf<@Composable () -> Unit>(
                        {
                            MenuRow(
                                stringResource(R.string.tracks_follow_this),
                                icon = SettingsIcons.Route,
                                subtitle = stringResource(R.string.tracks_follow_this_sub),
                            ) { onFollow(trackId) }
                        },
                    ),
                )
                GroupCaption(stringResource(R.string.tracks_group_edit))
                val editRows =
                    buildList<@Composable () -> Unit> {
                        if (d.missingElevation > 0) {
                            add {
                                MenuRow(
                                    stringResource(R.string.tracks_elevation_complete),
                                    enabled = !busy,
                                    icon = SettingsIcons.Terrain,
                                    subtitle = stringResource(R.string.tracks_elevation_missing, d.missingElevation),
                                ) { vm.completeElevation(trackId) }
                            }
                        }
                        add {
                            MenuRow(stringResource(R.string.tracks_edit), icon = Icons.Filled.Edit) { onEdit(trackId) }
                        }
                        add {
                            MenuRow(stringResource(R.string.tracks_reverse), icon = MenuIcons.SwapHoriz) {
                                ask = Ask.REVERSE
                            }
                        }
                        add {
                            MenuRow(stringResource(R.string.tracks_simplify), icon = TracksIcons.Compress) {
                                ask = Ask.SIMPLIFY
                            }
                        }
                        add {
                            MenuRow(stringResource(R.string.tracks_merge), icon = TracksIcons.CallMerge) {
                                ask = Ask.MERGE
                            }
                        }
                    }
                GroupBlock(inset, editRows)
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    ask?.let { what ->
        TrackDialogs(
            what = what,
            trackId = trackId,
            entity = entity,
            library = library,
            vm = vm,
            onDownloadAround = onDownloadAround,
            onDeleted = onBack,
            onDone = { ask = null },
        )
    }
}

/** What the screen asks about: a confirmation, a choice or a name. */
private enum class Ask { RADIUS, REVERSE, SIMPLIFY, MERGE, RENAME, STYLE, DESCRIBE, MOVE, EXPORT, DELETE }

/** The dialogs of the tools and of the ⋮; [onDone] closes them. */
@Composable
private fun TrackDialogs(
    what: Ask,
    trackId: Long,
    entity: TrackEntity?,
    library: TrackLibrary?,
    vm: TracksViewModel,
    onDownloadAround: (trackId: Long, radiusMeters: Double) -> Unit,
    onDeleted: () -> Unit,
    onDone: () -> Unit,
) {
    val fmt = LocalFormats.current
    val unnamed = stringResource(R.string.tracks_unnamed)
    val ref = ItemRef(ItemKind.TRACK, trackId)
    val name = entity?.name ?: unnamed
    when (what) {
        Ask.RADIUS -> {
            ChoiceDialog(
                title = stringResource(R.string.tracks_download_around),
                ask = stringResource(R.string.tracks_download_radius_ask),
                choices = RADII.map { fmt.distance(it) to it },
                onPick = { radius ->
                    onDone()
                    onDownloadAround(trackId, radius)
                },
                onDismiss = onDone,
            )
        }

        Ask.REVERSE -> {
            ConfirmDialog(
                title = stringResource(R.string.tracks_reverse),
                message = stringResource(R.string.tracks_reverse_ask),
                confirmLabel = stringResource(R.string.tracks_reverse),
                onConfirm = {
                    onDone()
                    vm.reverse(trackId)
                },
                onDismiss = onDone,
            )
        }

        Ask.SIMPLIFY -> {
            ChoiceDialog(
                title = stringResource(R.string.draw_simplify_title),
                ask = stringResource(R.string.draw_simplify_ask),
                choices = SIMPLIFY_TOLERANCES.map { fmt.distance(it) to it },
                onPick = { meters ->
                    onDone()
                    vm.simplify(trackId, meters)
                },
                onDismiss = onDone,
            )
        }

        Ask.MERGE -> {
            val others = library?.tracks?.filter { it.id != trackId }.orEmpty()
            AlertDialog(
                onDismissRequest = onDone,
                title = { Text(stringResource(R.string.tracks_merge_pick)) },
                text = {
                    if (others.isEmpty()) {
                        Text(stringResource(R.string.tracks_merge_none))
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            others.forEach { other ->
                                TextButton(onClick = {
                                    onDone()
                                    vm.merge(listOf(trackId, other.id), "$name + ${other.name ?: unnamed}")
                                }) { Text(other.name ?: unnamed) }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.action_cancel)) } },
            )
        }

        Ask.RENAME -> {
            RenameDialog(
                title = stringResource(R.string.tracks_rename),
                initial = entity?.name.orEmpty(),
                onConfirm = {
                    onDone()
                    vm.rename(ref, it)
                },
                onDismiss = onDone,
            )
        }

        Ask.STYLE -> {
            if (entity != null) {
                StyleDialog(
                    initial = PathStyle(entity.colorArgb, entity.widthDp, entity.dashed, entity.arrows),
                    onConfirm = {
                        onDone()
                        vm.setStyle(ref, it)
                    },
                    onDismiss = onDone,
                )
            }
        }

        Ask.DESCRIBE -> {
            RenameDialog(
                title = stringResource(R.string.tracks_describe_title),
                initial = entity?.desc.orEmpty(),
                onConfirm = { text ->
                    onDone()
                    vm.describe(ref, text)
                },
                onDismiss = onDone,
                singleLine = false,
                allowBlank = true,
            )
        }

        Ask.MOVE -> {
            val lib = library
            if (lib != null) {
                MoveDialog(
                    title = stringResource(R.string.tracks_move_title, name),
                    targets = moveTargets(lib, null, entity?.folderId, stringResource(R.string.tracks_move_top)),
                    onPick = { target ->
                        onDone()
                        vm.moveItem(ref, target)
                    },
                    onDismiss = onDone,
                )
            }
        }

        Ask.DELETE -> {
            ConfirmDialog(
                title = stringResource(R.string.tracks_delete),
                message = stringResource(R.string.tracks_delete_ask, name),
                confirmLabel = stringResource(R.string.tracks_delete),
                onConfirm = {
                    onDone()
                    vm.delete(ref)
                    onDeleted()
                },
                onDismiss = onDone,
            )
        }

        Ask.EXPORT -> {
            Unit
        }
    }
}

/** A short list of values to pick from (a radius, a tolerance), and « Annuler ». */
@Composable
private fun ChoiceDialog(
    title: String,
    ask: String,
    choices: List<Pair<String, Double>>,
    onPick: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(ask)
                choices.forEach { (label, value) -> TextButton(onClick = { onPick(value) }) { Text(label) } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Distance, climb, descent and walking time, large: what decides a walk. */
@Composable
private fun KeyFigures(
    stats: TrackStats,
    modifier: Modifier = Modifier,
) {
    val fmt = LocalFormats.current
    val figures = ArrayList<Pair<String, String>>()
    figures += fmt.distance(stats.distance2dMeters) to stringResource(R.string.tracks_stat_distance)
    if (stats.minEleMeters != null && stats.maxEleMeters != null) {
        figures += fmt.elevation(stats.ascentMeters) to stringResource(R.string.tracks_stat_ascent_short)
        figures += fmt.elevation(stats.descentMeters) to stringResource(R.string.tracks_stat_descent_short)
    }
    figures += TrackFormat.duration(stats.estimatedWalkMillis) to stringResource(R.string.tracks_stat_walk_short)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        figures.forEach { (value, label) ->
            Column {
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** « Tous les chiffres », folded: a tap unfolds the fourteen lines of the statistics in the same white block. */
@Composable
private fun AllFigures(
    stats: TrackStats,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val lines = statLines(stats)
    GroupBlock(
        modifier,
        buildList<@Composable () -> Unit> {
            add {
                MenuRow(
                    stringResource(R.string.tracks_all_figures),
                    icon = TracksIcons.Analytics,
                    subtitle = stringResource(R.string.tracks_all_figures_sub),
                    trailing = {
                        Icon(
                            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    },
                    clickLabel = stringResource(if (open) R.string.menu_more_fold else R.string.menu_more_unfold),
                    stateText = stringResource(if (open) R.string.menu_more_shown else R.string.menu_more_hidden),
                ) { open = !open }
            }
            if (open) {
                add {
                    Column(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        lines.forEach { (label, value) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                Text(value, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
    )
}

/** Every figure of the track, in the order of the former statistics card. */
@Composable
private fun statLines(stats: TrackStats): List<Pair<String, String>> {
    val fmt = LocalFormats.current
    val rows = ArrayList<Pair<String, String>>()
    rows += stringResource(R.string.tracks_stat_distance) to fmt.distance(stats.distance2dMeters)
    if (stats.minEleMeters != null && stats.maxEleMeters != null) {
        rows += stringResource(R.string.tracks_stat_ascent) to "↑ " + fmt.elevation(stats.ascentMeters)
        rows += stringResource(R.string.tracks_stat_descent) to "↓ " + fmt.elevation(stats.descentMeters)
        rows += stringResource(R.string.tracks_stat_min) to fmt.elevation(stats.minEleMeters!!)
        rows += stringResource(R.string.tracks_stat_max) to fmt.elevation(stats.maxEleMeters!!)
    }
    rows += stringResource(R.string.tracks_stat_walk) to TrackFormat.duration(stats.estimatedWalkMillis)
    stats.totalDurationMillis?.let { rows += stringResource(R.string.tracks_stat_duration) to TrackFormat.duration(it) }
    stats.movingDurationMillis?.let { rows += stringResource(R.string.tracks_stat_moving) to TrackFormat.duration(it) }
    stats.averageSpeedMps?.let { rows += stringResource(R.string.tracks_stat_speed) to fmt.speed(it) }
    stats.averageMovingSpeedMps?.let { rows += stringResource(R.string.tracks_stat_speed_moving) to fmt.speed(it) }
    stats.maxSpeedMps?.let { rows += stringResource(R.string.tracks_stat_speed_max) to fmt.speed(it) }
    stats.startTimeMillis?.let { rows += stringResource(R.string.tracks_stat_start) to NameDates.format(it) }
    stats.endTimeMillis?.let { rows += stringResource(R.string.tracks_stat_end) to NameDates.format(it) }
    rows += stringResource(R.string.tracks_stat_points) to
        pluralStringResource(R.plurals.tracks_points_segments, stats.segmentCount, stats.pointCount, stats.segmentCount)
    return rows
}

/** « 21 oct. 2026 »: the day the track was walked, or added when it has no time. */
private fun fullDate(track: TrackEntity): String =
    Instant
        .ofEpochMilli(track.startTimeMillis ?: track.createdAt)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(FULL_DATE)

private val FULL_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.FRENCH)

private val RADII = listOf(500.0, 1000.0, 2000.0, 5000.0)

private val SIMPLIFY_TOLERANCES = listOf(1.0, 2.0, 5.0, 10.0, 25.0)
