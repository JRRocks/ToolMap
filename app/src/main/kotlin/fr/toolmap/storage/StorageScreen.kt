package fr.toolmap.storage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.source.TileSource
import fr.toolmap.data.storage.ImportTarget
import fr.toolmap.data.storage.PackageException
import fr.toolmap.data.storage.SourceUsage
import fr.toolmap.data.storage.StorageManager
import fr.toolmap.download.formatBytes
import fr.toolmap.map.MenuIcons
import fr.toolmap.ui.GroupBlock
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.DropdownMenu
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap

private const val PURGE_AFTER_DAYS = 30
private const val MIME_BINARY = "application/octet-stream"

/** What a confirmation dialog is about. */
private sealed interface Confirm {
    data class Purge(
        val usage: SourceUsage,
    ) : Confirm

    data class DeleteMap(
        val usage: SourceUsage,
    ) : Confirm
}

/**
 * Tiles kept on the device: size per map, purges, and `.mbtiles` export / import. Since P29.7 (« Poids ») it opens on
 * the gauge of the phone (used and total, ToolMap's part in turquoise), then the maps from the heaviest in one white
 * block; the import is an icon of the bar, the empty storage leads to the selection of a zone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    vm: StorageViewModel,
    onBack: () -> Unit,
    /** Back to the map, with the zone selection started (from the empty storage, P29.7). */
    onSelectZone: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var exportingId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.stage(uri)
        }
    val exporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_BINARY)) { uri ->
            val id = exportingId
            if (uri != null && id != null) vm.export(id, uri)
            exportingId = null
        }

    val message = state.message
    val text = message?.let { messageText(it) }
    LaunchedEffect(message) {
        if (text != null) {
            snackbar.showSnackbar(text)
            vm.consumeMessage()
        }
    }

    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            ToolMapTopBar(
                title = stringResource(R.string.storage_title),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { importer.launch(arrayOf("*/*")) }, enabled = !state.busy) {
                        Icon(MenuIcons.UploadFile, contentDescription = stringResource(R.string.storage_import))
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
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
        ) {
            if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            // The room of the phone first, where one decides to purge; then the maps from the heaviest down.
            val total = state.usages.sumOf { it.diskBytes }
            SpaceGauge(state, total)
            if (state.usages.isEmpty()) {
                Text(
                    stringResource(R.string.storage_empty),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 24.dp),
                )
                Text(
                    stringResource(R.string.download_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Button(onClick = onSelectZone, modifier = Modifier.padding(top = 16.dp)) {
                    Text(stringResource(R.string.maps_save_area))
                }
                OutlinedButton(
                    onClick = { importer.launch(arrayOf("*/*")) },
                    enabled = !state.busy,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.storage_import))
                }
            } else {
                Text(
                    stringResource(R.string.storage_sorted),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp),
                )
                val sorted = state.usages.sortedByDescending { it.diskBytes }
                GroupBlock(
                    rows =
                        sorted.map { usage ->
                            {
                                UsageRow(
                                    usage = usage,
                                    share = if (total > 0) usage.diskBytes.toFloat() / total else 0f,
                                    isLocalMap = vm.isLocalMap(usage.sourceId),
                                    enabled = !state.busy,
                                    onExport = {
                                        exportingId = usage.sourceId
                                        exporter.launch(StorageManager.slug(usage.name) + ".mbtiles")
                                    },
                                    onPurgeOld = { vm.purgeOlderThan(usage.sourceId, PURGE_AFTER_DAYS) },
                                    onPurge = { confirm = Confirm.Purge(usage) },
                                    onDeleteMap = { confirm = Confirm.DeleteMap(usage) },
                                )
                            }
                        },
                )
            }
        }
    }

    confirm?.let { pending ->
        ConfirmDialog(
            pending = pending,
            onDismiss = { confirm = null },
            onConfirm = {
                when (pending) {
                    is Confirm.Purge -> vm.purge(pending.usage.sourceId)
                    is Confirm.DeleteMap -> vm.deleteLocalMap(pending.usage.sourceId)
                }
                confirm = null
            },
        )
    }
    state.pendingImport?.let { pending ->
        ImportPackageDialog(
            pending = pending,
            sources = sources.filter { it.type != "dem" && it.isSupported && !it.isLocalOnly },
            onCancel = vm::cancelImport,
            onConfirm = vm::commitImport,
        )
    }
}

/**
 * The gauge of the phone: « 5,4 Go utilisés » and « sur 10,4 Go », a bar where ToolMap's part is turquoise (never
 * thinner than 8 dp, so that it stays seen) and the rest of the used room grey, and its legend « Tuiles de ToolMap ».
 */
