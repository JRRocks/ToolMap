package fr.toolmap.navigate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.geo.Navigation
import fr.toolmap.core.route.GoTo
import fr.toolmap.core.route.GoToRules
import fr.toolmap.core.route.RoutingError
import fr.toolmap.core.track.TrackFormat
import fr.toolmap.draw.profileIcon
import fr.toolmap.draw.profileLabel
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.MapPill
import fr.toolmap.ui.theme.DropdownMenu
import fr.toolmap.ui.theme.TextButton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * « Vers Refuge du Pré » (P30.3, variants A and C of `docs/previews/p30-itineraires.html`): an arrow that points along
 * the way to follow (or to the target in a straight line) as the map is drawn on screen, what remains of the way
 * (« 3,8 km par les chemins · ↑ 92 m · 1 h 15 »), « arrivée 15:20 · recalcul dans 1 min 40 » with the profile, which a
 * touch changes, and « Arrêter »; under it, in red, the walker off the way while a new one is computed.
 */
@Composable
fun TargetHud(
    ui: GoToUi,
    mapBearing: Double,
    onStop: () -> Unit,
    onProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val target = ui.target ?: return
    val fmt = LocalFormats.current
    val name = target.name ?: stringResource(R.string.target_unnamed)
    val progress = ui.progress?.takeIf { ui.routed }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MapPill {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val heading = progress?.headingDegrees ?: ui.guidance?.bearingDegrees
                if (heading != null && !ui.arrived) Arrow(rotation = (heading - mapBearing).toFloat())
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.goto_title, name), style = MaterialTheme.typography.titleSmall)
                    Text(mainLine(ui), style = MaterialTheme.typography.bodyMedium)
                    if (ui.routed && !ui.arrived && ui.position != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                statusLine(ui),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            ProfileButton(ui.profile, onProfile)
                        }
                    }
                }
                TextButton(onClick = onStop) { Text(stringResource(R.string.goto_stop)) }
            }
        }
        val off = ui.rerouteMeters
        if (ui.rerouting && off != null) {
            val colors = MaterialTheme.colorScheme
            MapPill(
                color = colors.errorContainer,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            ) {
                Text(
                    stringResource(
                        if (ui.reroutedAtMillis == null) R.string.goto_off_route else R.string.goto_rerouted,
                        fmt.distance(off),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** « 3,8 km par les chemins · ↑ 92 m · 1 h 15 », else the straight line « 2,90 km · cap 44° NE », « Arrivé »… */
@Composable
internal fun mainLine(ui: GoToUi): String {
    val fmt = LocalFormats.current
    val progress = ui.progress?.takeIf { ui.routed }
    val guidance = ui.guidance
    return when {
        ui.arrived -> {
            stringResource(R.string.target_arrived)
        }

        progress != null -> {
            listOfNotNull(
                stringResource(R.string.goto_by_ways_left, fmt.distance(progress.remainingMeters)),
                progress.remainingAscentMeters?.let { stringResource(R.string.goto_ascent, fmt.elevation(it)) },
                progress.remainingMillis?.let { TrackFormat.duration(it) },
            ).joinToString(" · ")
        }

        guidance != null -> {
            stringResource(
                R.string.target_distance_bearing,
                fmt.distance(guidance.distanceMeters),
                fmt.bearing(guidance.bearingDegrees),
                Navigation.compassPoint(guidance.bearingDegrees),
            )
        }

        ui.gpsFailed -> {
            stringResource(R.string.target_gps_failed)
        }

        else -> {
            stringResource(R.string.target_waiting)
        }
    }
}

/** « arrivée 15:20 · recalcul dans 1 min 40 », « Pas de données BRouter ici · nouvel essai dans 2 min »… */
@Composable
private fun statusLine(ui: GoToUi): String {
    val arrival = arrivalOf(ui)?.let { stringResource(R.string.goto_arrival, it) }
    val next = ui.nextAtMillis?.let { (it - ui.nowMillis).coerceAtLeast(0) }
    val answered = ui.way?.computedAtMillis
    val state =
        when {
            ui.computing && ui.way == null -> {
                stringResource(R.string.goto_computing_short)
            }

            ui.failure != null -> {
                listOfNotNull(
                    stringResource(failureText(ui.failure)),
                    next?.let { stringResource(R.string.goto_retry, GoTo.countdown(it)) },
                ).joinToString(" · ")
            }

            answered != null && !ui.computing && ui.nowMillis - answered < JUST_NOW_MILLIS -> {
                stringResource(R.string.goto_just_now)
            }

            next != null -> {
                stringResource(R.string.goto_next, GoTo.countdown(next))
            }

            else -> {
                null
            }
        }
    return listOfNotNull(arrival, state).joinToString(" · ")
}

/** « 15:20 »: now and the time left along the way; `null` while it cannot be told. */
internal fun arrivalOf(ui: GoToUi): String? {
    val left = ui.progress?.takeIf { ui.routed && !ui.arrived }?.remainingMillis ?: return null
    return ARRIVAL.format(Instant.ofEpochMilli(ui.nowMillis + left).atZone(ZoneId.systemDefault()))
}

/** Why there is no way, in a few words. */
internal fun failureText(error: RoutingError): Int =
    when (error) {
        RoutingError.NO_DATA -> R.string.goto_no_data
        RoutingError.NOT_INSTALLED -> R.string.route_not_installed
        RoutingError.NO_PATH -> R.string.goto_no_path
        else -> R.string.goto_failed
    }

/** The icon of the profile (« 🚶 » of the mockup), which opens the profiles of the start sheet. */
@Composable
private fun ProfileButton(
    profile: String,
    onProfile: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
            Icon(
                profileIcon(profile),
                contentDescription = stringResource(R.string.goto_profile, profileLabel(profile)),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (p in GoToRules.PROFILES) {
                DropdownMenuItem(
                    text = { Text(profileLabel(p)) },
                    leadingIcon = { Icon(profileIcon(p), contentDescription = null, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        open = false
                        onProfile(p)
                    },
                )
            }
        }
    }
}

@Composable
private fun Arrow(rotation: Float) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.size(32.dp).rotate(rotation)) {
        val w = size.width
        val h = size.height
        val arrow =
            Path().apply {
                moveTo(w / 2, 0f)
                lineTo(w * 0.85f, h * 0.85f)
                lineTo(w / 2, h * 0.65f)
                lineTo(w * 0.15f, h * 0.85f)
                close()
            }
        drawPath(arrow, color)
        drawCircle(Color.White, radius = w * 0.05f, center = androidx.compose.ui.geometry.Offset(w / 2, h * 0.62f))
    }
}

/** « Recalculé à l'instant » for this long after BRouter answered. */
private const val JUST_NOW_MILLIS = 10_000L

private val ARRIVAL: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
