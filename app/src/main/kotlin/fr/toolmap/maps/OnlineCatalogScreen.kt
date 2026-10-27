package fr.toolmap.maps

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.maps.CatalogEntry
import fr.toolmap.core.maps.CatalogProvider
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.TextButton
import kotlinx.coroutines.launch

/** The title of the section of a provider of the online catalogue. */
@StringRes
private fun CatalogProvider.titleRes(): Int =
    when (this) {
        CatalogProvider.OPENSTREETMAP -> R.string.catalog_provider_osm
        CatalogProvider.IGN -> R.string.catalog_provider_ign
        CatalogProvider.SWISSTOPO -> R.string.catalog_provider_swisstopo
        CatalogProvider.BKG -> R.string.catalog_provider_bkg
        CatalogProvider.USGS -> R.string.catalog_provider_usgs
        CatalogProvider.ORDNANCE_SURVEY -> R.string.catalog_provider_os
    }

/**
 * « Catalogue en ligne » (P16.4): the maps of the providers that can be added in one gesture, as AlpineQuest's
 * catalogue of remote maps. A map already in the catalogue (same id or same tiles) shows « Ajoutée »; one that needs an
 * API key asks for it first. The tiles themselves are fetched from the provider's server when the map is shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineCatalogScreen(
    maps: MapsViewModel,
    onBack: () -> Unit,
) {
    val added by maps.catalogAdded.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    var asking by remember { mutableStateOf<CatalogEntry?>(null) }

    fun add(
        entry: CatalogEntry,
        keys: Map<String, String>,
    ) {
        maps.addFromCatalog(entry, keys)
        scope.launch {
            snackbar.showSnackbar(resources.getString(R.string.catalog_added_message, entry.source.name))
        }
    }

    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            ToolMapTopBar(
                title = stringResource(R.string.catalog_title),
                subtitle = stringResource(R.string.catalog_subtitle),
                onBack = onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (group in maps.onlineCatalog) {
                item(key = "head-${group.provider}") {
                    Text(
                        stringResource(group.provider.titleRes()).uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(group.entries, key = { it.source.id }) { entry ->
                    CatalogRow(
                        entry = entry,
                        added = entry.source.id in added,
                        onAdd = { if (entry.keyParams.isEmpty()) add(entry, emptyMap()) else asking = entry },
                    )
                }
            }
        }
    }
    asking?.let { entry ->
        KeyDialog(
            entry = entry,
            onConfirm = { keys ->
                asking = null
                add(entry, keys)
            },
            onDismiss = { asking = null },
        )
    }
}

@Composable
private fun CatalogRow(
    entry: CatalogEntry,
    added: Boolean,
    onAdd: () -> Unit,
) {
    val source = entry.source
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(source.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.layers_zoom_range, source.minZoom, source.maxZoom) +
                        if (source.isLayer) " · " + stringResource(R.string.catalog_layer) else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (added) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.catalog_added),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 4.dp, end = 8.dp),
                )
            } else {
                TextButton(onClick = onAdd) { Text(stringResource(R.string.catalog_add)) }
            }
        }
    }
}

/** Asks for the API keys of a map (one field per parameter left empty in the catalogue); they stay on the phone. */
@Composable
private fun KeyDialog(
    entry: CatalogEntry,
    onConfirm: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var values by remember(entry.source.id) { mutableStateOf(entry.keyParams.associateWith { "" }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.catalog_key_title, entry.source.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.catalog_key_hint, entry.source.dataSource.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                for (key in entry.keyParams) {
                    OutlinedTextField(
                        value = values[key].orEmpty(),
                        onValueChange = { values = values + (key to it) },
                        label = { Text(stringResource(R.string.catalog_key_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(values) },
                enabled = values.values.all { it.isNotBlank() },
            ) { Text(stringResource(R.string.catalog_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
