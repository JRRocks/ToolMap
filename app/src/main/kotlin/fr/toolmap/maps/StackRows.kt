package fr.toolmap.maps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.layers.LayerElevation
import fr.toolmap.core.maps.MapCatalog
import fr.toolmap.core.source.TileSource
import fr.toolmap.download.formatBytes
import fr.toolmap.map.MapViewModel
import fr.toolmap.settings.SettingsIcons
import fr.toolmap.ui.theme.Switch
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap

/*
 * The lines of the layer stack in « Cartes et calques » (P29.3, « Strates »): a handle to drag the layer up or down,
 * its thumbnail (ringed in turquoise when the map draws it at its zoom, faded when it does not), its name and state,
 * its eye. A tap unfolds the line in place: opacity, Monter · Descendre · Conditions · Couleurs · Plus, the colours
 * unfolding under it in turn. The former « Cartes affichées » sheet and its ⋮ menu are gone.
 */

/** The layers, top first; [expanded] is the one unfolded (one at a time), [onExpand] changes it. */
@Composable
internal fun StackRows(
    layers: List<LayerConfig>,
    byId: Map<String, TileSource>,
    maps: MapsViewModel,
    vm: MapViewModel,
    currentZoom: Double,
    expanded: String?,
    onExpand: (String?) -> Unit,
) {
    val forced by maps.forced.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val step = with(LocalDensity.current) { ROW_STEP.toPx() }
    val latestLayers by rememberUpdatedState(layers)
    layers.forEachIndexed { index, layer ->
        key(layer.id) {
            val open = expanded == layer.id
            val drag: suspend PointerInputScope.() -> Unit = {
                detectVerticalDragGestures(
                    onDragStart = {
                        dragging = layer.id
                        dragOffset = 0f
                    },
                    onDragEnd = {
                        dragging = null
                        dragOffset = 0f
                    },
                    onDragCancel = {
                        dragging = null
                        dragOffset = 0f
                    },
                ) { change, dy ->
                    change.consume()
                    dragOffset += dy
                    // The layer changes place each time the finger has gone half a line further.
                    val at = latestLayers.indexOfFirst { it.id == layer.id }
                    if (dragOffset < -step / 2 && at > 0) {
                        vm.moveLayerUp(layer.id)
                        dragOffset += step
                    } else if (dragOffset > step / 2 && at in 0 until latestLayers.lastIndex) {
                        vm.moveLayerDown(layer.id)
                        dragOffset -= step
                    }
                }
            }
            val lifted = dragging == layer.id
            Column(
                Modifier
                    .zIndex(if (lifted) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (lifted) dragOffset else 0f
                        shadowElevation = if (lifted) 8.dp.toPx() else 0f
                    }.then(
                        if (open) {
                            Modifier
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.toolMap.tile)
                        } else {
                            Modifier.background(MaterialTheme.toolMap.panel)
                        },
                    ),
            ) {
                LayerLine(
                    layer = layer,
                    source = byId[layer.sourceId],
                    maps = maps,
                    currentZoom = currentZoom,
                    open = open,
                    canUp = index > 0,
                    canDown = index < layers.lastIndex,
                    dragGesture = drag,
                    onToggle = { onExpand(if (open) null else layer.id) },
                    vm = vm,
                )
                if (open) {
                    LayerSettings(
                        layer = layer,
                        forced = layer.sourceId in forced,
                        canUp = index > 0,
                        canDown = index < layers.lastIndex,
                        onConditions = { maps.openConditions(layer.id) },
                        onDetails = { details = layer.id },
                        onForce = { maps.setForced(layer.sourceId, layer.sourceId !in forced) },
                        vm = vm,
                    )
                }
            }
            if (!open && index < layers.lastIndex && expanded != layers[index + 1].id) {
                HorizontalDivider(
                    Modifier.padding(start = 48.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
    details?.let { layerId ->
        val layer = layers.firstOrNull { it.id == layerId }
        if (layer == null) {
            details = null
        } else {
            DetailsDialog(layer, byId[layer.sourceId], maps, layer.sourceId in forced) { details = null }
        }
    }
}

/** The line of a layer: handle, thumbnail, name and state, the arrow that unfolds it, the eye. */
@Composable
private fun LayerLine(
    layer: LayerConfig,
    source: TileSource?,
    maps: MapsViewModel,
    currentZoom: Double,
    open: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    dragGesture: suspend PointerInputScope.() -> Unit,
    onToggle: () -> Unit,
    vm: MapViewModel,
) {
    val name = source?.name ?: stringResource(R.string.layers_missing_source, layer.sourceId)
    val drawn = layer.visible && layer.opacity > 0f && LayerElevation.isDrawnAt(layer, currentZoom)
    val up = stringResource(R.string.layers_move_up)
    val down = stringResource(R.string.layers_move_down)
    val dragLabel = stringResource(R.string.maps_layer_drag)
    val state = stringResource(if (open) R.string.menu_more_shown else R.string.menu_more_hidden)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_STEP)
            .clickable(
                onClickLabel = stringResource(if (open) R.string.maps_layer_fold else R.string.maps_layer_unfold),
                onClick = onToggle,
            ).semantics { stateDescription = state }
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The handle: dragged, it moves the layer; TalkBack offers Monter and Descendre instead.
        Box(
            Modifier
                .size(48.dp)
                .pointerInput(layer.id, block = dragGesture)
                .semantics {
                    contentDescription = dragLabel
                    customActions =
                        listOfNotNull(
                            CustomAccessibilityAction(up) {
                                vm.moveLayerUp(layer.id)
                                true
                            }.takeIf { canUp },
                            CustomAccessibilityAction(down) {
                                vm.moveLayerDown(layer.id)
                                true
                            }.takeIf { canDown },
                        )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(MapsIcons.DragIndicator, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // What the map draws now: ringed in turquoise; a layer not drawn at this zoom is faded.
        Box(
            Modifier
                .then(
                    if (drawn) {
                        Modifier.border(2.dp, MaterialTheme.toolMap.accent, MaterialTheme.shapes.extraSmall)
                    } else {
                        Modifier.alpha(FADED)
                    },
                ).padding(2.dp),
        ) {
            MapThumbnail(layer.sourceId, name, maps.thumbnails, size = 32.dp)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 6.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (drawn) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                layerStateLine(layer, currentZoom),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        IconButton(onClick = { vm.setVisible(layer.id, !layer.visible) }) {
            Icon(
                if (layer.visible) MapsIcons.Visibility else MapsIcons.VisibilityOff,
                contentDescription =
                    stringResource(if (layer.visible) R.string.maps_layer_hide else R.string.maps_layer_show),
            )
        }
    }
}

/** What an unfolded line adds: the opacity, then the commands of the layer, the colours unfolding under them. */
@Composable
private fun LayerSettings(
    layer: LayerConfig,
    forced: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onConditions: () -> Unit,
    onDetails: () -> Unit,
    onForce: () -> Unit,
    vm: MapViewModel,
) {
    var colors by remember(layer.id) { mutableStateOf(false) }
    Box(Modifier.padding(start = 56.dp, end = 16.dp)) {
        LabeledSlider(
            label = stringResource(R.string.layers_opacity, percentOf(layer.opacity)),
            value = layer.opacity,
            range = 0f..1f,
            onChange = { vm.setOpacity(layer.id, it) },
        )
    }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp)) {
        Command(MapsIcons.ArrowUpward, stringResource(R.string.layers_move_up), enabled = canUp) {
            vm.moveLayerUp(layer.id)
        }
        Command(MapsIcons.ArrowDownward, stringResource(R.string.layers_move_down), enabled = canDown) {
            vm.moveLayerDown(layer.id)
        }
        Command(MapsIcons.Tune, stringResource(R.string.maps_cmd_conditions), onClick = onConditions)
        Command(SettingsIcons.Palette, stringResource(R.string.maps_cmd_colors), selected = colors) {
            colors = !colors
        }
        MoreCommand(layer, forced, onDetails, onForce, vm)
    }
    if (colors) {
        Column(Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp)) {
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
                Text(
                    stringResource(R.string.maps_layer_inverted),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = layer.inverted, onCheckedChange = { vm.setInverted(layer.id, it) })
            }
            TextButton(onClick = {
                vm.setAdjustments(layer.id, 0f, 0f, 0f)
                vm.setInverted(layer.id, false)
            }) { Text(stringResource(R.string.layers_reset_adjustments)) }
        }
    }
}

/** A command of an unfolded line: its icon over a short name, a 56 dp cell. */
@Composable
private fun RowScope.Command(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val color = MaterialTheme.toolMap.onTile
    Column(
        Modifier
            .weight(1f)
            .heightIn(min = ROW_STEP)
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.toolMap.tile)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else DISABLED)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/** « Plus »: what an unfolded line keeps behind a touch (details, forced update, removing it or the others). */
@Composable
private fun RowScope.MoreCommand(
    layer: LayerConfig,
    forced: Boolean,
    onDetails: () -> Unit,
    onForce: () -> Unit,
    vm: MapViewModel,
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.weight(1f)) {
        Row(Modifier.fillMaxWidth()) {
            Command(Icons.Filled.MoreVert, stringResource(R.string.maps_cmd_more)) { open = true }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun choose(action: () -> Unit) {
                open = false
                action()
            }
            DropdownMenuItem(text = {
                Text(stringResource(R.string.maps_layer_details))
            }, onClick = { choose(onDetails) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.maps_layer_force_update)) },
                trailingIcon = { if (forced) Icon(Icons.Filled.Check, contentDescription = null) },
                onClick = { choose(onForce) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.maps_layer_undisplay)) },
                onClick = { choose { vm.removeLayer(layer.id) } },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.maps_layer_undisplay_others)) },
                onClick = { choose { vm.removeOtherLayers(layer.id) } },
            )
        }
    }
}

