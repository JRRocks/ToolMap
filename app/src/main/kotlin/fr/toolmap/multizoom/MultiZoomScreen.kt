package fr.toolmap.multizoom

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.source.DraftIssue
import fr.toolmap.core.source.MultiZoomDraft
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomBand
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.TopBarTextAction
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.Switch
import fr.toolmap.ui.theme.TextButton
import kotlin.math.roundToInt

/** Colours of the bands in the zoom strip, in turn. */
private val BAND_COLORS =
    listOf(
        Color(0xFF1E88E5),
        Color(0xFF43A047),
        Color(0xFFFB8C00),
        Color(0xFF8E24AA),
        Color(0xFFE53935),
        Color(0xFF00897B),
        Color(0xFF6D4C41),
    )

private fun bandColor(index: Int) = BAND_COLORS[index % BAND_COLORS.size]

/**
 * "Multi-zoom maps": the list of maps made of other maps by zoom range, and the editor of one of them. The editor
 * replaces the hand-edited AlpineQuest `.aqx` (`USR.PERSONNAL_MULTIZOOM.AQX`): each band says which map is shown at
 * which zooms, possibly always with the tiles of one zoom (the "zoom forcé" of some `.aqx` files). With [editorOnly]
 * (opened on a map of the catalogue), cancelling the editor closes the screen instead of showing the list.
 */
