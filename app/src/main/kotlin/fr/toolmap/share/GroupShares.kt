package fr.toolmap.share

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.measure.Measure
import fr.toolmap.core.p2p.GroupItemKind
import fr.toolmap.core.p2p.GroupPoint
import fr.toolmap.core.p2p.PointDuration
import fr.toolmap.core.track.NameDates
import fr.toolmap.draw.RouteIcons
import fr.toolmap.map.MenuIcons
import fr.toolmap.settings.SettingsIcons
import fr.toolmap.sketch.SketchIcons
import fr.toolmap.ui.Formats
import fr.toolmap.ui.GroupBlock
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.TextButton

// The shares of the group (P27): the block « Dans le groupe », the sheet « Partager dans le groupe », the dialog that
// sends (variant B of `docs/previews/p27-creer-pour-le-groupe.html`, 2026-11-03) and the sheet of a share.

/** The last message under « Messages »: « Marie : On attend », or the sentence of a share. */
@Composable
internal fun messageLine(message: ShareMessage): String =
    message.point?.let { shareSentence(message, it) }
        ?: stringResource(R.string.share_messages_last, messageAuthor(message), message.text)

/** The automatic message of a share: « Marie a partagé un croquis « Accès parking » », « Vous avez retiré… ». */
@Composable
internal fun shareSentence(
    message: ShareMessage,
    point: GroupPoint,
): String {
    val name = message.text
    return when {
        message.removal && message.mine -> {
            stringResource(R.string.group_shared_removed_mine, name)
        }

        message.removal -> {
            stringResource(R.string.group_shared_removed, message.name, name)
        }

        message.mine -> {
            when (point.kind) {
                GroupItemKind.WAYPOINT -> stringResource(R.string.group_shared_waypoint_mine, name)
                GroupItemKind.TRACK -> stringResource(R.string.group_shared_track_mine, name)
                GroupItemKind.SKETCH -> stringResource(R.string.group_shared_sketch_mine, name)
            }
        }

        else -> {
            when (point.kind) {
                GroupItemKind.WAYPOINT -> stringResource(R.string.group_shared_waypoint, message.name, name)
                GroupItemKind.TRACK -> stringResource(R.string.group_shared_track, message.name, name)
                GroupItemKind.SKETCH -> stringResource(R.string.group_shared_sketch, message.name, name)
            }
        }
    }
}

internal fun kindIcon(kind: GroupItemKind): ImageVector =
    when (kind) {
        GroupItemKind.WAYPOINT -> SettingsIcons.Place
        GroupItemKind.TRACK -> MenuIcons.Polyline
        GroupItemKind.SKETCH -> SketchIcons.Pen
    }

@Composable
private fun kindLabel(kind: GroupItemKind): String =
    stringResource(
        when (kind) {
            GroupItemKind.WAYPOINT -> R.string.group_item_waypoint
            GroupItemKind.TRACK -> R.string.group_item_track
            GroupItemKind.SKETCH -> R.string.group_item_sketch
        },
    )

/** The colour of the author of [point], `null` when it is not in the group any more. */
private fun authorColor(
    point: GroupPoint,
    ui: ShareUi,
): Color? = ui.members.firstOrNull { it.id == point.author }?.let { Color(it.argb) }

/**
 * « Dans le groupe » (variant A of `docs/previews/p27-groupe-partages.html`, 2026-11-03): what the group shares, a row
 * opening its sheet, then « Partager dans le groupe ».
 */
