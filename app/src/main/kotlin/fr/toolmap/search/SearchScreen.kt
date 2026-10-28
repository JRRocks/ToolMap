package fr.toolmap.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.search.LibraryKind
import fr.toolmap.core.search.SearchResult
import fr.toolmap.core.search.SearchSource
import fr.toolmap.core.track.Geodesy
import fr.toolmap.data.search.GeocodeFailure
import fr.toolmap.data.search.ProviderFailure
import fr.toolmap.draw.RouteIcons
import fr.toolmap.maps.MapsIcons
import fr.toolmap.settings.SettingsIcons
import fr.toolmap.ui.GroupBlock
import fr.toolmap.ui.GroupCaption
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.DropdownMenu
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap

/**
 * Search for a place: the user's own items and a typed position appear as the text changes, the geocoders answer when
 * the keyboard's search key is pressed. A result opens on the map; its ⋮ goes there, sets it as the target or keeps
 * it as a waypoint. Since P29.10 (« Équerre »): a field with its loupe and a cross that clears it, the state of the
 * search in cards under the field (offline, a service down with « Réessayer »), the results in white groups with an
 * icon each and their distance on the second line, the credits on one line at the foot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    vm: SearchViewModel,
    onBack: () -> Unit,
    onShow: (SearchResult) -> Unit,
    onGoTo: (SearchResult) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val center by vm.center.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(vm) {
        vm.messages.collect { snackbar.showSnackbar(resources.getString(it.resId, *it.args.toTypedArray())) }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            ToolMapTopBar(title = stringResource(R.string.search_title), onBack = onBack)
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                value = ui.query,
                onValueChange = vm::onQueryChange,
                label = { Text(stringResource(R.string.search_label)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (ui.query.isNotEmpty()) {
                        IconButton(onClick = { vm.onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.submit() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
            )
            if (ui.searching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Notices(ui, onRetry = vm::submit)
            if (ui.here.isNotEmpty()) {
                GroupCaption(stringResource(R.string.search_section_here))
                ResultGroup(ui.here, center, vm, onShow, onGoTo)
            }
            if (ui.online.isNotEmpty()) {
                GroupCaption(stringResource(R.string.search_section_online))
                ResultGroup(ui.online, center, vm, onShow, onGoTo)
            }
            Credits(ui)
        }
    }
}

/** The state of the search, in cards under the field: what to type, offline, nothing found, a service down. */
@Composable
private fun Notices(
    ui: SearchUi,
    onRetry: () -> Unit,
) {
    val query = ui.query.trim()
    when {
        query.isEmpty() -> {
            NoticeCard(stringResource(R.string.search_empty_hint))
        }

        ui.skippedOffline -> {
            NoticeCard(stringResource(R.string.search_offline))
        }

        !ui.searched && !ui.searching -> {
            NoticeCard(stringResource(R.string.search_press_search))
        }

        ui.searched && ui.online.isEmpty() && ui.failures.isEmpty() && ui.here.isEmpty() -> {
            NoticeCard(stringResource(R.string.search_none))
        }
    }
    for (failure in ui.failures) NoticeCard(failureText(failure), failed = true, onRetry = onRetry)
}

/** A card of state: on the colour of the tiles, or of errors with « Réessayer » when a service did not answer. */
@Composable
private fun NoticeCard(
    text: String,
    failed: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val tm = MaterialTheme.toolMap
    Column(
        modifier =
            Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .fillMaxWidth()
                .background(if (failed) colors.errorContainer else tm.tile, MaterialTheme.shapes.medium)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = if (onRetry == null) 12.dp else 4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (failed) colors.onErrorContainer else tm.onTile,
            modifier = Modifier.padding(end = 8.dp),
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.search_retry))
            }
        }
    }
}

/** The credits of the services whose results are shown, on one line at the foot. */
@Composable
private fun Credits(ui: SearchUi) {
    val credits =
        listOfNotNull(
            stringResource(R.string.search_credit_ign).takeIf { ui.online.any { it.source == SearchSource.IGN } },
            stringResource(R.string.search_credit_osm).takeIf { ui.online.any { it.source == SearchSource.OSM } },
        )
    if (credits.isEmpty()) return
    Text(
        stringResource(R.string.search_credits, credits.joinToString(" · ")),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 16.dp),
    )
}

