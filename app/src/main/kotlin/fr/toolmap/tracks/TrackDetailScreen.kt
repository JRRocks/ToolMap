package fr.toolmap.tracks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.track.NameDates
import fr.toolmap.core.track.TrackFormat
import fr.toolmap.core.track.TrackStats
import fr.toolmap.data.tracks.ItemKind
import fr.toolmap.data.tracks.ItemRef
import fr.toolmap.data.tracks.TrackSelection
import fr.toolmap.map.MenuIcons
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.TextButton
import kotlinx.coroutines.launch

/** Statistics, elevation profile and actions of one track. */
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
    val fmt = LocalFormats.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val library by vm.library.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val cursorPoint by vm.cursor.collectAsStateWithLifecycle()
    var cursorMeters by remember { mutableStateOf<Double?>(null) }
    var askRadius by remember { mutableStateOf(false) }
    var askReverse by remember { mutableStateOf(false) }
    var askSimplify by remember { mutableStateOf(false) }
    var askMerge by remember { mutableStateOf(false) }
    var askDescribe by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val entity = library?.tracks?.firstOrNull { it.id == trackId }

    LaunchedEffect(trackId) { vm.select(trackId) }
    DisposableEffect(trackId) { onDispose { vm.setCursor(null) } }
    LaunchedEffect(vm) {
        vm.messages.collect { snackbar.showSnackbar(resources.getString(it.resId, *it.args.toTypedArray())) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            ToolMapTopBar(title = entity?.name ?: stringResource(R.string.tracks_unnamed), onBack = onBack)
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val d = detail
            if (entity == null) {
                Text(stringResource(R.string.tracks_gone))
            } else if (d == null || d.trackId != trackId) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            } else {
                entity.desc?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                StatsCard(d.stats)

                Text(stringResource(R.string.tracks_profile_title), style = MaterialTheme.typography.titleMedium)
                val profile = d.profile
                if (profile == null) {
                    Text(stringResource(R.string.tracks_profile_none))
                } else {
                    ElevationProfileView(
                        profile = profile,
                        cursorMeters = cursorMeters,
                        onCursor = { at ->
                            cursorMeters = at
                            vm.setCursor(at)
                        },
                    )
                    if (cursorPoint != null) {
                        OutlinedButton(
                            onClick = { cursorPoint?.let { onShowOnMap(MapRequest(it.lat, it.lon, it.lat, it.lon)) } },
                        ) { Text(stringResource(R.string.tracks_cursor_on_map)) }
                    }
                }
                if (d.missingElevation > 0) {
                    Card {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.tracks_elevation_missing, d.missingElevation))
                            OutlinedButton(onClick = { vm.completeElevation(trackId) }, enabled = !busy) {
                                Text(stringResource(R.string.tracks_elevation_complete))
                            }
                        }
                    }
                }

                Button(
                    onClick = { onShowOnMap(MapRequest(entity.minLat, entity.minLon, entity.maxLat, entity.maxLon)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.tracks_show_on_map)) }
                OutlinedButton(onClick = { askRadius = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tracks_download_around))
                }
                OutlinedButton(onClick = { onPrintAlong(trackId) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(MenuIcons.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(stringResource(R.string.tracks_print_along))
                        Text(
                            stringResource(R.string.tracks_print_along_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val file =
                                vm.exportToCache(
                                    TrackSharing.directory(context),
                                    TrackSharing.fileName(entity.name),
                                    TrackSelection(trackIds = listOf(trackId)),
                                )
                            if (file !=
                                null
                            ) {
                                TrackSharing.share(context, file, resources.getString(R.string.tracks_share))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.tracks_share)) }

                Text(stringResource(R.string.tracks_tools), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { onFollow(trackId) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tracks_follow_this))
                }
                OutlinedButton(onClick = { onEdit(trackId) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tracks_edit))
                }
                OutlinedButton(onClick = { askReverse = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tracks_reverse))
                }
                OutlinedButton(onClick = { askSimplify = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tracks_simplify))
                }
                OutlinedButton(onClick = { askMerge = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tracks_merge))
                }
                OutlinedButton(onClick = { askDescribe = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tracks_describe))
                }
            }
        }
    }

    if (askReverse) {
        ConfirmDialog(
            title = stringResource(R.string.tracks_reverse),
            message = stringResource(R.string.tracks_reversed),
            confirmLabel = stringResource(R.string.tracks_reverse),
            onConfirm = {
                askReverse = false
                vm.reverse(trackId)
            },
            onDismiss = { askReverse = false },
        )
    }
    if (askSimplify) {
        AlertDialog(
            onDismissRequest = { askSimplify = false },
            title = { Text(stringResource(R.string.draw_simplify_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.draw_simplify_ask))
                    SIMPLIFY_TOLERANCES.forEach { meters ->
                        TextButton(onClick = {
                            askSimplify = false
                            vm.simplify(trackId, meters)
                        }) { Text(fmt.distance(meters)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { askSimplify = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    if (askMerge) {
        val others = library?.tracks?.filter { it.id != trackId }.orEmpty()
        val unnamed = stringResource(R.string.tracks_unnamed)
        AlertDialog(
            onDismissRequest = { askMerge = false },
            title = { Text(stringResource(R.string.tracks_merge_pick)) },
            text = {
                if (others.isEmpty()) {
                    Text(stringResource(R.string.tracks_merge_none))
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        others.forEach { other ->
                            TextButton(onClick = {
                                askMerge = false
                                vm.merge(
                                    listOf(trackId, other.id),
                                    "${entity?.name ?: unnamed} + ${other.name ?: unnamed}",
                                )
                            }) { Text(other.name ?: unnamed) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { askMerge = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    if (askDescribe) {
        RenameDialog(
            title = stringResource(R.string.tracks_describe_title),
            initial = entity?.desc.orEmpty(),
            onConfirm = { text ->
                askDescribe = false
                vm.describe(ItemRef(ItemKind.TRACK, trackId), text)
            },
            onDismiss = { askDescribe = false },
            singleLine = false,
            allowBlank = true,
        )
    }

    if (askRadius) {
        RadiusDialog(
            onPick = { radius ->
                askRadius = false
                onDownloadAround(trackId, radius)
            },
            onDismiss = { askRadius = false },
        )
    }
}

@Composable
private fun RadiusDialog(
    onPick: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val fmt = LocalFormats.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tracks_download_around)) },
        text = {
            Column {
                Text(stringResource(R.string.tracks_download_radius_ask))
                RADII.forEach { meters ->
                    TextButton(onClick = { onPick(meters) }) { Text(fmt.distance(meters)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun StatsCard(stats: TrackStats) {
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
    stats.averageMovingSpeedMps?.let {
        rows +=
            stringResource(R.string.tracks_stat_speed_moving) to fmt.speed(it)
    }
    stats.maxSpeedMps?.let { rows += stringResource(R.string.tracks_stat_speed_max) to fmt.speed(it) }
    stats.startTimeMillis?.let { rows += stringResource(R.string.tracks_stat_start) to NameDates.format(it) }
    stats.endTimeMillis?.let { rows += stringResource(R.string.tracks_stat_end) to NameDates.format(it) }
    rows += stringResource(R.string.tracks_stat_points) to
        pluralStringResource(R.plurals.tracks_points_segments, stats.segmentCount, stats.pointCount, stats.segmentCount)

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private val RADII = listOf(500.0, 1000.0, 2000.0, 5000.0)

private val SIMPLIFY_TOLERANCES = listOf(1.0, 2.0, 5.0, 10.0, 25.0)
