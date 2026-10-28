package fr.toolmap.altitude

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.settings.UnitSystem
import fr.toolmap.core.track.TrackFormat
import fr.toolmap.data.altitude.AltitudeStatus
import fr.toolmap.ui.LocalAppSettings
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.TextButton
import java.util.Locale

/** Calibrates the barometer on an altitude the user knows (a marked summit, a signpost), in the units of the settings. */
@Composable
fun AltitudeDialog(
    status: AltitudeStatus,
    onCalibrate: (meters: Double) -> Boolean,
    onDismiss: () -> Unit,
) {
    val units = LocalAppSettings.current.units
    val imperial = units == UnitSystem.IMPERIAL
    val current = status.altitude?.meters
    var text by remember {
        mutableStateOf(
            current?.let {
                String.format(Locale.ROOT, "%d", Math.round(if (imperial) it * TrackFormat.FEET_PER_METER else it))
            }
                .orEmpty(),
        )
    }
    var failed by remember { mutableStateOf(false) }
    val value = text.trim().replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.altitude_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.altitude_dialog_text))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        failed = false
                    },
                    label = {
                        Text(stringResource(R.string.altitude_dialog_field, if (imperial) "ft" else "m"))
                    },
                    singleLine = true,
                    isError = failed,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                status.seaLevelHpa?.let {
                    Text(
                        stringResource(R.string.altitude_dialog_sea_level, LocalFormats.current.pressure(it)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (failed) {
                    Text(
                        stringResource(R.string.altitude_dialog_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = value != null,
                onClick = {
                    val meters = if (imperial) value!! / TrackFormat.FEET_PER_METER else value!!
                    if (onCalibrate(meters)) onDismiss() else failed = true
                },
            ) { Text(stringResource(R.string.altitude_dialog_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
