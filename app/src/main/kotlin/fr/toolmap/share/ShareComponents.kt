package fr.toolmap.share

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.p2p.MemberLook
import fr.toolmap.core.p2p.MemberPalette
import fr.toolmap.core.p2p.MemberStatus
import fr.toolmap.core.p2p.MemberView
import fr.toolmap.core.p2p.PeerState
import fr.toolmap.core.track.TrackFormat
import fr.toolmap.map.ActionTile
import fr.toolmap.map.ActionTiles
import fr.toolmap.maps.MapsIcons
import fr.toolmap.p2p.QrCodes
import fr.toolmap.p2p.QrMatrix
import fr.toolmap.settings.SettingsIcons
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.DropdownMenu
import fr.toolmap.ui.theme.TextButton

/** A QR code on a white square with its quiet zone (4 modules), which scanners need to find it. */
@Composable
fun QrImage(
    text: String,
    modifier: Modifier = Modifier,
) {
    val matrix: QrMatrix? = remember(text) { runCatching { QrCodes.encode(text) }.getOrNull() }
    if (matrix == null) {
        Text(stringResource(R.string.share_qr_failed), color = MaterialTheme.colorScheme.error, modifier = modifier)
        return
    }
    Canvas(modifier = modifier.aspectRatio(1f).background(Color.White)) {
        val modules = matrix.size + 2 * QUIET_ZONE_MODULES
        val cell = size.minDimension / modules
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix[x, y]) {
                    drawRect(
                        Color.Black,
                        topLeft = Offset((x + QUIET_ZONE_MODULES) * cell, (y + QUIET_ZONE_MODULES) * cell),
                        // A hair larger than the cell so that no seam shows between neighbours.
                        size = Size(cell + SEAM, cell + SEAM),
                    )
                }
            }
        }
    }
}

/**
 * A member as the others see it, like a game token: its silhouette (or its initials) in its colour on a contrasting
 * disc ringed with that colour, or its own [picture] in the ring ([MemberLook]).
 */
