package fr.toolmap.update

import android.content.Context
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.update.PublishedApk
import fr.toolmap.map.MenuIcons
import fr.toolmap.ui.MapPill
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.Switch
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/*
 * The screens of the update (P31), as validated on 2026-10-29 in `docs/previews/p31-mise-a-jour.html`: variant D for the
 * message (a banner over the map at launch, a red dot on « Menu », the row « Mises à jour » under « Plus »), variant B
 * for the progress (a dialog, then Android's confirmation), and the dialogs common to all variants.
 */

/** The banner of the map at launch (variant D): the new version, the one installed and the size; closes by itself. */
@Composable
fun UpdateBanner(
    ui: UpdateUi,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val apk = ui.available ?: return
    val context = LocalContext.current
    MapPill(modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(MenuIcons.CloudDownload, contentDescription = null, tint = MaterialTheme.toolMap.accent)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.update_banner_title, apk.version.toString()),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.update_banner_sub, ui.installed, fileSize(context, apk.sizeBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onUpdate) { Text(stringResource(R.string.update_action)) }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
            }
        }
    }
}

/** The second line of the row « Mises à jour » of the menu. */
@Composable
fun updateMenuSubtitle(ui: UpdateUi): String {
    val context = LocalContext.current
    val available = ui.available
    val checkedAt = ui.checkedAtMillis
    return when {
        available != null -> {
            stringResource(R.string.menu_updates_available, available.version.toString())
        }

        ui.checking -> {
            stringResource(R.string.menu_updates_checking, ui.installed)
        }

        checkedAt != null && !ui.unreachable -> {
            stringResource(R.string.menu_updates_up_to_date, ui.installed, timeOfDay(context, checkedAt))
        }

        else -> {
            stringResource(R.string.menu_version, ui.installed)
        }
    }
}

/** « Mises à jour » (common to all variants): the versions, the check at launch, a check or the update now. */
@Composable
fun UpdatesDialog(
    ui: UpdateUi,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
    onCheckOnLaunch: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.updates_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(labelled(stringResource(R.string.updates_installed), ui.installed))
                val latest = ui.latest
                when {
                    ui.checking -> {
                        Text(stringResource(R.string.updates_latest_checking))
                    }

                    ui.unreachable && latest == null -> {
                        Text(stringResource(R.string.updates_latest_unreachable))
                    }

                    latest != null -> {
                        Text(labelled(stringResource(R.string.updates_latest), latest.version.toString(), day(latest)))
                    }
                }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .toggleable(ui.checkOnLaunch, role = Role.Switch, onValueChange = onCheckOnLaunch),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.updates_on_launch), modifier = Modifier.weight(1f))
                    Switch(checked = ui.checkOnLaunch, onCheckedChange = null)
                }
            }
        },
        confirmButton = {
            if (ui.available != null) {
                Button(onClick = onUpdate) { Text(stringResource(R.string.update_action)) }
            } else {
                Button(onClick = onCheck, enabled = !ui.checking) { Text(stringResource(R.string.updates_check)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/**
 * The download (variant B): its bar and its figures, « Annuler ». Closed, the download goes on in the notification;
 * at its end Android asks itself. After an error, the same dialog says so and offers to try again.
 */
@Composable
fun UpdateProgressDialog(
    download: UpdateDownload,
    failed: Boolean,
    onHide: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismissFailed: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = if (failed) onDismissFailed else onHide,
        title = { Text(stringResource(R.string.update_progress_title, download.version)) },
        text = {
            if (failed) {
                Text(stringResource(R.string.update_failed))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val total = download.totalBytes
                    LinearProgressIndicator(
                        progress = { if (total > 0) (download.doneBytes.toFloat() / total).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        color = MaterialTheme.toolMap.accent,
                    )
                    Text(
                        stringResource(
                            R.string.update_progress_bytes,
                            fileSize(context, download.doneBytes),
                            fileSize(context, total),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            if (failed) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.update_retry)) }
            } else {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
        },
        dismissButton =
            if (failed) {
                { TextButton(onClick = onDismissFailed) { Text(stringResource(R.string.action_close)) } }
            } else {
                null
            },
    )
}

/** « Installation refusée » (common to variants B to E): Android would not replace the app; why, and what to do. */
@Composable
fun UpdateRefusedDialog(
    version: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = WARNING) },
        title = { Text(stringResource(R.string.update_refused_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.update_refused_why, version))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                            append(stringResource(R.string.update_refused_once))
                        }
                        append(' ')
                        append(stringResource(R.string.update_refused_steps, version))
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** « Label : value », the value in bold, and a note after it in brackets. */
private fun labelled(
    label: String,
    value: String,
    note: String? = null,
): AnnotatedString =
    buildAnnotatedString {
        append(label)
        append(' ')
        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(value) }
        if (note != null) append(" ($note)")
    }

/** The day a version was published, « 29 oct. »; empty when GitHub did not give it. */
private fun day(apk: PublishedApk): String? =
    apk.publishedAt?.let { text ->
        runCatching {
            Instant
                .parse(text)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        }.getOrNull()
    }

private fun timeOfDay(
    context: Context,
    millis: Long,
): String = DateFormat.getTimeFormat(context).format(Date(millis))

private fun fileSize(
    context: Context,
    bytes: Long,
): String = Formatter.formatShortFileSize(context, bytes)

/** The amber of the warnings (« Installation refusée »), as in the previews. */
private val WARNING = Color(0xFFB26A00)
