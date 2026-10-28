package fr.toolmap.draw

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Checkbox
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
import fr.toolmap.ui.MapPill
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.DropdownMenu
import fr.toolmap.ui.theme.Switch
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.waypoints.WaypointIconBadge
import fr.toolmap.waypoints.WaypointIconPicker

/** What the route panel needs from the map screen (P19). */
class RouteHooks(
    /** The leg « Insérer ici » would cut at the crosshair, read from the camera: `null` for « Ajouter ici ». */
    val insertLegAt: () -> Int?,
    /** The stop « Prendre l'étape » would take at the crosshair, read from the camera: `null` when it is on none. */
    val stopUnder: () -> Int?,
    /** The zone to avoid the crosshair is in, that « Retirer la zone » would remove: `null` outside them all (P19.8). */
    val zoneUnder: () -> Int?,
    /** 24 dp in meters at the current zoom: how close the crosshair must be to a waypoint to take it. */
    val reachMeters: () -> Double,
    /** The last known position, `null` when the location is off. */
    val position: () -> Pair<Double, Double>?,
    /** Moves the map onto a stop picked in the list. */
    val onCenter: (Double, Double) -> Unit,
    /** Opens F-Droid, else the Play Store, on BRouter. */
    val onInstall: () -> Unit,
    /** The point of interest « Modifier le point » would open at the crosshair: `null` when it is on none (P30.1). */
    val poiUnder: () -> Int? = { null },
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
internal fun profileIcon(profile: String): Painter =
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
    val pois = route.plan.pois.size
    if (pois > 0) sub += pluralStringResource(R.plurals.route_poi_count, pois, pois)
    val zones = route.plan.zones.size
    if (zones > 0) sub += pluralStringResource(R.plurals.route_zone_count, zones, zones)
    if (s.pendingLegs > 0) sub += pluralStringResource(R.plurals.route_pending_count, s.pendingLegs, s.pendingLegs)
    if (route.atStart) sub += stringResource(R.string.route_at_start_status)
    MapPill(modifier = modifier.fillMaxWidth()) {
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
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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

/**
 * The profile of the route on a chip (P29.10): the outline and the 12 dp corners of Material's assist chip, 32 dp seen
 * and 48 dp to touch, with the margins of the preview so that « Suivre les chemins (BRouter) » keeps two lines. It
 * opens the profiles while the route follows the ways; drawn by hand, it only says which profile the route will take.
 */
@Composable
private fun ProfileChip(
    profile: String,
    dropDown: Boolean,
    onClick: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.height(32.dp).padding(start = 8.dp, end = if (dropDown) 4.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(profileIcon(profile), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(profileLabel(profile), style = MaterialTheme.typography.labelLarge)
            if (dropDown) Icon(RouteIcons.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
    val shape = MaterialTheme.shapes.small
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    if (dropDown) {
        Surface(onClick = onClick, shape = shape, color = Color.Transparent, border = border, content = content)
    } else {
        Surface(shape = shape, color = Color.Transparent, border = border, content = content)
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
    /** « Modifier… » of a point of interest (P30.1): « Quel point ? » on it. */
    onEditPoi: (Int) -> Unit = {},
) {
    val route = ui.route ?: return
    val plan = route.plan
    val fmt = LocalFormats.current
    val distances = remember(plan) { plan.stopDistances() }
    val places = remember(plan) { plan.pois.indices.map { plan.placeOf(it) } }
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
            // The points of interest (P30.1), under the stops: where they stand along the route and beside it.
            if (plan.pois.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.route_poi_section, plan.pois.size),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 6.dp),
                    )
                }
                itemsIndexed(plan.pois) { k, poi ->
                    val place = places.getOrNull(k)
                    val along = place?.let { fmt.distance(it.alongMeters) }
                    val detail =
                        when {
                            place == null || along == null -> null
                            place.offMeters < ON_ROUTE_METERS -> stringResource(R.string.route_poi_place_on, along)
                            else -> stringResource(R.string.route_poi_place, along, fmt.distance(place.offMeters))
                        }
                    Column {
                        HorizontalDivider()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { hooks.onCenter(poi.lat, poi.lon) }
                                    .padding(start = 4.dp),
                        ) {
                            WaypointIconBadge(WaypointSymbols.iconOf(poi.sym), 24.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    poi.name ?: stringResource(R.string.route_poi_unnamed),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                detail?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            PoiMenu(index = k, onEdit = { onEditPoi(k) }, vm = vm)
                        }
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

/** A point of interest this close to the way is « sur le tracé » (P30.1). */
private const val ON_ROUTE_METERS = 15.0

/** The ⋮ menu of a point of interest (P30.1): « Modifier… », « Enregistrer comme waypoint », « Supprimer ». */
@Composable
private fun PoiMenu(
    index: Int,
    onEdit: () -> Unit,
    vm: DrawViewModel,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuLine(R.string.route_poi_edit_menu, Icons.Filled.Edit) {
                open = false
                onEdit()
            }
            MenuLine(R.string.route_stop_save_waypoint, RouteIcons.AddLocationAlt) {
                open = false
                vm.savePoiAsWaypoint(index)
            }
            HorizontalDivider()
            MenuLine(R.string.route_stop_delete, Icons.Filled.Delete) {
                open = false
                vm.removePoi(index)
            }
        }
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
