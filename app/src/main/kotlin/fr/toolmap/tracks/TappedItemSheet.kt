package fr.toolmap.tracks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.alert.AlertUi
import fr.toolmap.core.alert.FollowKind
import fr.toolmap.core.geo.NavTarget
import fr.toolmap.core.geo.Navigation
import fr.toolmap.core.route.SavedRouteInfo
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
import fr.toolmap.map.StateCard
import fr.toolmap.map.StateIcon
import fr.toolmap.settings.SettingsIcons
import fr.toolmap.ui.LocalAppSettings
import fr.toolmap.ui.LocalFormats
import fr.toolmap.waypoints.WaypointIconBadge

/**
 * What the user tapped on the map: its icon, name and figures, how far it is from the position when the position is
 * on, and its commands as tiles, in the style of the panels of the bar (P14.10). « Supprimer » asks first.
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
    /** « Convertir en trace » of a route, with the name shown in the message. */
    onConvert: (ItemRef, String) -> Unit = { _, _ -> },
) {
    val fmt = LocalFormats.current
    val unnamed = stringResource(R.string.tracks_unnamed)
    var askDelete by remember { mutableStateOf(false) }
    val name: String?
    val figures = ArrayList<String>()
    val notes = ArrayList<String>()
    var leading: @Composable () -> Unit = { StateIcon(MenuIcons.Polyline) }
    var away: String? = null
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
            if (from != null) {
                val g = Navigation.guide(from.first, from.second, NavTarget(w.lat, w.lon))
                away =
                    stringResource(
                        R.string.target_distance_bearing,
                        fmt.distance(g.distanceMeters),
                        fmt.bearing(g.bearingDegrees),
                        Navigation.compassPoint(g.bearingDegrees),
                    )
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = PANEL_INSET + 4.dp, vertical = 4.dp),
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
            }
            notes.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = PANEL_INSET + 4.dp),
                )
            }
            away?.let {
                StateCard(
                    title = it,
                    overline = stringResource(R.string.tracks_sheet_from_here),
                    leading = { StateIcon(MenuIcons.MyLocation) },
                )
            }
            if (ref.kind == ItemKind.WAYPOINT) {
                val watch = alerts.watches.firstOrNull { it.waypointId == ref.id }
                ActionTiles {
                    ActionTile(MenuIcons.Directions, stringResource(R.string.tracks_go_to)) { onGoTo(ref) }
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
            } else {
                val followed =
                    alerts.follow?.let { f ->
                        f.id == ref.id && (f.kind == FollowKind.TRACK) == (ref.kind == ItemKind.TRACK)
                    } == true
                ActionTiles {
                    if (ref.kind == ItemKind.TRACK) {
                        ActionTile(MenuIcons.Gauge, stringResource(R.string.tracks_map_card_details)) {
                            onDetails(ref.id)
                        }
                    }
                    if (followed) {
                        ActionTile(
                            MenuIcons.Polyline,
                            stringResource(R.string.tracks_unfollow),
                            onClick = onStopFollowing,
                        )
                    } else {
                        ActionTile(MenuIcons.Polyline, stringResource(R.string.tracks_follow)) { onFollow(ref) }
                    }
                    ActionTile(Icons.Filled.Edit, stringResource(R.string.tracks_edit_path)) { onEdit(ref) }
                    if (ref.kind == ItemKind.ROUTE) {
                        ActionTile(SettingsIcons.Timeline, stringResource(R.string.route_convert_track)) {
                            onConvert(ref, name ?: unnamed)
                        }
                    }
                }
            }
            ActionTiles {
                ActionTile(Icons.Filled.Share, stringResource(R.string.tracks_share)) { onShare(ref, name) }
                ActionTile(MenuIcons.FileSave, stringResource(R.string.tracks_sheet_export)) { onExport(ref, name) }
                ActionTile(MenuIcons.VisibilityOff, stringResource(R.string.tracks_map_card_hide)) { onHide(ref) }
                ActionTile(Icons.Filled.Delete, stringResource(R.string.tracks_delete)) { askDelete = true }
            }
        }
    }
    if (askDelete) {
        ConfirmDialog(
            title = stringResource(R.string.tracks_delete),
            message = stringResource(R.string.tracks_delete_ask, name ?: unnamed),
            confirmLabel = stringResource(R.string.tracks_delete),
            onConfirm = {
                askDelete = false
                onDelete(ref)
            },
            onDismiss = { askDelete = false },
        )
    }
}
