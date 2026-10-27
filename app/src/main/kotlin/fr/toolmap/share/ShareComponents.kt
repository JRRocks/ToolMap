package fr.toolmap.share

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import fr.toolmap.p2p.QrCodes
import fr.toolmap.p2p.QrMatrix
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.TextButton
import java.util.Locale

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

/** The facts about a member's last position, one per line, only those that are known. */
@Composable
fun memberFacts(
    member: MemberView,
    state: PeerState,
): List<String> {
    val fmt = LocalFormats.current
    val fix = member.fix
    val lines = ArrayList<String>()
    lines += peerStateLabel(state)
    if (fix == null) {
        lines += stringResource(R.string.share_no_position)
        return lines
    }
    lines += stringResource(R.string.share_last_position, ageText(fix.timeSec * MS_PER_S))
    lines += fmt.position(fix.lat, fix.lon).text
    fix.accuracyM?.let { lines += stringResource(R.string.share_accuracy, it) }
    fix.altM?.let { lines += stringResource(R.string.share_altitude, it) }
    fix.speedMs?.let { lines += stringResource(R.string.share_speed, fmt.speed(it)) }
    fix.battery?.let { lines += stringResource(R.string.share_battery, it) }
    return lines
}

/** What can be done with a member: look at it on the map, walk to it, keep its trail, remove it. */
@Composable
fun MemberDialog(
    member: MemberView,
    state: PeerState,
    onShowOnMap: (() -> Unit)?,
    onGoTo: (() -> Unit)?,
    onSaveTrail: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MemberBadge(member.name, member.color, picture = member.picture)
                Text(member.name, modifier = Modifier.weight(1f))
                PeerStatusIcon(state)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (line in memberFacts(member, state)) Text(line)
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    if (onShowOnMap != null) {
                        TextButton(onClick = onShowOnMap) { Text(stringResource(R.string.share_show_on_map)) }
                    }
                    if (onGoTo != null) {
                        TextButton(onClick = onGoTo) { Text(stringResource(R.string.share_go_to)) }
                    }
                    TextButton(onClick = onSaveTrail, enabled = member.trail.size >= 2) {
                        Text(stringResource(R.string.share_save_trail))
                    }
                    if (!member.isSelf) {
                        TextButton(onClick = onRemove) {
                            Text(stringResource(R.string.share_remove), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** An SOS that was just received: it comes back for nothing until acknowledged. */
@Composable
fun SosAlertDialog(
    alert: SosAlert,
    onAcknowledge: () -> Unit,
    onShowOnMap: (() -> Unit)?,
) {
    AlertDialog(
        onDismissRequest = onAcknowledge,
        title = { Text(stringResource(R.string.share_sos_received_title, alert.name), color = Color(SOS_RED)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (alert.text.isNotBlank()) Text(alert.text, style = MaterialTheme.typography.titleMedium)
                alert.fix?.let {
                    Text(String.format(Locale.ROOT, "%.5f, %.5f", it.lat, it.lon))
                    Text(stringResource(R.string.share_last_position, ageText(it.timeSec * MS_PER_S)))
                } ?: Text(stringResource(R.string.share_no_position))
            }
        },
        confirmButton = { TextButton(onClick = onAcknowledge) { Text(stringResource(R.string.share_sos_seen)) } },
        dismissButton =
            onShowOnMap?.let {
                {
                    TextButton(onClick = {
                        onAcknowledge()
                        it()
                    }) { Text(stringResource(R.string.share_show_on_map)) }
                }
            },
    )
}

/** The strip on the map: SOS in progress and the state of the sharing ([sosOnly]: the SOS alone, frame placement). */
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
        for (alert in ui.sos) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(SOS_RED)),
                onClick = { onShowSos(alert) },
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
            ) {
                Text(
                    stringResource(R.string.share_sos_banner, alert.name, alert.text),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
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
private const val SOS_RED = 0xFFD32F2F
