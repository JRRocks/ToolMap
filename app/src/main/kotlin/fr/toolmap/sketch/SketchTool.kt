package fr.toolmap.sketch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.measure.Measure
import fr.toolmap.core.p2p.GroupItemKind
import fr.toolmap.core.p2p.PointDuration
import fr.toolmap.core.sketch.SketchFill
import fr.toolmap.core.sketch.SketchFillPreset
import fr.toolmap.core.sketch.SketchPalette
import fr.toolmap.core.sketch.SketchPath
import fr.toolmap.core.sketch.SketchScale
import fr.toolmap.core.sketch.SketchSmoothing
import fr.toolmap.core.sketch.SketchStroke
import fr.toolmap.core.sketch.XY
import fr.toolmap.draw.RouteIcons
import fr.toolmap.map.MapController
import fr.toolmap.share.GroupTag
import fr.toolmap.share.SendToGroupDialog
import fr.toolmap.tracks.ConfirmDialog
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.DropdownMenu
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap
import kotlin.math.hypot

/*
 * The sketch tool (P28.3), variant D « Carnet » of docs/previews/p28-croquis.html, chosen on 2026-11-03: the bar of
 * the map gives way to a petrol band at the top (✕, the name, « 4 traits », « Enregistrer ») and a panel at the
 * bottom on two tiers: the eight colours, then the width and the smoothing; under a line, Main · Stylo · Gomme and
 * undo / redo. The strokes being edited are drawn by the tool over the map, the saved ones by the map (SketchLayers).
 */
