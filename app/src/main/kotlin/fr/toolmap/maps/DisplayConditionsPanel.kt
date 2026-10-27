package fr.toolmap.maps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.layers.ElevationBar
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.layers.LayerElevation
import fr.toolmap.core.layers.RangeLogic
import fr.toolmap.core.layers.ZoomBar
import fr.toolmap.layers.RangeBar
import fr.toolmap.map.PanelBand
import fr.toolmap.ui.theme.toolMap
import kotlin.math.roundToInt

private val LOGIC_WIDTH = 168.dp
private val LABEL_WIDTH = 72.dp

/**
 * « Conditions d'affichage » of a layer (P20, variant A validated on 2026-10-23), compact since P29.3 (« Strates »): a
 * panel at the bottom of the map, which stays visible and usable above it, under the petrol band (its ✕ and Back come
 * back to the maps panel, the line of the layer still unfolded). The zoom range and the elevation band are bars in line
 * with their label; ET / OU and its sentence show only when both are set. A bar applies its range when the finger lifts
 * (the map is redrawn then); the choice ET / OU and the resets apply at once.
 */
@Composable
fun DisplayConditionsPanel(
    layer: LayerConfig,
    name: String,
    currentZoom: Double,
    /** Ground elevation at the centre of the map, marked on the elevation bar; `null` while unknown. */
    centerElevation: Double?,
    onZoomRange: (min: Int?, max: Int?) -> Unit,
    onElevationRange: (min: Int?, max: Int?, logic: RangeLogic) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // What the bars show while a finger moves them; the layer itself changes when it lifts.
    var zoomDraft by remember(layer.displayMinZoom, layer.displayMaxZoom) {
        mutableStateOf(ZoomBar.rangeOf(layer.displayMinZoom, layer.displayMaxZoom))
    }
    var bandDraft by remember(layer.displayMinElevation, layer.displayMaxElevation) {
        mutableStateOf(ElevationBar.rangeOf(layer.displayMinElevation, layer.displayMaxElevation))
    }
    val (zoomMin, zoomMax) = ZoomBar.toBounds(zoomDraft)
    val (bandMin, bandMax) = ElevationBar.toBounds(bandDraft)
    val draft =
        layer.copy(
            displayMinZoom = zoomMin,
            displayMaxZoom = zoomMax,
            displayMinElevation = bandMin,
            displayMaxElevation = bandMax,
        )
    val drawnNow = LayerElevation.isDrawnAt(draft, currentZoom)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        color = MaterialTheme.toolMap.panel,
        shadowElevation = 8.dp,
    ) {
        Column {
            PanelBand(title = stringResource(R.string.maps_layer_conditions), subtitle = name) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 8.dp)) {
                val zoomSummary = zoomSummary(zoomMin, zoomMax)
                BarRow(
                    label = stringResource(R.string.cond_section_zoom),
                    value = zoomShort(zoomMin, zoomMax),
                    onReset = { onZoomRange(null, null) }.takeIf { layer.hasZoomRange },
                ) {
                    RangeBar(
                        range = zoomDraft,
                        max = ZoomBar.MAX,
                        cursor = currentZoom,
                        activeNow = drawnNow,
                        description = stringResource(R.string.layers_display_bar),
                        stateText = zoomSummary,
                        onChange = { zoomDraft = it },
                        onRelease = {
                            val (low, high) = ZoomBar.toBounds(zoomDraft)
                            if (low != layer.displayMinZoom || high != layer.displayMaxZoom) onZoomRange(low, high)
                        },
                    )
                }
                val bandSummary = bandSummary(bandMin, bandMax)
                BarRow(
                    label = stringResource(R.string.cond_section_elevation),
                    value = bandShort(bandMin, bandMax),
                    onReset = { onElevationRange(null, null, layer.rangeLogic) }.takeIf { layer.hasElevationRange },
                ) {
                    RangeBar(
                        range = bandDraft,
                        max = ElevationBar.STEPS,
                        cursor = centerElevation?.let(ElevationBar::positionOf),
                        activeNow = drawnNow,
                        description = stringResource(R.string.cond_elevation_bar),
                        stateText = bandSummary,
                        onChange = { bandDraft = it },
                        onRelease = {
                            val (low, high) = ElevationBar.toBounds(bandDraft)
                            if (low != layer.displayMinElevation || high != layer.displayMaxElevation) {
                                onElevationRange(low, high, layer.rangeLogic)
                            }
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = LABEL_WIDTH + 20.dp, end = 52.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.cond_alt_start),
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                    )
                    if (centerElevation != null) {
                        Text(
                            stringResource(R.string.cond_alt_center, centerElevation.roundToInt()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(
                        stringResource(R.string.cond_alt_end, ElevationBar.MAX_METERS),
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                    )
                }
                // ET / OU changes the drawing only when both a zoom range and an elevation band are set.
                if (layer.hasZoomRange && layer.hasElevationRange) {
                    LogicChoice(
                        selected = layer.rangeLogic,
                        onSelect = { onElevationRange(layer.displayMinElevation, layer.displayMaxElevation, it) },
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 4.dp),
                    )
                    Text(
                        explanation(draft),
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

/** A condition in one line: its name and value on the left, its bar, « Réinitialiser » as an icon at the end. */
@Composable
private fun BarRow(
    label: String,
    value: String,
    onReset: (() -> Unit)?,
    bar: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(LABEL_WIDTH)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        Box(Modifier.weight(1f).padding(start = 4.dp)) { bar() }
        if (onReset != null) {
            IconButton(onClick = onReset) {
                Icon(
                    MapsIcons.RestartAlt,
                    contentDescription = stringResource(R.string.cond_reset),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            Spacer(Modifier.size(48.dp))
        }
    }
}

/** « 12 à 17 », « tous »: the zoom range in the label column. */
@Composable
private fun zoomShort(
    min: Int?,
    max: Int?,
): String =
    when {
        min == null && max == null -> stringResource(R.string.cond_short_zoom_all)
        max == null -> stringResource(R.string.cond_short_zoom_from, min ?: 0)
        min == null -> stringResource(R.string.cond_short_zoom_until, max)
        min == max -> stringResource(R.string.cond_short_zoom_single, min)
        else -> stringResource(R.string.cond_short_zoom_between, min, max)
    }

/** « toutes », « dès 1500 m »: the elevation band in the label column. */
@Composable
private fun bandShort(
    min: Int?,
    max: Int?,
): String =
    when {
        min == null && max == null -> stringResource(R.string.cond_short_alt_all)
        max == null -> stringResource(R.string.cond_short_alt_above, min ?: 0)
        min == null -> stringResource(R.string.cond_short_alt_below, max)
        else -> stringResource(R.string.cond_short_alt_between, min, max)
    }

@Composable
private fun LogicChoice(
    selected: RangeLogic,
    onSelect: (RangeLogic) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = listOf(RangeLogic.AND to R.string.cond_and, RangeLogic.OR to R.string.cond_or)
    SingleChoiceSegmentedButtonRow(modifier.width(LOGIC_WIDTH)) {
        labels.forEachIndexed { index, (logic, label) ->
            SegmentedButton(
                selected = logic == selected,
                onClick = { onSelect(logic) },
                shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                icon = {},
                label = { Text(stringResource(label)) },
            )
        }
    }
}

/** "Visible at zooms 12 to 17" for a layer display range (`null` = unbounded on that side). */
@Composable
fun zoomRangeText(
    min: Int?,
    max: Int?,
): String =
    when {
        min == null && max == null -> stringResource(R.string.layers_display_all)
        max == null -> stringResource(R.string.layers_display_from, min ?: 0)
        min == null -> stringResource(R.string.layers_display_until, max)
        min == max -> stringResource(R.string.layers_display_single, min)
        else -> stringResource(R.string.layers_display_between, min, max)
    }

/** "Zooms 8 à 16": the zoom range as the panel says it. */
@Composable
private fun zoomSummary(
    min: Int?,
    max: Int?,
): String =
    when {
        min == null && max == null -> stringResource(R.string.cond_zoom_all)
        max == null -> stringResource(R.string.cond_zoom_from, min ?: 0)
        min == null -> stringResource(R.string.cond_zoom_until, max)
        min == max -> stringResource(R.string.cond_zoom_single, min)
        else -> stringResource(R.string.cond_zoom_between, min, max)
    }

/** "Au-dessus de 1500 m": the elevation band as the panel says it. */
@Composable
private fun bandSummary(
    min: Int?,
    max: Int?,
): String =
    when {
        min == null && max == null -> stringResource(R.string.cond_alt_all)
        max == null -> stringResource(R.string.cond_alt_above, min ?: 0)
        min == null -> stringResource(R.string.cond_alt_below, max)
        else -> stringResource(R.string.cond_alt_between, min, max)
    }

/** "au-dessus de 1500 m", inside a sentence; the band is set. */
@Composable
private fun bandPhrase(
    min: Int?,
    max: Int?,
): String =
    when {
        max == null -> stringResource(R.string.cond_band_above, min ?: 0)
        min == null -> stringResource(R.string.cond_band_below, max)
        else -> stringResource(R.string.cond_band_between, min, max)
    }

/** The sentence under ET / OU saying where the layer is drawn, whole or in its band. */
@Composable
private fun explanation(layer: LayerConfig): String {
    val zoomMin = layer.displayMinZoom
    val zoomMax = layer.displayMaxZoom
    if (!layer.hasElevationRange) {
        return stringResource(
            R.string.cond_explain_zoom_only,
            zoomRangeText(zoomMin, zoomMax),
        )
    }
    val band = bandPhrase(layer.displayMinElevation, layer.displayMaxElevation)
    return when {
        layer.rangeLogic == RangeLogic.AND -> {
            stringResource(R.string.cond_explain_and, zoomRangeText(zoomMin, zoomMax), band)
        }

        !layer.hasZoomRange -> {
            stringResource(R.string.cond_explain_or_no_zoom)
        }

        else -> {
            val zooms =
                when {
                    zoomMax == null -> stringResource(R.string.cond_at_from, zoomMin ?: 0)
                    zoomMin == null -> stringResource(R.string.cond_at_until, zoomMax)
                    zoomMin == zoomMax -> stringResource(R.string.cond_at_single, zoomMin)
                    else -> stringResource(R.string.cond_at_between, zoomMin, zoomMax)
                }
            stringResource(R.string.cond_explain_or, zooms, band)
        }
    }
}

/**
 * The conditions part of a layer line in « Cartes affichées »: "Au-dessus de 1500 m · zooms 8 à 16" (ET) or
 * "Au-dessus de 1500 m ou zooms 8 à 16" (OU); `null` for a layer without elevation band (its line is unchanged).
 */
@Composable
fun conditionsLineText(layer: LayerConfig): String? {
    if (!layer.hasElevationRange) return null
    val band = bandSummary(layer.displayMinElevation, layer.displayMaxElevation)
    if (!layer.hasZoomRange) return band
    val min = layer.displayMinZoom
    val max = layer.displayMaxZoom
    val zooms =
        when {
            max == null -> stringResource(R.string.cond_line_zoom_from, min ?: 0)
            min == null -> stringResource(R.string.cond_line_zoom_until, max)
            min == max -> stringResource(R.string.cond_line_zoom_single, min)
            else -> stringResource(R.string.cond_line_zoom_between, min, max)
        }
    return if (layer.rangeLogic ==
        RangeLogic.OR
    ) {
        stringResource(R.string.cond_line_or, band, zooms)
    } else {
        "$band · $zooms"
    }
}
