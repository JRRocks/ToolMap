package fr.toolmap.sketch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.track.NameDates
import fr.toolmap.data.tracks.SavedSketch
import fr.toolmap.map.BarPanel
import fr.toolmap.map.MenuIcons
import fr.toolmap.map.PANEL_INSET
import fr.toolmap.map.PanelBand
import fr.toolmap.map.PanelSwitch
import fr.toolmap.tracks.ConfirmDialog
import fr.toolmap.tracks.RenameDialog
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.DropdownMenu
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max

/** One sketch of the list, with what the screen knows about the zoom of the map. */
data class SketchRow(
    val saved: SavedSketch,
    /** Thinner than a pixel at the zoom of the map: hidden there, shown again from [visibleFromZoom]. */
    val hiddenHere: Boolean,
    val visibleFromZoom: Int?,
)

/*
 * The « Croquis » space (P28.6), variant A « Trousse » of docs/previews/p28-croquis.html, chosen on 2026-11-03: the
 * panel of the flag turns into the list of the sketches, a back arrow returning to « Traces et waypoints ». Each row
 * has a thumbnail, the name, « 3 traits · aujourd'hui », a switch that shows or hides the sketch and ⋮ (Ouvrir,
 * Renommer, Supprimer); a tap on the row opens the sketch. « Nouveau croquis » ends the list.
 */
@Composable
fun SketchSpacePanel(
    rows: List<SketchRow>,
    onBack: () -> Unit,
    onHelp: () -> Unit,
    onNew: () -> Unit,
    onOpen: (Long) -> Unit,
    onShow: (Long, Boolean) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    var renaming by remember { mutableStateOf<SavedSketch?>(null) }
    var deleting by remember { mutableStateOf<SavedSketch?>(null) }
    val shown = rows.count { it.saved.visible && !it.hiddenHere }
    BarPanel(
        maxHeight = maxHeight,
        modifier = modifier,
        header = {
            PanelBand(
                title = stringResource(R.string.sketch_title),
                subtitle =
                    stringResource(
                        R.string.sketch_band_sub,
                        pluralStringResource(R.plurals.sketch_count, rows.size, rows.size),
                        if (shown == 0) {
                            stringResource(R.string.sketch_none_shown)
                        } else {
                            pluralStringResource(R.plurals.sketch_shown, shown, shown)
                        },
                    ),
                leading = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            ) {
                IconButton(onClick = onHelp) {
                    Icon(MenuIcons.Help, contentDescription = stringResource(R.string.menu_help))
                }
            }
        },
        footer = {
            Button(
                onClick = onNew,
                modifier =
                    Modifier.fillMaxWidth().padding(
                        horizontal = PANEL_INSET,
                    ).padding(top = 4.dp, bottom = 12.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sketch_new))
            }
        },
    ) {
        for (row in rows) {
            SketchListRow(
                row = row,
                onOpen = { onOpen(row.saved.id) },
                onShow = { onShow(row.saved.id, it) },
                onRename = { renaming = row.saved },
                onDelete = { deleting = row.saved },
            )
        }
    }
    renaming?.let { s ->
        RenameDialog(
            title = stringResource(R.string.sketch_rename_title),
            initial = s.sketch.name,
            onConfirm = { name ->
                onRename(s.id, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { s ->
        ConfirmDialog(
            title = stringResource(R.string.sketch_delete_title, s.sketch.name),
            message = stringResource(R.string.sketch_delete_text),
            confirmLabel = stringResource(R.string.sketch_delete),
            onConfirm = {
                onDelete(s.id)
                deleting = null
            },
            onDismiss = { deleting = null },
            destructive = true,
        )
    }
}

@Composable
private fun SketchListRow(
    row: SketchRow,
    onOpen: () -> Unit,
    onShow: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val saved = row.saved
    val dim = if (row.hiddenHere) DIM_ALPHA else 1f
    val count = saved.sketch.strokes.size
    val day =
        if (sameDay(saved.updatedAt, System.currentTimeMillis())) {
            stringResource(R.string.sketch_today)
        } else {
            NameDates.formatDay(saved.updatedAt)
        }
    val hidden = row.visibleFromZoom?.takeIf { row.hiddenHere }?.let { stringResource(R.string.sketch_hidden_zoom, it) }
    val line =
        listOfNotNull(
            stringResource(R.string.sketch_row_sub, pluralStringResource(R.plurals.sketch_strokes, count, count), day),
            hidden,
        ).joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(start = PANEL_INSET, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SketchThumbnail(saved, Modifier.size(48.dp).alpha(dim))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                saved.sketch.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(dim),
            )
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
            )
        }
        Box(Modifier.clickable(role = Role.Switch) { onShow(!saved.visible) }.padding(4.dp)) {
            PanelSwitch(checked = saved.visible)
        }
        var menu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.sketch_more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.sketch_open)) }, onClick = {
                    menu = false
                    onOpen()
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.sketch_rename)) }, onClick = {
                    menu = false
                    onRename()
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.sketch_delete)) }, onClick = {
                    menu = false
                    onDelete()
                })
            }
        }
    }
}

/** The strokes of a sketch fitted into a small square on the colour of a paper map. */
@Composable
fun SketchThumbnail(
    saved: SavedSketch,
    modifier: Modifier = Modifier,
) {
    val strokes = saved.sketch.strokes
    val points = remember(strokes) { strokes.map { s -> s.path.sample(s.style.widthMeters) } }
    Canvas(modifier.clip(MaterialTheme.shapes.small).background(THUMB_PAPER)) {
        val all = points.flatten()
        if (all.isEmpty()) return@Canvas
        val minX = all.minOf { it.x }
        val maxX = all.maxOf { it.x }
        val minY = all.minOf { it.y }
        val maxY = all.maxOf { it.y }
        val inset = size.minDimension * 0.15f
        val span = max(maxX - minX, maxY - minY).coerceAtLeast(1e-6)
        val scale = ((size.minDimension - 2 * inset) / span).toFloat()
        val ox = (size.width - (maxX - minX).toFloat() * scale) / 2
        val oy = (size.height - (maxY - minY).toFloat() * scale) / 2
        strokes.forEachIndexed { i, s ->
            val color = Color(s.style.color)
            val pts = points[i]
            val width = (s.style.widthMeters.toFloat() * scale).coerceIn(1.5f, 4.dp.toPx())
            val at = { k: Int ->
                Offset(
                    ox + (pts[k].x - minX).toFloat() * scale,
                    oy + (pts[k].y - minY).toFloat() * scale,
                )
            }
            if (pts.size == 1) {
                drawCircle(color, radius = width, center = at(0))
            } else {
                val path =
                    Path().apply {
                        moveTo(at(0).x, at(0).y)
                        for (k in 1 until pts.size) lineTo(at(k).x, at(k).y)
                    }
                drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

private fun sameDay(
    a: Long,
    b: Long,
): Boolean {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
}

private const val DIM_ALPHA = 0.55f
private val THUMB_PAPER = Color(0xFFE9ECDD)
