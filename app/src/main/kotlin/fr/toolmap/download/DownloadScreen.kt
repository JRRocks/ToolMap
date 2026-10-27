package fr.toolmap.download

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.download.ZoneJob
import fr.toolmap.core.download.ZoneShape
import fr.toolmap.core.download.ZoneStatus
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.track.NameDates
import fr.toolmap.ui.CheckRow
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.TextButton
import kotlin.math.cos

/** "Download for offline use": the new-download form (when a zone is pending) and the list of jobs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    vm: DownloadViewModel,
    onBack: () -> Unit,
    /** Back to the map, with the zone selection started (from the empty list). */
    onSelectZone: () -> Unit,
) {
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val offline by vm.offline.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            ToolMapTopBar(title = stringResource(R.string.download_title), onBack = onBack)
        },
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
            Text(stringResource(R.string.download_jobs), style = MaterialTheme.typography.titleMedium)
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
            jobs.forEach { job ->
                JobCard(job, vm)
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
private fun JobCard(
    job: ZoneJob,
    vm: DownloadViewModel,
) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(job.request.name, style = MaterialTheme.typography.titleSmall)
                    Text(statusText(job), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { vm.remove(job.request.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.download_remove))
                }
            }
            if (job.status != ZoneStatus.DONE || job.progress.total > 0) {
                LinearProgressIndicator(progress = { job.progress.fraction }, modifier = Modifier.fillMaxWidth())
            }
            val p = job.progress
            Text(
                stringResource(
                    R.string.download_counters,
                    p.processed,
                    p.total,
                    formatBytes(p.bytes),
                    p.failed,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            job.message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (job.status) {
                    ZoneStatus.QUEUED, ZoneStatus.RUNNING, ZoneStatus.INTERRUPTED -> {
                        OutlinedButton(onClick = {
                            vm.pause(job.request.id)
                        }) { Text(stringResource(R.string.download_pause)) }
                    }

                    ZoneStatus.PAUSED, ZoneStatus.FAILED -> {
                        OutlinedButton(onClick = {
                            vm.resume(job.request.id)
                        }) { Text(stringResource(R.string.download_resume)) }
                    }

                    ZoneStatus.DONE -> {
                        Unit
                    }
                }
            }
        }
    }
    HorizontalDivider()
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
private const val DEFAULT_MAX_ZOOM = 16
private const val BYTES_PER_KB = 1_000L
private const val BYTES_PER_MB = 1_000_000L
private const val BYTES_PER_GB = 1_000_000_000L
