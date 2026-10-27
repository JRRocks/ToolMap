package fr.toolmap.map

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.maps.MapsIcons

/** What the tracks panel shows, read from the screen each time it is composed. */
data class TracksMenuState(
    /** A track or a route can be drawn: no drawing and no measure in progress. */
    val drawAllowed: Boolean,
    /** A waypoint can be added at the centre: no track is being drawn. */
    val waypointAllowed: Boolean,
    val tracks: Int = 0,
    val routes: Int = 0,
    val waypoints: Int = 0,
    /** Every folder, track, route and waypoint of the library is shown on the map. */
    val allVisible: Boolean = true,
)

/*
 * The tracks and waypoints panel, opened from the flag of the bar (P14.3, ADR-043), as the placemark panel of AlpineQuest.
 * Since P14.10: the search first, as a field; then three tiles for what is created at the centre (a waypoint, a track, a
 * route, each with its own icon); then the explorer with what the library holds, the import of a file and the switch
 * that shows or hides everything. A command closes the panel; the switch does not. The « ? » opens the help.
 */
@Composable
fun TracksMenuPanel(
    state: TracksMenuState,
    onAction: (MenuAction) -> Unit,
    onExplorer: () -> Unit,
    onHelp: () -> Unit,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    onShowAll: (Boolean) -> Unit = {},
) {
    BarPanel(
        maxHeight = maxHeight,
        modifier = modifier,
        header = { PanelHeader(stringResource(R.string.tracks_title), onHelp = onHelp) },
    ) {
        SearchField(onClick = { onAction(MenuAction.SEARCH) })
        ActionTiles {
            ActionTile(
                MenuIcons.AddLocation,
                stringResource(R.string.tracks_tile_waypoint),
                enabled = state.waypointAllowed,
            ) {
                onAction(MenuAction.WAYPOINT)
            }
            ActionTile(MenuIcons.Polyline, stringResource(R.string.tracks_tile_track), enabled = state.drawAllowed) {
                onAction(MenuAction.DRAW_TRACK)
            }
            ActionTile(MenuIcons.Directions, stringResource(R.string.tracks_tile_route), enabled = state.drawAllowed) {
                onAction(MenuAction.DRAW_ROUTE)
            }
        }
        MenuRow(
            stringResource(R.string.tracks_explore),
            icon = Icons.AutoMirrored.Filled.List,
            subtitle =
                stringResource(
                    R.string.tracks_counts,
                    countText(R.plurals.tracks_count_tracks, R.string.tracks_none_tracks, state.tracks),
                    countText(R.plurals.tracks_count_routes, R.string.tracks_none_routes, state.routes),
                    countText(R.plurals.tracks_count_waypoints, R.string.tracks_none_waypoints, state.waypoints),
                ),
            trailing = { PanelChevron() },
        ) {
            onExplorer()
        }
        MenuRow(
            stringResource(R.string.tracks_import_file),
            icon = MenuIcons.UploadFile,
            subtitle = stringResource(R.string.tracks_import_sub),
        ) {
            onAction(MenuAction.IMPORT_TRACKS)
        }
        MenuRow(
            stringResource(R.string.tracks_show_all),
            icon = if (state.allVisible) MapsIcons.Visibility else MapsIcons.VisibilityOff,
            trailing = { PanelSwitch(checked = state.allVisible) },
        ) {
            onShowAll(!state.allVisible)
        }
    }
}

/** The search as a field at the top of the panel: a touch opens the search screen, where the typing is done. */
@Composable
private fun SearchField(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = PANEL_INSET, vertical = 4.dp).heightIn(min = 48.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.tracks_search),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/**
 * "aucune trace" rather than "0 traces" or "0 trace": French counts 0 as singular, an
 * English-locale device picks the plural, and neither reads well in a summary line.
 */
@Composable
private fun countText(
    @PluralsRes plural: Int,
    @StringRes none: Int,
    count: Int,
): String = if (count == 0) stringResource(none) else pluralStringResource(plural, count, count)
