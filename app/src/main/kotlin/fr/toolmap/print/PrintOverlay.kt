package fr.toolmap.print

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.print.PageSetup
import fr.toolmap.core.print.Paper
import fr.toolmap.core.print.PrintFormat
import fr.toolmap.core.print.PrintPlan
import fr.toolmap.core.print.PrintTemplate
import fr.toolmap.core.print.PrintTexts
import fr.toolmap.core.print.PrintViewport
import fr.toolmap.core.state.CameraState
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.map.ToolPanel
import fr.toolmap.settings.SettingsIcons
import fr.toolmap.ui.MapPill
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.DropdownMenu
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.TextButton
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The page's map frame over the map at its true ground size (paper millimeters times the scale), fixed on the ground
 * at [centerLat], [centerLon]: what is inside is what the sheet will show. The outside is dimmed. When [onMove] is
 * given, a finger dragged inside the frame moves it and two fingers zoom the map ([onZoom]); the map outside the
 * frame keeps its own gestures. The print is always north-up, so a rotated map shows the frame tilted the same way.
 */
@Composable
fun PrintFrameOverlay(
    camera: CameraState,
    template: PrintTemplate,
    centerLat: Double,
    centerLon: Double,
    project: (lat: Double, lon: Double) -> Offset?,
    unproject: (x: Float, y: Float) -> TrackPoint?,
    onMove: ((lat: Double, lon: Double) -> Unit)?,
    onZoom: (factor: Float) -> Unit,
) {
    val density = LocalDensity.current.density
    val frameColor = MaterialTheme.colorScheme.primary
    val dim = Color.Black.copy(alpha = DIM_ALPHA)
    // The camera is a key only: when it changes the map moved, and the centre is projected again.
    val center = remember(camera, centerLat, centerLon) { project(centerLat, centerLon) } ?: return
    val (widthDp, heightDp) = PrintViewport.frameDp(template.page, template.scale, centerLat, camera.zoom)
    val w = (widthDp * density).toFloat()
    val h = (heightDp * density).toFloat()
    if (!(w > 0f && h > 0f) || !w.isFinite() || !h.isFinite()) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        val left = center.x - w / 2f
        val top = center.y - h / 2f
        rotate(degrees = -camera.bearing.toFloat(), pivot = center) {
            val reach = maxOf(size.width, size.height) * 2f
            clipRect(left, top, left + w, top + h, ClipOp.Difference) {
                drawRect(dim, Offset(-reach, -reach), Size(size.width + 2f * reach, size.height + 2f * reach))
            }
            drawRect(frameColor, Offset(left, top), Size(w, h), style = Stroke(FRAME_STROKE_DP * density))
            val arm = CROSS_DP * density
            drawLine(frameColor, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), density)
            drawLine(frameColor, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), density)
        }
    }
    if (onMove == null) return
    val latestCenter by rememberUpdatedState(center)
    val latestUnproject by rememberUpdatedState(unproject)
    val latestMove by rememberUpdatedState(onMove)
    val latestZoom by rememberUpdatedState(onZoom)
    val description = stringResource(R.string.print_frame_drag)
    Box(
        modifier =
            Modifier
                .offset { IntOffset((center.x - w / 2f).roundToInt(), (center.y - h / 2f).roundToInt()) }
                .size((w / density).dp, (h / density).dp)
                .semantics { contentDescription = description }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        // The centre follows the finger from where it was when the finger went down; once a second
                        // finger came, the gesture only zooms (the centre on screen moved with the zoom).
                        var anchor = latestCenter
                        var zoomed = false
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } > 1) {
                                zoomed = true
                                val zoom = event.calculateZoom()
                                if (zoom != 1f) latestZoom(zoom)
                            } else if (!zoomed) {
                                val pan = event.calculatePan()
                                if (pan != Offset.Zero) {
                                    anchor += pan
                                    latestUnproject(anchor.x, anchor.y)?.let { latestMove(it.lat, it.lon) }
                                }
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                },
    )
}

/**
 * The pages of a print along a track over the map (F9): each frame at its true ground size, numbered in the order of
 * the walk. They follow from the track, the format and the scale, so they do not move; the map keeps its gestures.
 */
@Composable
fun PrintPagesOverlay(
    camera: CameraState,
    pages: List<List<LonLat>>,
    project: (lat: Double, lon: Double) -> Offset?,
) {
    val density = LocalDensity.current.density
    val color = MaterialTheme.colorScheme.primary
    val numberStyle =
        MaterialTheme.typography.labelMedium.copy(
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
        )
    val measurer = rememberTextMeasurer()
    // The camera is a key only: when it changes the map moved, and the corners are projected again.
    val screens = remember(camera, pages) { pages.map { corners -> corners.mapNotNull { project(it.lat, it.lon) } } }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val stroke = Stroke(FRAME_STROKE_DP * density)
        val drawn = screens.filter { it.size == CORNERS }
        for (corners in drawn) {
            val outline =
                Path().apply {
                    moveTo(corners[0].x, corners[0].y)
                    for (i in 1 until CORNERS) lineTo(corners[i].x, corners[i].y)
                    close()
                }
            drawPath(outline, color.copy(alpha = PAGE_FILL_ALPHA))
            drawPath(outline, color, style = stroke)
        }
        // The numbers over every outline, in the top left corner of their page (along its edges: the map may turn).
        screens.forEachIndexed { index, corners ->
            if (corners.size != CORNERS) return@forEachIndexed
            val along = (corners[1] - corners[0]).unit() + (corners[3] - corners[0]).unit()
            val badge = corners[0] + along * (BADGE_INSET_DP * density)
            drawCircle(color, BADGE_RADIUS_DP * density, badge)
            val number = measurer.measure((index + 1).toString(), numberStyle)
            drawText(number, topLeft = badge - Offset(number.size.width / 2f, number.size.height / 2f))
        }
    }
}

