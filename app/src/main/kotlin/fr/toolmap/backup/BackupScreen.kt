package fr.toolmap.backup

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.backup.BackupArea
import fr.toolmap.core.backup.BackupManifest
import fr.toolmap.core.backup.RestoreReport
import fr.toolmap.core.problem.catchExpected
import fr.toolmap.download.formatBytes
import fr.toolmap.map.MenuIcons
import fr.toolmap.maps.MapThumbnail
import fr.toolmap.maps.MapThumbnails
import fr.toolmap.maps.MapsIcons
import fr.toolmap.settings.SettingsIcons
import fr.toolmap.ui.GroupBlock
import fr.toolmap.ui.GroupCaption
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.FilledTonalButton
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap
import java.io.IOException
import java.time.LocalDate

private const val MIME_ZIP = "application/zip"

/** The areas of data on « Sauvegarder », in the order of the list (P33, variant C). */
private val SAVE_ORDER =
    listOf(
        BackupArea.DATABASE,
        BackupArea.WORKSPACES,
        BackupArea.SOURCES,
        BackupArea.SETTINGS,
        BackupArea.ALERTS,
        BackupArea.PRINT,
        BackupArea.STATE,
    )

/** How many of the lightest areas share one line. */
private const val MERGED = 3

/** The lines of the restore dialog (P33, variant C): the areas of a backup in four groups. */
private enum class RestoreGroup(
    val areas: Set<BackupArea>,
) {
    TRACKS(setOf(BackupArea.DATABASE)),
    FAVORITES(setOf(BackupArea.WORKSPACES, BackupArea.SOURCES)),
    SETTINGS(setOf(BackupArea.SETTINGS, BackupArea.ALERTS, BackupArea.PRINT, BackupArea.STATE)),
    TILES(setOf(BackupArea.TILES)),
}

/** The two halves of the screen (P29.9): one decision at a time. */
private enum class BackupSegment { SAVE, RESTORE }

/**
 * Saves the app's data to a zip and restores it from one (P29.9, « Loupe »; P33, « Poids »): a segmented button parts
 * « Sauvegarder » and « Restaurer », each with one main action. A restore waiting for the restart opens the screen on
 * « Restaurer », its card first; the report of the last restore and the progress of a task are above the segmented
 * button, which is greyed while a task runs.
 */
@Composable
fun BackupScreen(
    vm: BackupViewModel,
    thumbnails: MapThumbnails,
    onBack: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.messages.collect { snackbar.showSnackbar(resources.getString(it.resId, *it.args.toTypedArray())) }
    }

    val createLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_ZIP)) { uri ->
            if (uri != null) {
                vm.create(
                    open = { context.contentResolver.openOutputStream(uri) },
                    discard = {
                        // Best effort: the provider of the document may refuse to delete it or not support it.
                        catchExpected(
                            "backup.create.delete",
                            IOException::class,
                            SecurityException::class,
                            UnsupportedOperationException::class,
                            IllegalArgumentException::class,
                        ) { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    },
                )
            }
        }
    val openLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.choose { context.contentResolver.openInputStream(uri) }
        }
    BackupPage(
        ui = ui,
        thumbnails = thumbnails,
        snackbar = snackbar,
        onBack = onBack,
        onToggleAreas = vm::toggleAreas,
        onToggleTiles = vm::toggleTiles,
        onCreate = { createLauncher.launch("toolmap-${LocalDate.now()}.zip") },
        onChoose = { openLauncher.launch(arrayOf(MIME_ZIP, "application/x-zip-compressed", "*/*")) },
        onRestart = { restartApp(context) },
        onCancelPending = vm::cancelPending,
        onHideReport = vm::dismissReport,
    )

    ui.preview?.let { manifest ->
        RestoreDialog(
            manifest = manifest,
            chosen = ui.restoreAreas,
            onToggle = vm::toggleRestore,
            onConfirm = vm::confirm,
            onDismiss = vm::cancelPreview,
        )
    }
}

