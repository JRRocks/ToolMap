package fr.toolmap.record

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.toolmap.R
import fr.toolmap.core.record.RecorderProfile
import fr.toolmap.core.record.RecorderState
import fr.toolmap.core.track.NameDates
import fr.toolmap.core.track.TrackFormat
import fr.toolmap.map.MenuIcons
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap

/**
 * The running recording as a chip of the top right corner (P29.1, variant E « Pastilles »): a red dot, the time and
 * the distance, « En pause » with a pause sign while it waits. It turns to the error colour while the GPS is lost or
 * the track cannot be saved. A tap opens its detail under it: the four figures, the warnings, and the buttons.
 */
@Composable
fun RecordingChip(
    ui: RecordingUi,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (ui.phase == RecorderState.IDLE) return
    val fmt = LocalFormats.current
    val tm = MaterialTheme.toolMap
    var open by remember { mutableStateOf(false) }
    val paused = ui.phase == RecorderState.PAUSED
    val warning = ui.gpsUnavailable || ui.saveFailing
    val container = if (warning) MaterialTheme.colorScheme.error else tm.accent
    val content = if (warning) MaterialTheme.colorScheme.onError else tm.onAccent
    val text =
        listOfNotNull(
            if (paused) stringResource(R.string.record_state_paused) else null,
            TrackFormat.duration(ui.live.elapsedMillis),
            fmt.distance(ui.live.distanceMeters),
        ).joinToString(" · ")
    val openLabel = stringResource(R.string.record_chip_open)
    Box(modifier) {
        Row(
            Modifier
                .shadow(2.dp, CircleShape)
                .background(container, CircleShape)
                .clickable(onClickLabel = openLabel, role = Role.Button) { open = true }
                .height(32.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (paused) {
                Icon(MenuIcons.Pause, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
            } else {
                // The red dot of a camera that records, ringed in white to stand out on the turquoise.
                Box(
                    Modifier
                        .size(9.dp)
                        .background(Color.White, CircleShape)
                        .padding(1.5.dp)
                        .background(RECORD_RED, CircleShape),
                )
            }
            Text(
                text,
                color = content,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                maxLines = 1,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            RecordingDetail(
                ui = ui,
                onPause = onPause,
                onResume = onResume,
                onStop = {
                    open = false
                    onStop()
                },
            )
        }
    }
}

private val RECORD_RED = Color(0xFFE53935)

/** Live figures of the running recording with Pause / Resume and Stop: the detail of [RecordingChip]. */
@Composable
private fun RecordingDetail(
    ui: RecordingUi,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val fmt = LocalFormats.current
    val live = ui.live
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (ui.phase == RecorderState.PAUSED) {
            Text(stringResource(R.string.record_state_paused), style = MaterialTheme.typography.titleSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Figure(stringResource(R.string.record_stat_time), TrackFormat.duration(live.elapsedMillis))
            Figure(stringResource(R.string.record_stat_distance), fmt.distance(live.distanceMeters))
            Figure(
                stringResource(R.string.record_stat_speed),
                live.currentSpeedMps?.let { fmt.speed(it) } ?: "–",
            )
            Figure(stringResource(R.string.record_stat_ascent), fmt.elevation(live.ascentMeters))
        }
        if (ui.gpsUnavailable) {
            Text(stringResource(R.string.record_gps_unavailable), color = MaterialTheme.colorScheme.error)
        }
        if (ui.saveFailing) {
            Text(stringResource(R.string.record_save_failing), color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.phase == RecorderState.PAUSED) {
                Button(onClick = onResume) { Text(stringResource(R.string.record_resume)) }
            } else {
                OutlinedButton(onClick = onPause) { Text(stringResource(R.string.record_pause)) }
            }
            OutlinedButton(onClick = onStop) { Text(stringResource(R.string.record_stop)) }
        }
    }
}

@Composable
private fun Figure(
    label: String,
    value: String,
) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** Offered when the process died during a recording. */
@Composable
fun RecordingRecoveryDialog(
    onResume: () -> Unit,
    onKeep: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.record_recover_title)) },
        text = { Text(stringResource(R.string.record_recover_message)) },
        confirmButton = { TextButton(onClick = onResume) { Text(stringResource(R.string.record_recover_resume)) } },
        dismissButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.record_recover_keep)) } },
    )
}
