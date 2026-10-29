package fr.toolmap.print

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.toolmap.R
import fr.toolmap.core.print.Dressing
import fr.toolmap.core.print.GridKind
import fr.toolmap.core.print.GridSpacing
import fr.toolmap.core.print.PageSetup
import fr.toolmap.core.print.Paper
import fr.toolmap.core.print.PrintFormat
import fr.toolmap.core.print.PrintIssue
import fr.toolmap.core.print.PrintPlan
import fr.toolmap.core.print.PrintTemplate
import fr.toolmap.core.print.PrintTexts
import fr.toolmap.core.print.PrintWarning
import fr.toolmap.download.formatBytes
import fr.toolmap.draw.RouteIcons
import fr.toolmap.map.ToolPanel
import fr.toolmap.maps.MapsIcons
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.DropdownMenu
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.Switch
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap
import java.util.Locale
import kotlin.math.roundToInt

/** Everything the print panels can ask for; the screen wires each one to the [PrintViewModel]. */
class PrintActions(
    val onUpdate: ((PrintTemplate) -> PrintTemplate) -> Unit,
    val onRotate: () -> Unit,
    val onChooseZone: () -> Unit,
    val onNext: () -> Unit,
    val onBack: () -> Unit,
    /** A page of the settings (P17.8): « Éléments de la feuille », « Qualité et marges », or the main one. */
    val onPage: (PrintSheetPage) -> Unit,
    val onClose: () -> Unit,
    val onDownload: () -> Unit,
    val onExport: () -> Unit,
    val onCancelJob: () -> Unit,
)

// --- step 1: the sheet and the scale (P17.8, variant E « Épreuve » of docs/previews/p17-impression-menus.html)

/**
 * « Imprimer »: the sheets drawn to the same scale, the orientation (or the size of a custom sheet, with no largest),
 * the scale and the ground the frame covers, in a panel at the foot of the map with the frame drawn above it (variant E,
 * validated on 2026-10-29). « Choisir la zone » then lets the frame be moved.
 */
@Composable
fun PrintSetupPanel(
    ui: PrintUi,
    actions: PrintActions,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val template = ui.template
    val page = template.page
    var customScale by remember { mutableStateOf(false) }
    ToolPanel(
        title = stringResource(R.string.print_title),
        maxHeight = maxHeight,
        modifier = modifier,
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = actions.onClose, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.print_cancel))
                }
                Button(onClick = actions.onChooseZone, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.print_choose_zone))
                }
            }
        },
    ) {
        SheetCards(page) { paper -> actions.onUpdate { it.copy(page = it.page.copy(paper = paper)) } }
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (page.paper == Paper.CUSTOM) {
                CustomSize(page) { w, h ->
                    actions.onUpdate {
                        it.copy(
                            page =
                                it.page.copy(
                                    paper = Paper.CUSTOM,
                                    customWidthMm = w,
                                    customHeightMm = h,
                                    landscape = w >= h,
                                ),
                        )
                    }
                }
            } else {
                OrientationChoice(page.landscape) { landscape ->
                    actions.onUpdate { it.copy(page = it.page.copy(landscape = landscape)) }
                }
            }
            SectionTitle(R.string.print_section_scale)
            ScaleChips(
                scale = template.scale,
                onScale = { scale -> actions.onUpdate { it.copy(scale = scale) } },
                onOther = { customScale = true },
            )
            GroundLine(template)
            heavyOf(ui.plan)?.let { heavy -> HeavyLine(heavy, ui.plan) }
        }
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

/** The sheets offered, each drawn at the same millimetres per dp; the custom one last, fitted to its card. */
@Composable
private fun SheetCards(
    page: PageSetup,
    onPaper: (Paper) -> Unit,
) {
    val scroll = rememberScrollState()
    val custom = page.paper == Paper.CUSTOM
    // The custom sheet is the last card: the row comes to it once it is chosen.
    LaunchedEffect(custom) { if (custom) scroll.animateScrollTo(scroll.maxValue) }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (paper in Paper.OFFERED) SheetCard(paper, page, selected = page.paper == paper) { onPaper(paper) }
    }
}