/** The screen itself, from what [ui] says: the progress and the report, the segmented button, then its half. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupPage(
    ui: BackupUi,
    thumbnails: MapThumbnails,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onToggleAreas: (Set<BackupArea>) -> Unit,
    onToggleTiles: (String) -> Unit,
    onCreate: () -> Unit,
    onChoose: () -> Unit,
    onRestart: () -> Unit,
    onCancelPending: () -> Unit,
    onHideReport: () -> Unit,
) {
    val context = LocalContext.current
    val busy = ui.busy != null
    var segment by rememberSaveable { mutableStateOf(BackupSegment.SAVE) }
    // A restore waiting is the most urgent: the screen shows it as soon as it is known.
    val pending = ui.pending != null
    LaunchedEffect(pending) { if (pending) segment = BackupSegment.RESTORE }
    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            ToolMapTopBar(title = stringResource(R.string.backup_title), onBack = onBack)
        },
        bottomBar = { if (segment == BackupSegment.SAVE) SaveBar(ui = ui, busy = busy, onCreate = onCreate) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
        ) {
            ui.busy?.let { task -> Progress(task, ui.progress) }
            ui.report?.let { report -> Report(report, context, onHide = onHideReport) }
            Segments(segment, enabled = !busy) { segment = it }
            when (segment) {
                BackupSegment.SAVE -> {
                    SaveHalf(
                        ui = ui,
                        busy = busy,
                        thumbnails = thumbnails,
                        onToggleAreas = onToggleAreas,
                        onToggleTiles = onToggleTiles,
                    )
                }

                BackupSegment.RESTORE -> {
                    RestoreHalf(
                        pending = ui.pending,
                        busy = busy,
                        context = context,
                        onRestart = onRestart,
                        onCancel = onCancelPending,
                        onChoose = onChoose,
                    )
                }
            }
        }
    }
}

/** What runs, with its bar: determined when the amount is known. */
@Composable
private fun Progress(
    task: BackupBusy,
    progress: Float?,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Text(
            stringResource(
                when (task) {
                    BackupBusy.CREATING -> R.string.backup_busy_creating
                    BackupBusy.CHECKING -> R.string.backup_busy_checking
                    else -> R.string.backup_busy_unpacking
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        if (progress == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** How the last restore went, until « Masquer ». */
@Composable
private fun Report(
    report: RestoreReport,
    context: Context,
    onHide: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val container = if (report.ok) colors.secondaryContainer else colors.errorContainer
    val content = if (report.ok) colors.onSecondaryContainer else colors.onErrorContainer
    Column(
        modifier =
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                .fillMaxWidth()
                .background(container, MaterialTheme.shapes.medium)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(
            if (report.ok) {
                stringResource(R.string.backup_report_ok, dateText(context, report.backupMillis))
            } else {
                stringResource(R.string.backup_report_failed)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = content,
        )
        TextButton(onClick = onHide, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.backup_report_dismiss))
        }
    }
}

/** « Sauvegarder » | « Restaurer »: the chosen segment shows a tick in place of its icon. */
@Composable
private fun Segments(
    selected: BackupSegment,
    enabled: Boolean,
    onSelect: (BackupSegment) -> Unit,
) {
    val segments =
        listOf(
            Triple(BackupSegment.SAVE, SettingsIcons.Backup, R.string.backup_save_title),
            Triple(BackupSegment.RESTORE, SettingsIcons.SettingsBackupRestore, R.string.backup_restore_title),
        )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        segments.forEachIndexed { index, (segment, icon, label) ->
            SegmentedButton(
                selected = segment == selected,
                onClick = { onSelect(segment) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, segments.size),
                icon = {
                    SegmentedButtonDefaults.Icon(active = segment == selected) {
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                        )
                    }
                },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

/**
 * « Sauvegarder » (P33, variant C « Poids »): the gauge of the file, data against offline maps; the areas of data to
 * tick with their weight, the three lightest on one line that unfolds; the offline maps one by one with their bar,
 * unticked at first; what is never saved. « Créer une sauvegarde… » is in the bar at the foot ([SaveBar]).
 */
@Composable
private fun SaveHalf(
    ui: BackupUi,
    busy: Boolean,
    thumbnails: MapThumbnails,
    onToggleAreas: (Set<BackupArea>) -> Unit,
    onToggleTiles: (String) -> Unit,
) {
    var unfolded by rememberSaveable { mutableStateOf(false) }
    Spacer(Modifier.height(12.dp))
    WeightGauge(dataBytes = ui.dataBytes, tilesBytes = ui.tilesBytes)
    GroupCaption(stringResource(R.string.backup_data_caption))
    val bytes = { area: BackupArea -> ui.areaBytes[area] ?: 0L }
    // The lightest share one line; on a tie, the later in the list.
    val merged =
        SAVE_ORDER
            .withIndex()
            .sortedWith(compareBy({ bytes(it.value) }, { -it.index }))
            .take(MERGED)
            .map { it.value }
            .toSet()
    val mergedInOrder = SAVE_ORDER.filter { it in merged }
    val resources = LocalResources.current
    val areaRow = { area: BackupArea ->
        @Composable {
            AreaRow(
                icon = areaIcon(area),
                title = stringResource(areaLabel(area)),
                bytes = bytes(area),
                state = ToggleableState(area in ui.areas),
                enabled = !busy,
                onToggle = { onToggleAreas(setOf(area)) },
            )
        }
    }
    val rows =
        buildList<@Composable () -> Unit> {
            SAVE_ORDER.filterNot { it in merged }.forEach { add(areaRow(it)) }
            add {
                val ticked = mergedInOrder.count { it in ui.areas }
                AreaRow(
                    icon = if (unfolded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    title = pluralStringResource(R.plurals.backup_other_areas, mergedInOrder.size, mergedInOrder.size),
                    subtitle =
                        mergedInOrder
                            .mapIndexed { index, area ->
                                val name = resources.getString(areaLabel(area))
                                if (index == 0) name else name.replaceFirstChar { it.lowercase() }
                            }.joinToString(", "),
                    bytes = mergedInOrder.sumOf { bytes(it) },
                    state =
                        when (ticked) {
                            0 -> ToggleableState.Off
                            mergedInOrder.size -> ToggleableState.On
                            else -> ToggleableState.Indeterminate
                        },
                    enabled = !busy,
                    onToggle = { onToggleAreas(merged) },
                    onOpen = { unfolded = !unfolded },
                    openLabel =
                        stringResource(
                            if (unfolded) R.string.backup_other_areas_hide else R.string.backup_other_areas_show,
                        ),
                )
            }
            if (unfolded) mergedInOrder.forEach { add(areaRow(it)) }
        }
    GroupBlock(modifier = Modifier.padding(horizontal = 16.dp), dividerStart = 56.dp, rows = rows)
    if (ui.offlineMaps.isNotEmpty()) {
        GroupCaption(stringResource(R.string.backup_tiles_caption))
        val heaviest = ui.offlineMaps.maxOf { it.bytes }.coerceAtLeast(1L)
        GroupBlock(
            modifier = Modifier.padding(horizontal = 16.dp),
            dividerStart = 60.dp,
            rows =
                ui.offlineMaps.map { map ->
                    {
                        OfflineMapRow(
                            map = map,
                            share = map.bytes.toFloat() / heaviest,
                            checked = map.file in ui.tiles,
                            enabled = !busy,
                            thumbnails = thumbnails,
                            onToggle = { onToggleTiles(map.file) },
                        )
                    }
                },
        )
    }
    Text(
        stringResource(R.string.backup_not_saved_short),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
    )
}

/**
 * What the file will weigh, « 263 Mo fichier .zip », and a bar shared between the data (primary colour, never thinner
 * than 6 dp, so that it stays seen) and the offline maps ticked (accent), with its legend.
 */
@Composable
private fun WeightGauge(
    dataBytes: Long,
    tilesBytes: Long,
) {
    val tm = MaterialTheme.toolMap
    val dataColor = MaterialTheme.colorScheme.primary
    val total = dataBytes + tilesBytes
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(tm.panel)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
    ) {
        Row {
            Text(
                formatBytes(total),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.backup_gauge_file),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alignByBaseline(),
            )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 8.dp)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp)),
        ) {
            drawRect(tm.tile)
            if (total > 0) {
                val data =
                    (size.width * dataBytes / total)
                        .coerceAtLeast(GAUGE_MIN_PART.toPx())
                        .coerceAtMost(size.width)
                drawRect(dataColor, size = Size(data, size.height))
                if (tilesBytes > 0) {
                    drawRect(tm.accent, topLeft = Offset(data, 0f), size = Size(size.width - data, size.height))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GaugeLegend(dataColor, stringResource(R.string.backup_gauge_data, formatBytes(dataBytes)))
            if (tilesBytes > 0) {
                GaugeLegend(tm.accent, stringResource(R.string.backup_gauge_tiles, formatBytes(tilesBytes)))
            }
        }
    }
}

@Composable
private fun GaugeLegend(
    color: Color,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A line of the data: a grey icon, the name (and what it stands for), the weight, the box. [onOpen], on the line that
 * stands for the lightest areas, unfolds them: then only the box ticks.
 */
@Composable
private fun AreaRow(
    icon: ImageVector,
    title: String,
    bytes: Long,
    state: ToggleableState,
    enabled: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
    onOpen: (() -> Unit)? = null,
    openLabel: String? = null,
) {
    val action =
        if (onOpen == null) {
            Modifier.triStateToggleable(state = state, enabled = enabled, role = Role.Checkbox, onClick = onToggle)
        } else {
            Modifier.clickable(onClickLabel = openLabel, onClick = onOpen)
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (subtitle == null) 48.dp else 56.dp)
                .then(action)
                .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Weight(bytes)
        TriStateCheckbox(
            state = state,
            onClick = if (onOpen == null) null else onToggle,
            enabled = enabled,
            modifier = if (onOpen == null) Modifier.padding(12.dp) else Modifier,
        )
    }
}

/** An offline map: its picture, its name over a bar of its weight against the heaviest (accent when ticked). */
@Composable
private fun OfflineMapRow(
    map: OfflineMapFile,
    share: Float,
    checked: Boolean,
    enabled: Boolean,
    thumbnails: MapThumbnails,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
                .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MapThumbnail(map.pictureId, map.name, thumbnails, size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(map.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val bar = if (checked) MaterialTheme.toolMap.accent else MaterialTheme.colorScheme.outline
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .width(MAP_BAR)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(Modifier.fillMaxWidth(share.coerceIn(MAP_BAR_MIN, 1f)).fillMaxHeight().background(bar))
            }
        }
        Weight(map.bytes)
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun Weight(bytes: Long) {
    Text(
        formatBytes(bytes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.padding(start = 8.dp),
    )
}

/** The foot of « Sauvegarder »: the weight without the offline maps, and « Créer une sauvegarde… ». */
@Composable
private fun SaveBar(
    ui: BackupUi,
    busy: Boolean,
    onCreate: () -> Unit,
) {
    Column(Modifier.background(MaterialTheme.toolMap.panel).navigationBarsPadding()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.backup_without_tiles, formatBytes(ui.dataBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val enabled = !busy && ui.canCreate
            val label = @Composable { Text(stringResource(R.string.backup_save_button)) }
            // Tonal while a restore waits, whose « Redémarrer maintenant » is the main action.
            if (ui.pending != null) {
                FilledTonalButton(onClick = onCreate, enabled = enabled) { label() }
            } else {
                Button(onClick = onCreate, enabled = enabled) { label() }
            }
        }
    }
}

/**
 * « Restaurer »: the restore waiting first, with « Redémarrer maintenant », the only filled button of the screen; then
 * what a restore does, and « Choisir une sauvegarde… », filled when nothing waits.
 */
@Composable
private fun RestoreHalf(
    pending: BackupManifest?,
    busy: Boolean,
    context: Context,
    onRestart: () -> Unit,
    onCancel: () -> Unit,
    onChoose: () -> Unit,
) {
    if (pending != null) {
        Column(
            modifier =
                Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium)
                    .padding(12.dp),
        ) {
            val onCard = MaterialTheme.colorScheme.onPrimaryContainer
            Row(verticalAlignment = Alignment.Top) {
                Icon(SettingsIcons.SettingsBackupRestore, contentDescription = null, tint = onCard)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.backup_pending_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = onCard,
                    )
                    Text(
                        stringResource(
                            R.string.backup_pending_text,
                            dateText(context, pending.createdAtMillis),
                            pending.entries.size,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = onCard,
                    )
                }
            }
            Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onRestart, enabled = !busy) { Text(stringResource(R.string.backup_restart)) }
                TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.backup_pending_cancel)) }
            }
        }
    }
    Text(
        stringResource(R.string.backup_restore_text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
    )
    val wide = Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 48.dp)
    val label = @Composable { Text(stringResource(R.string.backup_restore_button)) }
    if (pending != null) {
        OutlinedButton(onClick = onChoose, enabled = !busy, modifier = wide) { label() }
    } else {
        Button(onClick = onChoose, enabled = !busy, modifier = wide) { label() }
    }
}