/**
 * The grey line of a layer: hidden; else its zoom range or elevation band and whether it is drawn now (ADR-063, P20),
 * with the opacity and inverted colours when they are not the plain ones.
 */
@Composable
internal fun layerStateLine(
    layer: LayerConfig,
    currentZoom: Double,
): String {
    val percent = percentOf(layer.opacity)
    val look =
        when {
            layer.inverted -> stringResource(R.string.maps_layer_line_inverted, percent)
            else -> stringResource(R.string.maps_layer_line, percent)
        }
    return when {
        !layer.visible -> {
            stringResource(R.string.maps_layer_hidden)
        }

        !layer.hasZoomRange && !layer.hasElevationRange -> {
            look
        }

        else -> {
            listOfNotNull(
                conditionsLineText(layer) ?: zoomRangeText(layer.displayMinZoom, layer.displayMaxZoom),
                stringResource(R.string.layers_inactive_here).takeUnless {
                    LayerElevation.isDrawnAt(layer, currentZoom)
                },
                look.takeIf { percent < FULL_PERCENT || layer.inverted },
            ).joinToString(" · ")
        }
    }
}

/** « Détails » of a layer: its kind, zooms, origin, rights, data source, what is stored and the forced update. */
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

/** The height of a line, and how far the finger goes to move a dragged layer by one place. */
private val ROW_STEP = 56.dp
private const val FADED = 0.5f
private const val DISABLED = 0.38f

/** Opacity shown in a layer line only below this (a plain opaque layer says nothing about it). */
private const val FULL_PERCENT = 100
