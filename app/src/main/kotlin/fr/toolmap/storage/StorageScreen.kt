package fr.toolmap.storage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.TextButton

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

/** Tiles kept on the device: size per map, purges, and `.mbtiles` export / import. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    vm: StorageViewModel,
    onBack: () -> Unit,
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
            ToolMapTopBar(title = stringResource(R.string.storage_title), onBack = onBack)
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            // How much is kept, then the maps from the largest down: the ones worth purging come first.
            val total = state.usages.sumOf { it.diskBytes }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.usages.isEmpty()) {
                        Text(stringResource(R.string.storage_empty), style = MaterialTheme.typography.titleMedium)
                    } else {
                        Text(formatBytes(total), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            pluralStringResource(R.plurals.storage_maps, state.usages.size, state.usages.size),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    OutlinedButton(
                        onClick = { importer.launch(arrayOf("*/*")) },
                        enabled = !state.busy,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(stringResource(R.string.storage_import))
                    }
                }
            }
            for (usage in state.usages.sortedByDescending { it.diskBytes }) {
                UsageCard(
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
 * One map of the storage: its name, size, tiles and zooms on two lines, a bar of its part of the total, and its
 * commands in the ⋮ menu (export, purges, or deletion of a local map).
 */
@Composable
private fun UsageCard(
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        usage.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        formatBytes(usage.diskBytes),
                        style = MaterialTheme.typography.titleSmall,
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
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    drawStopIndicator = {},
                )
            }
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.storage_actions, usage.name),
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
}

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
