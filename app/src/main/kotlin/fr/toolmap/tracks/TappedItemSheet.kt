package fr.toolmap.tracks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.alert.AlertUi
import fr.toolmap.core.alert.FollowKind
import fr.toolmap.core.geo.Guidance
import fr.toolmap.core.geo.NavTarget
import fr.toolmap.core.geo.Navigation
import fr.toolmap.core.route.SavedRouteInfo
import fr.toolmap.core.track.ElevationProfile
import fr.toolmap.core.track.TrackFormat
import fr.toolmap.core.track.TrackJson
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.core.track.WaypointSymbols
import fr.toolmap.data.tracks.ItemKind
import fr.toolmap.data.tracks.ItemRef
import fr.toolmap.data.tracks.TrackLibrary
import fr.toolmap.draw.profileLabel
import fr.toolmap.map.ActionTile
import fr.toolmap.map.ActionTiles
import fr.toolmap.map.MenuIcons
import fr.toolmap.map.PANEL_INSET
import fr.toolmap.map.StateIcon
import fr.toolmap.maps.MapsIcons
import fr.toolmap.settings.SettingsIcons
import fr.toolmap.ui.LocalAppSettings
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.DropdownMenu
import fr.toolmap.ui.theme.FilledTonalButton
import fr.toolmap.waypoints.WaypointIconBadge

