package fr.toolmap.maps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.layers.LayerElevation
import fr.toolmap.core.layers.LayerZoom
import fr.toolmap.map.MenuRow
import fr.toolmap.map.PanelBand
import fr.toolmap.map.PanelChevron
import fr.toolmap.settings.SettingsIcons
import fr.toolmap.ui.theme.DropdownMenu
import fr.toolmap.ui.theme.Switch
import fr.toolmap.ui.theme.toolMap

/*
 * The sheet of a layer in « Cartes et calques » (P34, style 4 « Fiche du calque », docs/previews/p34-cartes-onglets.html,
 * band 4, validated on 2026-11-04): a touch on a line of the list opens it in the panel, ← goes back to the list. Its
 * band names the map and its place in the stack; then what the map does with it at its zoom and the display switch,
 * its opacity, and one row each for its conditions, its colours, its order and the rest of its commands.
 */

/** The band of the sheet: ←, the name of the map, « Rando Hiver · 1re carte sur 5 », ✕ that closes the panel. */
@Composable
internal fun LayerSheetBand(
    name: String,
    favorite: String?,
    rank: Int,
    count: Int,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val position = stringResource(R.string.maps_sheet_position, rankText(rank), count)
    BackBand(name, favorite?.let { "$it · $position" } ?: position, onBack, onClose)
}

/** The band of a page of the panel (the sheet of a map, « Hors ligne »): ←, [title], [subtitle], ✕. */
@Composable
internal fun BackBand(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    PanelBand(
        title = title,
        subtitle = subtitle,
        leading = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.toolMap.onShell,
                )
            }
        },
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
        }
    }
}

/** What the sheet holds under its band; [rank] is the place of [layer] from the top, 1 to [count]. */
@Composable
internal fun LayerSheet(
    layer: LayerConfig,
    name: String,
    rank: Int,
    count: Int,
    thumbnails: MapThumbnails,
    currentZoom: Double,
    fade: PanelFade,
    onVisible: (Boolean) -> Unit,
    onOpacity: (Float) -> Unit,
    onTypeOpacity: () -> Unit,
    onConditions: () -> Unit,
    onColors: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    more: MoreActions,
) {
    val rest = Modifier.alpha(fade.shown)
    StateBlock(layer, name, thumbnails, currentZoom, onVisible, rest)
    HorizontalDivider(rest, color = MaterialTheme.colorScheme.outlineVariant)
    Box(Modifier.padding(vertical = 6.dp)) {
        AdjustSlider(
            key = "opacity",
            label = stringResource(R.string.layers_opacity_name),
            valuePill = "${percentOf(layer.opacity)} %",
            onType = onTypeOpacity,
            value = layer.opacity,
            range = 0f..1f,
            fade = fade,
            onChange = onOpacity,
            onPage = true,
        )
    }
    HorizontalDivider(rest, color = MaterialTheme.colorScheme.outlineVariant)
    Column(rest) {
        MenuRow(
            label = stringResource(R.string.maps_layer_conditions),
            icon = MapsIcons.Tune,
            subtitle = conditionsSummary(layer),
            trailing = { PanelChevron() },
            onClick = onConditions,
        )
        RowDivider()
        MenuRow(
            label = stringResource(R.string.maps_cmd_colors),
            icon = SettingsIcons.Palette,
            subtitle = colorsSummary(layer),
            trailing = { PanelChevron() },
            onClick = onColors,
        )
        RowDivider()
        OrderRow(rank, count, onUp, onDown)
        RowDivider()
        MoreRow(more)
    }
}

/** What « Plus » offers on the sheet of a layer, and whether its data are being renewed this session ([forced]). */
internal class MoreActions(
    val forced: Boolean,
    val onDetails: () -> Unit,
    val onForce: () -> Unit,
    val onRemoveOthers: () -> Unit,
    val onRemove: () -> Unit,
)

/**
 * « Plus », and the menu it opens over the sheet (choice F of docs/previews/p34-complements.html, validated on
 * 2026-11-05): Détails, Forcer la mise à jour; then Ne plus afficher les autres, and Ne plus afficher in red, last.
 */
