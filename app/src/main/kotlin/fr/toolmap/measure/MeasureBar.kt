package fr.toolmap.measure

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.download.ZoneShape
import fr.toolmap.core.geo.Navigation
import fr.toolmap.core.measure.MeasureMode
import fr.toolmap.core.measure.MeasureResult
import fr.toolmap.core.settings.BearingFormat
import fr.toolmap.core.track.NameDates
import fr.toolmap.data.tracks.ItemKind
import fr.toolmap.draw.RouteIcons
import fr.toolmap.map.ActionTile
import fr.toolmap.map.ActionTiles
import fr.toolmap.map.PANEL_INSET
import fr.toolmap.map.TextMenu
import fr.toolmap.map.ToolFooter
import fr.toolmap.map.ToolPanel
import fr.toolmap.maps.MapsIcons
import fr.toolmap.tracks.RenameDialog
import fr.toolmap.ui.Formats
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.DropdownMenu
import fr.toolmap.ui.theme.FilledTonalButton
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.TextButton

/**
 * The panel of the measure tool (P29.10, « Équerre »): a panel of the bar, floating above it, with the petrol band
 * « Mesure » and its figures (« 3 points · 2,35 km »), ✕ to leave; « Distance | Surface », the figures, the actions at
 * the crosshair as tiles, then undo / redo and « Garder… » (as a route or a track). Taps on the map are handled by the
 * map screen.
 */
@Composable
fun MeasureBar(
    ui: MeasureUi,
    vm: MeasureViewModel,
    center: () -> Pair<Double, Double>,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
    /** « Télécharger cette zone… » (F10): the download form with the surface measured. */
    onDownloadArea: (ZoneShape) -> Unit = {},
) {
    val fmt = LocalFormats.current
    var askName by remember { mutableStateOf<ItemKind?>(null) }
    // Back closes the tool as ✕ does, instead of leaving the app.
    BackHandler(enabled = backEnabled, onBack = vm::close)
    val points = ui.result.pointCount
    ToolPanel(
        title = stringResource(R.string.measure_title),
        maxHeight = maxHeight,
        modifier = modifier,
        subtitle =
            if (points > 0) {
                pluralStringResource(R.plurals.measure_band, points, points, fmt.distance(ui.result.lengthMeters))
            } else {
                null
            },
        onClose = vm::close,
        closeDescription = stringResource(R.string.draw_quit),
    ) {
        val modes =
            listOf(
                MeasureMode.PATH to R.string.measure_mode_path,
                MeasureMode.AREA to R.string.measure_mode_area,
            )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = PANEL_INSET, vertical = 4.dp)) {
            modes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = ui.mode == mode,
                    onClick = { vm.setMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    icon = {},
                    label = { Text(stringResource(label)) },
                )
            }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { MeasureFigures(ui.result, ui.mode, fmt) }
        ActionTiles {
            ActionTile(RouteIcons.AddLocationAlt, stringResource(R.string.draw_add_here), highlighted = true) {
                val (lat, lon) = center()
                vm.addAt(lat, lon)
            }
            ActionTile(
                RouteIcons.DeleteSweep,
                stringResource(R.string.draw_tool_clear),
                points > 0,
                onClick = vm::clear,
            )
            // Only in « Surface » mode, from three points (F10).
            if (ui.mode == MeasureMode.AREA && points >= MIN_AREA_POINTS) {
                ActionTile(MapsIcons.Download, stringResource(R.string.measure_download_area)) {
                    vm.areaZone()?.let(onDownloadArea)
                }
            }
        }
        ToolFooter(
            onUndo = vm::undo,
            canUndo = ui.canUndo,
            onRedo = vm::redo,
            canRedo = ui.canRedo,
            menu = {
                TextMenu(stringResource(R.string.measure_keep), enabled = points >= 2) { close ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.measure_keep_route)) },
                        onClick = {
                            close()
                            askName = ItemKind.ROUTE
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.measure_keep_track)) },
                        onClick = {
                            close()
                            askName = ItemKind.TRACK
                        },
                    )
                }
            },
        )
    }

    askName?.let { kind ->
        val date = NameDates.format(System.currentTimeMillis())
        RenameDialog(
            title =
                stringResource(
                    if (kind == ItemKind.ROUTE) R.string.draw_name_route else R.string.draw_name_track,
                ),
            initial = stringResource(R.string.measure_default_name, date),
            onConfirm = { name ->
                askName = null
                vm.save(kind, name)
            },
            onDismiss = { askName = null },
        )
    }
}

@Composable
private fun MeasureFigures(
    result: MeasureResult,
    mode: MeasureMode,
    fmt: Formats,
) {
    if (result.pointCount == 0) {
        Text(stringResource(R.string.measure_hint), style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // The number of points is in the band of the panel since P29.10.
        if (result.pointCount >= 2) {
            Text(
                stringResource(R.string.measure_length, fmt.distance(result.lengthMeters)),
                style = MaterialTheme.typography.titleMedium,
            )
            val lastMeters = result.lastSegmentMeters
            val lastBearing = result.lastBearingDegrees
            if (lastMeters != null && lastBearing != null && result.pointCount > 2) {
                Text(
                    stringResource(R.string.measure_last, fmt.distance(lastMeters), bearingText(lastBearing, fmt)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (lastBearing != null) {
                Text(
                    stringResource(R.string.measure_bearing, bearingText(lastBearing, fmt)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val direct = result.directMeters
            val directBearing = result.directBearingDegrees
            if (direct != null && directBearing != null && result.pointCount > 2) {
                Text(
                    stringResource(R.string.measure_direct, fmt.distance(direct), bearingText(directBearing, fmt)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        val area = result.areaSquareMeters
        val perimeter = result.perimeterMeters
        if (mode == MeasureMode.AREA && area != null && perimeter != null) {
            Text(
                stringResource(R.string.measure_area, fmt.area(area)),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.measure_perimeter, fmt.distance(perimeter)),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (mode == MeasureMode.AREA) {
            Text(stringResource(R.string.measure_area_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A bearing in the unit of « Bearing unit », with its point of the compass (N, NE…). */
private fun bearingText(
    degrees: Double,
    fmt: Formats,
): String {
    val normalised = BearingFormat.normalize(degrees)
    return "${fmt.bearing(normalised)} ${Navigation.compassPoint(normalised)}"
}