/**
 * What the user tapped on the map: its icon, name and figures, and what can be done with it. Since P29.4 (« Repère »)
 * the sheet puts one action forward: « Aller vers ce point » for a waypoint, under a large reading of its distance and
 * bearing from the position (when the position is known), « Suivre » for a track (under its small profile) or a route;
 * two tiles follow, and « Partager », « Exporter », « Masquer » and « Supprimer » (which asks first) go into its ⋮.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TappedItemSheet(
    ref: ItemRef,
    library: TrackLibrary,
    /** The position of the user, when known: the sheet then gives the distance and the bearing to a waypoint. */
    from: Pair<Double, Double>?,
    onDetails: (Long) -> Unit,
    onHide: (ItemRef) -> Unit,
    onEdit: (ItemRef) -> Unit,
    onGoTo: (ItemRef) -> Unit,
    onShare: (ItemRef, String?) -> Unit,
    /** « Exporter » (F8): the GPX saved in a file the user picks. */
    onExport: (ItemRef, String?) -> Unit,
    onDelete: (ItemRef) -> Unit,
    alerts: AlertUi,
    onFollow: (ItemRef) -> Unit,
    onStopFollowing: () -> Unit,
    onWatch: (ItemRef) -> Unit,
    onDismiss: () -> Unit,
    /** « Convertir en trace » of a route (in its ⋮ since P30.2), with the name shown in the message. */
    onConvert: (ItemRef, String) -> Unit = { _, _ -> },
    /** « Télécharger autour » of a track or a route (P30.2): the band around it in « Enregistrer une zone ». */
    onDownloadAround: (ItemRef, String) -> Unit = { _, _ -> },
    /** The profile of a track, for the small one of its sheet. */
    profileOf: suspend (Long) -> ElevationProfile? = { null },
) {
    val fmt = LocalFormats.current
    val unnamed = stringResource(R.string.tracks_unnamed)
    var askDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val name: String?
    val figures = ArrayList<String>()
    val notes = ArrayList<String>()
    var leading: @Composable () -> Unit = { StateIcon(MenuIcons.Polyline) }
    var guide: Guidance? = null
    when (ref.kind) {
        ItemKind.TRACK -> {
            val t = library.tracks.firstOrNull { it.id == ref.id } ?: return
            name = t.name
            figures += fmt.distance(t.distanceMeters)
            t.ascentMeters?.let { figures += "↑ " + fmt.elevation(it) }
            t.descentMeters?.let { figures += "↓ " + fmt.elevation(it) }
        }

        ItemKind.ROUTE -> {
            val r = library.routes.firstOrNull { it.id == ref.id } ?: return
            name = r.name
            leading = { StateIcon(MenuIcons.Directions) }
            // Length, climb, time and profile of the plan (P19, screen 6); a route drawn by hand has no time.
            val info =
                remember(r) {
                    SavedRouteInfo.of(
                        TrackJson.decodeWaypoints(r.pointsJson).map { TrackPoint(it.lat, it.lon, it.ele) },
                        TrackJson.decodeExtras(r.extrasJson),
                    )
                }
            val s = info.summary
            figures += fmt.distance(r.distanceMeters)
            if (s.hasElevation) {
                figures += "↑ " + fmt.elevation(s.ascentMeters)
                figures += "↓ " + fmt.elevation(s.descentMeters)
            }
            s.durationMillis?.let { figures += (if (s.durationIsMinimum) "≥ " else "") + TrackFormat.duration(it) }
            info.profile?.let { figures += profileLabel(it) }
        }

        ItemKind.WAYPOINT -> {
            val w = library.waypoints.firstOrNull { it.id == ref.id } ?: return
            name = w.name
            leading = { WaypointIconBadge(WaypointSymbols.iconOf(w.sym), 40.dp) }
            w.ele?.let { figures += fmt.elevation(it) }
            figures += fmt.position(w.lat, w.lon).text
            w.cmt?.takeIf { it.isNotBlank() }?.let { notes += it }
            w.desc?.takeIf { it.isNotBlank() && it != w.cmt }?.let { notes += it }
            if (from != null) guide = Navigation.guide(from.first, from.second, NavTarget(w.lat, w.lon))
        }
    }
    val followed =
        alerts.follow?.let { f ->
            f.id == ref.id && (f.kind == FollowKind.TRACK) == (ref.kind == ItemKind.TRACK)
        } == true
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = PANEL_INSET + 4.dp, end = 4.dp, top = 2.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leading()
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        name ?: unnamed,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        figures.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.menu),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        listOfNotNull<Pair<Int, () -> Unit>>(
                            R.string.tracks_share to { onShare(ref, name) },
                            R.string.tracks_sheet_export to { onExport(ref, name) },
                            // Its tile went to « Télécharger autour » (P30.2, variant B of p30-itineraires.html).
                            (R.string.route_convert_track to { onConvert(ref, name ?: unnamed) })
                                .takeIf { ref.kind == ItemKind.ROUTE },
                            R.string.tracks_map_card_hide to { onHide(ref) },
                            R.string.tracks_delete to { askDelete = true },
                        ).forEach { (label, action) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                onClick = {
                                    menu = false
                                    action()
                                },
                            )
                        }
                    }
                }
            }
            notes.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = PANEL_INSET + 4.dp),
                )
            }
            when (ref.kind) {
                ItemKind.WAYPOINT -> {
                    guide?.let { BearingReading(it) }
                    PrimaryAction(
                        icon = SettingsIcons.NearMe,
                        label = stringResource(R.string.tracks_go_to),
                        tonal = false,
                    ) { onGoTo(ref) }
                    val watch = alerts.watches.firstOrNull { it.waypointId == ref.id }
                    ActionTiles {
                        ActionTile(
                            MenuIcons.Bell,
                            if (watch == null) {
                                stringResource(R.string.tracks_watch)
                            } else {
                                val radius = watch.radiusMeters ?: LocalAppSettings.current.alerts.proximityRadiusMeters
                                stringResource(R.string.tracks_watch_on, fmt.distance(radius.toDouble()))
                            },
                        ) { onWatch(ref) }
                        ActionTile(Icons.Filled.Edit, stringResource(R.string.tracks_edit_waypoint)) { onEdit(ref) }
                    }
                }

                ItemKind.TRACK -> {
                    var profile by remember(ref) { mutableStateOf<ElevationProfile?>(null) }
                    LaunchedEffect(ref) { profile = profileOf(ref.id) }
                    profile?.let {
                        MiniProfile(
                            it,
                            Modifier
                                .padding(horizontal = PANEL_INSET + 4.dp, vertical = 4.dp)
                                .semantics(mergeDescendants = true) {},
                        )
                    }
                    FollowAction(followed, stringResource(R.string.tracks_follow_this), ref, onFollow, onStopFollowing)
                    ActionTiles {
                        ActionTile(MenuIcons.Gauge, stringResource(R.string.tracks_map_card_details)) {
                            onDetails(ref.id)
                        }
                        ActionTile(Icons.Filled.Edit, stringResource(R.string.tracks_edit_path)) { onEdit(ref) }
                        ActionTile(MapsIcons.Download, stringResource(R.string.tracks_download_around_tile)) {
                            onDownloadAround(ref, name ?: unnamed)
                        }
                    }
                }

                ItemKind.ROUTE -> {
                    FollowAction(followed, stringResource(R.string.tracks_follow_route), ref, onFollow, onStopFollowing)
                    ActionTiles {
                        ActionTile(Icons.Filled.Edit, stringResource(R.string.tracks_edit_path)) { onEdit(ref) }
                        ActionTile(MapsIcons.Download, stringResource(R.string.tracks_download_around_tile)) {
                            onDownloadAround(ref, name ?: unnamed)
                        }
                    }
                }
            }
        }
    }
    if (askDelete) {
        ConfirmDialog(
            title = stringResource(R.string.tracks_delete_title, name ?: unnamed),
            message = stringResource(R.string.tracks_delete_final),
            confirmLabel = stringResource(R.string.tracks_delete),
            destructive = true,
            onConfirm = {
                askDelete = false
                onDelete(ref)
            },
            onDismiss = { askDelete = false },
        )
    }
}

