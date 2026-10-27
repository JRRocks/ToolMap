package fr.toolmap.backup

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.backup.BackupArea
import fr.toolmap.core.backup.BackupManifest
import fr.toolmap.core.backup.RestoreReport
import fr.toolmap.download.formatBytes
import fr.toolmap.settings.SettingsIcons
import fr.toolmap.ui.GroupBlock
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.FilledTonalButton
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.Switch
import fr.toolmap.ui.theme.TextButton
import java.time.LocalDate

private const val MIME_ZIP = "application/zip"

/** The two halves of the screen (P29.9): one decision at a time. */
private enum class BackupSegment { SAVE, RESTORE }

/**
 * Saves the app's data to a zip and restores it from one (P29.9, « Loupe »): a segmented button parts « Sauvegarder »
 * and « Restaurer », each with one main action. A restore waiting for the restart opens the screen on « Restaurer »,
 * its card first; the report of the last restore and the progress of a task are above the segmented button, which is
 * greyed while a task runs.
 */
@Composable
fun BackupScreen(
    vm: BackupViewModel,
    onBack: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.messages.collect { snackbar.showSnackbar(resources.getString(it.resId, *it.args.toTypedArray())) }
    }

    val createLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_ZIP)) { uri ->
            if (uri != null) {
                vm.create(
                    open = { context.contentResolver.openOutputStream(uri) },
                    discard = { runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) } },
                )
            }
        }
    val openLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.choose { context.contentResolver.openInputStream(uri) }
        }
    BackupPage(
        ui = ui,
        snackbar = snackbar,
        onBack = onBack,
        onIncludeTiles = vm::setIncludeTiles,
        onCreate = { createLauncher.launch("toolmap-${LocalDate.now()}.zip") },
        onChoose = { openLauncher.launch(arrayOf(MIME_ZIP, "application/x-zip-compressed", "*/*")) },
        onRestart = { restartApp(context) },
        onCancelPending = vm::cancelPending,
        onHideReport = vm::dismissReport,
    )

    ui.preview?.let { manifest ->
        AlertDialog(
            onDismissRequest = vm::cancelPreview,
            title = { Text(stringResource(R.string.backup_preview_title)) },
            text = { PreviewText(manifest, context) },
            confirmButton = {
                TextButton(onClick = vm::confirm) { Text(stringResource(R.string.backup_preview_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelPreview) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** The screen itself, from what [ui] says: the progress and the report, the segmented button, then its half. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupPage(
    ui: BackupUi,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onIncludeTiles: (Boolean) -> Unit,
    onCreate: () -> Unit,
    onChoose: () -> Unit,
    onRestart: () -> Unit,
    onCancelPending: () -> Unit,
    onHideReport: () -> Unit,
) {
    val context = LocalContext.current
    val busy = ui.busy != null
    var segment by rememberSaveable { mutableStateOf(BackupSegment.SAVE) }
    // A restore waiting is the most urgent: the screen shows it as soon as it is known.
    val pending = ui.pending != null
    LaunchedEffect(pending) { if (pending) segment = BackupSegment.RESTORE }
    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            ToolMapTopBar(title = stringResource(R.string.backup_title), onBack = onBack)
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
        ) {
            ui.busy?.let { task -> Progress(task, ui.progress) }
            ui.report?.let { report -> Report(report, context, onHide = onHideReport) }
            Segments(segment, enabled = !busy) { segment = it }
            when (segment) {
                BackupSegment.SAVE -> {
                    SaveHalf(ui = ui, busy = busy, onIncludeTiles = onIncludeTiles, onCreate = onCreate)
                }

                BackupSegment.RESTORE -> {
                    RestoreHalf(
                        pending = ui.pending,
                        busy = busy,
                        context = context,
                        onRestart = onRestart,
                        onCancel = onCancelPending,
                        onChoose = onChoose,
                    )
                }
            }
        }
    }
}

/** What runs, with its bar: determined when the amount is known. */
@Composable
private fun Progress(
    task: BackupBusy,
    progress: Float?,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Text(
            stringResource(
                when (task) {
                    BackupBusy.CREATING -> R.string.backup_busy_creating
                    BackupBusy.CHECKING -> R.string.backup_busy_checking
                    else -> R.string.backup_busy_unpacking
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        if (progress == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** How the last restore went, until « Masquer ». */
@Composable
private fun Report(
    report: RestoreReport,
    context: Context,
    onHide: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val container = if (report.ok) colors.secondaryContainer else colors.errorContainer
    val content = if (report.ok) colors.onSecondaryContainer else colors.onErrorContainer
    Column(
        modifier =
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                .fillMaxWidth()
                .background(container, MaterialTheme.shapes.medium)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(
            if (report.ok) {
                stringResource(R.string.backup_report_ok, dateText(context, report.backupMillis))
            } else {
                stringResource(R.string.backup_report_failed)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = content,
        )
        TextButton(onClick = onHide, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.backup_report_dismiss))
        }
    }
}

/** « Sauvegarder » | « Restaurer »: the chosen segment shows a tick in place of its icon. */
@Composable
private fun Segments(
    selected: BackupSegment,
    enabled: Boolean,
    onSelect: (BackupSegment) -> Unit,
) {
    val segments =
        listOf(
            Triple(BackupSegment.SAVE, SettingsIcons.Backup, R.string.backup_save_title),
            Triple(BackupSegment.RESTORE, SettingsIcons.SettingsBackupRestore, R.string.backup_restore_title),
        )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        segments.forEachIndexed { index, (segment, icon, label) ->
            SegmentedButton(
                selected = segment == selected,
                onClick = { onSelect(segment) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, segments.size),
                icon = {
                    SegmentedButtonDefaults.Icon(active = segment == selected) {
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                        )
                    }
                },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

/**
 * « Sauvegarder »: what the file is for, what it holds and the offline maps on demand, what it never holds, read before
 * « Créer une sauvegarde… » (tonal while a restore waits, whose « Redémarrer maintenant » is the main action).
 */
@Composable
private fun SaveHalf(
    ui: BackupUi,
    busy: Boolean,
    onIncludeTiles: (Boolean) -> Unit,
    onCreate: () -> Unit,
) {
    Text(
        stringResource(R.string.backup_save_short),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
    )
    GroupBlock(
        modifier = Modifier.padding(horizontal = 16.dp),
        rows =
            listOf(
                {
                    BackupRow(
                        icon = SettingsIcons.FolderZip,
                        title = stringResource(R.string.backup_content),
                        subtitle = stringResource(R.string.backup_content_list),
                    )
                },
                {
                    BackupRow(
                        icon = SettingsIcons.OfflinePin,
                        title = stringResource(R.string.backup_include_tiles),
                        subtitle = stringResource(R.string.backup_include_tiles_hint, formatBytes(ui.tilesBytes)),
                        modifier =
                            Modifier.toggleable(
                                value = ui.includeTiles,
                                enabled = !busy,
                                role = Role.Switch,
                                onValueChange = onIncludeTiles,
                            ),
                        trailing = {
                            Spacer(Modifier.width(8.dp))
                            Switch(checked = ui.includeTiles, onCheckedChange = null, enabled = !busy)
                        },
                    )
                },
            ),
    )
    Text(
        stringResource(R.string.backup_not_saved_short),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
    )
    val wide = Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 48.dp)
    val label = @Composable { Text(stringResource(R.string.backup_save_button)) }
    if (ui.pending != null) {
        FilledTonalButton(onClick = onCreate, enabled = !busy, modifier = wide) { label() }
    } else {
        Button(onClick = onCreate, enabled = !busy, modifier = wide) { label() }
    }
}

/**
 * « Restaurer »: the restore waiting first, with « Redémarrer maintenant », the only filled button of the screen; then
 * what a restore does, and « Choisir une sauvegarde… », filled when nothing waits.
 */
@Composable
private fun RestoreHalf(
    pending: BackupManifest?,
    busy: Boolean,
    context: Context,
    onRestart: () -> Unit,
    onCancel: () -> Unit,
    onChoose: () -> Unit,
) {
    if (pending != null) {
        Column(
            modifier =
                Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium)
                    .padding(12.dp),
        ) {
            val onCard = MaterialTheme.colorScheme.onPrimaryContainer
            Row(verticalAlignment = Alignment.Top) {
                Icon(SettingsIcons.SettingsBackupRestore, contentDescription = null, tint = onCard)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.backup_pending_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = onCard,
                    )
                    Text(
                        stringResource(
                            R.string.backup_pending_text,
                            dateText(context, pending.createdAtMillis),
                            pending.entries.size,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = onCard,
                    )
                }
            }
            Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onRestart, enabled = !busy) { Text(stringResource(R.string.backup_restart)) }
                TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.backup_pending_cancel)) }
            }
        }
    }
    Text(
        stringResource(R.string.backup_restore_text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
    )
    val wide = Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 48.dp)
    val label = @Composable { Text(stringResource(R.string.backup_restore_button)) }
    if (pending != null) {
        OutlinedButton(onClick = onChoose, enabled = !busy, modifier = wide) { label() }
    } else {
        Button(onClick = onChoose, enabled = !busy, modifier = wide) { label() }
    }
}

/** A row of the group of « Sauvegarder »: a grey icon on the line of the name, the name, its second line. */
@Composable
private fun BackupRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(modifier)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Top).size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing?.invoke(this)
    }
}