@Composable
private fun failureText(failure: ProviderFailure): String {
    val service =
        stringResource(
            if (failure.source ==
                SearchSource.IGN
            ) {
                R.string.search_service_ign
            } else {
                R.string.search_service_osm
            },
        )
    return when (failure.reason) {
        GeocodeFailure.NETWORK -> {
            stringResource(R.string.search_failure_network, service)
        }

        GeocodeFailure.UNREADABLE -> {
            stringResource(R.string.search_failure_unreadable, service)
        }

        GeocodeFailure.HTTP -> {
            if (failure.httpCode == HTTP_TOO_MANY_REQUESTS) {
                stringResource(R.string.search_failure_busy, service)
            } else {
                stringResource(R.string.search_failure_http, service, failure.httpCode ?: 0)
            }
        }
    }
}

/** The results of one origin in a white group. */
@Composable
private fun ResultGroup(
    results: List<SearchResult>,
    center: Pair<Double, Double>?,
    vm: SearchViewModel,
    onShow: (SearchResult) -> Unit,
    onGoTo: (SearchResult) -> Unit,
) {
    GroupBlock(
        modifier = Modifier.padding(horizontal = 16.dp),
        rows = results.map { result -> { ResultRow(result, center, vm, onShow, onGoTo) } },
    )
}

/**
 * A result: its icon in a disc (the user's own items on the secondary colour), its name, what it is, where and how far
 * on one line; a tap shows it on the map, its ⋮ offers the rest.
 */
@Composable
private fun ResultRow(
    result: SearchResult,
    center: Pair<Double, Double>?,
    vm: SearchViewModel,
    onShow: (SearchResult) -> Unit,
    onGoTo: (SearchResult) -> Unit,
) {
    val fmt = LocalFormats.current
    var menu by remember { mutableStateOf(false) }
    val title =
        if (result.source == SearchSource.COORDINATES) fmt.position(result.lat, result.lon).text else result.title
    val kind =
        when (result.source) {
            SearchSource.COORDINATES -> {
                stringResource(R.string.search_kind_position)
            }

            SearchSource.LIBRARY -> {
                when (result.libraryKind) {
                    LibraryKind.TRACK -> stringResource(R.string.search_kind_track)
                    LibraryKind.ROUTE -> stringResource(R.string.search_kind_route)
                    else -> stringResource(R.string.search_kind_waypoint)
                }
            }

            SearchSource.IGN, SearchSource.OSM -> {
                result.category
            }
        }
    val distance = center?.let { fmt.distance(Geodesy.distance(it.first, it.second, result.lat, result.lon)) }
    val mine = result.source == SearchSource.LIBRARY || result.source == SearchSource.COORDINATES
    val icon =
        when {
            result.source == SearchSource.COORDINATES -> SettingsIcons.MyLocation
            result.libraryKind == LibraryKind.TRACK -> SettingsIcons.Route
            result.libraryKind == LibraryKind.ROUTE -> SettingsIcons.AltRoute
            else -> SettingsIcons.Place
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable { onShow(result) }
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val colors = MaterialTheme.colorScheme
        val tm = MaterialTheme.toolMap
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (mine) colors.secondaryContainer else tm.tile),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (mine) colors.onSecondaryContainer else tm.onTile,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail =
                listOfNotNull(
                    kind?.takeIf { it.isNotBlank() },
                    result.subtitle.takeIf { it.isNotBlank() },
                    distance?.let { stringResource(R.string.search_at, it) },
                ).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.search_actions))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.search_action_show)) },
                    leadingIcon = { Icon(MapsIcons.Map, contentDescription = null) },
                    onClick = {
                        menu = false
                        onShow(result)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.search_action_go)) },
                    leadingIcon = { Icon(SettingsIcons.NearMe, contentDescription = null) },
                    onClick = {
                        menu = false
                        onGoTo(result)
                    },
                )
                if (result.source != SearchSource.LIBRARY) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.search_action_save)) },
                        leadingIcon = { Icon(RouteIcons.AddLocationAlt, contentDescription = null) },
                        onClick = {
                            menu = false
                            vm.saveAsWaypoint(result)
                        },
                    )
                }
            }
        }
    }
}

private const val HTTP_TOO_MANY_REQUESTS = 429
