package fr.toolmap.share

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.p2p.MeshNode
import fr.toolmap.core.p2p.PeerFix
import fr.toolmap.core.track.Geodesy
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.TextButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The alert of the group (P22, `docs/previews/p22-alerte.html` validated on 2026-10-28: « B pour la page "Envoi",
// C pour le reste »): the sheet that sends it, held for a second (B); the line that opens it, your alert in progress
// with its confirmed cancel, the alert received and its lift, in dialogs and banners (C).

/** « Envoyer une alerte au groupe », the line under « Partager ma position » that opens [AlertSendSheet]. */
@Composable
internal fun SendAlertLine(onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ShareIcons.EmergencyShare, contentDescription = null, tint = Color(ALERT_RED))
        Text(
            stringResource(R.string.group_alert_send),
            style = MaterialTheme.typography.bodyLarge,
            color = Color(ALERT_RED),
            modifier = Modifier.padding(start = 20.dp),
        )
    }
}

/** The sheet that sends the alert: an optional message, then the red button held for [HOLD_MS] (variant B). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlertSendSheet(
    onSend: (String) -> Unit,
    onClose: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.group_alert_send), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_ALERT_TEXT) },
                placeholder = { Text(stringResource(R.string.group_alert_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.group_alert_send_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HoldToSend(onSend = { onSend(text) }, modifier = Modifier.padding(top = 8.dp))
            TextButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

/**
 * « Maintenez pour envoyer »: the button fills while it is held and sends once full; let go before, nothing is sent.
 * TalkBack's double tap sends at once (its own gesture is deliberate enough).
 */
@Composable
private fun HoldToSend(
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val action = stringResource(R.string.group_alert_send_action)
    Box(
        modifier
            .fillMaxWidth()
            .height(HOLD_HEIGHT)
            .clip(RoundedCornerShape(HOLD_HEIGHT / 2))
            .background(Color(ALERT_RED))
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick(label = action) {
                    onSend()
                    true
                }
            }.pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val hold =
                            scope.launch {
                                progress.snapTo(0f)
                                progress.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
                                onSend()
                            }
                        tryAwaitRelease()
                        if (hold.isActive) {
                            hold.cancel()
                            scope.launch { progress.animateTo(0f, tween(RELEASE_MS)) }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(progress.value)
                .background(Color.White.copy(alpha = FILL_ALPHA)),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ShareIcons.EmergencyShare, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.group_alert_hold),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/**
 * Your alert in progress (variant C): « Alerte en cours », the time its repeats still run then the time it was sent,
 * and « Annuler l'alerte » after a confirmation that says what the group will see.
 */
@Composable
internal fun OwnAlertCard(
    sinceMs: Long,
    myName: String,
    onCancel: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    val now = tickingNow()
    val leftS = (MeshNode.SOS_REPEAT_FOR_S * MS_PER_S - (now - sinceMs)).coerceAtLeast(0L) / MS_PER_S
    val time = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val status =
        when {
            sinceMs <= 0L -> {
                null
            }

            leftS >= SECONDS_PER_MINUTE -> {
                stringResource(
                    R.string.group_alert_left_min,
                    leftS / SECONDS_PER_MINUTE,
                    leftS % SECONDS_PER_MINUTE,
                )
            }

            leftS > 0 -> {
                stringResource(R.string.group_alert_left_s, leftS)
            }

            else -> {
                stringResource(
                    R.string.group_alert_sent_at,
                    time.format(Instant.ofEpochMilli(sinceMs).atZone(ZoneId.systemDefault())),
                )
            }
        }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                ShareIcons.EmergencyShare,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    stringResource(R.string.group_alert_ongoing),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
        OutlinedButton(
            onClick = { confirm = true },
            colors =
                ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            modifier = Modifier.align(Alignment.End).padding(top = 6.dp),
        ) { Text(stringResource(R.string.group_alert_cancel)) }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.group_alert_cancel_title)) },
            text = { Text(stringResource(R.string.group_alert_cancel_text, myName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onCancel()
                }) { Text(stringResource(R.string.group_alert_cancel), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.group_alert_keep)) }
            },
        )
    }
}