/**
 * « Restaurer cette sauvegarde ? » (P33, variant C): the date and version of the file, then its areas in four lines to
 * tick, with their weight, and what the ticked ones weigh. An unticked line stays as it is on the phone; the offline
 * maps are added to the phone's (ADR-106).
 */
@Composable
internal fun RestoreDialog(
    manifest: BackupManifest,
    chosen: Set<BackupArea>,
    onToggle: (Set<BackupArea>) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        // As wide as the screen but 16 dp on each side, as in the preview: a line keeps its name and weight.
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(R.string.backup_preview_title)) },
        text = {
            Column {
                val date =
                    DateUtils.formatDateTime(
                        context,
                        manifest.createdAtMillis,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR,
                    )
                val intro =
                    if (manifest.covers(BackupArea.TILES)) {
                        R.string.backup_restore_intro
                    } else {
                        R.string.backup_restore_intro_no_tiles
                    }
                Text(
                    stringResource(intro, date, manifest.appVersion),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                for (group in RestoreGroup.entries) {
                    val held = group.areas.filterTo(HashSet()) { manifest.covers(it) }
                    if (held.isEmpty()) continue
                    val checked = chosen.containsAll(held)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(held) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            groupIcon(group),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            groupLabel(group, manifest),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Weight(held.sumOf { manifest.bytes(it) })
                        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                val total = chosen.filter { manifest.covers(it) }.sumOf { manifest.bytes(it) }
                Text(
                    stringResource(R.string.backup_restore_total, formatBytes(total)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = chosen.isNotEmpty()) {
                Text(stringResource(R.string.backup_preview_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun groupLabel(
    group: RestoreGroup,
    manifest: BackupManifest,
): String =
    when (group) {
        RestoreGroup.TRACKS -> stringResource(R.string.backup_area_database)
        RestoreGroup.FAVORITES -> stringResource(R.string.backup_group_favorites)
        RestoreGroup.SETTINGS -> stringResource(R.string.backup_group_settings)
        RestoreGroup.TILES -> stringResource(R.string.backup_group_tiles, manifest.fileCount(BackupArea.TILES))
    }

private fun groupIcon(group: RestoreGroup): ImageVector =
    when (group) {
        RestoreGroup.TRACKS -> SettingsIcons.Route
        RestoreGroup.FAVORITES -> MapsIcons.Workspaces
        RestoreGroup.SETTINGS -> Icons.Filled.Settings
        RestoreGroup.TILES -> SettingsIcons.OfflinePin
    }

/** The short names of the areas (P33): they fit with their weight and their box. */
private fun areaLabel(area: BackupArea): Int =
    when (area) {
        BackupArea.DATABASE -> R.string.backup_area_database
        BackupArea.SETTINGS -> R.string.backup_area_settings
        BackupArea.ALERTS -> R.string.backup_area_alerts
        BackupArea.STATE -> R.string.backup_area_state
        BackupArea.SOURCES -> R.string.backup_area_sources
        BackupArea.WORKSPACES -> R.string.backup_area_workspaces
        BackupArea.PRINT -> R.string.backup_area_print
        BackupArea.TILES -> R.string.backup_area_tiles
    }

private fun areaIcon(area: BackupArea): ImageVector =
    when (area) {
        BackupArea.DATABASE -> SettingsIcons.Route
        BackupArea.WORKSPACES -> MapsIcons.Workspaces
        BackupArea.SOURCES -> MapsIcons.Map
        BackupArea.SETTINGS -> Icons.Filled.Settings
        BackupArea.ALERTS -> SettingsIcons.NotificationsActive
        BackupArea.PRINT -> MenuIcons.Print
        BackupArea.STATE -> SettingsIcons.Layers
        BackupArea.TILES -> SettingsIcons.OfflinePin
    }

private val GAUGE_MIN_PART = 6.dp
private val MAP_BAR = 120.dp

/** The shortest bar of an offline map, as a share of the longest: a tiny map stays seen. */
private const val MAP_BAR_MIN = 0.03f

private fun dateText(
    context: Context,
    millis: Long,
): String =
    DateUtils.formatDateTime(
        context,
        millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
    )

/**
 * Starts the app again in a new process, so that the restore waiting on disk is applied before anything reads a file.
 * The launch is asked first (the app is in the foreground, so the system allows it), then this process ends.
 */
private fun restartApp(context: Context) {
    val intent =
        context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?: return
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}
