package fr.toolmap.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.search.LibraryKind
import fr.toolmap.core.search.SearchResult
import fr.toolmap.core.search.SearchSource
import fr.toolmap.core.track.Geodesy
import fr.toolmap.data.search.GeocodeFailure
import fr.toolmap.data.search.ProviderFailure
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.DropdownMenu
import fr.toolmap.ui.theme.TextButton

/**
 * Search for a place: the user's own items and a typed position appear as the text changes, the geocoders answer when
 * the keyboard's search key is pressed. A result opens on the map; its menu goes there, sets it as the target or keeps
 * it as a waypoint. Each result shows its distance to the map centre the screen was opened from.
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
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = ui.query,
                onValueChange = vm::onQueryChange,
                label = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.submit() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
            )
            if (ui.searching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (ui.here.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.search_section_here)) }
                    items(ui.here) { result -> ResultRow(result, center, vm, onShow, onGoTo) }
                }
                if (ui.online.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.search_section_online)) }
                    items(ui.online) { result -> ResultRow(result, center, vm, onShow, onGoTo) }
                }
                item { Notes(ui) }
            }
        }
    }
}

@Composable
private fun Notes(ui: SearchUi) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val query = ui.query.trim()
        when {
            query.isEmpty() -> {
                Text(stringResource(R.string.search_empty_hint), style = MaterialTheme.typography.bodyMedium)
            }

            ui.skippedOffline -> {
                Text(stringResource(R.string.search_offline), color = MaterialTheme.colorScheme.error)
            }

            !ui.searched && !ui.searching -> {
                Text(stringResource(R.string.search_press_search), style = MaterialTheme.typography.bodyMedium)
            }

            ui.searched && ui.online.isEmpty() && ui.failures.isEmpty() && ui.here.isEmpty() -> {
                Text(stringResource(R.string.search_none))
            }
        }
        for (failure in ui.failures) {
            Text(
                failureText(failure),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (ui.online.any { it.source == SearchSource.IGN }) {
            Text(stringResource(R.string.search_credit_ign), style = MaterialTheme.typography.labelSmall)
        }
        if (ui.online.any { it.source == SearchSource.OSM }) {
            Text(stringResource(R.string.search_credit_osm), style = MaterialTheme.typography.labelSmall)
        }
    }
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

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

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

            SearchSource.IGN -> {
                result.category
            }

            SearchSource.OSM -> {
                result.category
            }
        }
    val distance = center?.let { fmt.distance(Geodesy.distance(it.first, it.second, result.lat, result.lon)) }
    Row(
        modifier =
            Modifier.fillMaxWidth().clickable {
                onShow(result)
            }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            val detail = listOfNotNull(kind, result.subtitle.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (distance != null) {
                Text(
                    stringResource(R.string.search_distance, distance),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            TextButton(onClick = { menu = true }) { Text(stringResource(R.string.search_actions)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.search_action_show)) },
                    onClick = {
                        menu = false
                        onShow(result)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.search_action_go)) },
                    onClick = {
                        menu = false
                        onGoTo(result)
                    },
                )
                if (result.source != SearchSource.LIBRARY) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.search_action_save)) },
                        onClick = {
                            menu = false
                            vm.saveAsWaypoint(result)
                        },
                    )
                }
            }
        }
    }
    HorizontalDivider()
}

private const val HTTP_TOO_MANY_REQUESTS = 429