@Composable
private fun SheetCard(
    paper: Paper,
    page: PageSetup,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = MaterialTheme.toolMap.accent
    val other = paper == Paper.CUSTOM
    val outline = scheme.outline
    val shape = MaterialTheme.shapes.medium
    // The custom card waits for its size with a dashed edge, as a sheet still to be cut.
    val dashed =
        if (other && !selected) {
            Modifier.drawBehind {
                val radius = CORNER_DP.dp.toPx()
                drawRoundRect(
                    outline,
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH, GAP))),
                )
            }
        } else {
            Modifier
        }
    Surface(
        onClick = onClick,
        shape = shape,
        color = if (selected) scheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
        border =
            when {
                selected -> BorderStroke(2.dp, accent)
                other -> null
                else -> BorderStroke(1.dp, scheme.outlineVariant)
            },
        modifier = dashed,
    ) {
        Column(
            modifier = Modifier.widthIn(min = 64.dp).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!other || selected) {
                val sheet = page.copy(paper = paper)
                val (w, h) = thumbnailSize(sheet, fitted = other)
                SheetThumbnail(sheet, w, h)
            } else {
                Spacer(Modifier.height(OTHER_SPACE_DP.dp))
            }
            Text(
                if (other) stringResource(R.string.print_paper_other_card) else paperChoiceName(paper),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                when {
                    !other -> String.format(Locale.getDefault(), "%,.0f × %,.0f", paper.widthMm, paper.heightMm)
                    selected -> String.format(Locale.getDefault(), "%,.0f × %,.0f", page.widthMm, page.heightMm)
                    else -> stringResource(R.string.print_paper_other_dims)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
            )
        }
    }
}

/** Width and height of a custom sheet, applied as soon as both are numbers of at least 50 mm; no largest. */
@Composable
private fun CustomSize(
    page: PageSetup,
    onSize: (widthMm: Double, heightMm: Double) -> Unit,
) {
    var width by remember { mutableStateOf(page.widthMm.roundToInt().toString()) }
    var height by remember { mutableStateOf(page.heightMm.roundToInt().toString()) }

    fun push() {
        val w = millimetres(width)
        val h = millimetres(height)
        if (w != null && h != null && PageSetup.isCustomSide(w) && PageSetup.isCustomSide(h)) onSize(w, h)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MillimetreField(R.string.print_custom_width_short, width, Modifier.weight(1f)) {
            width = it
            push()
        }
        MillimetreField(R.string.print_custom_height_short, height, Modifier.weight(1f)) {
            height = it
            push()
        }
    }
    Text(
        stringResource(R.string.print_custom_unlimited),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
    )
}

private fun millimetres(text: String): Double? = text.replace(" ", "").replace(',', '.').toDoubleOrNull()

