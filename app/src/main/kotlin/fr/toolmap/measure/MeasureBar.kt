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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.download.ZoneShape
import fr.toolmap.core.geo.Navigation
import fr.toolmap.core.measure.MeasureMode
import fr.toolmap.core.measure.MeasureResult
import fr.toolmap.core.settings.BearingFormat
import fr.toolmap.core.track.NameDates
import fr.toolmap.data.tracks.ItemKind
import fr.toolmap.maps.MapsIcons
import fr.toolmap.tracks.RenameDialog
import fr.toolmap.ui.Formats
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.FilledTonalButton
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.TextButton

/**
 * The controls of the measure tool: the figures for the points placed so far, mode (path or area), undo / redo,
 * add at the crosshair, and keeping the line as a route or a track. Taps on the map are handled by the map screen.
 */
@Composable
fun MeasureBar(
    ui: MeasureUi,
    vm: MeasureViewModel,
    center: () -> Pair<Double, Double>,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
    /** « Télécharger cette zone… » (F10): the download form with the surface measured. */
    onDownloadArea: (ZoneShape) -> Unit = {},
) {
    val fmt = LocalFormats.current
    var askName by remember { mutableStateOf<ItemKind?>(null) }
    var saveOpen by remember { mutableStateOf(false) }
    // Back closes the tool as « Quitter » does, instead of leaving the app.
    BackHandler(enabled = backEnabled, onBack = vm::close)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.navigationBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.measure_title), style = MaterialTheme.typography.titleSmall)
            MeasureFigures(ui.result, ui.mode, fmt)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = ui.mode == MeasureMode.PATH,
                    onClick = { vm.setMode(MeasureMode.PATH) },
                    label = { Text(stringResource(R.string.measure_mode_path)) },
                )
                FilterChip(
                    selected = ui.mode == MeasureMode.AREA,
                    onClick = { vm.setMode(MeasureMode.AREA) },
                    label = { Text(stringResource(R.string.measure_mode_area)) },
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedButton(onClick = vm::undo, enabled = ui.canUndo) { Text(stringResource(R.string.draw_undo)) }
                OutlinedButton(onClick = vm::redo, enabled = ui.canRedo) { Text(stringResource(R.string.draw_redo)) }
                Button(onClick = {
                    val (lat, lon) = center()
                    vm.addAt(lat, lon)
                }) { Text(stringResource(R.string.draw_add_here)) }
                OutlinedButton(onClick = vm::clear, enabled = ui.result.pointCount > 0) {
                    Text(stringResource(R.string.draw_tool_clear))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = vm::close) { Text(stringResource(R.string.draw_quit)) }
                Box {
                    OutlinedButton(onClick = { saveOpen = true }, enabled = ui.result.pointCount >= 2) {
                        Text(stringResource(R.string.measure_keep))
                    }
                    DropdownMenu(expanded = saveOpen, onDismissRequest = { saveOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.measure_keep_route)) },
                            onClick = {
                                saveOpen = false
                                askName = ItemKind.ROUTE
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.measure_keep_track)) },
                            onClick = {
                                saveOpen = false
                                askName = ItemKind.TRACK
                            },
                        )
                    }
                }
                // Only in « Surface » mode, from three points (F10).
                if (ui.mode == MeasureMode.AREA && ui.result.pointCount >= MIN_AREA_POINTS) {
                    FilledTonalButton(onClick = { vm.areaZone()?.let(onDownloadArea) }) {
                        Icon(MapsIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.measure_download_area), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
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
        Text(
            stringResource(R.string.measure_points, result.pointCount),
            style = MaterialTheme.typography.bodySmall,
        )
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