/**
 * « 3,90 km ↘ cap 146° SE », large, « depuis votre position » under it: the arrow turns to the bearing of the item, so
 * that the sheet answers « c'est où, par rapport à moi ? ». TalkBack reads it as one sentence.
 */
@Composable
private fun BearingReading(guide: Guidance) {
    val fmt = LocalFormats.current
    val distance = fmt.distance(guide.distanceMeters)
    val bearing = fmt.bearing(guide.bearingDegrees)
    val compass = Navigation.compassPoint(guide.bearingDegrees)
    val spoken = stringResource(R.string.tracks_sheet_reading, distance, bearing, compass)
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }) {
        Row(
            Modifier.padding(start = PANEL_INSET + 4.dp, end = PANEL_INSET + 4.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(distance, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
            Icon(
                TracksIcons.Arrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp).rotate(guide.bearingDegrees.toFloat()),
            )
            Text(
                stringResource(R.string.tracks_sheet_bearing, bearing, compass),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Text(
            stringResource(R.string.tracks_sheet_from_position),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = PANEL_INSET + 4.dp, bottom = 12.dp),
        )
    }
}

/** « Suivre cette trace » (filled), or « Ne plus suivre » (tonal) while it is followed. */
@Composable
private fun FollowAction(
    followed: Boolean,
    label: String,
    ref: ItemRef,
    onFollow: (ItemRef) -> Unit,
    onStopFollowing: () -> Unit,
) {
    if (followed) {
        PrimaryAction(
            SettingsIcons.Route,
            stringResource(R.string.tracks_unfollow),
            tonal = true,
            onClick = onStopFollowing,
        )
    } else {
        PrimaryAction(SettingsIcons.Route, label, tonal = false) { onFollow(ref) }
    }
}

/** The one action the sheet puts forward, the width of the sheet. */
@Composable
private fun PrimaryAction(
    icon: ImageVector,
    label: String,
    tonal: Boolean,
    onClick: () -> Unit,
) {
    val modifier = Modifier.fillMaxWidth().padding(horizontal = PANEL_INSET, vertical = 4.dp).heightIn(min = 48.dp)
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
    if (tonal) {
        FilledTonalButton(onClick = onClick, modifier = modifier, content = content)
    } else {
        Button(onClick = onClick, modifier = modifier, content = content)
    }
}