@Composable
fun SketchTool(
    ui: SketchUi,
    vm: SketchViewModel,
    controller: MapController,
    /** After « Enregistrer », with the id of the saved sketch (`null`: it was left empty). */
    onSaved: (Long?) -> Unit,
    /** The colour of « Pour le groupe » and what sends a sketch drawn for the group (P27). */
    groupColor: Int? = null,
    onSendToGroup: (name: String, strokes: List<SketchStroke>, duration: PointDuration, alert: Boolean) -> Boolean =
        { _, _, _, _ -> false },
) {
    var quitAsked by remember { mutableStateOf(false) }
    var askGroup by remember { mutableStateOf(false) }
    val close = { if (ui.dirty) quitAsked = true else vm.close() }
    BackHandler(onBack = close)
    var size by remember { mutableStateOf(IntSize.Zero) }
    var bandHeight by remember { mutableIntStateOf(0) }
    var panelHeight by remember { mutableIntStateOf(0) }
    // The centre point of the shapes (P28.4), in the middle of the map left between the band and the panel, as the
    // centre point of the measure is.
    val cross = Offset(size.width / 2f, bandHeight + (size.height - bandHeight - panelHeight) / 2f)
    val reachDp = SHAPE_REACH_DP
    Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
        SketchCanvas(ui, vm, controller, cross.takeIf { ui.mode == SketchMode.SHAPE })
        SketchBand(
            name = ui.name,
            subtitle = bandSubtitle(ui),
            onClose = close,
            // A sketch for the group is sent once named and given a duration (P27); empty, there is nothing to send.
            onSave = {
                vm.commitShape()
                if (ui.forGroup) askGroup = vm.ui.value.strokes.isNotEmpty() else vm.save(onSaved)
            },
            modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { bandHeight = it.height },
            groupColor = groupColor?.takeIf { ui.forGroup },
        )
        SketchPanel(
            ui,
            vm,
            onAddHere = {
                controller.screenToLatLon(cross.x, cross.y)?.let {
                    val zoom = controller.liveCamera.zoom
                    vm.addShapePoint(XY.of(LonLat(it.lon, it.lat)), zoom, reachDp * SketchScale.metersPerPixel(zoom))
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { panelHeight = it.height },
        )
    }
    if (askGroup) {
        SendToGroupDialog(
            kind = GroupItemKind.SKETCH,
            initialName = ui.name,
            onSend = { name, duration, alert ->
                if (onSendToGroup(name, vm.groupStrokes(), duration, alert)) {
                    askGroup = false
                    vm.close()
                }
            },
            onDismiss = { askGroup = false },
        )
    }
    if (quitAsked) {
        ConfirmDialog(
            title = stringResource(R.string.draw_quit_title),
            message = stringResource(R.string.draw_quit_message),
            confirmLabel = stringResource(R.string.draw_quit_confirm),
            onConfirm = {
                quitAsked = false
                vm.close()
            },
            onDismiss = { quitAsked = false },
            destructive = true,
        )
    }
}

/** « 2 traits · 1 forme · 1 en cours » (P28.4): the freehand strokes, the shapes and the shape being placed. */
@Composable
private fun bandSubtitle(ui: SketchUi): String {
    val shapes = ui.strokes.count { it.raw.shape != null }
    val free = ui.strokes.size - shapes
    val parts =
        buildList {
            if (free > 0) add(pluralStringResource(R.plurals.sketch_strokes, free, free))
            if (shapes > 0) add(pluralStringResource(R.plurals.sketch_shapes, shapes, shapes))
            if (ui.draft.isNotEmpty()) {
                add(
                    stringResource(
                        if (shapes > 0) R.string.sketch_band_in_progress else R.string.sketch_band_shape_in_progress,
                    ),
                )
            }
        }
    return if (parts.isEmpty()) stringResource(R.string.sketch_no_strokes) else parts.joinToString(" · ")
}

@Composable
private fun SketchBand(
    name: String,
    subtitle: String,
    onClose: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    groupColor: Int? = null,
) {
    val tm = MaterialTheme.toolMap
    Surface(color = tm.shell, contentColor = tm.onShell, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .heightIn(min = 64.dp)
                .padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.sketch_close))
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = tm.onShellVariant,
                )
            }
            if (groupColor != null) GroupTag(groupColor, Modifier.padding(horizontal = 4.dp))
            TextButton(onClick = onSave) {
                Text(
                    stringResource(if (groupColor != null) R.string.group_send else R.string.sketch_save),
                    color = tm.navIndicator,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun SketchPanel(
    ui: SketchUi,
    vm: SketchViewModel,
    onAddHere: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tm = MaterialTheme.toolMap
    Surface(
        color = tm.panel,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
        ) {
            // The eraser has its own first tier (variant A of docs/previews/p28-gomme.html, chosen on 2026-11-03), laid
            // over the pen's, which keeps its place: the panel does not change height between the pen and the eraser.
            val erasing = ui.mode == SketchMode.ERASER
            if (ui.mode == SketchMode.SHAPE) {
                ShapeTier(ui, vm, onAddHere)
            } else {
                Box {
                    Column(if (erasing) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier) {
                        PenTier(ui, vm)
                    }
                    if (erasing) EraserTier(ui.eraserPx, vm::setEraser, Modifier.matchParentSize())
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ModeSegments(ui.mode, vm::setMode, Modifier.weight(1f))
                IconButton(onClick = vm::undo, enabled = ui.canUndo) {
                    Icon(RouteIcons.Undo, contentDescription = stringResource(R.string.draw_undo_a11y))
                }
                IconButton(onClick = vm::redo, enabled = ui.canRedo) {
                    Icon(RouteIcons.Redo, contentDescription = stringResource(R.string.draw_redo))
                }
            }
        }
    }
}

/** The colours, then the width and the smoothing. */
@Composable
private fun PenTier(
    ui: SketchUi,
    vm: SketchViewModel,
) {
    ColorDots(selected = ui.colorIndex, onSelect = vm::setColor)
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val width = stringResource(R.string.sketch_width_value, ui.widthPx)
        val widthLabel = stringResource(R.string.sketch_width)
        Slider(
            value = ui.widthPx.toFloat(),
            onValueChange = { vm.setWidth(Math.round(it)) },
            valueRange = SketchScale.MIN_WIDTH_PX.toFloat()..SketchScale.MAX_WIDTH_PX.toFloat(),
            modifier =
                Modifier.weight(1f).semantics {
                    contentDescription = widthLabel
                    stateDescription = width
                },
        )
        Text(
            width,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp).width(40.dp),
        )
        SmoothingChip(ui.smoothing, vm::setSmoothing)
    }
}

