package fr.toolmap.print

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.print.Dressing
import fr.toolmap.core.print.GridKind
import fr.toolmap.core.print.Paper
import fr.toolmap.core.print.PrintFormat
import fr.toolmap.core.print.PrintIssue
import fr.toolmap.core.print.PrintPlan
import fr.toolmap.core.print.PrintTemplate
import fr.toolmap.core.print.PrintTexts
import fr.toolmap.core.print.PrintWarning
import fr.toolmap.download.formatBytes
import fr.toolmap.map.ToolPanel
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.Switch
import fr.toolmap.ui.theme.TextButton
import kotlin.math.roundToInt

/** Everything the print dialogs can ask for; the screen wires each one to the [PrintViewModel]. */
class PrintActions(
    val onUpdate: ((PrintTemplate) -> PrintTemplate) -> Unit,
    val onRotate: () -> Unit,
    val onChooseZone: () -> Unit,
    val onNext: () -> Unit,
    val onBack: () -> Unit,
    val onToggleMore: () -> Unit,
    val onClose: () -> Unit,
    val onDownload: () -> Unit,
    val onExport: () -> Unit,
    val onCancelJob: () -> Unit,
)

// --- step 1: the two questions

/** « Imprimer »: the size of the sheet and the scale, then « Choisir la zone » (P17, Q1). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrintSetupDialog(
    ui: PrintUi,
    actions: PrintActions,
) {
    val template = ui.template
    val page = template.page
    var custom by remember { mutableStateOf(false) }
    var customScale by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = actions.onClose,
        title = { Text(stringResource(R.string.print_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionTitle(R.string.print_section_paper)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (paper in Paper.OFFERED) {
                        FilterChip(
                            selected = page.paper == paper,
                            onClick = {
                                if (paper == Paper.CUSTOM) {
                                    custom = true
                                } else {
                                    actions.onUpdate { it.copy(page = it.page.copy(paper = paper)) }
                                }
                            },
                            label = {
                                val custom = paper == Paper.CUSTOM && page.paper == paper
                                Text(if (custom) paperName(page) else paperChoiceName(paper))
                            },
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (landscape in listOf(false, true)) {
                        FilterChip(
                            selected = page.landscape == landscape,
                            onClick = { actions.onUpdate { it.copy(page = it.page.copy(landscape = landscape)) } },
                            label = {
                                Text(orientationName(landscape))
                            },
                        )
                    }
                }
                SectionTitle(R.string.print_section_scale)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (scale in PrintTemplate.SCALES) {
                        FilterChip(
                            selected = template.scale == scale.toDouble(),
                            onClick = { actions.onUpdate { it.copy(scale = scale.toDouble()) } },
                            label = { Text(PrintFormat.scale(scale.toDouble(), PrintTexts())) },
                        )
                    }
                    val other = template.scale.roundToInt() !in PrintTemplate.SCALES
                    FilterChip(
                        selected = other,
                        onClick = { customScale = true },
                        label = {
                            Text(
                                if (other) {
                                    PrintFormat.scale(template.scale, PrintTexts())
                                } else {
                                    stringResource(R.string.print_scale_other)
                                },
                            )
                        },
                    )
                }
                Text(summaryLine(template), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = actions.onChooseZone) { Text(stringResource(R.string.print_choose_zone)) } },
        dismissButton = { TextButton(onClick = actions.onClose) { Text(stringResource(R.string.print_cancel)) } },
    )
    if (custom) {
        CustomPaperDialog(
            setup = page,
            onDone = { w, h ->
                custom = false
                actions.onUpdate {
                    it.copy(page = it.page.copy(paper = Paper.CUSTOM, customWidthMm = w, customHeightMm = h))
                }
            },
            onDismiss = { custom = false },
        )
    }
    if (customScale) {
        CustomScaleDialog(
            scale = template.scale,
            onDone = { s ->
                customScale = false
                actions.onUpdate { it.copy(scale = s) }
            },
            onDismiss = { customScale = false },
        )
    }
}

// --- step 3: the confirmation

/**
 * The grid, waypoints, tracks and title; the rest under « Plus d'options » (P17, Q7). A tool panel since P29.10, above
 * the bar come back, with ✕ « Fermer » in its band; the actions stay at its foot while the options scroll.
 */