@Composable
internal fun GroupItems(
    ui: ShareUi,
    nowMs: Long,
    onOpen: (GroupPoint) -> Unit,
    onShare: () -> Unit,
) {
    val items = ui.points.filterNot { it.isOver(nowMs / MS_PER_S) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    stringResource(R.string.group_items),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${items.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GroupBlock(rows = items.map { p -> { GroupItemLine(p, ui, nowMs, onOpen) } })
        }
        OutlinedButton(onClick = onShare) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.group_share_title), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** A share: its type in the colour of its author, its name, then « Croquis · Marie · encore 1 h 40 ». */
@Composable
private fun GroupItemLine(
    point: GroupPoint,
    ui: ShareUi,
    nowMs: Long,
    onOpen: (GroupPoint) -> Unit,
) {
    val name = point.name.ifBlank { kindLabel(point.kind) }
    SheetRow(
        icon = kindIcon(point.kind),
        tint = authorColor(point, ui),
        title = name,
        summary = itemSummary(point, ui, nowMs),
        chevron = true,
        onClick = { onOpen(point) },
    )
}

/** « Croquis · Marie · encore 1 h 40 », with the length of a track. */
@Composable
private fun itemSummary(
    point: GroupPoint,
    ui: ShareUi,
    nowMs: Long,
): String {
    val formats = LocalFormats.current
    val by =
        if (point.author == ui.selfId) {
            stringResource(R.string.group_item_mine)
        } else {
            ui.members.firstOrNull { it.id == point.author }?.name ?: "?"
        }
    val length =
        if (point.kind == GroupItemKind.TRACK) {
            remember(point) { point.track().sumOf { Measure.pathLength(it) } }.let { formats.distance(it) }
        } else {
            null
        }
    val left = itemTimeLeft(point.remainingSec(nowMs / MS_PER_S))
    return listOfNotNull(kindLabel(point.kind), by, length, left).joinToString(" · ")
}

/** « encore 1 h 40 », « encore 3 h », « encore 42 min », or « tant que le groupe existe » for `null`. */
@Composable
private fun itemTimeLeft(seconds: Long?): String {
    if (seconds == null) return stringResource(R.string.group_item_while_group)
    // To the nearest minute: the clock of the screen moves every 15 s, a share just made reads « encore 2 h ».
    val minutes = ((seconds + S_PER_MIN / 2) / S_PER_MIN).toInt()
    return when {
        minutes < MIN_PER_HOUR -> stringResource(R.string.group_item_left_min, minutes)
        minutes % MIN_PER_HOUR == 0 -> stringResource(R.string.group_item_left_hours, minutes / MIN_PER_HOUR)
        else -> stringResource(R.string.group_item_left_h, minutes / MIN_PER_HOUR, minutes % MIN_PER_HOUR)
    }
}

/** A row of a block or a sheet: an icon, a title and a summary under it (also the sheet of a favourite, P25.5). */
@Composable
internal fun SheetRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    summary: String? = null,
    tint: Color? = null,
    textColor: Color? = null,
    chevron: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (summary == null) 52.dp else 64.dp)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint ?: textColor ?: MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = 20.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor ?: MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (chevron) {
            Icon(RouteIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** « Partager dans le groupe »: a new waypoint, track or sketch, or one of mine. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareChoiceSheet(
    onWaypoint: () -> Unit,
    onTrack: () -> Unit,
    onSketch: () -> Unit,
    onMine: () -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(
                stringResource(R.string.group_share_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            )
            val accent = MaterialTheme.colorScheme.primary
            SheetRow(
                SettingsIcons.Place,
                stringResource(R.string.group_share_waypoint),
                onWaypoint,
                stringResource(R.string.group_share_waypoint_sub),
                accent,
            )
            SheetRow(
                MenuIcons.Polyline,
                stringResource(R.string.group_share_track),
                onTrack,
                stringResource(R.string.group_share_track_sub),
                accent,
            )
            SheetRow(
                SketchIcons.Pen,
                stringResource(R.string.group_share_sketch),
                onSketch,
                stringResource(R.string.group_share_sketch_sub),
                accent,
            )
            SheetRow(
                Icons.Filled.Share,
                stringResource(R.string.group_share_mine),
                onMine,
                stringResource(R.string.group_share_mine_sub),
            )
        }
    }
}

/** The durations offered when sending: 30 min to 24 h, then « Tant que le groupe existe ». */
private val SEND_DURATIONS: List<PointDuration> = PointDuration.PRESETS + PointDuration.WhileGroup

/** Two hours unless chosen otherwise. */
private val DEFAULT_DURATION: PointDuration = PointDuration.PRESETS[2]

@Composable
private fun durationLabel(duration: PointDuration): String =
    when (duration) {
        PointDuration.WhileGroup -> {
            stringResource(R.string.group_send_while)
        }

        is PointDuration.For -> {
            if (duration.seconds < S_PER_HOUR) {
                stringResource(R.string.group_send_minutes, (duration.seconds / S_PER_MIN).toInt())
            } else {
                stringResource(R.string.group_send_hours, (duration.seconds / S_PER_HOUR).toInt())
            }
        }
    }

/**
 * « Envoyer au groupe » (variant B, 2026-11-03): the name, and on one line the type, the duration and the mode, which
 * a touch opens to change. [count] items sent at once have no name to give.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SendToGroupDialog(
    kind: GroupItemKind,
    initialName: String,
    onSend: (name: String, duration: PointDuration, alert: Boolean) -> Unit,
    onDismiss: () -> Unit,
    count: Int = 1,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var durationIndex by rememberSaveable { mutableStateOf(SEND_DURATIONS.indexOf(DEFAULT_DURATION)) }
    var alert by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf(false) }
    val duration = SEND_DURATIONS[durationIndex]
    val what = if (count > 1) stringResource(R.string.group_send_count, count) else kindLabel(kind)
    val mode = stringResource(if (alert) R.string.group_send_alert else R.string.group_send_silent)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_send_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (count == 1) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(MAX_NAME) },
                        label = { Text(stringResource(R.string.group_send_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable { open = !open }
                        .semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(kindIcon(kind), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(listOf(what, durationLabel(duration), mode).joinToString(" · "))
                        if (!open) {
                            Text(
                                stringResource(R.string.group_send_change),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Icon(
                        RouteIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (open) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SEND_DURATIONS.forEachIndexed { i, d ->
                            FilterChip(
                                selected = i == durationIndex,
                                onClick = { durationIndex = i },
                                label = { Text(durationLabel(d)) },
                            )
                        }
                    }
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(false, true).forEachIndexed { i, value ->
                            SegmentedButton(
                                selected = alert == value,
                                onClick = { alert = value },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                                icon = {},
                            ) {
                                Text(
                                    stringResource(
                                        if (value) R.string.group_send_alert else R.string.group_send_silent,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(name.trim(), duration, alert) },
                enabled = count > 1 || name.isNotBlank(),
            ) { Text(stringResource(R.string.group_send)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** What the sheet of a share asks the screen to do. */
internal class GroupItemActions(
    val onShow: (GroupPoint) -> Unit,
    val onRename: (GroupPoint, String) -> Unit,
    val onExtend: (GroupPoint, PointDuration) -> Unit,
    val onKeep: (GroupPoint) -> Unit,
    val onRemove: (GroupPoint) -> Unit,
)

/**
 * The sheet of a share (page 3 of `docs/previews/p27-creer-pour-le-groupe.html`, 2026-11-03): « Voir sur la carte »,
 * « Renommer » and « Prolonger » for its author, « Garder dans mes… », « Retirer du groupe » for everyone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupItemSheet(
    point: GroupPoint,
    ui: ShareUi,
    nowMs: Long,
    actions: GroupItemActions,
    onClose: () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var extending by remember { mutableStateOf(false) }
    val mine = point.author == ui.selfId
    val danger = MaterialTheme.colorScheme.error
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    kindIcon(point.kind),
                    contentDescription = null,
                    tint = authorColor(point, ui) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f).padding(start = 20.dp)) {
                    Text(point.name.ifBlank { kindLabel(point.kind) }, style = MaterialTheme.typography.titleMedium)
                    Text(
                        itemSummary(point, ui, nowMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SheetRow(SettingsIcons.Place, stringResource(R.string.group_item_show_map), { actions.onShow(point) })
            if (mine) {
                SheetRow(SketchIcons.Pen, stringResource(R.string.group_item_rename), { renaming = true })
                SheetRow(
                    Icons.Filled.Add,
                    stringResource(R.string.group_item_extend),
                    { extending = true },
                    stringResource(R.string.group_item_extend_sub),
                )
            }
            SheetRow(
                Icons.Filled.Add,
                stringResource(
                    when (point.kind) {
                        GroupItemKind.WAYPOINT -> R.string.group_item_keep_waypoint
                        GroupItemKind.TRACK -> R.string.group_item_keep_track
                        GroupItemKind.SKETCH -> R.string.group_item_keep_sketch
                    },
                ),
                { actions.onKeep(point) },
                stringResource(R.string.group_item_keep_sub),
            )
            SheetRow(
                RouteIcons.Delete,
                stringResource(R.string.group_item_remove),
                { actions.onRemove(point) },
                textColor = danger,
            )
        }
    }
    if (renaming) {
        var name by rememberSaveable { mutableStateOf(point.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.group_item_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.onRename(point, name)
                    renaming = false
                }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    if (extending) {
        val left = point.remainingSec(nowMs / MS_PER_S) ?: 0L
        val choices =
            listOf(
                stringResource(R.string.group_item_extend_1h) to extendBy(left, S_PER_HOUR),
                stringResource(R.string.group_item_extend_4h) to extendBy(left, 4 * S_PER_HOUR),
                stringResource(R.string.group_send_while) to PointDuration.WhileGroup,
            )
        AlertDialog(
            onDismissRequest = { extending = false },
            title = { Text(stringResource(R.string.group_item_extend)) },
            text = {
                Column {
                    for ((label, duration) in choices) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        actions.onExtend(point, duration)
                                        extending = false
                                    }.padding(vertical = 14.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { extending = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** [left] seconds and [more], counted from now, within the week a duration may last. */
private fun extendBy(
    left: Long,
    more: Long,
): PointDuration = PointDuration.For((left + more).coerceIn(PointDuration.MIN_S, PointDuration.MAX_S))

private const val MS_PER_S = 1000L
private const val S_PER_MIN = 60L
private const val S_PER_HOUR = 3600L
private const val MIN_PER_HOUR = 60
private const val MAX_NAME = 60

/** « Pour le groupe » in the colour of its author, in the band of a tool that draws for the group. */
@Composable
internal fun GroupTag(
    argb: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        stringResource(R.string.group_for_group),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        maxLines = 1,
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color(argb))
                .padding(horizontal = 9.dp, vertical = 2.dp),
    )
}

