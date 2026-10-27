package fr.toolmap.backup

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.backup.BackupArea
import fr.toolmap.core.backup.BackupManifest
import fr.toolmap.download.formatBytes
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.Switch
import fr.toolmap.ui.theme.TextButton
import java.time.LocalDate

private const val MIME_ZIP = "application/zip"

/** Saves the app's data to a zip and restores it from one. */
@OptIn(ExperimentalMaterial3Api::class)
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
    val busy = ui.busy != null

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
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) {
                Text(
                    stringResource(
                        when (ui.busy) {
                            BackupBusy.CREATING -> R.string.backup_busy_creating
                            BackupBusy.CHECKING -> R.string.backup_busy_checking
                            else -> R.string.backup_busy_unpacking
                        },
                    ),
                )
                val progress = ui.progress
                if (progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
            ui.report?.let { report ->
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                if (report.ok) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.errorContainer
                                },
                        ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            if (report.ok) {
                                stringResource(R.string.backup_report_ok, dateText(context, report.backupMillis))
                            } else {
                                stringResource(R.string.backup_report_failed)
                            },
                        )
                        TextButton(onClick = vm::dismissReport) { Text(stringResource(R.string.backup_report_dismiss)) }
                    }
                }
            }

            Text(stringResource(R.string.backup_save_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.backup_save_text), style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.backup_include_tiles))
                    Text(
                        stringResource(R.string.backup_include_tiles_hint, formatBytes(ui.tilesBytes)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = ui.includeTiles, onCheckedChange = vm::setIncludeTiles, enabled = !busy)
            }
            Button(
                enabled = !busy,
                onClick = { createLauncher.launch("toolmap-${LocalDate.now()}.zip") },
            ) { Text(stringResource(R.string.backup_save_button)) }

            Text(
                stringResource(R.string.backup_restore_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(stringResource(R.string.backup_restore_text), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                enabled = !busy,
                onClick = { openLauncher.launch(arrayOf(MIME_ZIP, "application/x-zip-compressed", "*/*")) },
            ) { Text(stringResource(R.string.backup_restore_button)) }

            ui.pending?.let { pending ->
                Card {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.backup_pending_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(
                                R.string.backup_pending_text,
                                dateText(context, pending.createdAtMillis),
                                pending.entries.size,
                            ),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { restartApp(context) }) {
                                Text(stringResource(R.string.backup_restart))
                            }
                            TextButton(onClick = vm::cancelPending) {
                                Text(stringResource(R.string.backup_pending_cancel))
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.backup_not_saved), style = MaterialTheme.typography.bodySmall)
        }
    }

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
