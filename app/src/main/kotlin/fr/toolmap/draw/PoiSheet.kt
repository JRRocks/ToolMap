package fr.toolmap.draw

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.route.RoutePoi
import fr.toolmap.core.track.WaypointSymbols
import fr.toolmap.map.PanelBand
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.waypoints.WaypointIconBadge
import fr.toolmap.waypoints.WaypointIconPicker

/** The eight icons of « Quel point ? » (P30.1): the GPX symbol and the name it proposes. */
private val QUICK =
    listOf(
        "Spring" to R.string.route_poi_q_spring,
        "Alpinehut" to R.string.route_poi_q_hut,
        "View point" to R.string.route_poi_q_view,
        "Parking" to R.string.route_poi_q_parking,
        "Danger area" to R.string.route_poi_q_danger,
        "Peak" to R.string.route_poi_q_peak,
        "Picture" to R.string.route_poi_q_photo,
    )

private const val QUICK_PER_ROW = 4

/**
 * « Quel point ? » (P30.1, variant B of `docs/previews/p30-itineraires.html`): seven common icons and « Autre… » (the
 * whole grid of the waypoints), the name under them (a touch on an icon proposes its name while the user has typed
 * none), « Annuler » and « Ajouter ». For a point of interest already placed ([poi]), « OK », with « Déplacer » and
 * « Supprimer le point ».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoiSheet(
    poi: RoutePoi?,
    onDone: (name: String, sym: String?) -> Unit,
    onDismiss: () -> Unit,
    onMove: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val labels = QUICK.associate { (sym, label) -> sym to stringResource(label) }
    var sym by rememberSaveable { mutableStateOf(poi?.sym) }
    var name by rememberSaveable { mutableStateOf(poi?.name.orEmpty()) }
    // The name the last icon proposed: replaced by the next icon's while the user has not typed another one.
    var proposed by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val choose = { key: String, label: String? ->
        sym = key
        if (label != null && (name.isBlank() || name == proposed)) {
            name = label
            proposed = label
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            PanelBand(title = stringResource(R.string.route_poi_sheet_title)) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                }
            }
            // The seven icons, then « Autre… » (`null`), four to a row.
            val keys: List<String?> = QUICK.map { it.first } + null
            val other = sym != null && QUICK.none { (key, _) -> same(key, sym) }
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                keys.chunked(QUICK_PER_ROW).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { key ->
                            if (key != null) {
                                val label = labels.getValue(key)
                                QuickTile(
                                    label = label,
                                    selected = same(key, sym),
                                    modifier = Modifier.weight(1f),
                                    onClick = { choose(key, label) },
                                ) { WaypointIconBadge(WaypointSymbols.iconOf(key), ICON_SIZE) }
                            } else {
                                QuickTile(
                                    label = stringResource(R.string.route_poi_q_other),
                                    selected = other,
                                    modifier = Modifier.weight(1f),
                                    onClick = { picking = true },
                                ) {
                                    if (other) {
                                        WaypointIconBadge(WaypointSymbols.iconOf(sym), ICON_SIZE)
                                    } else {
                                        // The three dots across: the core icons have them only upright.
                                        Icon(
                                            Icons.Filled.MoreVert,
                                            contentDescription = null,
                                            modifier = Modifier.size(ICON_SIZE).rotate(90f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.route_poi_name)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.route_poi_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (onMove != null) {
                    TextButton(onClick = onMove) { Text(stringResource(R.string.route_poi_move)) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Button(onClick = { onDone(name, sym) }) {
                    Text(stringResource(if (poi == null) R.string.route_poi_add_confirm else R.string.action_ok))
                }
            }
        }
    }
    if (picking) {
        WaypointIconPicker(
            selectedId = WaypointSymbols.iconOf(sym).id,
            onSelect = {
                picking = false
                choose(it.key, null)
            },
            onDismiss = { picking = false },
        )
    }
}

/** Two symbol names for the same icon (files spell them differently). */
private fun same(
    a: String,
    b: String?,
): Boolean = b != null && WaypointSymbols.canonical(a) == WaypointSymbols.canonical(b)

/** One of the icons of « Quel point ? »: the icon over its name, the chosen one highlighted. */
@Composable
private fun QuickTile(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerHigh)
            .semantics { this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        icon()
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            color = if (selected) colors.onSecondaryContainer else colors.onSurface,
        )
    }
}

private val ICON_SIZE = 28.dp