/**
 * An alert of the group not seen yet, over whichever screen shows: the message, the position, its age, and its
 * distance and direction from [from] (this device's last position) when known.
 */
@Composable
fun GroupAlertDialog(
    alert: SosAlert,
    from: PeerFix?,
    onAcknowledge: () -> Unit,
    onShowOnMap: (() -> Unit)?,
) {
    val formats = LocalFormats.current
    val directions = stringArrayResource(R.array.group_alert_directions)
    AlertDialog(
        onDismissRequest = onAcknowledge,
        title = { Text(stringResource(R.string.group_alert_from, alert.name), color = Color(ALERT_RED)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (alert.text.isNotBlank()) Text(alert.text, style = MaterialTheme.typography.titleMedium)
                alert.fix?.let {
                    Text(String.format(Locale.ROOT, "%.5f, %.5f", it.lat, it.lon))
                    val age = stringResource(R.string.group_alert_last_position, ageText(it.timeSec * MS_PER_S))
                    val where =
                        from?.let { me ->
                            val meters = Geodesy.distance(me.lat, me.lon, it.lat, it.lon)
                            val bearing = Geodesy.bearing(me.lat, me.lon, it.lat, it.lon)
                            stringResource(
                                R.string.group_alert_where,
                                formats.distance(meters),
                                directions[octant(bearing)],
                            )
                        }
                    Text(listOfNotNull(age, where).joinToString(" · "))
                } ?: Text(stringResource(R.string.share_no_position))
            }
        },
        confirmButton = { TextButton(onClick = onAcknowledge) { Text(stringResource(R.string.group_alert_seen)) } },
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

/** « Alerte levée »: the alert was lifted before it was seen, so its dialog says so until « OK » (variant C). */
@Composable
fun AlertLiftedDialog(
    lifted: SosLifted,
    onOk: () -> Unit,
) {
    val time = remember { DateTimeFormatter.ofPattern("HH:mm") }
    AlertDialog(
        onDismissRequest = onOk,
        title = { Text(stringResource(R.string.group_alert_lifted_title), color = Color(LIFTED_GREEN)) },
        text = {
            Text(
                stringResource(
                    R.string.group_alert_lifted_text,
                    lifted.name,
                    time.format(Instant.ofEpochMilli(lifted.alertAtMs).atZone(ZoneId.systemDefault())),
                ),
            )
        },
        confirmButton = { TextButton(onClick = onOk) { Text(stringResource(R.string.action_ok)) } },
    )
}

/** The red banner of an alert on the map: « Alerte de … » and the message under it. */
@Composable
internal fun AlertBanner(
    alert: SosAlert,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(ALERT_RED), contentColor = Color.White),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(ShareIcons.EmergencyShare, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(Modifier.padding(start = 10.dp)) {
                Text(stringResource(R.string.group_alert_from, alert.name), style = MaterialTheme.typography.titleSmall)
                if (alert.text.isNotBlank()) Text(alert.text, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
    }
}

/** The banner of a lifted alert: white with a green tick, « Alerte levée par … » (variant C). */
@Composable
internal fun LiftedBanner(lifted: SosLifted) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = Color(LIFTED_GREEN),
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(R.string.group_alert_lifted_by, lifted.name),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

/** The eighth of the compass a bearing points to: 0 for north, 1 for north-east… 7 for north-west. */
internal fun octant(bearingDeg: Double): Int = Math.floorMod(Math.round(bearingDeg / OCTANT_DEG).toInt(), OCTANTS)

/** The time, again every second, for the countdown of the repeats. */
@Composable
private fun tickingNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(MS_PER_S)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private const val ALERT_RED = 0xFFD32F2F
private const val LIFTED_GREEN = 0xFF2E7D32
private const val HOLD_MS = 1_000
private const val RELEASE_MS = 150
private val HOLD_HEIGHT = 64.dp
private const val FILL_ALPHA = 0.25f
private const val MAX_ALERT_TEXT = 200
private const val MS_PER_S = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val OCTANT_DEG = 45.0
private const val OCTANTS = 8
