package fr.toolmap.maps

import android.content.res.Resources
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.maps.AreaCorner
import fr.toolmap.core.maps.AreaSelection
import fr.toolmap.core.maps.ZoneStrips
import fr.toolmap.core.maps.ZoomCell
import fr.toolmap.core.maps.ZoomSpan
import fr.toolmap.core.state.CameraState
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.download.SourceEstimate
import fr.toolmap.download.ZoneEstimate
import fr.toolmap.download.formatBytes
import fr.toolmap.ui.CheckRow
import fr.toolmap.ui.theme.Button
import kotlin.math.roundToInt

private val HANDLE_TOUCH = 48.dp
private val HANDLE_DOT = 22.dp
private val STRIP_HEIGHT = 8.dp
private val ROWS_MAX_HEIGHT = 168.dp

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

/** The bottom panel of "Select and save an area": the maps with their zoom strips, the zoom, the total. */
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
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PanelHeader(
                title = stringResource(R.string.sa_title),
                onHelp = onHelp,
                trailing = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.sa_close))
                    }
                },
            )
            if (ui.rows.isEmpty()) {
                Text(stringResource(R.string.sa_no_maps), style = MaterialTheme.typography.bodyMedium)
            }
            Column(modifier = Modifier.heightIn(max = ROWS_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                for (row in ui.rows) {
                    MapRow(row, ui, currentTileZoom, maps)
                }
            }
            if (ui.sliderMax > 0) {
                Text(stringResource(R.string.sa_zoom, ui.maxZoom), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = ui.maxZoom.toFloat(),
                    onValueChange = { maps.setAreaMaxZoom(it.roundToInt()) },
                    valueRange = 0f..ui.sliderMax.toFloat(),
                    steps = (ui.sliderMax - 1).coerceAtLeast(0),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(totalText(ui), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Button(onClick = onSave, enabled = ui.canSave && !ui.estimating) {
                    Text(stringResource(R.string.sa_download))
                }
            }
        }
    }
}

@Composable
private fun totalText(ui: SaveAreaUi): String =
    when (val estimate = ui.estimate.takeUnless { ui.estimating }) {
        null -> {
            stringResource(R.string.sa_estimating)
        }

        is ZoneEstimate.Ready -> {
            val resources = LocalResources.current
            when {
                estimate.stored == 0L -> {
                    stringResource(R.string.sa_total, tilesText(resources, estimate.tiles), formatBytes(estimate.bytes))
                }

                estimate.missing == 0L -> {
                    stringResource(R.string.sa_total_all_stored, tilesText(resources, estimate.tiles))
                }

                else -> {
                    stringResource(
                        R.string.sa_total_missing,
                        tilesText(resources, estimate.missing),
                        formatBytes(estimate.bytes),
                        storedText(resources, estimate.stored),
                    )
                }
            }
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
    CheckRow(checked = row.checked, onCheckedChange = { maps.setAreaChecked(source.id, it) }) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (row.isElevation) {
                    stringResource(R.string.sa_elevation_row, row.forMaps.joinToString(", "))
                } else {
                    source.name
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (row.isElevation) elevationDetail(row, span, figures) else rowDetail(row, span, figures),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ZoomStrip(row.minZoom, row.maxZoom, ui, currentTileZoom, row.checked)
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