@Composable
private fun PreviewText(
    manifest: BackupManifest,
    context: Context,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(
                R.string.backup_preview_when,
                dateText(context, manifest.createdAtMillis),
                manifest.appVersion,
            ),
        )
        val resources = LocalResources.current
        val lines =
            BackupArea.entries.filter { manifest.covers(it) }.map { area ->
                val count = manifest.fileCount(area)
                val files = resources.getQuantityString(R.plurals.backup_area_files, count, count)
                "${resources.getString(areaLabel(area))} ($files)"
            }
        lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        Text(stringResource(R.string.backup_preview_size, formatBytes(manifest.totalBytes)))
        Text(
            stringResource(R.string.backup_preview_warning),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun areaLabel(area: BackupArea): Int =
    when (area) {
        BackupArea.DATABASE -> R.string.backup_area_database
        BackupArea.SETTINGS -> R.string.backup_area_settings
        BackupArea.ALERTS -> R.string.backup_area_alerts
        BackupArea.STATE -> R.string.backup_area_state
        BackupArea.SOURCES -> R.string.backup_area_sources
        BackupArea.WORKSPACES -> R.string.backup_area_workspaces
        BackupArea.PRINT -> R.string.backup_area_print
        BackupArea.TILES -> R.string.backup_area_tiles
    }

private fun dateText(
    context: Context,
    millis: Long,
): String =
    DateUtils.formatDateTime(
        context,
        millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
    )

/**
 * Starts the app again in a new process, so that the restore waiting on disk is applied before anything reads a file.
 * The launch is asked first (the app is in the foreground, so the system allows it), then this process ends.
 */
private fun restartApp(context: Context) {
    val intent =
        context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?: return
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}