@Composable
fun MemberBadge(
    name: String,
    look: Int,
    modifier: Modifier = Modifier,
    size: Dp = BADGE_SIZE,
    picture: ByteArray? = null,
) {
    val argb = MemberLook.argb(look)
    val color = Color(argb)
    val icon = MemberLook.icon(look)
    val image = remember(picture) { picture?.let(MemberPicture::decode)?.asImageBitmap() }
    val silhouette = memberIconRes(icon)
    androidx.compose.foundation.layout.Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                .background(Color(MemberLook.backdrop(argb)))
                .border(size * RING_SHARE, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            icon == MemberLook.PICTURE && image != null -> {
                Image(
                    image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.padding(size * RING_SHARE).matchParentSize().clip(CircleShape),
                )
            }

            silhouette != null -> {
                Icon(
                    painterResource(silhouette),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(size * ICON_SHARE),
                )
            }

            else -> {
                Text(
                    MemberPalette.initials(name),
                    color = color,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
fun statusLabel(status: MemberStatus): String =
    stringResource(
        when (status) {
            MemberStatus.DIRECT -> R.string.share_status_direct
            MemberStatus.RELAYED -> R.string.share_status_relayed
            MemberStatus.LOST -> R.string.share_status_lost
            MemberStatus.LEFT -> R.string.share_status_left
        },
    )

/** "il y a 2 minutes", in the user's language, for a time in epoch milliseconds. */
fun ageText(
    timeMs: Long,
    nowMs: Long = System.currentTimeMillis(),
): String =
    DateUtils
        .getRelativeTimeSpanString(timeMs, nowMs.coerceAtLeast(timeMs), DateUtils.SECOND_IN_MILLIS)
        .toString()

/**
 * The sheet of a member (P29.8, « Cordée »), as the sheet of a waypoint: its token, name and state, four figures
 * (altitude, speed, battery, accuracy), its position in the chosen format, then the tiles « Voir sur la carte » ·
 * « Aller vers ce membre » · « Enregistrer sa trace »; « Retirer du groupe » is in the ⋮ of the head, with a
 * confirmation. Your own sheet has neither « Aller vers ce membre » nor ⋮.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberSheet(
    member: MemberView,
    state: PeerState,
    onShowOnMap: (() -> Unit)?,
    onGoTo: (() -> Unit)?,
    onSaveTrail: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fmt = LocalFormats.current
    val fix = member.fix
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MemberBadge(member.name, member.color, size = 48.dp, picture = member.picture)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        if (member.isSelf) stringResource(R.string.share_self, member.name) else member.name,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        listOfNotNull(peerStateLabel(state), fix?.let { ageText(it.timeSec * MS_PER_S) })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted,
                    )
                }
                PeerStatusIcon(state)
                if (!member.isSelf) {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.share_member_menu, member.name),
                            )
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(R.string.share_remove), color = MaterialTheme.colorScheme.error)
                                },
                                onClick = {
                                    menu = false
                                    confirmRemove = true
                                },
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
            }
            val figures =
                listOfNotNull(
                    fix?.altM?.let {
                        stringResource(R.string.share_fig_altitude_value, it) to
                            R.string.share_fig_altitude
                    },
                    fix?.speedMs?.let { fmt.speed(it) to R.string.share_fig_speed },
                    fix?.battery?.let {
                        stringResource(R.string.share_fig_battery_value, it) to
                            R.string.share_fig_battery
                    },
                    fix?.accuracyM?.let {
                        stringResource(R.string.share_fig_accuracy_value, it) to R.string.share_fig_accuracy
                    },
                )
            if (figures.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    for ((value, label) in figures) {
                        Column {
                            Text(value, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(label), style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(SettingsIcons.Place, contentDescription = null, tint = muted)
                Text(
                    fix?.let { fmt.position(it.lat, it.lon).text } ?: stringResource(R.string.share_no_position),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 20.dp),
                )
            }
            Box(Modifier.padding(top = 8.dp)) {
                ActionTiles {
                    ActionTile(
                        MapsIcons.Map,
                        stringResource(R.string.share_show_on_map),
                        enabled = onShowOnMap != null,
                    ) {
                        onShowOnMap?.invoke()
                    }
                    if (!member.isSelf) {
                        ActionTile(
                            SettingsIcons.NearMe,
                            stringResource(R.string.share_go_to),
                            enabled = onGoTo != null,
                        ) {
                            onGoTo?.invoke()
                        }
                    }
                    ActionTile(
                        MapsIcons.Download,
                        stringResource(R.string.share_save_trail),
                        enabled = member.trail.size >= 2,
                        onClick = onSaveTrail,
                    )
                }
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.share_remove_title, member.name)) },
            text = { Text(stringResource(R.string.share_remove_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemove()
                }) { Text(stringResource(R.string.share_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * The strip on the map: the alerts of the group and their lifts (P22), then the state of the sharing ([sosOnly]: the
 * alerts alone, frame placement).
 */
@Composable
fun ShareHud(
    ui: ShareUi,
    onOpen: () -> Unit,
    onShowSos: (SosAlert) -> Unit,
    modifier: Modifier = Modifier,
    sosOnly: Boolean = false,
) {
    if (!ui.inGroup) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (alert in ui.sos) AlertBanner(alert, onClick = { onShowSos(alert) })
        for (lifted in ui.sosLifted) LiftedBanner(lifted)
        if (!sosOnly && (ui.sharing || ui.others.isNotEmpty())) {
            // Amber with a warning sign while a phone setting may cut the sharing (P21.5 o, c).
            val warn = ui.sharing && ui.alerts.isNotEmpty()
            val amber = alertColors(now = false)
            Card(
                onClick = onOpen,
                colors =
                    if (warn) {
                        CardDefaults.cardColors(containerColor = amber.container, contentColor = amber.content)
                    } else {
                        CardDefaults.cardColors()
                    },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    if (warn) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = stringResource(R.string.share_alert_hud),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        if (ui.sharing) {
                            stringResource(
                                R.string.share_hud_sharing,
                                ui.others.count {
                                    it.status ==
                                        MemberStatus.DIRECT
                                },
                            )
                        } else {
                            stringResource(R.string.share_hud_watching, ui.others.size)
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

private const val QUIET_ZONE_MODULES = 4
private const val SEAM = 0.4f
private val BADGE_SIZE = 36.dp
private const val ICON_SHARE = 0.6f
private const val RING_SHARE = 0.08f
private const val MS_PER_S = 1000L