/** The four corners on the ground of each map page, clockwise from the top left. */
fun PrintPlan.pageCorners(): List<List<LonLat>> =
    mapPages.map { page ->
        val f = page.frame
        listOf(
            f.toLonLat(0.0, 0.0),
            f.toLonLat(f.frameWidthMm, 0.0),
            f.toLonLat(f.frameWidthMm, f.frameHeightMm),
            f.toLonLat(0.0, f.frameHeightMm),
        )
    }

private fun Offset.unit(): Offset = getDistance().let { if (it > 0f) this / it else Offset.Zero }

/**
 * The band over the map while the frame is placed: the format, the orientation and the scale, each a tap away, and
 * nothing else (preview validated on 2026-10-26): the ground size and the warnings wait for the confirmation sheet. A
 * white pill of the map since P29.10.
 */
@Composable
fun PrintFrameBar(
    ui: PrintUi,
    onUpdate: ((PrintTemplate) -> PrintTemplate) -> Unit,
    onRotate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val template = ui.template
    var custom by remember { mutableStateOf(false) }
    var customScale by remember { mutableStateOf(false) }
    MapPill(modifier = modifier) {
        Row(
            modifier =
                Modifier.padding(
                    start = 8.dp,
                    top = 4.dp,
                    bottom = 4.dp,
                ).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MenuChip(paperName(template.page)) { close ->
                for (paper in Paper.OFFERED) {
                    DropdownMenuItem(
                        text = { Text(paperChoiceName(paper)) },
                        onClick = {
                            close()
                            if (paper == Paper.CUSTOM) {
                                custom = true
                            } else {
                                onUpdate { it.copy(page = it.page.copy(paper = paper)) }
                            }
                        },
                    )
                }
            }
            MenuChip(orientationName(template.page)) { close ->
                for (landscape in listOf(false, true)) {
                    DropdownMenuItem(
                        text = {
                            Text(orientationName(landscape))
                        },
                        onClick = {
                            close()
                            onUpdate { it.copy(page = it.page.copy(landscape = landscape)) }
                        },
                    )
                }
            }
            MenuChip(PrintFormat.scale(template.scale, PrintTexts())) { close ->
                for (scale in PrintTemplate.SCALES) {
                    DropdownMenuItem(
                        text = { Text(PrintFormat.scale(scale.toDouble(), PrintTexts())) },
                        onClick = {
                            close()
                            onUpdate { it.copy(scale = scale.toDouble()) }
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.print_scale_other)) },
                    onClick = {
                        close()
                        customScale = true
                    },
                )
            }
            IconButton(onClick = onRotate) {
                Icon(SettingsIcons.Rotate90, contentDescription = stringResource(R.string.print_rotate))
            }
        }
    }
    if (custom) {
        CustomPaperDialog(
            setup = template.page,
            onDone = { w, h ->
                custom = false
                onUpdate { it.copy(page = it.page.copy(paper = Paper.CUSTOM, customWidthMm = w, customHeightMm = h)) }
            },
            onDismiss = { custom = false },
        )
    }
    if (customScale) {
        CustomScaleDialog(
            scale = template.scale,
            onDone = { s ->
                customScale = false
                onUpdate { it.copy(scale = s) }
            },
            onDismiss = { customScale = false },
        )
    }
}

/**
 * Annuler / Suivant under the frame, with the reminder that the frame moves under a finger: the panel « Imprimer à
 * l'échelle », without ✕ (P29.10, as in the validated preview of the frame: « Annuler » gives up).
 */