@Composable
private fun MoreRow(more: MoreActions) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        MenuRow(
            label = stringResource(R.string.maps_cmd_more),
            icon = Icons.Filled.MoreVert,
            subtitle = stringResource(R.string.maps_more_summary),
            trailing = { PanelChevron() },
            onClick = { open = true },
        )
        // Anchored at the top right of the row: the menu holds to the right edge of the sheet, over the rows above.
        Box(Modifier.align(Alignment.TopEnd)) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                fun choose(action: () -> Unit) {
                    open = false
                    action()
                }
                MoreItem(MapsIcons.InfoOutline, stringResource(R.string.maps_layer_details)) {
                    choose(more.onDetails)
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.maps_layer_force_update_short)) },
                    leadingIcon = { Icon(MapsIcons.Sync, contentDescription = null) },
                    trailingIcon = { if (more.forced) Icon(Icons.Filled.Check, contentDescription = null) },
                    onClick = { choose(more.onForce) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MoreItem(MapsIcons.VisibilityOff, stringResource(R.string.maps_layer_undisplay_others)) {
                    choose(more.onRemoveOthers)
                }
                MoreItem(
                    MapsIcons.Delete,
                    stringResource(R.string.maps_layer_undisplay),
                    color = MaterialTheme.colorScheme.error,
                ) { choose(more.onRemove) }
            }
        }
    }
}

@Composable
private fun MoreItem(
    icon: ImageVector,
    label: String,
    color: Color? = null,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label, color = color ?: Color.Unspecified) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = color ?: LocalContentColor.current) },
        onClick = onClick,
    )
}

/**
 * What the map does with the layer at its zoom (« Inactif au zoom 12 », « Visible à partir du zoom 13 »), after its
 * picture; the whole block is the display switch.
 */
@Composable
private fun StateBlock(
    layer: LayerConfig,
    name: String,
    thumbnails: MapThumbnails,
    currentZoom: Double,
    onVisible: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val zoom = LayerZoom.shownZoom(currentZoom)
    val title =
        when {
            !layer.visible -> stringResource(R.string.maps_sheet_hidden)
            LayerElevation.isDrawnAt(layer, currentZoom) -> stringResource(R.string.maps_sheet_active, zoom)
            else -> stringResource(R.string.maps_sheet_inactive, zoom)
        }
    val subtitle =
        if (layer.visible) {
            conditionsLineText(layer) ?: zoomRangeText(layer.displayMinZoom, layer.displayMaxZoom)
        } else {
            stringResource(R.string.maps_sheet_hidden_sub)
        }
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = layer.visible, role = Role.Switch, onValueChange = onVisible)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MapThumbnail(layer.sourceId, name, thumbnails, size = 56.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = layer.visible, onCheckedChange = null)
    }
}

/** « Ordre », « 1re sur 5 », and the two arrows that move the layer (disabled at the top and at the bottom). */
@Composable
private fun OrderRow(
    rank: Int,
    count: Int,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterStart) {
            Icon(MapsIcons.ArrowUpward, contentDescription = null)
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(stringResource(R.string.maps_order), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.maps_order_rank, rankText(rank), count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val tint = MaterialTheme.colorScheme.onSurfaceVariant
        IconButton(onClick = onUp, enabled = rank > 1) {
            Icon(MapsIcons.ArrowUpward, contentDescription = stringResource(R.string.layers_move_up), tint = tint)
        }
        IconButton(onClick = onDown, enabled = rank < count) {
            Icon(MapsIcons.ArrowDownward, contentDescription = stringResource(R.string.layers_move_down), tint = tint)
        }
    }
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** « 1re », « 2e »: the place of a map from the top of the stack. */
@Composable
private fun rankText(rank: Int): String =
    if (rank == 1) stringResource(R.string.maps_rank_first) else stringResource(R.string.maps_rank, rank)

/** « À partir du zoom 13 · toutes altitudes », « Au-dessus de 1500 m · zooms 8 à 16 ». */
@Composable
internal fun conditionsSummary(layer: LayerConfig): String {
    val band = conditionsLineText(layer)
    return when {
        band == null -> {
            zoomSummary(layer.displayMinZoom, layer.displayMaxZoom) + " · " +
                stringResource(R.string.maps_cond_any_altitude)
        }

        layer.hasZoomRange -> {
            band
        }

        else -> {
            "$band · " + zoomLineText(null, null)
        }
    }
}

/** « Normal · couleurs d'origine », « Produit · teinté · couleurs retouchées ». */
@Composable
internal fun colorsSummary(layer: LayerConfig): String {
    val adjusted = layer.contrast != 0f || layer.brightness != 0f || layer.saturation != 0f || layer.hue != 0f
    val parts =
        listOfNotNull(
            stringResource(R.string.maps_layer_tinted).takeIf { layer.tint != null },
            stringResource(R.string.maps_colors_inverted).takeIf { layer.inverted },
            stringResource(R.string.maps_colors_adjusted).takeIf { adjusted },
        ).ifEmpty { listOf(stringResource(R.string.maps_colors_original)) }
    return (listOf(stringResource(layer.blend.label())) + parts).joinToString(" · ")
}
