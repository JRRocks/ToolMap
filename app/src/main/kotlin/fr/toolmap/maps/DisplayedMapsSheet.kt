package fr.toolmap.maps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.layers.LayerElevation
import fr.toolmap.core.maps.MapCatalog
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.state.WorkState
import fr.toolmap.download.formatBytes
import fr.toolmap.map.MapViewModel
import fr.toolmap.ui.theme.Switch
import fr.toolmap.ui.theme.TextButton

/** The editing windows a layer menu can open. */
private enum class LayerDialog { DETAILS, COLORS }

/** "Displayed maps": the layer stack, top first, with the eye, the opacity and the ⋮ menu of each map. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplayedMapsSheet(
    state: WorkState,
    maps: MapsViewModel,
    vm: MapViewModel,
    currentZoom: Double,
    onDismiss: () -> Unit,
) {
    val sources by maps.sources.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val forced by maps.forced.collectAsStateWithLifecycle()
    val byId = remember(sources) { sources.associateBy { it.id } }
    var showHelp by remember { mutableStateOf(false) }
    var opacityOpen by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<Pair<String, LayerDialog>?>(null) }
    val knownFavorite = favorites.any { it.matches(state.stack) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            PanelHeader(
                title = stringResource(R.string.maps_displayed),
                onHelp = { showHelp = true },
                modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                navigation = {
                    IconButton(onClick = { maps.open(MapsPage.ROOT) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.maps_back),
                        )
                    }
                },
            )
            if (state.stack.isEmpty) {
                Text(
                    stringResource(R.string.maps_displayed_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
            LazyColumn {
                items(state.stack.topToBottom(), key = { it.id }) { layer ->
                    DisplayedRow(
                        layer = layer,
                        source = byId[layer.sourceId],
                        thumbnails = maps.thumbnails,
                        forced = layer.sourceId in forced,
                        currentZoom = currentZoom,
                        opacityOpen = opacityOpen == layer.id,
                        onToggleOpacity = { opacityOpen = if (opacityOpen == layer.id) null else layer.id },
                        onDialog = { dialog = layer.id to it },
                        onConditions = { maps.openConditions(layer.id) },
                        onForce = { maps.setForced(layer.sourceId, !(layer.sourceId in forced)) },
                        vm = vm,
                    )
                    HorizontalDivider()
                }
                if (!state.stack.isEmpty && !knownFavorite) {
                    item {
                        Text(
                            stringResource(R.string.maps_layer_save_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
    if (showHelp) HelpDialog(stringResource(R.string.maps_help_text)) { showHelp = false }
    dialog?.let { (layerId, kind) ->
        val layer = state.stack[layerId]
        val source = layer?.let { byId[it.sourceId] }
        if (layer == null) {
            dialog = null
        } else {
            when (kind) {
                LayerDialog.DETAILS -> {
                    DetailsDialog(layer, source, maps, layer.sourceId in forced) { dialog = null }
                }

                LayerDialog.COLORS -> {
                    ColorsDialog(layer, vm) { dialog = null }
                }
            }
        }
    }
}

@Composable
private fun DisplayedRow(
    layer: LayerConfig,
    source: TileSource?,
    thumbnails: MapThumbnails,
    forced: Boolean,
    currentZoom: Double,
    opacityOpen: Boolean,
    onToggleOpacity: () -> Unit,
    onDialog: (LayerDialog) -> Unit,
    onConditions: () -> Unit,
    onForce: () -> Unit,
    vm: MapViewModel,
) {
    val name = source?.name ?: stringResource(R.string.layers_missing_source, layer.sourceId)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MapThumbnail(layer.sourceId, name, thumbnails, size = 44.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val percent = percentOf(layer.opacity)
                val look =
                    when {
                        layer.inverted -> stringResource(R.string.maps_layer_line_inverted, percent)
                        else -> stringResource(R.string.maps_layer_line, percent)
                    }
                val line =
                    when {
                        !layer.visible -> {
                            stringResource(R.string.maps_layer_hidden)
                        }

                        !layer.hasZoomRange && !layer.hasElevationRange -> {
                            look
                        }

                        // A layer kept to a zoom range or an elevation band says so first (ADR-063, P20): which
                        // zooms and elevations, and whether it is drawn now; the opacity only when it is not the
                        // plain 100 %.
                        else -> {
                            listOfNotNull(
                                conditionsLineText(layer)
                                    ?: zoomRangeText(layer.displayMinZoom, layer.displayMaxZoom),
                                stringResource(R.string.layers_inactive_here)
                                    .takeUnless { LayerElevation.isDrawnAt(layer, currentZoom) },
                                look.takeIf { percent < FULL_PERCENT || layer.inverted },
                            ).joinToString(" · ")
                        }
                    }
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { vm.setVisible(layer.id, !layer.visible) }, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (layer.visible) MapsIcons.Visibility else MapsIcons.VisibilityOff,
                    contentDescription =
                        stringResource(if (layer.visible) R.string.maps_layer_hide else R.string.maps_layer_show),
                )
            }
            IconButton(onClick = onToggleOpacity, modifier = Modifier.size(40.dp)) {
                Icon(MapsIcons.Opacity, contentDescription = stringResource(R.string.maps_layer_opacity_button))
            }
            LayerMenu(layer, forced, onDialog, onConditions, onToggleOpacity, onForce, vm)
        }
        if (opacityOpen) {
            Box(modifier = Modifier.padding(horizontal = 24.dp)) {
                LabeledSlider(
                    label = stringResource(R.string.layers_opacity, percentOf(layer.opacity)),
                    value = layer.opacity,
                    range = 0f..1f,
                    onChange = { vm.setOpacity(layer.id, it) },
                )
            }
        }
    }
}

@Composable
private fun LayerMenu(
    layer: LayerConfig,
    forced: Boolean,
    onDialog: (LayerDialog) -> Unit,
    onConditions: () -> Unit,
    onOpacity: () -> Unit,
    onForce: () -> Unit,
    vm: MapViewModel,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.maps_layer_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun choose(action: () -> Unit) {
                open = false
                action()
            }
            MenuItem(R.string.maps_layer_details) { choose { onDialog(LayerDialog.DETAILS) } }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.maps_layer_force_update)) },
                trailingIcon = { if (forced) Icon(Icons.Filled.Check, contentDescription = null) },
                onClick = { choose(onForce) },
            )
            MenuHeader(R.string.maps_group_order)
            MenuItem(R.string.layers_move_up) { choose { vm.moveLayerUp(layer.id) } }
            MenuItem(R.string.layers_move_down) { choose { vm.moveLayerDown(layer.id) } }
            MenuItem(R.string.maps_layer_undisplay) { choose { vm.removeLayer(layer.id) } }
            MenuItem(R.string.maps_layer_undisplay_others) { choose { vm.removeOtherLayers(layer.id) } }
            MenuHeader(R.string.maps_group_edit)
            MenuItem(R.string.maps_layer_opacity) { choose(onOpacity) }
            MenuItem(R.string.maps_layer_colors) { choose { onDialog(LayerDialog.COLORS) } }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.maps_layer_inverted)) },
                trailingIcon = { if (layer.inverted) Icon(Icons.Filled.Check, contentDescription = null) },
                onClick = { choose { vm.setInverted(layer.id, !layer.inverted) } },
            )
            MenuItem(R.string.maps_layer_conditions) { choose(onConditions) }
        }
    }
}

@Composable
private fun MenuItem(
    label: Int,
    onClick: () -> Unit,
) = DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick)

@Composable
private fun MenuHeader(label: Int) {
    HorizontalDivider()
    Text(
        stringResource(label),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun DetailsDialog(
    layer: LayerConfig,
    source: TileSource?,
    maps: MapsViewModel,
    forced: Boolean,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { maps.refreshStorage() }
    val resources = LocalResources.current
    val figures by maps.storageFigures.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(source?.name ?: stringResource(R.string.maps_layer_unavailable)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (source != null) {
                    val kind =
                        stringResource(
                            if (source.isLayer) R.string.maps_details_kind_layer else R.string.maps_details_kind_base,
                        )
                    Text(stringResource(R.string.maps_details_type, kind))
                    if (source.isSupported) {
                        Text(stringResource(R.string.maps_details_zooms, source.minZoom, source.maxZoom))
                    }
                    Text(
                        stringResource(
                            when {
                                source.isLocalOnly -> R.string.maps_details_origin_local
                                maps.isImported(source.id) -> R.string.maps_details_origin_imported
                                else -> R.string.maps_details_origin_builtin
                            },
                        ),
                    )
                    source.copyright?.let(MapCatalog::cleanCopyright)?.let {
                        Text(stringResource(R.string.maps_details_copyright, it))
                    }
                    source.dataSource?.let(MapCatalog::hostOf)?.let {
                        Text(stringResource(R.string.maps_details_site, it))
                    }
                    val stored = figures?.perSource?.get(source.id)
                    if (stored != null && stored.first > 0) {
                        Text(
                            stringResource(
                                R.string.maps_details_stored,
                                tilesText(resources, stored.first),
                                formatBytes(stored.second),
                            ),
                        )
                    } else if (figures != null) {
                        Text(stringResource(R.string.maps_details_not_stored))
                    }
                } else {
                    Text(layer.sourceId)
                }
                if (forced) Text(stringResource(R.string.maps_details_forced))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun ColorsDialog(
    layer: LayerConfig,
    vm: MapViewModel,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.maps_layer_colors)) },
        text = {
            Column {
                LabeledSlider(
                    label = stringResource(R.string.layers_contrast, percentOf(layer.contrast)),
                    value = layer.contrast,
                    range = -1f..1f,
                    onChange = { vm.setAdjustments(layer.id, it, layer.brightness, layer.saturation) },
                )
                LabeledSlider(
                    label = stringResource(R.string.layers_brightness, percentOf(layer.brightness)),
                    value = layer.brightness,
                    range = -1f..1f,
                    onChange = { vm.setAdjustments(layer.id, layer.contrast, it, layer.saturation) },
                )
                LabeledSlider(
                    label = stringResource(R.string.layers_saturation, percentOf(layer.saturation)),
                    value = layer.saturation,
                    range = -1f..1f,
                    onChange = { vm.setAdjustments(layer.id, layer.contrast, layer.brightness, it) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.maps_layer_inverted), modifier = Modifier.weight(1f))
                    Switch(
                        checked = layer.inverted,
                        onCheckedChange = { vm.setInverted(layer.id, it) },
                    )
                }
                TextButton(onClick = {
                    vm.setAdjustments(layer.id, 0f, 0f, 0f)
                    vm.setInverted(layer.id, false)
                }) { Text(stringResource(R.string.layers_reset_adjustments)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** Opacity shown in a layer line only below this (a plain opaque layer says nothing about it). */
private const val FULL_PERCENT = 100
