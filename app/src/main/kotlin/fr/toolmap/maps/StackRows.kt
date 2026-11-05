package fr.toolmap.maps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.layers.BlendMode
import fr.toolmap.core.layers.FavoriteChange
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.layers.LayerElevation
import fr.toolmap.core.layers.LayerField
import fr.toolmap.core.layers.RasterAdjust
import fr.toolmap.core.maps.MapCatalog
import fr.toolmap.core.source.TileSource
import fr.toolmap.download.formatBytes
import fr.toolmap.map.MapViewModel
import fr.toolmap.ui.EntryField
import fr.toolmap.ui.ValueDialog
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap
import kotlin.math.roundToInt

/*
 * The lines of the layer stack in « Cartes et calques » (P34, style 4 « Fiche du calque », docs/previews/
 * p34-cartes-onglets.html, band 4): a handle to drag the layer up or down, its thumbnail (ringed in turquoise when the
 * map draws it at its zoom, faded when it does not), its name, « inactif à ce zoom » only when it is so, its eye and ›.
 * A touch opens the sheet of the layer in the panel ([LayerSheet]); nothing unfolds in the list any more.
 */

/** The layers, top first; [onOpen] opens the sheet of one. [changes] are the layers that differ from the favourite the
 * stack came from, by layer id (P25.9). */
@Composable
internal fun StackRows(
    layers: List<LayerConfig>,
    byId: Map<String, TileSource>,
    maps: MapsViewModel,
    vm: MapViewModel,
    currentZoom: Double,
    onOpen: (String) -> Unit,
    changes: Map<String, FavoriteChange> = emptyMap(),
) {
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val step = with(LocalDensity.current) { ROW_STEP.toPx() }
    val latestLayers by rememberUpdatedState(layers)
    layers.forEachIndexed { index, layer ->
        key(layer.id) {
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
            Box(
                Modifier
                    .zIndex(if (lifted) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (lifted) dragOffset else 0f
                        shadowElevation = if (lifted) 8.dp.toPx() else 0f
                    }.background(MaterialTheme.toolMap.panel),
            ) {
                LayerLine(
                    layer = layer,
                    change = changes[layer.id],
                    source = byId[layer.sourceId],
                    maps = maps,
                    currentZoom = currentZoom,
                    canUp = index > 0,
                    canDown = index < layers.lastIndex,
                    dragGesture = drag,
                    onOpen = { onOpen(layer.id) },
                    vm = vm,
                )
            }
        }
    }
}

/**
 * The line of a layer: handle, thumbnail, name, « inactif à ce zoom » when the map does not draw it at its zoom, the
 * eye and ›. A layer that differs from the favourite the stack came from ([change]) says how instead, in the accent
 * colour.
 */