/** The sheets and dialogs of the shares, over the main page of the group. */
@Composable
internal fun GroupShareDialogs(
    ui: ShareUi,
    vm: ShareViewModel,
    openShare: Long?,
    onCloseShare: () -> Unit,
    shareChoice: Boolean,
    onCloseChoice: () -> Unit,
    newWaypoint: Boolean,
    onNewWaypoint: (Boolean) -> Unit,
    onMine: () -> Unit,
    onNewTrack: () -> Unit,
    onNewSketch: () -> Unit,
    mapCenter: () -> LonLat,
    onShowOnMap: (GroupPoint) -> Unit,
) {
    val nowMs = System.currentTimeMillis()
    if (shareChoice) {
        ShareChoiceSheet(
            onWaypoint = {
                onCloseChoice()
                onNewWaypoint(true)
            },
            onTrack = {
                onCloseChoice()
                onNewTrack()
            },
            onSketch = {
                onCloseChoice()
                onNewSketch()
            },
            onMine = {
                onCloseChoice()
                onMine()
            },
            onClose = onCloseChoice,
        )
    }
    if (newWaypoint) {
        SendToGroupDialog(
            kind = GroupItemKind.WAYPOINT,
            initialName = "",
            onSend = { name, duration, alert ->
                val at = mapCenter()
                vm.shareWaypoint(at.lat, at.lon, name, duration, alert)
                onNewWaypoint(false)
            },
            onDismiss = { onNewWaypoint(false) },
        )
    }
    val point = openShare?.let { id -> ui.points.firstOrNull { it.id == id } }
    if (openShare != null && point == null) onCloseShare()
    if (point != null) {
        GroupItemSheet(
            point = point,
            ui = ui,
            nowMs = nowMs,
            actions =
                GroupItemActions(
                    onShow = {
                        onCloseShare()
                        onShowOnMap(it)
                    },
                    onRename = { p, name -> vm.renameShare(p, name) },
                    onExtend = { p, d -> vm.extendShare(p.id, d) },
                    onKeep = {
                        vm.keepShare(it)
                        onCloseShare()
                    },
                    onRemove = {
                        vm.removeShare(it.id)
                        onCloseShare()
                    },
                ),
            onClose = onCloseShare,
        )
    }
}

