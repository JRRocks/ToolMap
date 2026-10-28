package fr.toolmap.maps

import android.content.res.Resources
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.maps.AreaCorner
import fr.toolmap.core.maps.AreaCorridor
import fr.toolmap.core.maps.AreaSelection
import fr.toolmap.core.maps.CorridorWidth
import fr.toolmap.core.maps.ZoneStrips
import fr.toolmap.core.maps.ZoomCell
import fr.toolmap.core.maps.ZoomSpan
import fr.toolmap.core.route.AvoidZone
import fr.toolmap.core.state.CameraState
import fr.toolmap.core.track.PathSimplifier
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.download.SourceEstimate
import fr.toolmap.download.ZoneEstimate
import fr.toolmap.download.formatBytes
import fr.toolmap.map.MenuIcons
import fr.toolmap.map.PanelBand
import fr.toolmap.ui.CheckRow
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap
import kotlin.math.roundToInt

private val HANDLE_TOUCH = 48.dp
private val HANDLE_DOT = 22.dp
private val STRIP_HEIGHT = 8.dp
private val ROWS_MAX_HEIGHT = 216.dp

/**
 * The rectangle over the map: the part outside it is dimmed, and four handles move its corners. The rectangle
 * is fixed on the ground, so it is redrawn from [camera] each time the map moves. Only the handles take
 * touches, the rest of the map stays usable.
 */