@Composable
private fun SpaceGauge(
    state: StorageUiState,
    tilesBytes: Long,
) {
    val tm = MaterialTheme.toolMap
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth()) {
        if (state.spaceTotal > 0) {
            val used = (state.spaceTotal - state.spaceFree).coerceAtLeast(0L)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    stringResource(R.string.storage_phone_used, formatBytes(used)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.storage_phone_of, formatBytes(state.spaceTotal)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
            }
            val track = MaterialTheme.colorScheme.outlineVariant
            val rest = muted
            Canvas(Modifier.fillMaxWidth().padding(top = 10.dp).height(10.dp).clip(RoundedCornerShape(5.dp))) {
                drawRect(track)
                val usedWidth = size.width * (used.toFloat() / state.spaceTotal).coerceIn(0f, 1f)
                val ours =
                    (size.width * (tilesBytes.toFloat() / state.spaceTotal))
                        .coerceAtLeast(GAUGE_MIN_PART.toPx())
                        .coerceAtMost(usedWidth.coerceAtLeast(GAUGE_MIN_PART.toPx()))
                drawRect(tm.accent, size = Size(ours, size.height))
                val gap = GAUGE_GAP.toPx()
                if (usedWidth > ours + gap) {
                    drawRect(rest, topLeft = Offset(ours + gap, 0f), size = Size(usedWidth - ours - gap, size.height))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(tm.accent))
            Text(
                stringResource(
                    R.string.storage_toolmap_part,
                    formatBytes(tilesBytes),
                    pluralStringResource(R.plurals.storage_maps_short, state.usages.size, state.usages.size),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * One map of the storage as a row of the white block: its name and its size at the end, its tiles, zooms and kind,
 * a bar of its part of the total, and its commands in the ⋮ menu (export, purges, or deletion of a local map).
 */
@Composable
private fun UsageRow(
    usage: SourceUsage,
    share: Float,
    isLocalMap: Boolean,
    enabled: Boolean,
    onExport: () -> Unit,
    onPurgeOld: () -> Unit,
    onPurge: () -> Unit,
    onDeleteMap: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    usage.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatBytes(usage.diskBytes),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            val zooms = usage.perZoom.keys
            Text(
                listOfNotNull(
                    pluralStringResource(
                        R.plurals.storage_tiles,
                        usage.tiles.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        usage.tiles,
                    ),
                    when {
                        zooms.isEmpty() -> null
                        zooms.min() == zooms.max() -> stringResource(R.string.storage_zoom, zooms.min())
                        else -> stringResource(R.string.storage_zooms, zooms.min(), zooms.max())
                    },
                    if (isLocalMap) stringResource(R.string.storage_local_badge) else null,
                    if (usage.usedBy.size > 1) {
                        pluralStringResource(R.plurals.storage_shared, usage.usedBy.size, usage.usedBy.size)
                    } else {
                        null
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { share },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp),
                color = MaterialTheme.toolMap.accent,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = enabled) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.storage_actions, usage.name),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val items =
                    buildList {
                        add(stringResource(R.string.storage_export) to onExport)
                        if (isLocalMap) {
                            add(stringResource(R.string.storage_delete_map) to onDeleteMap)
                        } else {
                            add(stringResource(R.string.storage_purge_old, PURGE_AFTER_DAYS) to onPurgeOld)
                            add(stringResource(R.string.storage_purge) to onPurge)
                        }
                    }
                for ((label, action) in items) {
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            menu = false
                            action()
                        },
                    )
                }
            }
        }
    }
}

private val GAUGE_MIN_PART = 8.dp
private val GAUGE_GAP = 2.dp

@Composable
private fun ConfirmDialog(
    pending: Confirm,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val (name, body) =
        when (pending) {
            is Confirm.Purge -> pending.usage.name to R.string.storage_confirm_purge
            is Confirm.DeleteMap -> pending.usage.name to R.string.storage_confirm_delete_map
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = { Text(stringResource(body, name)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.storage_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ImportPackageDialog(
    pending: PendingImport,
    sources: List<TileSource>,
    onCancel: () -> Unit,
    onConfirm: (ImportTarget) -> Unit,
) {
    val info = pending.info
    var name by rememberSaveable { mutableStateOf(info.name ?: "") }
    // null = a new local map; otherwise the id of the source that receives the tiles.
    var targetId by rememberSaveable { mutableStateOf(pending.knownSourceId) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.storage_import_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(
                        R.string.storage_import_info,
                        info.name ?: stringResource(R.string.storage_import_unnamed),
                        info.minZoom,
                        info.maxZoom,
                        info.tiles,
                    ),
                )
                ChoiceRow(selected = targetId == null, label = stringResource(R.string.storage_import_new)) {
                    targetId =
                        null
                }
                if (targetId == null) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.storage_import_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.storage_import_local_note), style = MaterialTheme.typography.bodySmall)
                }
                for (source in sources) {
                    ChoiceRow(
                        selected = targetId == source.id,
                        label = stringResource(R.string.storage_import_into, source.name),
                    ) { targetId = source.id }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = targetId != null || name.isNotBlank(),
                onClick = {
                    val id = targetId
                    onConfirm(if (id != null) ImportTarget.Existing(id) else ImportTarget.NewLocalMap(name.trim()))
                },
            ) { Text(stringResource(R.string.import_confirm)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ChoiceRow(
    selected: Boolean,
    label: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label)
    }
}

@Composable
private fun messageText(message: StorageMessage): String =
    when (message) {
        is StorageMessage.Exported -> {
            stringResource(R.string.storage_exported, formatBytes(message.bytes))
        }

        is StorageMessage.Imported -> {
            stringResource(R.string.storage_imported, message.sourceName)
        }

        StorageMessage.Purged -> {
            stringResource(R.string.storage_purged)
        }

        is StorageMessage.PurgedTiles -> {
            stringResource(R.string.storage_purged_count, message.count)
        }

        StorageMessage.MapDeleted -> {
            stringResource(R.string.storage_map_deleted)
        }

        is StorageMessage.Failed -> {
            stringResource(R.string.storage_error, message.detail)
        }

        is StorageMessage.Refused -> {
            when (message.reason) {
                PackageException.Reason.NOT_MBTILES -> stringResource(R.string.storage_error_not_mbtiles)
                PackageException.Reason.UNSUPPORTED_FORMAT -> stringResource(R.string.storage_error_format)
                PackageException.Reason.EMPTY -> stringResource(R.string.storage_error_empty)
                PackageException.Reason.TOO_LARGE -> stringResource(R.string.storage_error_too_large)
                PackageException.Reason.UNREADABLE -> stringResource(R.string.storage_error_unreadable, message.detail)
            }
        }
    }
