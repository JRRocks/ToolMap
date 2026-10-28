package fr.toolmap.maps

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.maps.CatalogEntry
import fr.toolmap.core.maps.CatalogProvider
import fr.toolmap.core.maps.MapCatalog
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.groupedShape
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap
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
 * catalogue of remote maps. Since P29.6 (« Bascule ») the same two segments as « Cartes disponibles », with their
 * counts, a loupe, the providers as headers and the white rows of the list. A map already in the catalogue (same id or
 * same tiles) shows « Ajoutée »; one that needs an API key asks for it first; the message of an added map offers
 * « Afficher » ([onShow]). The terms of use close the list.
 */
@Composable
fun OnlineCatalogScreen(
    maps: MapsViewModel,
    onShow: (sourceId: String) -> Unit = {},
    onBack: () -> Unit,
) {
    val added by maps.catalogAdded.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    var asking by remember { mutableStateOf<CatalogEntry?>(null) }
    var layers by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val catalog = maps.onlineCatalog
    val all = remember(catalog) { catalog.flatMap { it.entries } }
    val layerCount = all.count { it.source.isLayer }
    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }

    fun add(
        entry: CatalogEntry,
        keys: Map<String, String>,
    ) {
        maps.addFromCatalog(entry, keys)
        scope.launch {
            val answer =
                snackbar.showSnackbar(
                    resources.getString(R.string.catalog_added_message, entry.source.name),
                    actionLabel = resources.getString(R.string.catalog_show),
                    duration = SnackbarDuration.Short,
                )
            if (answer == SnackbarResult.ActionPerformed) onShow(entry.source.id)
        }
    }

    // The search goes through both segments, as in « Cartes disponibles ».
    val searchText = query.takeIf { searching }.orEmpty()
    val groups =
        remember(catalog, layers, searchText) {
            catalog.mapNotNull { group ->
                val kept =
                    group.entries.filter { entry ->
                        if (searchText.isNotBlank()) {
                            MapCatalog.matches(entry.source.name, searchText)
                        } else {
                            entry.source.isLayer == layers
                        }
                    }
                if (kept.isEmpty()) null else group.copy(entries = kept)
            }
        }

    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            if (searching) {
                SearchBar(
                    query = query,
                    onChange = { query = it },
                    onClose = {
                        searching = false
                        query = ""
                    },
                )
            } else {
                ToolMapTopBar(
                    title = stringResource(R.string.catalog_title),
                    subtitle =
                        pluralStringResource(R.plurals.catalog_maps, all.size, all.size) + " · " +
                            pluralStringResource(R.plurals.catalog_providers, catalog.size, catalog.size),
                    onBack = onBack,
                    actions = {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.am_search_open))
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!searching) {
                SingleChoiceSegmentedButtonRow(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                ) {
                    SegmentedButton(
                        selected = !layers,
                        onClick = { layers = false },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        label = {
                            Text(stringResource(R.string.catalog_segment_bases, all.size - layerCount), maxLines = 1)
                        },
                    )
                    SegmentedButton(
                        selected = layers,
                        onClick = { layers = true },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        label = { Text(stringResource(R.string.catalog_segment_layers, layerCount), maxLines = 1) },
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            ) {
                if (groups.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.catalog_search_none, query.trim()),
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
                for (group in groups) {
                    item(key = "head-${group.provider}") {
                        ProviderHeader(group.provider, group.entries)
                    }
                    itemsIndexed(group.entries, key = { _, it -> it.source.id }) { index, entry ->
                        CatalogEntryRow(
                            entry = entry,
                            added = entry.source.id in added,
                            onAdd = { if (entry.keyParams.isEmpty()) add(entry, emptyMap()) else asking = entry },
                            first = index == 0,
                            last = index == group.entries.lastIndex,
                        )
                    }
                }
                item(key = "terms") {
                    Text(
                        stringResource(R.string.catalog_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, top = 16.dp),
                    )
                }
            }
        }
    }
    asking?.let { entry ->
        CatalogKeyDialog(
            entry = entry,
            onConfirm = { keys ->
                asking = null
                add(entry, keys)
            },
            onDismiss = { asking = null },
        )
    }
}

/** A provider over its maps, with how many there are of the segment's kind (« 4 calques »). */
@Composable
private fun ProviderHeader(
    provider: CatalogProvider,
    entries: List<CatalogEntry>,
) {
    val layers = entries.count { it.source.isLayer }
    val bases = entries.size - layers
    Row(
        Modifier.fillMaxWidth().padding(start = 2.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(provider.titleRes()),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            listOfNotNull(
                bases.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.catalog_group_bases, it, it) },
                layers.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.catalog_group_layers, it, it) },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A map of the online catalogue as a row of a white block: the icon of its type in a disc, its name and zooms,
 * « Ajouter » or « Ajoutée ». Also the rows of the search of « Cartes disponibles ».
 */
@Composable
internal fun CatalogEntryRow(
    entry: CatalogEntry,
    added: Boolean,
    onAdd: () -> Unit,
    first: Boolean,
    last: Boolean,
) {
    val source = entry.source
    val tm = MaterialTheme.toolMap
    Column(
        Modifier
            .fillMaxWidth()
            .clip(groupedShape(first, last))
            .background(tm.panel),
    ) {
        if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.heightIn(min = 72.dp).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(tm.tile), contentAlignment = Alignment.Center) {
                Icon(typeIcon(source), contentDescription = null, tint = tm.onTile, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(source.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.am_zooms, source.minZoom, source.maxZoom),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (added) {
                Row(
                    Modifier.padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        stringResource(R.string.catalog_added),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                TextButton(onClick = onAdd) { Text(stringResource(R.string.catalog_add)) }
            }
        }
    }
}

/** Asks for the API keys of a map (one field per parameter left empty in the catalogue); they stay on the phone. */
@Composable
internal fun CatalogKeyDialog(
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