/** « Taille de la gomme » and its value, the slider and the disc of the eraser at its size (up to 40 dp). */
@Composable
private fun EraserTier(
    sizePx: Int,
    onSize: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tm = MaterialTheme.toolMap
    val label = stringResource(R.string.sketch_eraser_size)
    val value = stringResource(R.string.sketch_width_value, sizePx)
    Column(
        // Opaque, and taking the touches: the pen's tier lies under it.
        modifier.background(tm.panel).pointerInput(Unit) {},
        verticalArrangement = Arrangement.Center,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = sizePx.toFloat(),
                onValueChange = { onSize(Math.round(it)) },
                valueRange = SketchUi.MIN_ERASER_PX.toFloat()..SketchUi.MAX_ERASER_PX.toFloat(),
                modifier =
                    Modifier.weight(1f).semantics {
                        contentDescription = label
                        stateDescription = value
                    },
            )
            Box(Modifier.padding(start = 12.dp).size(ERASER_PREVIEW_MAX), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(minOf(sizePx.dp, ERASER_PREVIEW_MAX))
                        .background(tm.onTile.copy(alpha = 0.12f), CircleShape)
                        .border(1.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
                )
            }
        }
    }
}

@Composable
private fun ColorDots(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val ring = MaterialTheme.toolMap.navIndicator
    val names = COLOR_NAMES.map { stringResource(it) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        SketchPalette.COLORS.forEachIndexed { i, argb ->
            val on = i == selected
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.RadioButton) { onSelect(i) }
                    .semantics {
                        contentDescription = names[i]
                        this.selected = on
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .then(if (on) Modifier.border(2.dp, ring, CircleShape) else Modifier)
                        .padding(4.dp)
                        .background(Color(argb), CircleShape)
                        // The white and the black stay visible on a panel of their colour.
                        .border(1.dp, Color.Gray.copy(alpha = 0.5f), CircleShape),
                )
            }
        }
    }
}

@Composable
private fun SmoothingChip(
    smoothing: SketchSmoothing,
    onChange: (SketchSmoothing) -> Unit,
) {
    val tm = MaterialTheme.toolMap
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .clip(MaterialTheme.shapes.small)
                .background(tm.tile)
                .border(1.dp, tm.navIndicator, MaterialTheme.shapes.small)
                .clickable(role = Role.DropdownList) { open = true }
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(smoothingLabel(smoothing)),
                style = MaterialTheme.typography.labelLarge,
                color = tm.onTile,
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = tm.onTile)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (level in SketchSmoothing.entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(smoothingLabel(level))) },
                    onClick = {
                        open = false
                        onChange(level)
                    },
                )
            }
        }
    }
}