@Composable
fun PrintConfirmSheet(
    ui: PrintUi,
    actions: PrintActions,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val template = ui.template
    ToolPanel(
        title = stringResource(R.string.print_title),
        maxHeight = maxHeight,
        modifier = modifier,
        onClose = actions.onClose,
        closeDescription = stringResource(R.string.print_close),
        footer = { ConfirmActions(ui, actions) },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(summaryLine(template), style = MaterialTheme.typography.bodyMedium)
            GridChoice(template, actions)
            SwitchRow(R.string.print_dress_waypoints, template.dressing.waypoints) { v ->
                actions.onUpdate { it.withDressing { copy(waypoints = v) } }
            }
            SwitchRow(R.string.print_dress_tracks, template.dressing.tracks) { v ->
                actions.onUpdate { it.withDressing { copy(tracks = v) } }
            }
            OutlinedTextField(
                value = template.title,
                onValueChange = { v -> actions.onUpdate { it.copy(title = v) } },
                label = { Text(stringResource(R.string.print_field_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PlanLines(ui)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = actions.onToggleMore)
                        .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.print_more_options),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (ui.moreOptions) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                )
            }
            if (ui.moreOptions) MoreOptions(template, actions)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GridChoice(
    template: PrintTemplate,
    actions: PrintActions,
) {
    SectionTitle(R.string.print_section_grid)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (kind in GRID_ORDER) {
            FilterChip(
                selected = template.grid == kind,
                onClick = { actions.onUpdate { it.copy(grid = kind, gridStep = null) } },
                label = { Text(stringResource(gridName(kind))) },
            )
        }
    }
}

/** What the plan says: tiles to download, enlarged maps, what stops the export. */
@Composable
private fun PlanLines(ui: PrintUi) {
    val plan = ui.plan
    val style = MaterialTheme.typography.bodySmall
    if (plan == null) {
        Text(stringResource(R.string.print_planning), style = style)
        return
    }
    for (issue in plan.issues) {
        Text(stringResource(issueText(issue)), style = style, color = MaterialTheme.colorScheme.error)
    }
    WarningLines(plan, style)
    when (val missing = ui.missingTiles) {
        null -> {
            Text(stringResource(R.string.print_sum_missing_counting), style = style)
        }

        0 -> {
            if (plan.totalTiles > 0) Text(stringResource(R.string.print_sum_all_stored), style = style)
        }

        else -> {
            Text(
                stringResource(R.string.print_sum_missing, missing),
                style = style,
                color = MaterialTheme.colorScheme.tertiary,
            )
            if (!ui.canDownloadMissing) {
                Text(stringResource(R.string.print_missing_hatched, PrintUi.MAX_DOWNLOAD_TILES), style = style)
            }
        }
    }
    val (low, high) = plan.estimatedBytes
    if (high > 0) Text(stringResource(R.string.print_sum_size, formatBytes(low), formatBytes(high)), style = style)
}

/** Oversampled maps in orange: the print stays possible, a bit soft (P17, Q17). */
@Composable
fun WarningLines(
    plan: PrintPlan,
    style: TextStyle,
) {
    for (warning in plan.warnings) {
        val text =
            when (warning) {
                is PrintWarning.Oversampled -> {
                    stringResource(R.string.print_warn_oversampled, warning.sourceName, warning.factor)
                }

                is PrintWarning.OutsideRange -> {
                    stringResource(R.string.print_warn_outside_range, warning.sourceName)
                }

                is PrintWarning.Unavailable -> {
                    stringResource(R.string.print_warn_unavailable, warning.sourceId)
                }
            }
        Text(text, style = style, color = MaterialTheme.colorScheme.tertiary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreOptions(
    template: PrintTemplate,
    actions: PrintActions,
) {
    val d = template.dressing
    SectionTitle(R.string.print_section_quality)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (dpi in PrintTemplate.DPIS) {
            FilterChip(
                selected = template.dpi == dpi,
                onClick = { actions.onUpdate { it.copy(dpi = dpi) } },
                label = { Text(stringResource(R.string.print_dpi, dpi)) },
            )
        }
    }
    SwitchRow(R.string.print_lighter, template.lighter) { v -> actions.onUpdate { it.copy(lighter = v) } }
    SwitchRow(R.string.print_respect_ranges, template.respectLayerRanges) { v ->
        actions.onUpdate { it.copy(respectLayerRanges = v) }
    }
    Text(
        stringResource(R.string.print_margin, template.page.marginMm.roundToInt()),
        style = MaterialTheme.typography.labelLarge,
    )
    Slider(
        value = template.page.marginMm.toFloat().coerceIn(0f, MAX_MARGIN_SLIDER_MM),
        onValueChange = { v ->
            actions.onUpdate { it.copy(page = it.page.copy(marginMm = v.roundToInt().toDouble())) }
        },
        valueRange = 0f..MAX_MARGIN_SLIDER_MM,
        steps = MAX_MARGIN_SLIDER_MM.toInt() - 1,
    )
    if (template.grid != GridKind.NONE) {
        val degrees = template.grid == GridKind.WGS84 || template.grid == GridKind.WGS84_DECIMAL
        NumberField(
            label = if (degrees) R.string.print_grid_step_degrees else R.string.print_grid_step,
            value = template.gridStep,
            allowEmpty = true,
        ) { v -> actions.onUpdate { it.copy(gridStep = v.takeIf { x -> x > 0.0 }) } }
        Text(
            stringResource(R.string.print_grid_step_auto, gridStepText(template.effectiveGridStep, degrees)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    SectionTitle(R.string.print_section_dressing)
    SwitchRow(R.string.print_dress_waypoint_names, d.waypointNames) { v ->
        actions.onUpdate { it.withDressing { copy(waypointNames = v) } }
    }
    SwitchRow(R.string.print_dress_scale_text, d.scaleText) { v ->
        actions.onUpdate { it.withDressing { copy(scaleText = v) } }
    }
    SwitchRow(R.string.print_dress_scale_bar, d.scaleBar) { v ->
        actions.onUpdate { it.withDressing { copy(scaleBar = v) } }
    }
    SwitchRow(R.string.print_dress_north, d.northArrow) { v ->
        actions.onUpdate { it.withDressing { copy(northArrow = v) } }
    }
    SwitchRow(R.string.print_dress_legend, d.legend) { v -> actions.onUpdate { it.withDressing { copy(legend = v) } } }
    SwitchRow(R.string.print_dress_cartouche, d.cartouche) { v ->
        actions.onUpdate { it.withDressing { copy(cartouche = v) } }
    }
    SwitchRow(R.string.print_dress_corners, d.cornerCoordinates) { v ->
        actions.onUpdate { it.withDressing { copy(cornerCoordinates = v) } }
    }
    OutlinedTextField(
        value = template.subtitle,
        onValueChange = { v -> actions.onUpdate { it.copy(subtitle = v) } },
        label = { Text(stringResource(R.string.print_field_subtitle)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ConfirmActions(
    ui: PrintUi,
    actions: PrintActions,
) {
    val job = ui.job
    if (job != null) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (job) {
                is PrintJob.Downloading -> {
                    Text(stringResource(R.string.print_download_title), style = MaterialTheme.typography.labelLarge)
                    LinearProgressIndicator(progress = { job.progress.fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(
                            R.string.print_progress_download,
                            job.progress.processed.toInt(),
                            job.progress.total.toInt(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                is PrintJob.Exporting -> {
                    Text(stringResource(R.string.print_export_title), style = MaterialTheme.typography.labelLarge)
                    LinearProgressIndicator(
                        progress = { job.progress.fraction.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            OutlinedButton(onClick = actions.onCancelJob) { Text(stringResource(R.string.print_cancel)) }
        }
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (ui.canDownloadMissing) {
            OutlinedButton(onClick = actions.onDownload, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.print_download_missing, ui.missingTiles ?: 0))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.print_back_to_zone))
            }
            Button(
                onClick = actions.onExport,
                enabled = ui.plan?.canExport == true && !ui.planning,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.print_export))
            }
        }
    }
}

// --- after the export

/** « PDF enregistré · 1 page · 2,4 Mo », the reminder to print at 100 %, Ouvrir and Partager (P17, Q19). */
@Composable
fun PrintOutcomeDialog(
    outcome: PrintOutcome,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    when (outcome) {
        is PrintOutcome.Exported -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Text(
                        stringResource(
                            R.string.print_done_title,
                            pluralPages(outcome.pages),
                            formatBytes(outcome.bytes),
                        ),
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (outcome.missingTiles > 0) {
                            Text(
                                stringResource(R.string.print_done_missing, outcome.missingTiles),
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        Text(stringResource(R.string.print_reminder))
                    }
                },
                confirmButton = {
                    Row {
                        TextButton(onClick = { openPdf(context, outcome.uri) }) {
                            Text(stringResource(R.string.print_open))
                        }
                        TextButton(onClick = { sharePdf(context, outcome.uri) }) {
                            Text(stringResource(R.string.print_share))
                        }
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.print_close)) } },
            )
        }

        is PrintOutcome.Failed -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.print_export_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.print_failed,
                            outcome.message ?: stringResource(R.string.print_failed_unknown),
                        ),
                    )
                },
                confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.print_close)) } },
            )
        }
    }
}

