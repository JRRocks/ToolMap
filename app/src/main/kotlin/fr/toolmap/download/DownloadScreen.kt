package fr.toolmap.download

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.download.ZoneJob
import fr.toolmap.core.download.ZoneShape
import fr.toolmap.core.download.ZoneStatus
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.track.NameDates
import fr.toolmap.draw.RouteIcons
import fr.toolmap.map.MenuIcons
import fr.toolmap.maps.MapThumbnail
import fr.toolmap.maps.MapsIcons
import fr.toolmap.ui.CheckRow
import fr.toolmap.ui.GROUP_RADIUS
import fr.toolmap.ui.GroupBlock
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.DropdownMenu
import fr.toolmap.ui.theme.FilledTonalButton
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.cos

/**
 * « Téléchargements » since P29.7 (« Poids »): the job going first, in large (its map, « 51 % », Pause, Retirer), the
 * other jobs waiting or interrupted just under it, then « En pause » (with the failures and their message in red) and
 * « Terminés ». « Nouvelle zone » opens the selection on the map; « Retirer » offers « Annuler ». The form of a band
 * around a track or of a polygon comes first while a zone waits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    vm: DownloadViewModel,
    onBack: () -> Unit,
    /** Back to the map, with the zone selection started (from the empty list and from « Nouvelle zone »). */
    onSelectZone: () -> Unit,
) {
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val offline by vm.offline.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val names = remember(sources) { sources.associate { it.id to it.name } }

    fun remove(job: ZoneJob) {
        vm.remove(job.request.id)
        scope.launch {
            val answer =
                snackbar.showSnackbar(
                    resources.getString(R.string.download_removed, job.request.name),
                    actionLabel = resources.getString(R.string.download_undo),
                    duration = SnackbarDuration.Long,
                )
            if (answer == SnackbarResult.ActionPerformed) vm.restore(job)
        }
    }

    val going = jobs.firstOrNull { it.status == ZoneStatus.RUNNING } ?: jobs.firstOrNull { it.status in WAITING }
    val waiting = jobs.filter { it != going && (it.status in WAITING || it.status == ZoneStatus.RUNNING) }
    val stopped = jobs.filter { it.status == ZoneStatus.PAUSED || it.status == ZoneStatus.FAILED }
    val done = jobs.filter { it.status == ZoneStatus.DONE }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            ToolMapTopBar(title = stringResource(R.string.download_title), onBack = onBack)
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            // Hidden while the form of a band or a polygon is open: it is the zone being prepared.
            if (pending == null) {
                ExtendedFloatingActionButton(
                    onClick = onSelectZone,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(MapsIcons.CropFree, contentDescription = null) },
                    text = { Text(stringResource(R.string.download_new_zone)) },
                )
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (offline) {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.download_offline_warning))
                        OutlinedButton(onClick = { vm.setOffline(false) }) {
                            Text(stringResource(R.string.download_go_online))
                        }
                    }
                }
            }
            pending?.let { zone ->
                NewDownloadForm(vm, zone, sources.filter { it.type != "dem" && it.isSupported && !it.isLocalOnly })
            }
            if (jobs.isEmpty() && pending == null) {
                // Nothing yet: say how a zone is saved and lead there, rather than leave an empty page.
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.download_jobs_empty), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.download_empty_hint), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onSelectZone) { Text(stringResource(R.string.maps_save_area)) }
                    }
                }
            } else if (jobs.isEmpty()) {
                Text(stringResource(R.string.download_jobs_empty))
            }
            going?.let { GoingJob(it, names, vm, onRemove = { remove(it) }) }
            if (waiting.isNotEmpty()) {
                GroupBlock(rows = waiting.map { job -> { JobLine(job, names, vm, onRemove = { remove(job) }) } })
            }
            if (stopped.isNotEmpty()) {
                Section(stringResource(R.string.download_section_paused))
                GroupBlock(rows = stopped.map { job -> { JobLine(job, names, vm, onRemove = { remove(job) }) } })
            }
            if (done.isNotEmpty()) {
                Section(stringResource(R.string.download_section_done))
                GroupBlock(rows = done.map { job -> { JobLine(job, names, vm, onRemove = { remove(job) }) } })
            }
        }
    }
}

