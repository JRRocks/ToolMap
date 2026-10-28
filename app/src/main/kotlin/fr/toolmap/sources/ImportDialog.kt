package fr.toolmap.sources

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.data.sources.DuplicatePolicy
import fr.toolmap.data.sources.ImportPreview
import fr.toolmap.ui.CheckRow
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.TextButton

/** Confirmation step of an `.aqx` import: choose the sources and what to do with ids already in use. */
@Composable
fun ImportDialog(
    preview: ImportPreview,
    onConfirm: (DuplicatePolicy, Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    // A map that is already in the catalogue under another file (same tiles, ADR-031) starts unticked.
    var selected by remember(preview) {
        mutableStateOf(
            preview.entries
                .filter { it.source.isSupported && it.duplicates.duplicateOf == null }
                .map { it.source.id }
                .toSet(),
        )
    }
    var policy by remember { mutableStateOf(DuplicatePolicy.KEEP_BOTH) }
    var showWarnings by remember { mutableStateOf(false) }
    val anyConflict = preview.entries.any { it.source.id in selected && it.idInUse }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_title, preview.fileName ?: "")) },
        text = {
            LazyColumn {
                items(preview.entries, key = { it.source.id }) { entry ->
                    val source = entry.source
                    CheckRow(
                        checked = source.id in selected,
                        enabled = source.isSupported,
                        onCheckedChange = { on -> selected = if (on) selected + source.id else selected - source.id },
                    ) {
                        Column {
                            Text(source.name, style = MaterialTheme.typography.bodyLarge)
                            val zooms =
                                if (source.isSupported) {
                                    stringResource(R.string.layers_zoom_range, source.minZoom, source.maxZoom)
                                } else {
                                    stringResource(R.string.source_unsupported, source.unsupportedReason.orEmpty())
                                }
                            Text(zooms, style = MaterialTheme.typography.labelSmall)
                            if (entry.idInUse) {
                                Text(
                                    stringResource(R.string.import_id_in_use),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            val duplicates = entry.duplicates
                            val twin = duplicates.duplicateOf
                            if (twin != null) {
                                Text(
                                    stringResource(R.string.import_duplicate_of, twin.name),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            } else if (duplicates.sharesWith.isNotEmpty()) {
                                Text(
                                    stringResource(
                                        R.string.import_shares_with,
                                        duplicates.sharesWith.joinToString(", ") { it.name },
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (preview.entries.any { it.duplicates.duplicateOf != null }) {
                    item {
                        Text(
                            stringResource(R.string.import_duplicates_hint),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                if (anyConflict) {
                    item {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            PolicyRow(DuplicatePolicy.KEEP_BOTH, R.string.import_policy_keep_both, policy) {
                                policy = it
                            }
                            PolicyRow(DuplicatePolicy.REPLACE, R.string.import_policy_replace, policy) { policy = it }
                            PolicyRow(DuplicatePolicy.SKIP, R.string.import_policy_skip, policy) { policy = it }
                        }
                    }
                }
                if (preview.warnings.isNotEmpty()) {
                    item {
                        TextButton(onClick = { showWarnings = !showWarnings }) {
                            Text(stringResource(R.string.import_warnings, preview.warnings.size))
                        }
                        if (showWarnings) {
                            preview.warnings.forEach {
                                Text("${it.context}: ${it.message}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(policy, selected) }, enabled = selected.isNotEmpty()) {
                Text(stringResource(R.string.import_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PolicyRow(
    value: DuplicatePolicy,
    label: Int,
    current: DuplicatePolicy,
    onSelect: (DuplicatePolicy) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = current == value, onClick = { onSelect(value) })
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
    }
}