@Composable
fun PrintFrameActions(
    onCancel: () -> Unit,
    onNext: () -> Unit,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    ToolPanel(title = stringResource(R.string.print_title), maxHeight = maxHeight, modifier = modifier) {
        Text(
            stringResource(R.string.print_frame_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        CancelNext(onCancel = onCancel, onNext = onNext, nextEnabled = true)
    }
}

/**
 * Under the pages of a print along a track (F9): how many pages, how they overlap, then Annuler / Suivant, in the panel
 * « Imprimer à l'échelle » (P29.10). Suivant waits for the pages to be counted.
 */
@Composable
fun PrintTrackActions(
    ui: PrintUi,
    onCancel: () -> Unit,
    onNext: () -> Unit,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val plan = ui.plan
    val pages = plan?.mapPages?.size ?: 0
    ToolPanel(title = stringResource(R.string.print_title), maxHeight = maxHeight, modifier = modifier) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            if (plan == null || ui.planning) {
                Text(stringResource(R.string.print_planning), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    AnnotatedString.fromHtml(pluralStringResource(R.plurals.print_track_pages, pages, pages)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(
                        R.string.print_track_overlap,
                        plan.template.overlapPercent.roundToInt().toString(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (issue in plan.issues) {
                    Text(
                        stringResource(issueText(issue)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        CancelNext(onCancel = onCancel, onNext = onNext, nextEnabled = pages > 0 && !ui.planning)
    }
}

/** « Annuler » in outline and « Suivant », the filled button, side by side. */
@Composable
private fun CancelNext(
    onCancel: () -> Unit,
    onNext: () -> Unit,
    nextEnabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.print_cancel))
        }
        Button(onClick = onNext, enabled = nextEnabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.print_next))
        }
    }
}

/**
 * A chip that opens a menu: the menu items get a function that closes it. Compact since P29.10 (32 dp seen, its name
 * and its arrow), so that the format, the orientation, the scale and the rotation fit the pill on a phone.
 */
@Composable
private fun MenuChip(
    label: String,
    items: @Composable (close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true },
            shape = MaterialTheme.shapes.small,
            color = Color.Transparent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                modifier = Modifier.height(32.dp).padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { items { open = false } }
    }
}

@Composable
fun CustomPaperDialog(
    setup: PageSetup,
    onDone: (widthMm: Double, heightMm: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var width by remember { mutableStateOf(setup.customWidthMm.roundToInt().toString()) }
    var height by remember { mutableStateOf(setup.customHeightMm.roundToInt().toString()) }
    val w = width.replace(',', '.').toDoubleOrNull()
    val h = height.replace(',', '.').toDoubleOrNull()
    // No largest size (2026-10-29): a sheet too big for its resolution is told on the confirmation.
    val valid = w != null && h != null && PageSetup.isCustomSide(w) && PageSetup.isCustomSide(h)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.print_paper_custom_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.print_paper_custom_hint), style = MaterialTheme.typography.bodySmall)
                NumberText(R.string.print_custom_width, width) { width = it }
                NumberText(R.string.print_custom_height, height) { height = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (valid) onDone(w!!, h!!) }, enabled = valid) {
                Text(stringResource(R.string.print_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.print_cancel)) } },
    )
}

@Composable
fun CustomScaleDialog(
    scale: Double,
    onDone: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(Math.round(scale).toString()) }
    val value = text.replace(" ", "").replace(" ", "").toDoubleOrNull()
    val valid = value != null && value in PrintTemplate.MIN_SCALE..PrintTemplate.MAX_SCALE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.print_scale_other)) },
        text = { NumberText(R.string.print_scale_custom, text) { text = it } },
        confirmButton = {
            TextButton(onClick = { if (valid) onDone(value!!) }, enabled = valid) {
                Text(stringResource(R.string.print_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.print_cancel)) } },
    )
}

/** "A4", "Letter", "250 × 180 mm"... */
@Composable
fun paperName(setup: PageSetup): String =
    when (setup.paper) {
        Paper.LETTER -> stringResource(R.string.print_paper_letter)
        Paper.LEGAL -> stringResource(R.string.print_paper_legal)
        Paper.CUSTOM -> String.format(Locale.ROOT, "%.0f × %.0f mm", setup.customWidthMm, setup.customHeightMm)
        else -> setup.paper.name
    }

@Composable
fun paperChoiceName(paper: Paper): String =
    when (paper) {
        Paper.LETTER -> stringResource(R.string.print_paper_letter)
        Paper.LEGAL -> stringResource(R.string.print_paper_legal)
        Paper.CUSTOM -> stringResource(R.string.print_paper_custom)
        else -> paper.name
    }

@Composable
fun orientationName(setup: PageSetup): String = orientationName(setup.landscape)

/** "Paysage" or "Portrait". */
@Composable
fun orientationName(landscape: Boolean): String =
    stringResource(if (landscape) R.string.print_landscape else R.string.print_portrait)

/** "A4 paysage · 1:25 000 · 6,6 × 3,7 km": what the sheet is, in one line. */
@Composable
fun summaryLine(template: PrintTemplate): String =
    stringResource(
        R.string.print_summary_line,
        paperName(template.page),
        orientationName(template.page).lowercase(),
        PrintFormat.scale(template.scale, PrintTexts()),
        groundSize(template).first,
        groundSize(template).second,
    )

/** The ground size of the frame, in the phone's language. */
private fun groundSize(template: PrintTemplate): Pair<String, String> =
    PrintFormat.groundSize(
        PrintViewport.groundMeters(template.page.frameWidthMm, template.scale),
        PrintViewport.groundMeters(template.page.frameHeightMm, template.scale),
        Locale.getDefault(),
    )

private const val DIM_ALPHA = 0.35f
private const val FRAME_STROKE_DP = 2f
private const val CROSS_DP = 8f
private const val CORNERS = 4
private const val PAGE_FILL_ALPHA = 0.08f
private const val BADGE_RADIUS_DP = 9f
private const val BADGE_INSET_DP = 12f