@Composable
fun SaveAreaOverlay(
    selection: AreaSelection,
    camera: CameraState,
    project: (lat: Double, lon: Double) -> Offset?,
    unproject: (x: Float, y: Float) -> TrackPoint?,
    onDrag: (AreaCorner, lat: Double, lon: Double) -> Unit,
    /** Draw the dimmed outside and the frame; off for the call that only puts the handles over the other parts. */
    shade: Boolean = true,
    /** Put the four handles; off for the call under the other parts of the map. */
    handles: Boolean = true,
) {
    // The camera is a key only: when it changes the map moved, and the corners are projected again.
    val corners =
        remember(selection, camera) {
            AreaCorner.entries.associateWith { corner -> project(selection.latOf(corner), selection.lonOf(corner)) }
        }
    val ring = listOf(AreaCorner.NORTH_WEST, AreaCorner.NORTH_EAST, AreaCorner.SOUTH_EAST, AreaCorner.SOUTH_WEST)
    val points = ring.mapNotNull { corners[it] }
    val scrim = Color.Black.copy(alpha = SCRIM_ALPHA)
    val line = MaterialTheme.colorScheme.primary
    if (shade) {
        Canvas(Modifier.fillMaxSize()) {
            if (points.size == ring.size) {
                val hole =
                    Path().apply {
                        points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                        close()
                    }
                val shade =
                    Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(0f, 0f, size.width, size.height))
                        addPath(hole)
                    }
                drawPath(shade, scrim)
                drawPath(hole, line, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
    if (!handles) return
    val description = stringResource(R.string.sa_handle)
    for ((corner, position) in corners) {
        if (position == null) continue
        CornerHandle(corner, position, unproject, onDrag, description)
    }
}

/**
 * The band around a track or a route over the map (« Télécharger autour », P30.2): outside it dimmed as for the
 * rectangle, its edge in the colour of the panel, the line itself left to the map. It is fixed on the ground, so it is
 * drawn again from [camera] each time the map moves; it takes no touch.
 */
@Composable
fun SaveCorridorOverlay(
    corridor: AreaCorridor,
    camera: CameraState,
    project: (lat: Double, lon: Double) -> Offset?,
) {
    // A line thinned to a fifth of the width draws the band as well, and keeps a long track light to project.
    val drawn =
        remember(corridor.path, corridor.radiusMeters) {
            PathSimplifier.simplify(
                corridor.path.map { TrackPoint(it.lat, it.lon) },
                (corridor.radiusMeters / BAND_THINNING).coerceAtLeast(MIN_THINNING_METERS),
            )
        }
    // The camera is a key only: when it changes the map moved, and the line is projected again.
    val points = remember(drawn, camera) { drawn.mapNotNull { project(it.lat, it.lon) } }
    val halfWidth =
        remember(drawn, corridor.radiusMeters, camera) {
            val p = drawn[drawn.size / 2]
            val east = AvoidZone(p.lat, p.lon, corridor.radiusMeters).east()
            val a = project(p.lat, p.lon)
            val b = project(east.lat, east.lon)
            if (a == null || b == null) 0f else (b - a).getDistance()
        }
    if (points.size < 2) return
    val scrim = Color.Black.copy(alpha = SCRIM_ALPHA)
    val line = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val path =
            Path().apply {
                points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            }
        val edge = 2.dp.toPx()
        drawRect(scrim)
        // The edge, then the inside of the band cleared: only the edge is left of the stroke, the map shows inside.
        drawPath(
            path,
            line,
            style = Stroke(width = 2 * halfWidth + 2 * edge, cap = StrokeCap.Round, join = StrokeJoin.Round),
            blendMode = BlendMode.Src,
        )
        drawPath(
            path,
            Color.Black,
            style = Stroke(width = 2 * halfWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
            blendMode = BlendMode.Clear,
        )
    }
}

/** The band is drawn on its line thinned to its width divided by this (at least [MIN_THINNING_METERS]). */
private const val BAND_THINNING = 5.0
private const val MIN_THINNING_METERS = 25.0

@Composable
private fun CornerHandle(
    corner: AreaCorner,
    position: Offset,
    unproject: (x: Float, y: Float) -> TrackPoint?,
    onDrag: (AreaCorner, lat: Double, lon: Double) -> Unit,
    description: String,
) {
    val latest by rememberUpdatedState(position)
    val latestUnproject by rememberUpdatedState(unproject)
    val latestOnDrag by rememberUpdatedState(onDrag)
    val fill = MaterialTheme.colorScheme.surface
    val ring = MaterialTheme.colorScheme.primary
    Box(
        modifier =
            Modifier
                .offset {
                    val half = HANDLE_TOUCH.toPx() / 2
                    IntOffset((position.x - half).roundToInt(), (position.y - half).roundToInt())
                }.size(HANDLE_TOUCH)
                .semantics { contentDescription = description }
                .pointerInput(corner) {
                    var finger = Offset.Zero
                    detectDragGestures(
                        onDragStart = { finger = latest },
                        onDrag = { change, amount ->
                            change.consume()
                            finger += amount
                            latestUnproject(finger.x, finger.y)?.let { latestOnDrag(corner, it.lat, it.lon) }
                        },
                    )
                }.drawBehind {
                    val radius = HANDLE_DOT.toPx() / 2
                    drawCircle(fill, radius)
                    drawCircle(ring, radius, style = Stroke(width = 3.dp.toPx()))
                },
    )
}

/**
 * The sheet of « Enregistrer une zone » (P29.7, « Poids »): what the zone weighs, in large, with the room left on the
 * phone; the maximal zoom; the maps folded in one line (« IGN Cartes, OpenTopoMap · 2 sur 3 ») that unfolds their rows
 * and zoom strips in the sheet; « Enregistrer la zone » across the sheet. The states of the total (computing, too
 * large, the limit of a community server, no tile, already saved) take the place of the large figure.
 */
@Composable
fun SaveAreaPanel(
    ui: SaveAreaUi,
    currentTileZoom: Double,
    maps: MapsViewModel,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val figures by maps.storageFigures.collectAsStateWithLifecycle()
    var unfolded by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        color = MaterialTheme.toolMap.panel,
        shadowElevation = 8.dp,
    ) {
        Column {
            val band = ui.corridor
            PanelBand(title = stringResource(if (band != null) R.string.sa_band_corridor else R.string.sa_band)) {
                IconButton(onClick = onHelp) {
                    Icon(MenuIcons.Help, contentDescription = stringResource(R.string.menu_help))
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.sa_close))
                }
            }
            Weight(ui, figures?.freeBytes)
            if (band != null) CorridorWidthRow(band.radiusMeters, maps::setCorridorRadius)
            if (ui.sliderMax > 0) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.sa_zoom_label),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${ui.maxZoom}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Slider(
                        value = ui.maxZoom.toFloat(),
                        onValueChange = { maps.setAreaMaxZoom(it.roundToInt()) },
                        valueRange = 0f..ui.sliderMax.toFloat(),
                        steps = (ui.sliderMax - 1).coerceAtLeast(0),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (ui.rows.isEmpty()) {
                Text(
                    stringResource(R.string.sa_no_maps),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                MapsLine(ui, unfolded) { unfolded = !unfolded }
                if (unfolded) {
                    Column(Modifier.heightIn(max = ROWS_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                        ui.rows.forEachIndexed { index, row ->
                            if (index > 0) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                                )
                            }
                            MapRow(row, ui, currentTileZoom, maps)
                        }
                    }
                }
            }
            Button(
                onClick = onSave,
                enabled = ui.canSave && !ui.estimating,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
                        .heightIn(min = 48.dp),
            ) {
                Text(stringResource(if (band != null) R.string.sa_download_corridor else R.string.sa_download))
            }
        }
    }
}

