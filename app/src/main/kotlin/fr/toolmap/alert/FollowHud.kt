package fr.toolmap.alert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.alert.AlertEvent
import fr.toolmap.ui.LocalAppSettings
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.MapPill
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.TextButton

/**
 * The followed line on the map: progress, distance left, how far off, and the last alert until it is dismissed; white
 * pills of the map since P29.10, the alert in its own colour.
 */
@Composable
fun FollowHud(
    ui: AlertUi,
    onReverse: () -> Unit,
    onStop: () -> Unit,
    onDismissBanner: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fmt = LocalFormats.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val banner = ui.banner
        if (banner != null) {
            val text = AlertTexts.of(resources, fmt, banner.event)
            val colors = MaterialTheme.colorScheme
            val urgent = banner.event is AlertEvent.OffTrack
            val container = if (urgent) colors.errorContainer else colors.secondaryContainer
            MapPill(
                color = container,
                modifier =
                    Modifier.semantics {
                        liveRegion = if (urgent) LiveRegionMode.Assertive else LiveRegionMode.Polite
                    },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text.title, style = MaterialTheme.typography.titleSmall)
                        Text(text.body, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = onDismissBanner) { Text(stringResource(R.string.follow_dismiss)) }
                }
            }
        }
        if (ui.gpsFailed) {
            MapPill {
                Text(
                    stringResource(R.string.follow_gps_failed),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        val follow = ui.follow
        if (follow != null) {
            val status = follow.status
            MapPill {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.follow_title, follow.name ?: stringResource(R.string.tracks_unnamed)),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (status == null) {
                        Text(stringResource(R.string.follow_waiting), style = MaterialTheme.typography.bodySmall)
                    } else {
                        LinearProgressIndicator(
                            progress = { status.fraction.toFloat() },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                        Text(
                            if (status.arrived) {
                                stringResource(R.string.follow_arrived)
                            } else {
                                stringResource(
                                    R.string.follow_progress,
                                    fmt.distance(status.alongMeters),
                                    fmt.distance(status.remainingMeters),
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            if (status.offTrack) {
                                stringResource(R.string.follow_off_warning, fmt.distance(status.offMeters))
                            } else {
                                stringResource(R.string.follow_off, fmt.distance(status.offMeters))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (status.offTrack) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onReverse) { Text(stringResource(R.string.follow_reverse)) }
                        TextButton(onClick = onStop) { Text(stringResource(R.string.follow_stop)) }
                    }
                }
            }
        }
    }
}

/** Chooses the radius of a waypoint's proximity alert (or the default one), or switches the alert off. */
@Composable
fun WatchDialog(
    name: String?,
    currentRadius: Int?,
    watched: Boolean,
    onConfirm: (radiusMeters: Int?) -> Unit,
    onDisable: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fmt = LocalFormats.current
    val defaultRadius = LocalAppSettings.current.alerts.proximityRadiusMeters
    var choice by remember { mutableStateOf(currentRadius) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.watch_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.watch_text))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = choice == null,
                        onClick = { choice = null },
                        label = {
                            Text(stringResource(R.string.watch_default, fmt.distance(defaultRadius.toDouble())))
                        },
                    )
                    RADII.forEach { radius ->
                        FilterChip(
                            selected = choice == radius,
                            onClick = { choice = radius },
                            label = { Text(fmt.distance(radius.toDouble())) },
                        )
                    }
                }
                Text(stringResource(R.string.watch_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(choice) }) { Text(stringResource(R.string.watch_enable)) } },
        dismissButton = {
            Row {
                if (watched) TextButton(onClick = onDisable) { Text(stringResource(R.string.watch_disable)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

private val RADII = listOf(25, 50, 100, 200, 500, 1000)