/** The jobs that wait for their turn or for the network: they go with the one going. */
private val WAITING = setOf(ZoneStatus.QUEUED, ZoneStatus.INTERRUPTED)

/** A small grey title over a group of jobs. */
@Composable
private fun Section(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

/** « IGN Cartes, OpenTopoMap · zooms jusqu'à 16 »: the maps of a job and its zooms. */
@Composable
private fun mapsAndZooms(
    job: ZoneJob,
    names: Map<String, String>,
): String {
    val request = job.request
    val maps = request.sourceIds.joinToString(", ") { names[it] ?: it }
    val zooms =
        if (request.minZoom <= 0) {
            stringResource(R.string.download_zooms_to, request.maxZoom)
        } else {
            stringResource(R.string.download_zooms_range, request.minZoom, request.maxZoom)
        }
    return "$maps · $zooms"
}

/** Counts with the thousands apart (« 1 450 »), in French as every text of the app. */
private fun count(value: Long): String = NumberFormat.getIntegerInstance(Locale.FRENCH).format(value)

/** « 310 / 1 450 tuiles · 6,2 Mo », and the errors only when there are some. */
@Composable
private fun progressLine(job: ZoneJob): String {
    val p = job.progress
    val tiles =
        if (job.status == ZoneStatus.DONE) {
            stringResource(R.string.download_tiles_all, count(p.processed))
        } else {
            stringResource(R.string.download_tiles_of, count(p.processed), count(p.total))
        }
    val failed = p.failed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return listOfNotNull(
        tiles,
        formatBytes(p.bytes).takeIf { p.bytes > 0 },
        if (failed > 0) pluralStringResource(R.plurals.download_errors, failed, failed) else null,
    ).joinToString(" · ")
}

/** The job going, in large on the colour of the tiles: its map, its percentage, its bar, Pause and Retirer. */
@Composable
private fun GoingJob(
    job: ZoneJob,
    names: Map<String, String>,
    vm: DownloadViewModel,
    onRemove: () -> Unit,
) {
    val tm = MaterialTheme.toolMap
    val first = job.request.sourceIds.firstOrNull()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GROUP_RADIUS))
            .background(tm.tile)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (first != null) MapThumbnail(first, names[first] ?: first, vm.thumbnails, size = 48.dp)
            Column(Modifier.weight(1f)) {
                Text(job.request.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    mapsAndZooms(job, names),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                stringResource(R.string.download_percent, (job.progress.fraction * PERCENT).toInt()),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
            )
        }
        LinearProgressIndicator(
            progress = { job.progress.fraction },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(8.dp),
            color = tm.accent,
            trackColor = tm.panel,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Text(
            statusText(job) + " · " + progressLine(job),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        job.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { vm.pause(job.request.id) }) {
                Icon(MenuIcons.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.download_pause), modifier = Modifier.padding(start = 8.dp))
            }
            TextButton(onClick = onRemove) { Text(stringResource(R.string.download_remove_short)) }
        }
    }
}

/**
 * A job as a row of a white block: the icon of its state, its name, play or pause, the ⋮ with « Retirer de la liste »;
 * under it the counts and the maps, a thin bar while it is not done, the message of a failure in red.
 */
