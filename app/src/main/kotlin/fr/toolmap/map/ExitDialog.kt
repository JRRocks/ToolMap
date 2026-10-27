package fr.toolmap.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.ui.theme.TextButton

/*
 * « Quitter ToolMap ? » (ADR-053, P29.2 « Portée »): leaving closes the screen and stops nothing. The dialog names what
 * goes on in the background, one list line each with its figures, and the button of ToolMap's notification that stops
 * it, instead of the three services that could be running.
 */

/** Something that goes on in the background when ToolMap is left: its line in the exit dialog. */
data class RunningService(
    val kind: Kind,
    val title: String,
    /** Its figures (« 1 h 12 · 4,8 km », « 2 membres connectés »), when it has some. */
    val detail: String?,
    /** The button of ToolMap's notification that stops it (« Arrêter l'enregistrement »). */
    val stopLabel: String,
) {
    enum class Kind { RECORDING, RECORDING_PAUSED, SHARING, ALERTS }
}

/** Asks before leaving: [running] says what goes on; with nothing running, the title alone (« Confirmer la sortie »). */
@Composable
fun ExitDialog(
    running: List<RunningService>,
    onExit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_exit_title)) },
        text =
            if (running.isEmpty()) {
                null
            } else {
                {
                    Column {
                        Text(stringResource(R.string.exit_running))
                        Spacer(Modifier.size(6.dp))
                        running.forEach { ServiceLine(it) }
                        Spacer(Modifier.size(6.dp))
                        Text(
                            if (running.size == 1) {
                                stringResource(R.string.exit_stop_one, running.single().stopLabel)
                            } else {
                                stringResource(R.string.exit_stop_many)
                            },
                        )
                    }
                }
            },
        confirmButton = {
            TextButton(onClick = onExit) { Text(stringResource(R.string.settings_exit_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** A list line, with no fill and no frame (no card inside a dialog): a mark, the service and its figures. */
@Composable
private fun ServiceLine(service: RunningService) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            when (service.kind) {
                RunningService.Kind.RECORDING -> {
                    Box(Modifier.size(10.dp).background(RECORD_RED, CircleShape))
                }

                RunningService.Kind.RECORDING_PAUSED -> {
                    Icon(
                        MenuIcons.Pause,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }

                RunningService.Kind.SHARING -> {
                    Icon(MenuIcons.ShareLocation, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }

                RunningService.Kind.ALERTS -> {
                    Icon(MenuIcons.Bell, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(service.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (service.detail != null) {
                Text(
                    service.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The recording dot, as on the map's recording chip (P29.1). */
private val RECORD_RED = Color(0xFFE53935)