@Composable
fun MultiZoomScreen(
    vm: MultiZoomViewModel,
    onBack: () -> Unit,
    onShow: (sourceId: String) -> Unit,
    editorOnly: Boolean = false,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val editor = ui.editor
    val cancel = {
        vm.cancel()
        if (editorOnly) onBack()
    }
    // Back in the editor is its « Annuler »; in the list, the screen's own Back closes it.
    BackHandler(enabled = editor != null, onBack = cancel)
    if (editor == null) {
        MultiZoomList(ui, vm, onBack, onShow)
    } else {
        MultiZoomEditorScreen(editor, ui.choices, vm, onSaved = onShow, onCancel = cancel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MultiZoomList(
    ui: MultiZoomUi,
    vm: MultiZoomViewModel,
    onBack: () -> Unit,
    onShow: (String) -> Unit,
) {
    var toDelete by remember { mutableStateOf<TileSource?>(null) }
    var deriveMenu by remember { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            ToolMapTopBar(title = stringResource(R.string.mz_title), onBack = onBack)
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.startNew() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.mz_new)) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.mz_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Box {
                    OutlinedButton(onClick = { deriveMenu = true }) { Text(stringResource(R.string.mz_derive)) }
                    DropdownMenu(expanded = deriveMenu, onDismissRequest = { deriveMenu = false }) {
                        ui.choices.filterNot { it.isMultiZoom }.forEach { map ->
                            DropdownMenuItem(
                                text = { Text(map.name) },
                                onClick = {
                                    deriveMenu = false
                                    vm.derive(map.id)
                                },
                            )
                        }
                    }
                }
            }
            if (ui.maps.isEmpty()) item { Text(stringResource(R.string.mz_empty)) }
            items(ui.maps, key = { it.id }) { map ->
                val imported = vm.isImported(map.id)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(map.name, style = MaterialTheme.typography.titleMedium)
                        val draft = MultiZoomDraft.of(map)
                        if (draft != null) {
                            ZoomStrip(draft.bands)
                            Text(
                                bandsSummary(draft.bands, ui.choices),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!imported) {
                            Text(
                                stringResource(R.string.mz_builtin),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onShow(map.id) }) { Text(stringResource(R.string.mz_show)) }
                            TextButton(onClick = { vm.edit(map.id) }) {
                                Text(stringResource(if (imported) R.string.mz_edit else R.string.mz_copy))
                            }
                            if (imported) {
                                TextButton(onClick = { toDelete = map }) { Text(stringResource(R.string.am_delete)) }
                            }
                        }
                    }
                }
            }
        }
    }
    toDelete?.let { map ->
        val users = vm.usersOf(map.id)
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.am_delete_title)) },
            text = {
                Text(
                    if (users.isEmpty()) {
                        stringResource(R.string.mz_delete_text, map.name)
                    } else {
                        stringResource(R.string.mz_delete_used, map.name, users.joinToString(", ") { it.name })
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(map.id)
                    toDelete = null
                }) { Text(stringResource(R.string.am_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MultiZoomEditorScreen(
    editor: MultiZoomEditor,
    choices: List<TileSource>,
    vm: MultiZoomViewModel,
    onSaved: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val draft = editor.draft
    val byId = remember(choices) { choices.associateBy { it.id } }
    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            // An editor: the ✕ cancels, « Enregistrer » keeps its words.
            ToolMapTopBar(
                title =
                    stringResource(
                        if (editor.editingId == null) R.string.mz_new_title else R.string.mz_edit_title,
                    ),
                onBack = onCancel,
                navigationIcon = Icons.Filled.Close,
                navigationDescription = stringResource(R.string.action_cancel),
                actions = {
                    TopBarTextAction(
                        stringResource(R.string.mz_save),
                        onClick = { vm.save()?.let(onSaved) },
                        enabled = editor.canSave,
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = vm::setName,
                    label = { Text(stringResource(R.string.mz_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ZoomStrip(draft.bands)
                    Text(
                        stringResource(R.string.mz_strip_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val general = editor.issues.filter { it.band == null || it.kind == DraftIssue.Kind.GAP }
            if (general.isNotEmpty()) {
                item { IssueLines(general, draft.bands) }
            }
            itemsIndexed(draft.bands) { index, band ->
                BandCard(
                    index = index,
                    band = band,
                    map = byId[band.mapId],
                    choices = choices.filter { it.id != editor.editingId },
                    issues = editor.issues.filter { it.band == index && it.kind != DraftIssue.Kind.GAP },
                    vm = vm,
                )
            }
            item {
                OutlinedButton(onClick = vm::addBand, enabled = draft.bands.size < MAX_BANDS) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(stringResource(R.string.mz_add_band), modifier = Modifier.padding(start = 8.dp))
                }
            }
            item {
                Text(
                    stringResource(R.string.mz_editor_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            editor.error?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun BandCard(
    index: Int,
    band: ZoomBand,
    map: TileSource?,
    choices: List<TileSource>,
    issues: List<DraftIssue>,
    vm: MultiZoomViewModel,
) {
    var mapMenu by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.padding(end = 8.dp).size(16.dp)) {
                    drawRect(bandColor(index))
                }
                Text(
                    stringResource(R.string.mz_band_title, index + 1, band.from, band.to),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.removeBand(index) }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.mz_remove_band))
                }
            }
            var range by remember(band.from, band.to) {
                mutableStateOf(band.from.toFloat()..band.to.toFloat().coerceAtLeast(band.from.toFloat()))
            }
            RangeSlider(
                value = range,
                onValueChange = { range = it },
                onValueChangeFinished = {
                    vm.setRange(index, range.start.roundToInt(), range.endInclusive.roundToInt())
                },
                valueRange = 0f..MultiZoomViewModel.MAX_EDIT_ZOOM.toFloat(),
                steps = MultiZoomViewModel.MAX_EDIT_ZOOM - 1,
            )
            Box {
                OutlinedButton(onClick = { mapMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        map?.name ?: stringResource(R.string.mz_pick_map),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(expanded = mapMenu, onDismissRequest = { mapMenu = false }) {
                    choices.forEach { choice ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(choice.name)
                                    Text(
                                        stringResource(R.string.layers_zoom_range, choice.minZoom, choice.maxZoom),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            },
                            onClick = {
                                mapMenu = false
                                vm.setMap(index, choice.id)
                            },
                        )
                    }
                }
            }
            if (map != null && !map.isMultiZoom) {
                Text(
                    stringResource(R.string.mz_map_zooms, map.minZoom, map.maxZoom),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.mz_forced), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.mz_forced_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = band.forcedZoom != null, onCheckedChange = { vm.setForced(index, it) })
            }
            band.forcedZoom?.let { forced ->
                val top = (band.from + MultiZoomViewModel.FORCE_REACH).coerceAtMost(MultiZoomViewModel.MAX_EDIT_ZOOM)
                var value by remember(forced) { mutableStateOf(forced.toFloat()) }
                Text(
                    stringResource(R.string.mz_forced_zoom, value.roundToInt(), forcedEffect(band, value.roundToInt())),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = value.coerceIn(0f, top.toFloat()),
                    onValueChange = { value = it },
                    onValueChangeFinished = { vm.setForcedZoom(index, value.roundToInt()) },
                    valueRange = 0f..top.toFloat(),
                    steps = (top - 1).coerceAtLeast(0),
                )
            }
            if (issues.isNotEmpty()) IssueLines(issues, emptyList())
        }
    }
}

/** What a forced zoom does to the band: enlarged, shrunk, or the band's own zooms. */
@Composable
private fun forcedEffect(
    band: ZoomBand,
    forced: Int,
): String =
    when {
        forced > band.to -> stringResource(R.string.mz_forced_shrunk)
        forced < band.from -> stringResource(R.string.mz_forced_enlarged)
        else -> stringResource(R.string.mz_forced_mixed)
    }

@Composable
private fun IssueLines(
    issues: List<DraftIssue>,
    bands: List<ZoomBand>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (issue in issues) {
            val color = if (issue.kind.blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
            Text(issueText(issue, bands), style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}

@Composable
private fun issueText(
    issue: DraftIssue,
    bands: List<ZoomBand>,
): String =
    when (issue.kind) {
        DraftIssue.Kind.NO_NAME -> {
            stringResource(R.string.mz_issue_no_name)
        }

        DraftIssue.Kind.NO_BAND -> {
            stringResource(R.string.mz_issue_no_band)
        }

        DraftIssue.Kind.BAD_RANGE -> {
            stringResource(R.string.mz_issue_bad_range)
        }

        DraftIssue.Kind.OVERLAP -> {
            stringResource(R.string.mz_issue_overlap, (issue.other ?: 0) + 1)
        }

        DraftIssue.Kind.UNKNOWN_MAP -> {
            stringResource(R.string.mz_issue_unknown_map)
        }

        DraftIssue.Kind.LOOP -> {
            stringResource(R.string.mz_issue_loop)
        }

        DraftIssue.Kind.FORCED_TOO_FAR -> {
            pluralStringResource(
                R.plurals.mz_issue_forced_too_far,
                MultiZoomViewModel.FORCE_REACH,
                MultiZoomViewModel.FORCE_REACH,
            )
        }

        DraftIssue.Kind.FORCED_NOT_SERVED -> {
            stringResource(R.string.mz_issue_forced_not_served)
        }

        DraftIssue.Kind.NOT_SERVED -> {
            stringResource(R.string.mz_issue_not_served)
        }

        DraftIssue.Kind.GAP -> {
            val before = issue.band?.let(bands::getOrNull)
            val after = issue.other?.let(bands::getOrNull)
            if (before != null && after != null && before.to + 1 == after.from - 1) {
                stringResource(R.string.mz_issue_gap_one, before.to + 1)
            } else if (before != null && after != null) {
                stringResource(R.string.mz_issue_gap, before.to + 1, after.from - 1)
            } else {
                stringResource(R.string.mz_issue_gap_plain)
            }
        }
    }

/** Zooms 0 to [MultiZoomViewModel.MAX_EDIT_ZOOM] in a row, each in the colour of the band that shows it (grey when none does). */
@Composable
private fun ZoomStrip(bands: List<ZoomBand>) {
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val mark = MaterialTheme.colorScheme.onSurface
    Column {
        Canvas(modifier = Modifier.fillMaxWidth().height(18.dp)) {
            val cells = MultiZoomViewModel.MAX_EDIT_ZOOM + 1
            val cell = size.width / cells
            val gap = 1.dp.toPx()
            for (z in 0 until cells) {
                val index = bands.indexOfFirst { z in it.zooms }
                val band = bands.getOrNull(index)
                val left = z * cell
                drawRect(
                    if (band == null) empty else bandColor(index),
                    topLeft = Offset(left + gap / 2, 0f),
                    size = Size(cell - gap, size.height),
                )
                // A forced band is marked by a dot: its tiles come from one zoom.
                if (band?.forcedZoom != null) {
                    drawCircle(mark, radius = size.height / 6, center = Offset(left + cell / 2, size.height / 2))
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (z in listOf(0, 5, 10, 15, 20)) {
                Text("$z", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** "1-12 IGN Cartes · 13-14 IGN Cartes (zoom 15) · …" */
@Composable
private fun bandsSummary(
    bands: List<ZoomBand>,
    maps: List<TileSource>,
): String {
    val names = maps.associate { it.id to it.name }
    val forcedFormat = stringResource(R.string.mz_summary_forced)
    return bands.joinToString(" · ") { band ->
        val name = names[band.mapId] ?: band.mapId
        val range = if (band.from == band.to) "${band.from}" else "${band.from}-${band.to}"
        val forced = band.forcedZoom?.let { " " + forcedFormat.format(it) }.orEmpty()
        "$range $name$forced"
    }
}

private const val MAX_BANDS = MultiZoomViewModel.MAX_EDIT_ZOOM + 1