@Composable
private fun ModeSegments(
    mode: SketchMode,
    onMode: (SketchMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tm = MaterialTheme.toolMap
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(tm.tile).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // Four modes since P28.4: the icon above the name, so that « Gomme » still fits the width.
        for ((m, icon, label) in MODES) {
            val on = m == mode
            Column(
                Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) tm.navIndicator else Color.Transparent)
                    .clickable(role = Role.Tab) { onMode(m) }
                    .semantics { selected = on },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val color = if (on) tm.onNavIndicator else tm.onTile
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(label),
                    color = color,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The tier of the shapes (P28.4, variant E « Motif × couleur » of docs/previews/p28-formes.html, chosen on 2026-11-04):
 * « Ligne | Zone », the figures of the measure, for a zone its pattern among eight and the name of a known combination
 * (« Hachures rouges : Interdit »), the colours, then « Ajouter ici » and « Terminer ».
 */
@Composable
private fun ShapeTier(
    ui: SketchUi,
    vm: SketchViewModel,
    onAddHere: () -> Unit,
) {
    val tm = MaterialTheme.toolMap
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).background(tm.tile).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            for ((closed, icon, label) in SHAPE_KINDS) {
                val on = closed == ui.shapeClosed
                Row(
                    Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) tm.navIndicator else Color.Transparent)
                        .clickable(role = Role.Tab) { vm.setShapeClosed(closed) }
                        .semantics { selected = on },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    val color = if (on) tm.onNavIndicator else tm.onTile
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(label),
                        color = color,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
        ShapeFigures(ui)
        if (ui.shapeClosed) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.sketch_pattern),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(fillName(ui.fill)),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
            FillSwatches(ui.fill, vm::setFill)
        }
        ColorDots(selected = ui.colorIndex, onSelect = vm::setColor)
        if (ui.shapeClosed) {
            Text(
                fillNote(ui.fill, ui.colorIndex, ui.color),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShapeButton(
                RouteIcons.AddLocationAlt,
                stringResource(R.string.draw_add_here),
                filled = true,
                enabled = true,
                onClick = onAddHere,
                modifier = Modifier.weight(1f),
            )
            ShapeButton(
                Icons.Filled.Check,
                stringResource(R.string.sketch_shape_finish),
                filled = false,
                enabled = ui.draftComplete,
                onClick = vm::finishShape,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** « 186 ha  périmètre 5,6 km · 4 points », as the measure of a surface; the length for a line. */
@Composable
private fun ShapeFigures(ui: SketchUi) {
    val n = ui.draft.size
    if (n == 0) {
        Text(
            stringResource(R.string.measure_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        return
    }
    val fmt = LocalFormats.current
    val result = Measure.measure(ui.draft.map { it.toLonLat() })
    val count = pluralStringResource(R.plurals.sketch_shape_points, n, n)
    val area = result.areaSquareMeters
    val perimeter = result.perimeterMeters
    val (big, small) =
        when {
            ui.shapeClosed && area != null && perimeter != null -> {
                fmt.area(area) to stringResource(R.string.sketch_shape_perimeter, fmt.distance(perimeter), count)
            }

            n >= 2 -> {
                fmt.distance(result.lengthMeters) to count
            }

            else -> {
                count to null
            }
        }
    Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            big,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.alignByBaseline(),
        )
        if (small != null) {
            Text(
                small,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

/** The eight patterns, drawn in grey on a paper ground; the one chosen has a ring. */
@Composable
private fun FillSwatches(
    selected: SketchFill,
    onSelect: (SketchFill) -> Unit,
) {
    val ring = MaterialTheme.toolMap.navIndicator
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (fill in SketchFill.entries) {
            val on = fill == selected
            val name = stringResource(fillName(fill))
            Box(
                Modifier
                    .size(40.dp)
                    .clip(shape)
                    .clickable(role = Role.RadioButton) { onSelect(fill) }
                    .semantics {
                        contentDescription = name
                        this.selected = on
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .then(if (on) Modifier.border(2.dp, ring, shape) else Modifier)
                        .padding(4.dp),
                ) {
                    FillSwatch(fill, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun FillSwatch(
    fill: SketchFill,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val tile = remember(fill, density) { SketchPatterns.bitmap(fill, SWATCH_INK, density)?.asImageBitmap() }
    Canvas(modifier) {
        drawRoundRect(SWATCH_PAPER, cornerRadius = CornerRadius(8.dp.toPx()))
        val inset = 4.dp.toPx()
        val topLeft = Offset(inset, inset)
        val inner = Size(size.width - 2 * inset, size.height - 2 * inset)
        val corner = CornerRadius(5.dp.toPx())
        val ink = Color(SWATCH_INK)
        when {
            fill == SketchFill.TINT -> {
                drawRoundRect(ink.copy(alpha = fill.opacity.toFloat()), topLeft, inner, corner)
            }

            tile != null -> {
                val brush = ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
                drawRoundRect(brush, topLeft, inner, corner)
            }
        }
        drawRoundRect(ink, topLeft, inner, corner, style = Stroke(2.dp.toPx()))
    }
}

/** « Hachures rouges : Interdit », or « Points orange » for a combination without a name. */
@Composable
private fun fillNote(
    fill: SketchFill,
    colorIndex: Int,
    color: Int,
): AnnotatedString {
    val (noun, adjectives) =
        when (fill) {
            SketchFill.NONE -> R.string.sketch_fill_outline to R.array.sketch_color_ms
            SketchFill.TINT -> R.string.sketch_fill_tint to R.array.sketch_color_fs
            SketchFill.HATCH -> R.string.sketch_fill_hatch to R.array.sketch_color_fp
            SketchFill.STRIPES -> R.string.sketch_fill_stripes to R.array.sketch_color_fp
            SketchFill.DOTS -> R.string.sketch_fill_dots to R.array.sketch_color_mp
            SketchFill.GRID -> R.string.sketch_fill_grid to R.array.sketch_color_ms
            SketchFill.CROSS -> R.string.sketch_fill_cross to R.array.sketch_color_mp
            SketchFill.WAVES -> R.string.sketch_fill_waves to R.array.sketch_color_fp
        }
    val described =
        stringResource(
            R.string.sketch_fill_described,
            stringResource(noun),
            stringArrayResource(adjectives)[colorIndex],
        )
    val preset = SketchFillPreset.of(fill, color) ?: return AnnotatedString(described)
    val name = stringResource(presetName(preset))
    val full = stringResource(R.string.sketch_fill_named, described, name)
    val strong = MaterialTheme.colorScheme.onSurface
    return buildAnnotatedString {
        append(full)
        addStyle(SpanStyle(color = strong, fontWeight = FontWeight.Medium), full.length - name.length, full.length)
    }
}

/** « Ajouter ici » (filled, as the tile of the measure) and « Terminer » (outlined). */
@Composable
private fun ShapeButton(
    icon: ImageVector,
    label: String,
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tm = MaterialTheme.toolMap
    val shape = RoundedCornerShape(12.dp)
    val content = if (filled) tm.onNavIndicator else MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .height(44.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(shape)
            .then(
                if (filled) {
                    Modifier.background(tm.navIndicator)
                } else {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
                },
            ).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = content, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    }
}

private fun fillName(fill: SketchFill): Int =
    when (fill) {
        SketchFill.NONE -> R.string.sketch_fill_none
        SketchFill.TINT -> R.string.sketch_fill_tint
        SketchFill.HATCH -> R.string.sketch_fill_hatch
        SketchFill.STRIPES -> R.string.sketch_fill_stripes
        SketchFill.DOTS -> R.string.sketch_fill_dots
        SketchFill.GRID -> R.string.sketch_fill_grid
        SketchFill.CROSS -> R.string.sketch_fill_cross
        SketchFill.WAVES -> R.string.sketch_fill_waves
    }

private fun presetName(preset: SketchFillPreset): Int =
    when (preset) {
        SketchFillPreset.FORBIDDEN -> R.string.sketch_preset_forbidden
        SketchFillPreset.DANGER -> R.string.sketch_preset_danger
        SketchFillPreset.PRESERVE -> R.string.sketch_preset_preserve
        SketchFillPreset.TO_SEARCH -> R.string.sketch_preset_to_search
        SketchFillPreset.SEARCHED -> R.string.sketch_preset_searched
        SketchFillPreset.WET -> R.string.sketch_preset_wet
    }

/**
 * The strokes of the open sketch over the map, smoothed as they will be saved, and the stroke under the finger.
 * The pen and the eraser take the finger; the hand leaves it to the map.
 */
@Composable
private fun SketchCanvas(
    ui: SketchUi,
    vm: SketchViewModel,
    controller: MapController,
    /** The centre point of the shape mode, `null` in the other modes. */
    cross: Offset?,
) {
    val accent = MaterialTheme.toolMap.accent
    val live = remember { mutableStateListOf<Offset>() }
    var eraser by remember { mutableStateOf<Offset?>(null) }
    // Read by the gesture, which outlives a change of size: the radius of the disc is half the size of the eraser.
    val eraserDp by rememberUpdatedState(ui.eraserPx / 2f)
    val eraserPx = with(LocalDensity.current) { eraserDp.dp.toPx() }
    val toXY = { o: Offset -> controller.screenToLatLon(o.x, o.y)?.let { XY.of(LonLat(it.lon, it.lat)) } }
    val gestures =
        when (ui.mode) {
            // In the shape mode, the map moves as with the hand and a tap on it places a point (MapScreen).
            SketchMode.HAND, SketchMode.SHAPE -> {
                Modifier
            }

            SketchMode.PEN -> {
                Modifier.pointerInput(SketchMode.PEN) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        live.clear()
                        live.add(down.position)
                        var cancelled = false
                        while (true) {
                            val event = awaitPointerEvent()
                            // A second finger is a pinch, not a stroke.
                            if (event.changes.size > 1) cancelled = true
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            if (!cancelled) live.add(change.position)
                        }
                        if (!cancelled) {
                            val points = live.mapNotNull(toXY)
                            vm.addStroke(points, controller.liveCamera.zoom)
                        }
                        live.clear()
                    }
                }
            }

            SketchMode.ERASER -> {
                Modifier.pointerInput(SketchMode.ERASER) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        vm.beginErase()
                        val radius = { eraserDp * SketchScale.metersPerPixel(controller.liveCamera.zoom) }
                        eraser = down.position
                        toXY(down.position)?.let { vm.eraseAt(it, radius()) }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            eraser = change.position
                            toXY(change.position)?.let { vm.eraseAt(it, radius()) }
                        }
                        eraser = null
                        vm.endErase()
                    }
                }
            }
        }
    Canvas(Modifier.fillMaxSize().then(gestures)) {
        // Read here, in the drawing, so that only the drawing follows the camera.
        val camera = controller.liveCamera
        val projector = Projector.at(controller, camera.lat, camera.lon, camera.zoom) ?: return@Canvas
        for (s in ui.strokes) {
            val shape = s.raw.shape
            if (shape != null && shape.closed && s.raw.points.size >= SketchUi.MIN_ZONE_POINTS) {
                drawZoneFill(s.raw.points.map(projector::screen), shape.fill, s.raw.style.color)
            }
            drawSketchPath(
                s.smooth,
                Color(s.raw.style.color),
                (s.raw.style.widthMeters * projector.pxPerMeter).toFloat(),
                projector,
            )
        }
        if (live.isNotEmpty()) {
            val color = Color(ui.color)
            val width = ui.widthPx.dp.toPx()
            if (live.size == 1) {
                drawCircle(color, width / 2, live[0])
            } else {
                val path =
                    Path().apply {
                        moveTo(live[0].x, live[0].y)
                        for (i in 1 until live.size) lineTo(live[i].x, live[i].y)
                    }
                drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        eraser?.let { at ->
            drawCircle(Color.White.copy(alpha = 0.35f), eraserPx, at)
            drawCircle(Color(0xFF263238), eraserPx, at, style = Stroke(1.5.dp.toPx()))
        }
        if (ui.draft.isNotEmpty()) drawDraft(ui, projector, cross)
        // The centre point, turquoise ringed with white as on the map.
        cross?.let {
            drawCircle(Color.White, CROSS_RADIUS.toPx(), it, style = Stroke(5.dp.toPx()))
            drawCircle(accent, CROSS_RADIUS.toPx(), it, style = Stroke(3.dp.toPx()))
        }
    }
}

/**
 * The shape being placed: its fill up to the centre point for a zone, its sides, the dashed sides that join the centre
 * point, and white points at its corners.
 */
private fun DrawScope.drawDraft(
    ui: SketchUi,
    projector: Projector,
    cross: Offset?,
) {
    val color = Color(ui.color)
    val points = ui.draft.map(projector::screen)
    val width =
        if (ui.draftWidthMeters > 0) (ui.draftWidthMeters * projector.pxPerMeter).toFloat() else ui.widthPx.dp.toPx()
    if (ui.shapeClosed && cross != null && points.size >= 2) drawZoneFill(points + cross, ui.fill, ui.color)
    if (points.size >= 2) {
        val sides =
            Path().apply {
                moveTo(points[0].x, points[0].y)
                for (p in points.drop(1)) lineTo(p.x, p.y)
            }
        drawPath(sides, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    if (cross != null) {
        val elastic =
            Path().apply {
                moveTo(points.last().x, points.last().y)
                lineTo(cross.x, cross.y)
                if (ui.shapeClosed && points.size >= 2) lineTo(points[0].x, points[0].y)
            }
        val dash = 4.dp.toPx()
        drawPath(
            elastic,
            color,
            style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
        )
    }
    for (p in points) {
        drawCircle(Color.White, VERTEX_RADIUS.toPx(), p)
        drawCircle(color, VERTEX_RADIUS.toPx(), p, style = Stroke(2.5.dp.toPx()))
    }
}

/** The fill of a zone through [corners] on the screen: a tint, or its pattern in its colour (P28.4). */
private fun DrawScope.drawZoneFill(
    corners: List<Offset>,
    fill: SketchFill,
    color: Int,
) {
    if (fill == SketchFill.NONE || corners.size < SketchUi.MIN_ZONE_POINTS) return
    val path =
        Path().apply {
            moveTo(corners[0].x, corners[0].y)
            for (p in corners.drop(1)) lineTo(p.x, p.y)
            close()
        }
    if (fill == SketchFill.TINT) {
        drawPath(path, Color(color).copy(alpha = fill.opacity.toFloat()))
        return
    }
    val tile = SketchPatterns.bitmap(fill, color, density) ?: return
    drawPath(path, ShaderBrush(ImageShader(tile.asImageBitmap(), TileMode.Repeated, TileMode.Repeated)))
}

/**
 * Map coordinates to screen pixels for the camera of this frame: two points projected by the map give the scale and
 * the bearing, the rest is computed, and a Bézier stays a Bézier.
 */
private class Projector(
    private val origin: XY,
    private val s0: Offset,
    private val ux: Double,
    private val uy: Double,
) {
    val pxPerMeter: Double = hypot(ux, uy)

    fun screen(p: XY): Offset {
        val a = p.x - origin.x
        val b = p.y - origin.y
        return Offset((s0.x + a * ux - b * uy).toFloat(), (s0.y + a * uy + b * ux).toFloat())
    }

    companion object {
        fun at(
            controller: MapController,
            lat: Double,
            lon: Double,
            zoom: Double,
        ): Projector? {
            val origin = XY.of(LonLat(lon, lat))
            val step = 100 * SketchScale.metersPerPixel(zoom)
            val far = (origin + XY(step, 0.0)).toLonLat()
            val s0 = controller.latLonToScreen(lat, lon) ?: return null
            val s1 = controller.latLonToScreen(far.lat, far.lon) ?: return null
            return Projector(origin, s0, (s1.x - s0.x) / step, (s1.y - s0.y) / step)
        }
    }
}

private fun DrawScope.drawSketchPath(
    path: SketchPath,
    color: Color,
    width: Float,
    projector: Projector,
) {
    val start = projector.screen(path.start)
    if (path.curves.isEmpty()) {
        drawCircle(color, width / 2, start)
        return
    }
    val p =
        Path().apply {
            moveTo(start.x, start.y)
            for (c in path.curves) {
                val c1 = projector.screen(c.c1)
                val c2 = projector.screen(c.c2)
                val end = projector.screen(c.end)
                cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
            }
        }
    drawPath(p, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun smoothingLabel(level: SketchSmoothing): Int =
    when (level) {
        SketchSmoothing.LOW -> R.string.sketch_smoothing_low
        SketchSmoothing.MEDIUM -> R.string.sketch_smoothing_medium
        SketchSmoothing.HIGH -> R.string.sketch_smoothing_high
    }

private val MODES: List<Triple<SketchMode, ImageVector, Int>> =
    listOf(
        Triple(SketchMode.HAND, SketchIcons.Hand, R.string.sketch_mode_hand),
        Triple(SketchMode.PEN, SketchIcons.Pen, R.string.sketch_mode_pen),
        Triple(SketchMode.SHAPE, SketchIcons.Shape, R.string.sketch_mode_shape),
        Triple(SketchMode.ERASER, SketchIcons.Eraser, R.string.sketch_mode_eraser),
    )

private val SHAPE_KINDS: List<Triple<Boolean, ImageVector, Int>> =
    listOf(
        Triple(false, SketchIcons.Line, R.string.sketch_shape_line),
        Triple(true, SketchIcons.Shape, R.string.sketch_shape_zone),
    )

/** The patterns of the swatches: grey ink on a paper ground, as in the preview. */
private const val SWATCH_INK = 0xFF455A64.toInt()
private val SWATCH_PAPER = Color(0xFFEEF0E0)
private const val DISABLED_ALPHA = 0.38f
private val CROSS_RADIUS = 8.dp
private val VERTEX_RADIUS = 5.5.dp

/** A tap or « Ajouter ici » this close to the first point of a zone closes it. */
private const val SHAPE_REACH_DP = 24.0

private val COLOR_NAMES =
    listOf(
        R.string.sketch_color_red,
        R.string.sketch_color_orange,
        R.string.sketch_color_yellow,
        R.string.sketch_color_green,
        R.string.sketch_color_blue,
        R.string.sketch_color_violet,
        R.string.sketch_color_black,
        R.string.sketch_color_white,
    )

/** The disc beside the slider of the eraser stops growing here; the eraser itself goes up to 64 px. */
private val ERASER_PREVIEW_MAX = 40.dp