@Composable
private fun JobLine(
    job: ZoneJob,
    names: Map<String, String>,
    vm: DownloadViewModel,
    onRemove: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var menu by remember { mutableStateOf(false) }
    val (icon, tint) =
        when (job.status) {
            ZoneStatus.DONE -> Icons.Filled.CheckCircle to MaterialTheme.toolMap.accent
            ZoneStatus.FAILED -> MapsIcons.Error to MaterialTheme.colorScheme.error
            ZoneStatus.PAUSED -> DownloadIcons.PauseCircle to muted
            ZoneStatus.INTERRUPTED -> RouteIcons.Refresh to muted
            ZoneStatus.QUEUED, ZoneStatus.RUNNING -> DownloadIcons.Schedule to muted
        }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 12.dp)) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = statusText(job), tint = tint, modifier = Modifier.size(24.dp))
            Text(
                job.request.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            when (job.status) {
                ZoneStatus.PAUSED, ZoneStatus.FAILED -> {
                    IconButton(onClick = { vm.resume(job.request.id) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.download_resume))
                    }
                }

                ZoneStatus.QUEUED, ZoneStatus.RUNNING, ZoneStatus.INTERRUPTED -> {
                    IconButton(onClick = { vm.pause(job.request.id) }) {
                        Icon(MenuIcons.Pause, contentDescription = stringResource(R.string.download_pause))
                    }
                }

                ZoneStatus.DONE -> {
                    Unit
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.download_more, job.request.name),
                        tint = muted,
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.download_remove))
                                Text(
                                    stringResource(R.string.download_remove_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = muted,
                                )
                            }
                        },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menu = false
                            onRemove()
                        },
                    )
                }
            }
        }
        Column(Modifier.padding(start = 36.dp, end = 12.dp).offset(y = (-6).dp)) {
            val maps = job.request.sourceIds.joinToString(", ") { names[it] ?: it }
            val day =
                if (job.status == ZoneStatus.DONE && job.updatedAtMillis > 0) {
                    stringResource(R.string.download_done_on, NameDates.formatDay(job.updatedAtMillis))
                } else {
                    null
                }
            Text(
                listOfNotNull(
                    if (job.status in WAITING) statusText(job) else null,
                    progressLine(job),
                    maps,
                    day,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            if (job.status != ZoneStatus.DONE && job.status != ZoneStatus.FAILED) {
                LinearProgressIndicator(
                    progress = { job.progress.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp),
                    color = MaterialTheme.colorScheme.outline,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
            job.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun NewDownloadForm(
    vm: DownloadViewModel,
    zone: PendingZone,
    sources: List<TileSource>,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var selected by remember(zone) {
        mutableStateOf(
            zone.sourceIds.filter { id ->
                sources.any { it.id == id }
            }.toSet(),
        )
    }
    var zooms by remember(zone) { mutableStateOf(zone.suggestedMinZoom.toFloat()..zone.suggestedMaxZoom.toFloat()) }
    var name by remember(zone) {
        mutableStateOf(
            resources.getString(
                R.string.download_default_name,
                NameDates.format(System.currentTimeMillis()),
            ),
        )
    }
    var respectRanges by remember(zone) { mutableStateOf(zone.layerLimits.isNotEmpty()) }
    val draft =
        ZoneDraft(
            zone.shape,
            selected,
            zooms.start.toInt(),
            zooms.endInclusive.toInt(),
            respectLayerRanges = respectRanges,
            layerLimits = zone.layerLimits,
        )
    var estimate by remember { mutableStateOf<Estimate?>(null) }
    LaunchedEffect(draft) {
        estimate = null
        estimate = vm.estimate(draft)
    }
    var offlineRefused by remember { mutableStateOf(false) }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Granted or not, the download goes on: without the permission the notification is simply hidden.
            offlineRefused = !vm.start(draft, name)
            if (!offlineRefused) vm.clearPending()
        }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.download_new), style = MaterialTheme.typography.titleMedium)
            Text(describe(zone.shape))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.download_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.download_maps), style = MaterialTheme.typography.titleSmall)
            sources.forEach { source ->
                CheckRow(
                    checked = source.id in selected,
                    onCheckedChange = { on -> selected = if (on) selected + source.id else selected - source.id },
                ) {
                    Column {
                        Text(source.name)
                        Text(
                            stringResource(R.string.layers_zoom_range, source.minZoom, source.maxZoom),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            val maxZoom =
                (
                    sources.filter {
                        it.id in selected
                    }.maxOfOrNull { it.maxZoom } ?: DEFAULT_MAX_ZOOM
                ).coerceAtLeast(1)
            Text(stringResource(R.string.download_zooms, zooms.start.toInt(), zooms.endInclusive.toInt()))
            RangeSlider(
                value = zooms.start.coerceIn(0f, maxZoom.toFloat())..zooms.endInclusive.coerceIn(0f, maxZoom.toFloat()),
                onValueChange = { zooms = it },
                valueRange = 0f..maxZoom.toFloat(),
                steps = (maxZoom - 1).coerceAtLeast(0),
            )
            if (zone.layerLimits.isNotEmpty()) {
                CheckRow(checked = respectRanges, onCheckedChange = { respectRanges = it }) {
                    Text(stringResource(R.string.download_respect_ranges))
                }
            }
            Text(estimateText(estimate), color = estimateColor(estimate))
            if (offlineRefused) {
                Text(
                    stringResource(R.string.download_refused_offline),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                enabled = estimate is Estimate.Ready,
                onClick = {
                    val needsPermission =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                    if (needsPermission) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        offlineRefused = !vm.start(draft, name)
                        if (!offlineRefused) vm.clearPending()
                    }
                },
            ) { Text(stringResource(R.string.download_start)) }
        }
    }
}

@Composable
private fun estimateColor(estimate: Estimate?) =
    when (estimate) {
        Estimate.TooMany, is Estimate.Restricted -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }

@Composable
private fun estimateText(estimate: Estimate?): String =
    when (estimate) {
        null -> {
            stringResource(R.string.download_estimating)
        }

        is Estimate.Ready -> {
            if (estimate.stored == 0L) {
                stringResource(R.string.download_estimate, estimate.tiles, formatBytes(estimate.bytes))
            } else {
                val missing = (estimate.tiles - estimate.stored).coerceAtLeast(0L)
                stringResource(
                    R.string.download_estimate_stored,
                    estimate.tiles,
                    estimate.stored,
                    missing,
                    formatBytes(estimate.bytes),
                )
            }
        }

        Estimate.Empty -> {
            stringResource(R.string.download_estimate_empty)
        }

        Estimate.TooMany -> {
            stringResource(R.string.download_estimate_too_many)
        }

        is Estimate.Restricted -> {
            stringResource(R.string.download_estimate_restricted, estimate.sourceName, estimate.limit, estimate.tiles)
        }
    }

@Composable
private fun describe(shape: ZoneShape): String =
    when (shape) {
        is ZoneShape.Rectangle -> {
            val midLat = Math.toRadians((shape.south + shape.north) / 2)
            val widthKm = (shape.east - shape.west) * KM_PER_DEGREE * cos(midLat)
            val heightKm = (shape.north - shape.south) * KM_PER_DEGREE
            stringResource(R.string.download_shape_rectangle, widthKm, heightKm)
        }

        is ZoneShape.Polygon -> {
            stringResource(R.string.download_shape_polygon, shape.points.size)
        }

        is ZoneShape.Corridor -> {
            stringResource(R.string.download_shape_corridor, shape.radiusMeters.toInt())
        }
    }

@Composable
private fun statusText(job: ZoneJob): String =
    stringResource(
        when (job.status) {
            ZoneStatus.QUEUED -> R.string.download_status_queued
            ZoneStatus.RUNNING -> R.string.download_status_running
            ZoneStatus.PAUSED -> R.string.download_status_paused
            ZoneStatus.INTERRUPTED -> R.string.download_status_interrupted
            ZoneStatus.DONE -> R.string.download_status_done
            ZoneStatus.FAILED -> R.string.download_status_failed
        },
    )

/** "12,3 Mo", "480 Ko"... (decimal units, like the file managers). */
fun formatBytes(bytes: Long): String =
    when {
        bytes >= BYTES_PER_GB -> {
            String.format(
                java.util.Locale.getDefault(),
                "%.1f Go",
                bytes / BYTES_PER_GB.toDouble(),
            )
        }

        bytes >= BYTES_PER_MB -> {
            String.format(
                java.util.Locale.getDefault(),
                "%.1f Mo",
                bytes / BYTES_PER_MB.toDouble(),
            )
        }

        bytes >= BYTES_PER_KB -> {
            String.format(java.util.Locale.getDefault(), "%d Ko", bytes / BYTES_PER_KB)
        }

        else -> {
            "$bytes o"
        }
    }

private const val KM_PER_DEGREE = 111.32
private const val PERCENT = 100f
private const val DEFAULT_MAX_ZOOM = 16
private const val BYTES_PER_KB = 1_000L
private const val BYTES_PER_MB = 1_000_000L
private const val BYTES_PER_GB = 1_000_000_000L
