package fr.toolmap.draw

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.toolmap.R
import fr.toolmap.core.route.LegMode
import fr.toolmap.core.route.LegState
import fr.toolmap.core.route.RouteMarkers
import fr.toolmap.core.route.RoutingProfiles
import fr.toolmap.core.route.StopKind
import fr.toolmap.core.track.TrackFormat
import fr.toolmap.core.track.WaypointSymbols
import fr.toolmap.settings.SettingsIcons
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.Switch
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.waypoints.WaypointIconBadge
import fr.toolmap.waypoints.WaypointIconPicker

/** What the route panel needs from the map screen (P19). */
class RouteHooks(
    /** The leg « Insérer ici » would cut at the crosshair, read from the camera: `null` for « Ajouter ici ». */
    val insertLegAt: () -> Int?,
    /** 24 dp in meters at the current zoom: how close the crosshair must be to a waypoint to take it. */
    val reachMeters: () -> Double,
    /** The last known position, `null` when the location is off. */
    val position: () -> Pair<Double, Double>?,
    /** Moves the map onto a stop picked in the list. */
    val onCenter: (Double, Double) -> Unit,
    /** Opens F-Droid, else the Play Store, on BRouter. */
    val onInstall: () -> Unit,
)

private val START_COLOR = Color(RouteMarkers.START_COLOR)
private val END_COLOR = Color(RouteMarkers.END_COLOR)
private val ROUTE_COLOR = Color(0xFFF57C00)

/** The name shown for a BRouter profile: the built-in ones in the user's language, the others as typed. */
@Composable
fun profileLabel(profile: String): String =
    when (profile) {
        RoutingProfiles.HIKING -> stringResource(R.string.route_profile_hiking)
        RoutingProfiles.BIKE -> stringResource(R.string.route_profile_bike)
        RoutingProfiles.ROAD_BIKE -> stringResource(R.string.route_profile_road_bike)
        RoutingProfiles.MTB -> stringResource(R.string.route_profile_mtb)
        RoutingProfiles.CAR -> stringResource(R.string.route_profile_car)
        RoutingProfiles.SHORTEST -> stringResource(R.string.route_profile_shortest)
        else -> profile
    }

@Composable
private fun profileIcon(profile: String): Painter =
    when (profile) {
        RoutingProfiles.HIKING -> {
            rememberVectorPainter(SettingsIcons.Hiking)
        }

        RoutingProfiles.BIKE, RoutingProfiles.ROAD_BIKE, RoutingProfiles.MTB -> {
            painterResource(
                R.drawable.ic_act_bicycle,
            )
        }

        RoutingProfiles.CAR -> {
            painterResource(R.drawable.ic_act_drive)
        }

        else -> {
            rememberVectorPainter(SettingsIcons.AltRoute)
        }
    }

/**
 * The figures of the route, at the top of the map in place of the position box while it follows the ways (variant A
 * of the validated mockups, `docs/previews/p19-routing.html` screen 2): `12,4 km · ↑ 830 m · ↓ 610 m · 4 h 10`, then
 * the profile, the number of stops and the legs being computed.
 */