@Composable
private fun LayerLine(
    layer: LayerConfig,
    change: FavoriteChange?,
    source: TileSource?,
    maps: MapsViewModel,
    currentZoom: Double,
    canUp: Boolean,
    canDown: Boolean,
    dragGesture: suspend PointerInputScope.() -> Unit,
    onOpen: () -> Unit,
    vm: MapViewModel,
) {
    val name = source?.name ?: stringResource(R.string.layers_missing_source, layer.sourceId)
    val drawn = layer.visible && layer.opacity > 0f && LayerElevation.isDrawnAt(layer, currentZoom)
    val up = stringResource(R.string.layers_move_up)
    val down = stringResource(R.string.layers_move_down)
    val dragLabel = stringResource(R.string.maps_layer_drag)
    val second =
        change?.let { changedStateLine(layer, it, currentZoom) }
            ?: stringResource(R.string.layers_inactive_here).takeIf {
                layer.visible && !LayerElevation.isDrawnAt(layer, currentZoom)
            }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_STEP)
            .clickable(onClickLabel = stringResource(R.string.maps_layer_open), onClick = onOpen)
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
            if (second != null) {
                Text(
                    second,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (change !=
                            null
                        ) {
                            MaterialTheme.toolMap.accent
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    fontWeight = if (change != null) FontWeight.Medium else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = { vm.setVisible(layer.id, !layer.visible) }) {
            Icon(
                if (layer.visible) MapsIcons.Visibility else MapsIcons.VisibilityOff,
                contentDescription =
                    stringResource(if (layer.visible) R.string.maps_layer_hide else R.string.maps_layer_show),
                tint = if (layer.visible) MaterialTheme.toolMap.accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The values of a layer that can be typed in a window (P23.4): its opacity on its sheet, its colours on its page. */
internal enum class Adjust { OPACITY, CONTRAST, BRIGHTNESS, SATURATION, HUE }

/** The window of one value of a layer: opacity 0 to 100 %, corrections −100 to 100 %, hue −180 to 180°. */
@Composable
internal fun AdjustDialog(
    adjust: Adjust,
    layer: LayerConfig,
    vm: MapViewModel,
    onClose: () -> Unit,
) {
    val percent = stringResource(R.string.value_unit_percent)
    val hueMax = RasterAdjust.MAX_HUE.roundToInt()
    val (title, field) =
        when (adjust) {
            Adjust.OPACITY -> {
                R.string.layers_opacity_name to EntryField(null, percentOf(layer.opacity), 0, PERCENT_MAX, percent)
            }

            Adjust.CONTRAST -> {
                R.string.layers_contrast_name to
                    EntryField(null, percentOf(layer.contrast), -PERCENT_MAX, PERCENT_MAX, percent)
            }

            Adjust.BRIGHTNESS -> {
                R.string.layers_brightness_name to
                    EntryField(null, percentOf(layer.brightness), -PERCENT_MAX, PERCENT_MAX, percent)
            }

            Adjust.SATURATION -> {
                R.string.layers_saturation_name to
                    EntryField(null, percentOf(layer.saturation), -PERCENT_MAX, PERCENT_MAX, percent)
            }

            Adjust.HUE -> {
                R.string.layers_hue_name to EntryField(null, layer.hue.roundToInt(), -hueMax, hueMax, "°")
            }
        }
    val name = stringResource(title)
    ValueDialog(
        title = name,
        fields = listOf(field),
        onDone = { values ->
            val v = values[0] ?: return@ValueDialog
            val fraction = v / PERCENT_MAX.toFloat()
            when (adjust) {
                Adjust.OPACITY -> vm.setOpacity(layer.id, fraction)
                Adjust.CONTRAST -> vm.setAdjustments(layer.id, fraction, layer.brightness, layer.saturation)
                Adjust.BRIGHTNESS -> vm.setAdjustments(layer.id, layer.contrast, fraction, layer.saturation)
                Adjust.SATURATION -> vm.setAdjustments(layer.id, layer.contrast, layer.brightness, fraction)
                Adjust.HUE -> vm.setHue(layer.id, v.toFloat())
            }
            onClose()
        },
        onDismiss = onClose,
    )
}

private const val PERCENT_MAX = 100

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
    val plain =
        when {
            layer.inverted -> stringResource(R.string.maps_layer_line_inverted, percent)
            else -> stringResource(R.string.maps_layer_line, percent)
        }
    // « 100 % d'opacité · Produit · teinté » (P26.4).
    val blend = stringResource(layer.blend.label()).takeIf { layer.blend != BlendMode.NORMAL }
    val tinted = stringResource(R.string.maps_layer_tinted).takeIf { layer.tint != null }
    val look =
        listOfNotNull(blend, tinted).fold(plain) { line, more ->
            stringResource(R.string.maps_layer_line_more, line, more)
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
                look.takeIf {
                    percent < FULL_PERCENT || layer.inverted || layer.blend != BlendMode.NORMAL ||
                        layer.tint != null
                },
            ).joinToString(" · ")
        }
    }
}

/**
 * The line of a layer that differs from the favourite the stack came from (P25.9): « Opacité 50 % (31 % dans le
 * favori) », the zoom range with the favourite's, « masquée »; a layer the favourite does not have is « Ajoutée depuis
 * le favori » (choice G, P34.1); other changes keep the usual line.
 */
@Composable
internal fun changedStateLine(
    layer: LayerConfig,
    change: FavoriteChange,
    currentZoom: Double,
): String {
    val usual = layerStateLine(layer, currentZoom)
    return when (change) {
        is FavoriteChange.Added -> {
            stringResource(R.string.maps_layer_line_added)
        }

        is FavoriteChange.Changed -> {
            val was = change.was
            listOfNotNull(
                stringResource(R.string.maps_layer_hidden).takeIf {
                    LayerField.VISIBILITY in change.fields && !layer.visible
                },
                stringResource(
                    R.string.maps_layer_line_zoom_was,
                    zoomRangeText(layer.displayMinZoom, layer.displayMaxZoom),
                    zoomLineText(was.displayMinZoom, was.displayMaxZoom),
                ).takeIf { LayerField.ZOOM in change.fields },
                stringResource(R.string.maps_layer_line_opacity_was, percentOf(layer.opacity), percentOf(was.opacity))
                    .takeIf { LayerField.OPACITY in change.fields },
            ).joinToString(" · ").ifEmpty { usual }
        }

        else -> {
            usual
        }
    }
}

/** « Détails » of a layer: its kind, zooms, origin, rights, data source, what is stored and the forced update. */
@Composable
internal fun DetailsDialog(
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

/** Opacity shown in a layer line only below this (a plain opaque layer says nothing about it). */
private const val FULL_PERCENT = 100