/**
 * « Partager un des miens… » (page 2 of the preview `p27-creer-pour-le-groupe.html`, 2026-11-03): your waypoints,
 * traces and sketches in three tabs, ticked, then sent by the dialog of variant B.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharePickPage(
    vm: ShareViewModel,
    onBack: () -> Unit,
) {
    val mine by vm.mine.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(GroupItemKind.TRACK) }
    var picked by rememberSaveable { mutableStateOf(listOf<String>()) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sending by rememberSaveable { mutableStateOf(false) }
    val formats = LocalFormats.current
    val selected = mine.filter { it.key in picked }
    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            ToolMapTopBar(
                title = stringResource(R.string.group_share_title),
                subtitle =
                    if (picked.isEmpty()) {
                        null
                    } else {
                        pluralStringResource(R.plurals.group_pick_selected, picked.size, picked.size)
                    },
                onBack = onBack,
                actions = {
                    IconButton(onClick = { searching = !searching }) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.group_pick_search))
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = { sending = true }, enabled = selected.isNotEmpty()) {
                    Text(stringResource(R.string.group_send))
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val tabs =
                listOf(
                    GroupItemKind.WAYPOINT to R.string.group_pick_waypoints,
                    GroupItemKind.TRACK to R.string.group_pick_tracks,
                    GroupItemKind.SKETCH to R.string.group_pick_sketches,
                )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                tabs.forEachIndexed { i, (kind, label) ->
                    SegmentedButton(
                        selected = tab == kind,
                        onClick = { tab = kind },
                        shape = SegmentedButtonDefaults.itemShape(i, tabs.size),
                        icon = {},
                    ) { Text(stringResource(label)) }
                }
            }
            if (searching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.group_pick_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
            val shown =
                mine
                    .filter { it.kind == tab && (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true)) }
                    .sortedByDescending { it.timeMs ?: 0L }
            if (shown.isEmpty()) {
                Text(
                    stringResource(R.string.group_pick_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    GroupBlock(
                        rows =
                            shown.map { item ->
                                {
                                    PickRow(item, item.key in picked, formats) { on ->
                                        picked = if (on) picked + item.key else picked - item.key
                                    }
                                }
                            },
                    )
                }
            }
        }
    }
    if (sending && selected.isNotEmpty()) {
        SendToGroupDialog(
            kind = selected.first().kind,
            initialName = selected.first().let { it.name.ifBlank { kindLabel(it.kind) } },
            count = selected.size,
            onSend = { name, duration, alert ->
                vm.shareMine(selected, name, duration, alert)
                sending = false
                picked = emptyList()
                onBack()
            },
            onDismiss = { sending = false },
        )
    }
}

/** One of yours: a tick, its name, then its length, its date and whether it will be simplified. */
@Composable
private fun PickRow(
    item: MineItem,
    on: Boolean,
    formats: Formats,
    onToggle: (Boolean) -> Unit,
) {
    val summary =
        listOfNotNull(
            item.lengthMeters?.let { formats.distance(it) },
            item.timeMs?.let { NameDates.formatDay(it) },
            if (item.points > SIMPLIFIED_FROM) stringResource(R.string.group_pick_simplified) else null,
        ).joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .toggleable(on, role = Role.Checkbox, onValueChange = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = on, onCheckedChange = null)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                item.name.ifBlank { kindLabel(item.kind) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.isNotEmpty()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The key of an item in the ticked list: its kind and id. */
private val MineItem.key: String get() = "${kind.code}:$id"

/** Points past which a track is simplified to fit one message of the group (about 3 bytes a point, 12 KB). */
private const val SIMPLIFIED_FROM = 4_000