@Composable
private fun pluralPages(pages: Int): String =
    LocalContext.current.resources.getQuantityString(R.plurals.print_pages, pages, pages)

private fun openPdf(
    context: Context,
    uri: Uri,
) {
    val intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun sharePdf(
    context: Context,
    uri: Uri,
) {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching {
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

// --- small building blocks

private fun PrintTemplate.withDressing(change: Dressing.() -> Dressing) = copy(dressing = dressing.change())

private fun gridName(kind: GridKind): Int =
    when (kind) {
        GridKind.NONE -> R.string.print_grid_none
        GridKind.UTM -> R.string.print_grid_utm
        GridKind.MGRS -> R.string.print_grid_mgrs
        GridKind.LAMBERT93 -> R.string.print_grid_lambert93
        GridKind.WGS84 -> R.string.print_grid_wgs84
        GridKind.WGS84_DECIMAL -> R.string.print_grid_wgs84_decimal
    }

/** "1 km", "500 m", "0,01°", "30″": the spacing of the grid in its own unit. */
private fun gridStepText(
    step: Double,
    degrees: Boolean,
): String =
    when {
        !degrees -> fr.toolmap.core.geo.ScaleBar.format(step)
        step < 1.0 / 60.0 -> "${(step * 3_600).roundToInt()}″"
        step < 1.0 -> "${(step * 60).roundToInt()}′"
        else -> "${step.roundToInt()}°"
    }

internal fun issueText(issue: PrintIssue): Int =
    when (issue) {
        PrintIssue.PAGE_SETUP -> R.string.print_issue_page_setup
        PrintIssue.SCALE_RANGE -> R.string.print_issue_scale_range
        PrintIssue.NO_AREA -> R.string.print_issue_no_area
        PrintIssue.NO_TRACK -> R.string.print_issue_no_track
        PrintIssue.TOO_MANY_PAGES -> R.string.print_issue_too_many_pages
        PrintIssue.HUGE_PAGE -> R.string.print_issue_huge_page
        PrintIssue.TOO_MANY_TILES -> R.string.print_issue_too_many_tiles
        PrintIssue.NO_LAYER -> R.string.print_issue_no_layer
    }

@Composable
private fun SectionTitle(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(
    label: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A text field for a number, kept as typed. */
@Composable
fun NumberText(
    label: Int,
    text: String,
    onText: (String) -> Unit,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onText,
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A text field for a number that only passes a value up when what is typed is one. */
@Composable
private fun NumberField(
    label: Int,
    value: Double?,
    allowEmpty: Boolean = false,
    onValue: (Double) -> Unit,
) {
    fun show(v: Double?): String =
        when {
            v == null -> ""
            v == Math.floor(v) -> v.toLong().toString()
            else -> v.toString()
        }
    var text by remember { mutableStateOf(show(value)) }
    // A value that changed from outside replaces what was typed; what the user typed that parses to the current value
    // stays as it is ("12." must not turn into "12" under their fingers).
    LaunchedEffect(value) {
        val typed = text.replace(',', '.').toDoubleOrNull()
        if (typed != value && !(value == null && text.isEmpty())) text = show(value)
    }
    NumberText(label, text) { typed ->
        text = typed
        val parsed = typed.replace(',', '.').toDoubleOrNull()
        when {
            parsed != null && parsed.isFinite() -> onValue(parsed)
            typed.isEmpty() && allowEmpty -> onValue(0.0)
        }
    }
}

/** The grids in the order of the choice (P17, Q8). */
private val GRID_ORDER =
    listOf(GridKind.NONE, GridKind.UTM, GridKind.MGRS, GridKind.LAMBERT93, GridKind.WGS84, GridKind.WGS84_DECIMAL)

/** Orange of the warnings that do not stop the print. */
private const val MAX_MARGIN_SLIDER_MM = 30f
