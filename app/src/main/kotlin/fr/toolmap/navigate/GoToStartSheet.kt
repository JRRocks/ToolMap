package fr.toolmap.navigate

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.route.GoToRules
import fr.toolmap.core.route.RoutingError
import fr.toolmap.core.track.TrackFormat
import fr.toolmap.core.track.WaypointSymbols
import fr.toolmap.draw.installBRouter
import fr.toolmap.draw.openBRouter
import fr.toolmap.draw.profileIcon
import fr.toolmap.draw.profileLabel
import fr.toolmap.settings.SettingsIcons
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.waypoints.WaypointIconBadge

/**
 * « Aller vers Refuge du Pré » (P30.3, variant C of `docs/previews/p30-itineraires.html`), opened by « Aller vers ce
 * point » of a waypoint: what the way weighs (« 3,8 km · ↑ 92 m · 1 h 15 · arrivée 15:20 ») as soon as BRouter gave
 * it, the way drawn behind; « Par les chemins | À vol d'oiseau », the profile, « Recalculer toutes les 1 2 5 10 min »,
 * then « Partir ». The choices are kept for the next time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoToStartSheet(
    ui: GoToUi,
    onChoose: (routed: Boolean, profile: String, intervalMinutes: Int) -> Unit,
    onGo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val target = ui.target ?: return
    val minutes = ui.intervalMinutes
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WaypointIconBadge(WaypointSymbols.iconOf(target.sym), 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            R.string.goto_sheet_title,
                            target.name ?: stringResource(R.string.target_unnamed),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        figures(ui),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ui.failure?.takeIf { ui.routed }?.let { Failure(it) }
            WaySwitch(ui.routed) { routed -> onChoose(routed, ui.profile, minutes) }
            if (ui.routed) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (profile in GoToRules.PROFILES) {
                        FilterChip(
                            selected = profile == ui.profile,
                            onClick = { onChoose(true, profile, minutes) },
                            label = { Text(profileLabel(profile)) },
                            leadingIcon = {
                                Icon(profileIcon(profile), contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                        )
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_goto_interval), style = MaterialTheme.typography.bodyMedium)
                    GoToRules.INTERVAL_MINUTES.forEachIndexed { i, m ->
                        FilterChip(
                            selected = m == minutes,
                            onClick = { onChoose(true, ui.profile, m) },
                            label = {
                                Text(
                                    if (i == GoToRules.INTERVAL_MINUTES.lastIndex) {
                                        stringResource(R.string.goto_minutes, m)
                                    } else {
                                        "$m"
                                    },
                                )
                            },
                        )
                    }
                }
            }
            Button(onClick = onGo, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(SettingsIcons.NearMe, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.goto_go))
            }
        }
    }
}

/** « Par les chemins | À vol d'oiseau ». */
@Composable
private fun WaySwitch(
    routed: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val choices = listOf(true to R.string.goto_by_ways, false to R.string.goto_straight)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == routed,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                icon = {
                    Icon(
                        if (value) SettingsIcons.AltRoute else SettingsIcons.NearMe,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                label = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** Why there is no way, with what to do about it: open BRouter for its data, or install it. */
@Composable
private fun Failure(error: RoutingError) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(failureText(error)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        when (error) {
            RoutingError.NO_DATA -> {
                TextButton(onClick = { openBRouter(context) }) { Text(stringResource(R.string.route_open_brouter)) }
            }

            RoutingError.NOT_INSTALLED -> {
                TextButton(onClick = { installBRouter(context) }) { Text(stringResource(R.string.route_install)) }
            }

            else -> {}
        }
    }
}

/** « 3,8 km · ↑ 92 m · 1 h 15 · arrivée 15:20 », « Calcul du chemin… », or the straight line. */
@Composable
private fun figures(ui: GoToUi): String {
    val fmt = LocalFormats.current
    val progress = ui.progress?.takeIf { ui.routed }
    return when {
        progress != null && !ui.arrived -> {
            listOfNotNull(
                fmt.distance(progress.remainingMeters),
                progress.remainingAscentMeters?.let { stringResource(R.string.goto_ascent, fmt.elevation(it)) },
                progress.remainingMillis?.let { TrackFormat.duration(it) },
                arrivalOf(ui)?.let { stringResource(R.string.goto_arrival, it) },
            ).joinToString(" · ")
        }

        ui.routed && ui.computing && ui.position != null -> {
            stringResource(R.string.goto_computing)
        }

        else -> {
            mainLine(ui)
        }
    }
}