@Composable
private fun MillimetreField(
    label: Int,
    text: String,
    modifier: Modifier,
    onText: (String) -> Unit,
) {
    val value = millimetres(text)
    OutlinedTextField(
        value = text,
        onValueChange = onText,
        label = { Text(stringResource(label)) },
        suffix = { Text(stringResource(R.string.print_mm)) },
        isError = value == null || !PageSetup.isCustomSide(value),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/** « Portrait | Paysage », the sheet drawn in each way. */
@Composable
private fun OrientationChoice(
    landscape: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val choices = listOf(false, true)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, value ->
            val selected = value == landscape
            SegmentedButton(
                selected = selected,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                icon = {
                    Icon(
                        when {
                            selected -> Icons.Filled.Check
                            value -> PrintIcons.Landscape
                            else -> PrintIcons.Portrait
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                label = { Text(orientationName(value)) },
            )
        }
    }
}

/** The scales offered on one line that scrolls, then « Autre… » (or the scale typed). */
@Composable
private fun ScaleChips(
    scale: Double,
    onScale: (Double) -> Unit,
    onOther: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (offered in PrintTemplate.SCALES) {
            val selected = scale == offered.toDouble()
            FilterChip(
                selected = selected,
                onClick = { onScale(offered.toDouble()) },
                label = { Text(PrintFormat.scale(offered.toDouble(), PrintTexts())) },
                leadingIcon = checkWhen(selected),
            )
        }
        val other = scale.roundToInt() !in PrintTemplate.SCALES
        FilterChip(
            selected = other,
            onClick = onOther,
            label = {
                Text(if (other) PrintFormat.scale(scale, PrintTexts()) else stringResource(R.string.print_scale_other))
            },
            leadingIcon = checkWhen(other),
        )
    }
}

/** « Sur le terrain 6,6 × 3,7 km ». */
@Composable
private fun GroundLine(template: PrintTemplate) {
    val label = stringResource(R.string.print_ground_label)
    val (w, h) = groundSize(template)
    val strong = MaterialTheme.colorScheme.onSurface
    Text(
        buildAnnotatedString {
            append(label)
            append(' ')
            withStyle(SpanStyle(color = strong, fontSize = 16.sp, fontWeight = FontWeight.Medium)) { append("$w × $h") }
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The orange line of a heavy PDF under the sheet (P17.9): its weight, its risk, where the resolution is set. */
@Composable
private fun HeavyLine(
    heavy: PrintWarning.Heavy,
    plan: PrintPlan?,
) {
    val (low, high) = plan?.estimatedBytes ?: (0L to 0L)
    val color = MaterialTheme.colorScheme.tertiary
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(
            stringResource(
                R.string.print_heavy_line,
                heavy.dpi,
                megapixels(heavy.pixelsPerPage),
                formatBytes(low),
                formatBytes(high),
            ),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = color,
        )
    }
}

// --- step 3: the settings before the PDF (P17.8, variant A « Gabarit »)

/**
 * The confirmation: the state of the PDF first (pages, size, weight, tiles), then « Sur la carte » and « Feuille », the
 * layout elements and the quality behind two summarised lines that open their own page (variant A, validated on
 * 2026-10-29), and the box of a heavy PDF (P17.9, form Q2). The actions stay at its foot while the settings scroll.
 */
@Composable
fun PrintConfirmSheet(
    ui: PrintUi,
    actions: PrintActions,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    when (ui.sheetPage) {
        PrintSheetPage.MAIN -> {
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
                ) { MainSettings(ui, actions) }
            }
        }

        PrintSheetPage.ELEMENTS -> {
            SettingsPage(R.string.print_elements_title, ui, actions, maxHeight, modifier) { ElementsPage(ui, actions) }
        }

        PrintSheetPage.QUALITY -> {
            SettingsPage(R.string.print_quality_title, ui, actions, maxHeight, modifier) { QualityPage(ui, actions) }
        }
    }
}

@Composable
private fun MainSettings(
    ui: PrintUi,
    actions: PrintActions,
) {
    val template = ui.template
    val d = template.dressing
    StatusBox(ui, actions)
    heavyOf(ui.plan)?.let { heavy ->
        HeavyBox(
            title = stringResource(R.string.print_heavy_pdf_title),
            text = stringResource(R.string.print_heavy_pdf_text, heavy.dpi, megapixels(heavy.pixelsPerPage)),
            action = stringResource(R.string.print_quality_title),
            onAction = { actions.onPage(PrintSheetPage.QUALITY) },
        )
    }
    GroupLabel(R.string.print_group_map)
    DropdownField(
        label = R.string.print_section_grid,
        value = stringResource(gridName(template.grid)),
        items =
            GRID_ORDER.map { kind ->
                stringResource(gridName(kind)) to { actions.onUpdate { it.copy(grid = kind, gridStep = null) } }
            },
    )
    SwitchRow(R.string.print_dress_waypoints_names, d.waypoints) { v ->
        actions.onUpdate { it.withDressing { copy(waypoints = v, waypointNames = v) } }
    }
    SwitchRow(R.string.print_dress_tracks, d.tracks) { v ->
        actions.onUpdate { it.withDressing { copy(tracks = v) } }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    GroupLabel(R.string.print_group_sheet)
    OutlinedTextField(
        value = template.title,
        onValueChange = { v -> actions.onUpdate { it.copy(title = v) } },
        label = { Text(stringResource(R.string.print_field_title)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    NavRow(R.string.print_elements_title, elementsSummary(d)) { actions.onPage(PrintSheetPage.ELEMENTS) }
    NavRow(R.string.print_quality_title, qualitySummary(template)) { actions.onPage(PrintSheetPage.QUALITY) }
}

/** A page of the settings: ← to the main one in the band, ✕ to leave, the same actions at the foot. */
@Composable
private fun SettingsPage(
    title: Int,
    ui: PrintUi,
    actions: PrintActions,
    maxHeight: Dp,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val back = stringResource(R.string.print_back_to_settings)
    val tint = MaterialTheme.toolMap.onShell
    ToolPanel(
        title = stringResource(title),
        subtitle = stringResource(R.string.print_title),
        maxHeight = maxHeight,
        modifier = modifier,
        onClose = actions.onClose,
        closeDescription = stringResource(R.string.print_close),
        footer = { ConfirmActions(ui, actions) },
        leading = {
            IconButton(onClick = { actions.onPage(PrintSheetPage.MAIN) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = back, tint = tint)
            }
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** « Éléments de la feuille »: the layout elements by where they stand on the sheet, then the subtitle. */
@Composable
private fun ElementsPage(
    ui: PrintUi,
    actions: PrintActions,
) {
    val d = ui.template.dressing
    GroupLabel(R.string.print_group_footer)
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
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    GroupLabel(R.string.print_group_frame)
    SwitchRow(R.string.print_dress_corners, d.cornerCoordinates) { v ->
        actions.onUpdate { it.withDressing { copy(cornerCoordinates = v) } }
    }
    OutlinedTextField(
        value = ui.template.subtitle,
        onValueChange = { v -> actions.onUpdate { it.copy(subtitle = v) } },
        label = { Text(stringResource(R.string.print_field_subtitle)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * « Qualité et marges »: the resolution (150, 200, 300 dpi or any other from 72 dpi, P17.9) with the box of a heavy
 * PDF, the lighter file, the layers out of their range, the margin and the spacing of the grid.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QualityPage(
    ui: PrintUi,
    actions: PrintActions,
) {
    val template = ui.template
    var other by remember { mutableStateOf(template.dpi !in PrintTemplate.DPIS) }
    SectionTitle(R.string.print_section_resolution)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (dpi in PrintTemplate.DPIS) {
            val selected = !other && template.dpi == dpi
            FilterChip(
                selected = selected,
                onClick = {
                    other = false
                    actions.onUpdate { it.copy(dpi = dpi) }
                },
                label = { Text(stringResource(R.string.print_dpi, dpi)) },
                leadingIcon = checkWhen(selected),
            )
        }
        FilterChip(
            selected = other,
            onClick = { other = true },
            label = { Text(stringResource(R.string.print_dpi_other)) },
            leadingIcon = checkWhen(other),
        )
    }
    if (other) {
        DpiField(template.dpi) { dpi -> actions.onUpdate { it.copy(dpi = dpi) } }
        Text(
            stringResource(R.string.print_dpi_hint, PrintTemplate.MIN_DPI),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
    heavyOf(ui.plan)?.let { heavy ->
        val (low, high) = ui.plan?.estimatedBytes ?: (0L to 0L)
        val highDpi = heavy.dpi > PrintTemplate.HIGH_DPI
        HeavyBox(
            title = stringResource(if (highDpi) R.string.print_heavy_dpi_title else R.string.print_heavy_pdf_title),
            text =
                stringResource(
                    if (highDpi) R.string.print_heavy_risk_dpi else R.string.print_heavy_risk,
                    heavy.dpi,
                    megapixels(heavy.pixelsPerPage),
                    formatBytes(low),
                    formatBytes(high),
                ),
        )
    }
    SwitchRow(R.string.print_lighter, template.lighter, hint = R.string.print_lighter_hint) { v ->
        actions.onUpdate { it.copy(lighter = v) }
    }
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
        val auto =
            stringResource(
                R.string.print_grid_step_auto_item,
                gridStepText(GridSpacing.auto(template.grid, template.scale), degrees),
            )
        DropdownField(
            label = R.string.print_grid_step_field,
            value = template.gridStep?.let { gridStepText(it, degrees) } ?: auto,
            items =
                listOf(auto to { actions.onUpdate { it.copy(gridStep = null) } }) +
                    GridSpacing.choices(template.grid).map { step ->
                        gridStepText(step, degrees) to { actions.onUpdate { it.copy(gridStep = step) } }
                    },
        )
    }
}

/** The resolution typed after « Autre… », applied from 72 dpi; no highest. */
@Composable
private fun DpiField(
    dpi: Int,
    onDpi: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(dpi.toString()) }
    val typed = text.trim().toIntOrNull()
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v
            v.trim().toIntOrNull()?.takeIf { it >= PrintTemplate.MIN_DPI }?.let(onDpi)
        },
        label = { Text(stringResource(R.string.print_dpi_field)) },
        suffix = { Text(stringResource(R.string.print_dpi_unit)) },
        isError = typed == null || typed < PrintTemplate.MIN_DPI,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The state of the PDF, first on the confirmation: what comes out, its weight, what stops it, the tiles to fetch. */
@Composable
private fun StatusBox(
    ui: PrintUi,
    actions: PrintActions,
) {
    val tm = MaterialTheme.toolMap
    val plan = ui.plan
    val template = ui.template
    val small = MaterialTheme.typography.bodySmall
    Surface(
        color = tm.tile,
        contentColor = tm.onTile,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val pages = plan?.mapPages?.size?.takeIf { it > 0 } ?: 1
            Text(
                stringResource(
                    R.string.print_status_title,
                    pluralStringResource(R.plurals.print_pages, pages, pages),
                    paperName(template.page),
                    orientationName(template.page).lowercase(),
                    PrintFormat.scale(template.scale, PrintTexts()),
                ),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
            )
            if (plan == null || ui.planning) {
                Text(stringResource(R.string.print_planning), style = small)
            } else {
                val (w, h) = groundSize(template)
                val (low, high) = plan.estimatedBytes
                Text(
                    stringResource(
                        R.string.print_status_details,
                        w,
                        h,
                        template.dpi,
                        formatBytes(low),
                        formatBytes(high),
                    ),
                    style = small,
                )
                for (issue in plan.issues) {
                    Text(stringResource(issueText(issue)), style = small, color = MaterialTheme.colorScheme.error)
                }
                WarningLines(plan, small)
            }
            TilesLine(ui, plan, actions)
        }
    }
}

/** The tiles still to fetch, with « Télécharger » when they are few enough; hatched beyond. */
@Composable
private fun TilesLine(
    ui: PrintUi,
    plan: PrintPlan?,
    actions: PrintActions,
) {
    val small = MaterialTheme.typography.bodySmall
    when (val missing = ui.missingTiles) {
        null -> {
            Text(stringResource(R.string.print_sum_missing_counting), style = small)
        }

        0 -> {
            if ((plan?.totalTiles ?: 0) > 0) Text(stringResource(R.string.print_sum_all_stored), style = small)
        }

        else -> {
            val color = MaterialTheme.colorScheme.tertiary
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 40.dp)) {
                Icon(MapsIcons.Download, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                Text(
                    pluralStringResource(R.plurals.print_tiles_missing, missing, missing),
                    style = small.copy(fontWeight = FontWeight.Medium),
                    color = color,
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                )
                if (ui.canDownloadMissing && ui.job == null) {
                    TextButton(onClick = actions.onDownload) { Text(stringResource(R.string.print_download)) }
                }
            }
            if (!ui.canDownloadMissing) {
                Text(stringResource(R.string.print_missing_hatched, PrintUi.MAX_DOWNLOAD_TILES), style = small)
            }
        }
    }
}

/** Oversampled maps in orange: the print stays possible, a bit soft (P17, Q17). A heavy PDF has its own box. */
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

                is PrintWarning.Heavy -> {
                    null
                }
            } ?: continue
        Text(text, style = style, color = MaterialTheme.colorScheme.tertiary)
    }
}

/** The orange box of a heavy PDF (P17.9, form Q2 of docs/previews/p17-impression-qualite.html). */
@Composable
private fun HeavyBox(
    title: String,
    text: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.tertiaryContainer,
        contentColor = scheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = if (action == null) 10.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall)
                if (action != null) {
                    TextButton(onClick = onAction) {
                        Text(action, color = scheme.onTertiaryContainer, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = actions.onBack, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.print_back_to_zone))
        }
        Button(
            onClick = actions.onExport,
            enabled = ui.plan?.canExport == true && !ui.planning,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.print_export))
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

/** The warning of a heavy PDF in [plan], if any (P17.9). */
private fun heavyOf(plan: PrintPlan?): PrintWarning.Heavy? =
    plan?.warnings?.filterIsInstance<PrintWarning.Heavy>()?.firstOrNull()

/** "21,8" or "257": millions of pixels in the phone's language. */
private fun megapixels(pixels: Long): String {
    val millions = pixels / PIXELS_PER_MEGAPIXEL
    return String.format(Locale.getDefault(), if (millions < WHOLE_MEGAPIXELS) "%.1f" else "%.0f", millions)
}

/** « Tous », « 5 sur 6 · sans coordonnées des coins », « 4 sur 6 ». */
@Composable
private fun elementsSummary(d: Dressing): String {
    val elements =
        listOf(
            d.scaleText to R.string.print_dress_scale_text,
            d.scaleBar to R.string.print_dress_scale_bar,
            d.northArrow to R.string.print_dress_north,
            d.legend to R.string.print_dress_legend,
            d.cartouche to R.string.print_dress_cartouche,
            d.cornerCoordinates to R.string.print_dress_corners,
        )
    val on = elements.count { it.first }
    val off = elements.filterNot { it.first }
    return when (off.size) {
        0 -> {
            stringResource(R.string.print_elements_all)
        }

        1 -> {
            val name = stringResource(off.single().second).replaceFirstChar { it.lowercase() }
            stringResource(R.string.print_elements_without, on, elements.size, name)
        }

        else -> {
            stringResource(R.string.print_elements_count, on, elements.size)
        }
    }
}

/** « 300 dpi · marge 8 mm · grille tous les 1 km ». */
@Composable
private fun qualitySummary(template: PrintTemplate): String {
    val margin = template.page.marginMm.roundToInt()
    if (template.grid == GridKind.NONE) return stringResource(R.string.print_quality_summary, template.dpi, margin)
    val degrees = template.grid == GridKind.WGS84 || template.grid == GridKind.WGS84_DECIMAL
    return stringResource(
        R.string.print_quality_summary_grid,
        template.dpi,
        margin,
        gridStepText(template.effectiveGridStep, degrees),
    )
}

/** The check of a chosen chip. */
private fun checkWhen(selected: Boolean): (@Composable () -> Unit)? =
    if (selected) {
        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
    } else {
        null
    }

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
        PrintIssue.TOO_MANY_TILES -> R.string.print_issue_too_many_tiles
        PrintIssue.NO_LAYER -> R.string.print_issue_no_layer
    }

@Composable
private fun SectionTitle(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/** « Sur la carte », « Feuille »: the name of a group of settings. */
@Composable
private fun GroupLabel(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    label: Int,
    checked: Boolean,
    hint: Int? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
            if (hint != null) {
                Text(
                    stringResource(hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A line that opens a page of the settings: its name, what it holds now, the chevron. */
@Composable
private fun NavRow(
    title: Int,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(RouteIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A read-only field that opens its menu when touched (« Grille », « Pas de la grille »). */
@Composable
private fun DropdownField(
    label: Int,
    value: String,
    items: List<Pair<String, () -> Unit>>,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(label)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // The field is read only: a touch anywhere on it opens the menu.
        Box(Modifier.matchParentSize().clickable(role = Role.Button) { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((text, pick) in items) {
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        open = false
                        pick()
                    },
                )
            }
        }
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

/** The grids in the order of the choice (P17, Q8). */
private val GRID_ORDER =
    listOf(GridKind.NONE, GridKind.UTM, GridKind.MGRS, GridKind.LAMBERT93, GridKind.WGS84, GridKind.WGS84_DECIMAL)

private const val MAX_MARGIN_SLIDER_MM = 30f
private const val PIXELS_PER_MEGAPIXEL = 1_000_000.0
private const val WHOLE_MEGAPIXELS = 100.0

/** Corner of a card (the medium shape of the theme), for the dashed edge of the custom sheet. */
private const val CORNER_DP = 14
private const val DASH = 8f
private const val GAP = 6f

/** The room of a sheet in the card of the custom one before its size is known. */
private const val OTHER_SPACE_DP = 44