@Composable
fun RouteStatsCard(
    route: RouteUi,
    modifier: Modifier = Modifier,
) {
    val fmt = LocalFormats.current
    val s = route.summary
    val parts = ArrayList<String>()
    parts += fmt.distance(s.lengthMeters)
    if (s.hasElevation) {
        parts += "↑ " + fmt.elevation(s.ascentMeters)
        parts += "↓ " + fmt.elevation(s.descentMeters)
    }
    s.durationMillis?.let { parts += (if (s.durationIsMinimum) "≥ " else "") + TrackFormat.duration(it) }
    // Variant A of the mockups: no profile here, the chip of the panel shows it.
    val sub = ArrayList<String>()
    sub += pluralStringResource(R.plurals.route_stop_count, route.plan.stops.size, route.plan.stops.size)
    if (s.pendingLegs > 0) sub += pluralStringResource(R.plurals.route_pending_count, s.pendingLegs, s.pendingLegs)
    if (route.atStart) sub += stringResource(R.string.route_at_start_status)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                buildStats(parts),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                sub.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The length in bold, the rest after it. */
private fun buildStats(parts: List<String>) =
    androidx.compose.ui.text.buildAnnotatedString {
        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
        append(parts.first())
        pop()
        for (p in parts.drop(1)) {
            append(" · ")
            append(p)
        }
    }

/**
 * « Suivre les chemins (BRouter) » and the profile chip; without BRouter, the switch is greyed with « Installer »
 * (screen 5 of the mockups).
 */
@Composable
fun RoutingSwitchRow(
    route: RouteUi,
    onToggle: (Boolean) -> Unit,
    onProfile: () -> Unit,
    onInstall: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(
            checked = route.routing && route.installed,
            onCheckedChange = onToggle,
            enabled = route.installed,
        )
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.route_follow),
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (route.installed) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    },
            )
            if (!route.installed) {
                Text(
                    stringResource(R.string.route_not_installed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (route.installed) {
            ProfileChip(route.profile, dropDown = route.routing, onClick = onProfile)
        } else {
            TextButton(onClick = onInstall) { Text(stringResource(R.string.route_install)) }
        }
    }
}

@Composable
private fun ProfileChip(
    profile: String,
    dropDown: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .clickable(enabled = dropDown, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Icon(profileIcon(profile), contentDescription = null, modifier = Modifier.size(18.dp))
        Text(profileLabel(profile), style = MaterialTheme.typography.labelLarge)
        if (dropDown) Icon(RouteIcons.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

/**
 * The profile sheet of the chip (screen 2): the profiles as radio buttons, « Autre… » for a profile of one's own, and
 * « Recalculer tout l'itinéraire avec ce profil » (ticked: every leg takes it; unticked: only the new ones).
 */
@Composable
fun ProfileDialog(
    route: RouteUi,
    onPick: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    allLegsOption: Boolean = true,
) {
    var all by remember { mutableStateOf(true) }
    var typing by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.route_profile_title)) },
        text = {
            Column {
                for (p in route.profiles) {
                    RadioLine(selected = p == route.profile, onClick = { onPick(p, all) }) {
                        Text(profileLabel(p))
                    }
                }
                RadioLine(selected = false, onClick = { typing = true }) {
                    Text(stringResource(R.string.route_profile_other))
                    Text(
                        " " + stringResource(R.string.route_profile_other_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (allLegsOption) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { all = !all },
                    ) {
                        Checkbox(checked = all, onCheckedChange = { all = it })
                        Text(stringResource(R.string.route_profile_all))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (typing) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { typing = false },
            title = { Text(stringResource(R.string.route_profile_custom_title)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.route_profile_custom_hint)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        typing = false
                        onPick(name.trim().removeSuffix(".brf"), all)
                    },
                    enabled = name.isNotBlank(),
                ) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { typing = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun RadioLine(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        content()
    }
}

/** The pin of a stop in the list: A in green, the last letter in red, a white ring for a passage, the icon if named. */
@Composable
private fun StopPin(
    index: Int,
    last: Int,
    kind: StopKind,
    sym: String?,
) {
    when {
        index == 0 || index == last -> {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (index == 0) START_COLOR else END_COLOR)
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    RouteMarkers.letter(index),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        kind == StopKind.NAMED -> {
            WaypointIconBadge(WaypointSymbols.iconOf(sym), 22.dp)
        }

        else -> {
            Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(3.dp, ROUTE_COLOR, CircleShape),
                )
            }
        }
    }
}

/**
 * The list of stops (screen 3): one line per stop with its pin, its name or « Passage », its distance from the start
 * and what differs on the next leg; a touch centres the map on it and selects it, ⋮ opens its menu.
 */
@Composable
fun StopsSheet(
    ui: DrawUi,
    vm: DrawViewModel,
    hooks: RouteHooks,
    onClose: () -> Unit,
) {
    val route = ui.route ?: return
    val plan = route.plan
    val fmt = LocalFormats.current
    val distances = remember(plan) { plan.stopDistances() }
    var naming by remember { mutableStateOf<Int?>(null) }
    var legProfile by remember { mutableStateOf<Int?>(null) }
    var askStart by remember { mutableStateOf<Int?>(null) }
    Column {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 2.dp, bottom = 6.dp)
                .size(width = 32.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClose).padding(vertical = 4.dp),
        ) {
            Icon(RouteIcons.FormatListNumbered, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                stringResource(R.string.route_stops, plan.stops.size),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Icon(RouteIcons.ExpandMore, contentDescription = null)
        }
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            itemsIndexed(plan.stops) { i, stop ->
                val last = plan.stops.lastIndex
                val next = plan.legs.getOrNull(i)
                val distance = fmt.distance(distances.getOrElse(i) { 0.0 })
                val where =
                    when (i) {
                        0 -> stringResource(R.string.route_stop_start)
                        last -> stringResource(R.string.route_stop_end, distance)
                        else -> distance
                    }
                val detail =
                    when {
                        next == null -> {
                            where
                        }

                        next.isByHand -> {
                            stringResource(R.string.route_stop_next_by_hand, where)
                        }

                        next.profile != route.profile -> {
                            stringResource(
                                R.string.route_stop_next_profile,
                                where,
                                profileLabel(next.profile),
                            )
                        }

                        else -> {
                            where
                        }
                    }
                val selected = ui.selected == i
                Column {
                    HorizontalDivider()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                )
                                .clickable {
                                    vm.selectStop(i)
                                    hooks.onCenter(stop.lat, stop.lon)
                                }.padding(start = 4.dp),
                    ) {
                        StopPin(i, last, stop.kind, stop.sym)
                        Column(Modifier.weight(1f)) {
                            Text(
                                stop.name?.takeIf { stop.kind == StopKind.NAMED }
                                    ?: stringResource(R.string.route_stop_passage),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StopMenu(
                            index = i,
                            ui = ui,
                            onName = { naming = i },
                            onLegProfile = { legProfile = i },
                            onStartHere = {
                                if (vm.stopsDroppedBy(i) > 0) askStart = i else vm.startFrom(i)
                            },
                            vm = vm,
                        )
                    }
                }
            }
        }
    }
    naming?.let { i ->
        val stop = plan.stops.getOrNull(i)
        if (stop == null) {
            naming = null
        } else {
            StopNameDialog(
                initialName = stop.name.orEmpty(),
                initialSym = stop.sym,
                onConfirm = { name, sym ->
                    naming = null
                    vm.nameStop(i, name, sym)
                },
                onDismiss = { naming = null },
            )
        }
    }
    legProfile?.let { i ->
        val leg = plan.legs.getOrNull(i)
        if (leg == null) {
            legProfile = null
        } else {
            ProfileDialog(
                route = route.copy(profile = leg.profile),
                onPick = { p, _ ->
                    legProfile = null
                    vm.setLegProfile(i, p)
                },
                onDismiss = { legProfile = null },
                allLegsOption = false,
            )
        }
    }
    askStart?.let { i ->
        val n = vm.stopsDroppedBy(i)
        AlertDialog(
            onDismissRequest = { askStart = null },
            title = { Text(stringResource(R.string.route_start_here)) },
            text = { Text(pluralStringResource(R.plurals.route_start_here_ask, n, n)) },
            confirmButton = {
                TextButton(onClick = {
                    askStart = null
                    vm.startFrom(i)
                }) { Text(stringResource(R.string.route_start_here_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { askStart = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** The ⋮ menu of a stop (screen 3). */
@Composable
private fun StopMenu(
    index: Int,
    ui: DrawUi,
    onName: () -> Unit,
    onLegProfile: () -> Unit,
    onStartHere: () -> Unit,
    vm: DrawViewModel,
) {
    val route = ui.route ?: return
    val plan = route.plan
    val stop = plan.stops.getOrNull(index) ?: return
    val next = plan.legs.getOrNull(index)
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun close(action: () -> Unit) {
                open = false
                action()
            }
            val named = stop.kind == StopKind.NAMED
            MenuLine(if (named) R.string.route_stop_edit else R.string.route_stop_name, Icons.Filled.Edit) {
                close(onName)
            }
            if (named) {
                MenuLine(R.string.route_stop_discreet, null) { close { vm.makeDiscreet(index) } }
            }
            MenuLine(R.string.route_stop_save_waypoint, RouteIcons.AddLocationAlt) {
                close { vm.saveStopAsWaypoint(index) }
            }
            if (next != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.route_leg_profile)) },
                    leadingIcon = { Icon(SettingsIcons.Hiking, contentDescription = null) },
                    trailingIcon = { Icon(RouteIcons.ChevronRight, contentDescription = null) },
                    onClick = { close(onLegProfile) },
                )
                if (next.mode == LegMode.STRAIGHT) {
                    MenuLine(R.string.route_leg_routed, SettingsIcons.AltRoute, enabled = route.installed) {
                        close { vm.setLegMode(index, LegMode.ROUTED) }
                    }
                } else {
                    MenuLine(R.string.route_leg_straight, RouteIcons.Straighten) {
                        close { vm.setLegMode(index, LegMode.STRAIGHT) }
                    }
                }
                if (next.mode == LegMode.ROUTED && (next.state == LegState.NO_DATA || next.state == LegState.FAILED)) {
                    MenuLine(R.string.route_leg_recompute, RouteIcons.Refresh, enabled = route.installed) {
                        close { vm.recompute(index) }
                    }
                }
            }
            HorizontalDivider()
            if (index > 0) {
                MenuLine(R.string.route_start_here, RouteIcons.Start) { close(onStartHere) }
            }
            MenuLine(R.string.route_stop_delete, Icons.Filled.Delete) { close { vm.deleteStop(index) } }
        }
    }
}

@Composable
private fun MenuLine(
    label: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null) } } ?: { Spacer(Modifier.size(24.dp)) },
        onClick = onClick,
        enabled = enabled,
    )
}

/** « Nommer… » / « Modifier… » of a stop: its name and its waypoint icon; an empty name makes it a passage again. */
@Composable
fun StopNameDialog(
    initialName: String,
    initialSym: String?,
    onConfirm: (String, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var sym by remember { mutableStateOf(WaypointSymbols.canonical(initialSym)) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.route_stop_name_title)) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.clip(CircleShape).clickable { picking = true }) {
                    WaypointIconBadge(WaypointSymbols.iconOf(sym), 48.dp)
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, sym) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (picking) {
        WaypointIconPicker(
            selectedId = WaypointSymbols.iconOf(sym).id,
            onSelect = {
                sym = it.key
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}