/**
 * « Largeur de chaque côté » of a band around a track or a route (P30.2, variant B of
 * `docs/previews/p30-itineraires.html`): a slider from 50 m to 5 km on a logarithmic scale, the width over it, which a
 * touch opens to type it. The band (and its weight) follow when the slider is let go.
 */
@Composable
private fun CorridorWidthRow(
    radius: Int,
    onWidth: (Int) -> Unit,
) {
    val fmt = LocalFormats.current
    var typing by rememberSaveable { mutableStateOf(false) }
    var fraction by remember(radius) { mutableFloatStateOf(CorridorWidth.toFraction(radius).toFloat()) }
    // A typed width (« 777 m ») stays as typed until the slider moves.
    var moved by remember(radius) { mutableStateOf(false) }
    val shown = if (moved) CorridorWidth.fromFraction(fraction.toDouble()) else radius
    val width = stringResource(R.string.sa_corridor_value, fmt.size(shown))
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.sa_corridor_width),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                width,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier =
                    Modifier
                        .clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.sa_corridor_type),
                        ) { typing = true }
                        .heightIn(min = 40.dp)
                        .wrapContentHeight(),
            )
        }
        Slider(
            value = fraction,
            onValueChange = {
                fraction = it
                moved = true
            },
            onValueChangeFinished = { onWidth(CorridorWidth.fromFraction(fraction.toDouble())) },
            modifier = Modifier.semantics { stateDescription = width },
        )
        Row {
            Text(fmt.size(CorridorWidth.MIN), style = MaterialTheme.typography.bodySmall, color = muted)
            Spacer(Modifier.weight(1f))
            Text(fmt.size(CorridorWidth.DEFAULT), style = MaterialTheme.typography.bodySmall, color = muted)
            Spacer(Modifier.weight(1f))
            Text(fmt.size(CorridorWidth.MAX), style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
    if (typing) {
        CorridorWidthDialog(
            radius = radius,
            onDone = { meters ->
                typing = false
                onWidth(meters)
            },
            onDismiss = { typing = false },
        )
    }
}

/** The width of the band typed in meters (P30.2): kept to the meter, from 50 m to 5 km. */
@Composable
private fun CorridorWidthDialog(
    radius: Int,
    onDone: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(radius.toString()) }
    val meters = text.trim().toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sa_corridor_width)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { value -> text = value.filter(Char::isDigit).take(MAX_DIGITS) },
                singleLine = true,
                suffix = { Text("m") },
                supportingText = { Text(stringResource(R.string.sa_corridor_bounds)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(onClick = { meters?.let { onDone(CorridorWidth.clamp(it)) } }, enabled = meters != null) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val MAX_DIGITS = 5

/**
 * « 9,5 Mo » in large with « 5,0 Go libres », then « 415 tuiles à télécharger · 31 déjà stockées »; a state of the total
 * in place of the figure while there is none.
 */
@Composable
private fun Weight(
    ui: SaveAreaUi,
    freeBytes: Long?,
) {
    val resources = LocalResources.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val estimate = ui.estimate.takeUnless { ui.estimating }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            val ready = estimate as? ZoneEstimate.Ready
            val big =
                when {
                    ready == null -> null
                    ready.missing == 0L -> null
                    else -> formatBytes(ready.bytes)
                }
            Box(Modifier.weight(1f)) {
                if (big != null) {
                    Text(
                        big,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Medium,
                    )
                } else {
                    Text(
                        totalState(estimate),
                        style = MaterialTheme.typography.titleMedium,
                        color =
                            if (estimate is ZoneEstimate.TooMany || estimate is ZoneEstimate.Restricted) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            if (freeBytes != null && freeBytes > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                ) {
                    Icon(
                        MapsIcons.PhoneAndroid,
                        contentDescription = null,
                        tint = muted,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(R.string.sa_free, formatBytes(freeBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
        (estimate as? ZoneEstimate.Ready)?.let { ready ->
            Text(
                when {
                    ready.missing == 0L -> {
                        tilesText(resources, ready.tiles)
                    }

                    ready.stored == 0L -> {
                        stringResource(R.string.sa_to_fetch, tilesText(resources, ready.missing))
                    }

                    else -> {
                        stringResource(R.string.sa_to_fetch, tilesText(resources, ready.missing)) + " · " +
                            storedText(resources, ready.stored)
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
        }
    }
}

/** The total when there is no weight to show: computing, already saved, nothing, too large, a server's limit. */
@Composable
private fun totalState(estimate: ZoneEstimate?): String =
    when (estimate) {
        null -> {
            stringResource(R.string.sa_estimating)
        }

        is ZoneEstimate.Ready -> {
            stringResource(R.string.sa_all_stored)
        }

        ZoneEstimate.Empty -> {
            stringResource(R.string.download_estimate_empty)
        }

        ZoneEstimate.TooMany -> {
            stringResource(R.string.download_estimate_too_many)
        }

        is ZoneEstimate.Restricted -> {
            stringResource(R.string.download_estimate_restricted, estimate.sourceName, estimate.limit, estimate.tiles)
        }
    }

/** « Cartes » folded: the names of the maps checked and how many of the maps offered they are. */
@Composable
private fun MapsLine(
    ui: SaveAreaUi,
    unfolded: Boolean,
    onToggle: () -> Unit,
) {
    val checked = ui.rows.filter { it.checked && !it.isElevation }
    val maps = ui.rows.filterNot { it.isElevation }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(value = unfolded, role = Role.Button, onValueChange = { onToggle() })
                .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MapsIcons.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        Column(Modifier.weight(1f).padding(start = 20.dp)) {
            Text(stringResource(R.string.sa_maps), style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(
                    checked.joinToString(", ") { it.source.name }.ifEmpty { null },
                    pluralStringResource(R.plurals.sa_maps_count, maps.size, checked.size, maps.size),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            if (unfolded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = stringResource(if (unfolded) R.string.am_collapse else R.string.am_expand),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MapRow(
    row: AreaRow,
    ui: SaveAreaUi,
    currentTileZoom: Double,
    maps: MapsViewModel,
) {
    val source = row.source
    val span = ZoneStrips.span(row.minZoom, row.maxZoom, ui.maxZoom)
    val figures = maps.rowFigures(ui, source.id)
    CheckRow(
        checked = row.checked,
        onCheckedChange = { maps.setAreaChecked(source.id, it) },
        modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (row.isElevation) {
                    stringResource(R.string.sa_elevation_row, row.forMaps.joinToString(", "))
                } else {
                    source.name
                },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // In words, 12 sp (« Zooms 6 à 16 (sur 18) · … »), no longer « 6-16/18 » in 11 sp.
            Text(
                if (row.isElevation) elevationDetail(row, span, figures) else rowDetail(row, span, figures),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                StripEnd("0")
                Box(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    ZoomStrip(row.minZoom, row.maxZoom, ui, currentTileZoom, row.checked)
                }
                StripEnd("${ui.sliderMax}")
            }
        }
    }
}

@Composable
private fun rowDetail(
    row: AreaRow,
    span: ZoomSpan?,
    figures: SourceEstimate?,
): String =
    when {
        !row.checked -> {
            stringResource(R.string.sa_row_unchecked)
        }

        span == null -> {
            stringResource(R.string.sa_row_none)
        }

        figures == null -> {
            stringResource(R.string.sa_row_waiting, span.from, span.to, span.available)
        }

        figures.stored == 0L -> {
            val count = tilesText(LocalResources.current, figures.tiles)
            stringResource(R.string.sa_row, span.from, span.to, span.available, count, formatBytes(figures.bytes))
        }

        figures.missing == 0L -> {
            stringResource(R.string.sa_row_all_stored, span.from, span.to, span.available)
        }

        else -> {
            val resources = LocalResources.current
            stringResource(
                R.string.sa_row_stored,
                span.from,
                span.to,
                span.available,
                tilesText(resources, figures.missing),
                formatBytes(figures.bytes),
                storedText(resources, figures.stored),
            )
        }
    }

/** The « Altitudes » row: "3-13 · 42 tuiles · 2.1 Mo", its zooms being those the masks read, not a map's own. */
@Composable
private fun elevationDetail(
    row: AreaRow,
    span: ZoomSpan?,
    figures: SourceEstimate?,
): String =
    when {
        !row.checked -> {
            stringResource(R.string.sa_row_unchecked)
        }

        span == null -> {
            stringResource(R.string.sa_row_none)
        }

        figures == null -> {
            stringResource(R.string.sa_elevation_waiting, span.from, span.to)
        }

        figures.stored == 0L -> {
            val count = tilesText(LocalResources.current, figures.tiles)
            stringResource(R.string.sa_elevation, span.from, span.to, count, formatBytes(figures.bytes))
        }

        figures.missing == 0L -> {
            stringResource(R.string.sa_elevation_all_stored, span.from, span.to)
        }

        else -> {
            val resources = LocalResources.current
            stringResource(
                R.string.sa_elevation_stored,
                span.from,
                span.to,
                tilesText(resources, figures.missing),
                formatBytes(figures.bytes),
                storedText(resources, figures.stored),
            )
        }
    }

/** « 0 » and « 18 » at the two ends of a zoom strip. */
@Composable
private fun StripEnd(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** "12 déjà stockées": the tiles of the zone a download would skip. */
private fun storedText(
    resources: Resources,
    count: Long,
): String {
    val n = count.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    return resources.getQuantityString(R.plurals.sa_stored, n, n)
}

/** One cell per zoom: saved, kept out by the chosen maximum, or not served by the map; a mark shows the map's zoom. */
@Composable
private fun ZoomStrip(
    sourceMin: Int,
    sourceMax: Int,
    ui: SaveAreaUi,
    currentTileZoom: Double,
    checked: Boolean,
) {
    val cells =
        remember(sourceMin, sourceMax, ui.maxZoom, ui.sliderMax) {
            ZoneStrips.cells(sourceMin, sourceMax, ui.maxZoom, ui.sliderMax)
        }
    val marker = ZoneStrips.marker(currentTileZoom, ui.sliderMax)
    val saved = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val skipped = MaterialTheme.colorScheme.outlineVariant
    val absent = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ABSENT_ALPHA)
    val markColor = MaterialTheme.colorScheme.error
    Canvas(Modifier.fillMaxWidth().height(STRIP_HEIGHT + 4.dp).padding(top = 2.dp)) {
        val gap = 1.dp.toPx()
        val cellWidth = size.width / cells.size
        val barHeight = STRIP_HEIGHT.toPx()
        cells.forEachIndexed { index, cell ->
            val color =
                when (cell) {
                    ZoomCell.SAVED -> saved
                    ZoomCell.SKIPPED -> skipped
                    ZoomCell.ABSENT -> absent
                }
            drawRoundRect(
                color,
                topLeft = Offset(index * cellWidth + gap / 2, 0f),
                size = Size(cellWidth - gap, barHeight),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
        if (marker != null) {
            val x = marker * size.width
            drawLine(markColor, Offset(x, 0f), Offset(x, barHeight + 2.dp.toPx()), strokeWidth = 2.dp.toPx())
        }
    }
}

private const val SCRIM_ALPHA = 0.45f
private const val ABSENT_ALPHA = 0.5f
