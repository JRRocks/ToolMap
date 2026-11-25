package fr.toolmap.operation

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.ui.theme.toolMap
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * « Quitter l'opération ? » of a member, a group chief or the chief of the operation (`tour2/08-quitter-membre`,
 * `tour3/09-chef-quitte`, `09-chef-operation-quitte`, with the text of `tour4/11-quitter-texte`).
 */
@Composable
fun QuitWindow(
    session: OperationSession,
    facts: OperationFacts,
) {
    val self = facts.self ?: return
    val state = facts.state
    val group = facts.myGroup
    Question(
        title = stringResource(R.string.op_quit_title),
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.op_quit_text))
                when {
                    state?.leader == self -> {
                        val deputy = state.deputy
                        if (deputy != null) {
                            InfoBox(
                                OperationIcons.Crown,
                                rich(stringResource(R.string.op_quit_op_chief_box, memberName(facts, deputy))),
                                BoxTone.ORANGE,
                            )
                        }
                    }

                    group != null && facts.leaderOf(group) == self -> {
                        val deputy = facts.deputyOf(group)
                        val title = groupTitle(facts.groupName(group))
                        InfoBox(
                            Icons.Filled.Star,
                            rich(
                                if (deputy != null) {
                                    stringResource(R.string.op_quit_chief_box, title, memberName(facts, deputy))
                                } else {
                                    stringResource(R.string.op_quit_chief_alone_box, title)
                                },
                            ),
                            BoxTone.ORANGE,
                        )
                    }

                    group != null -> {
                        InfoBox(OperationIcons.Info, rich(stringResource(R.string.op_quit_member_box)), BoxTone.GREY)
                    }

                    else -> {}
                }
            }
        },
        confirm = stringResource(R.string.op_quit),
        danger = true,
        onConfirm = session::leave,
        onDismiss = { session.dialog(null) },
    )
}

/**
 * The creator leaves (`tour3/05-createur-A`, Q4 A, T8 A; `tour4/09-glissiere`): designate a successor, or slide to the
 * end to dissolve the operation for everyone; a touch alone does nothing.
 */
@Composable
fun CreatorQuitWindow(
    session: OperationSession,
    facts: OperationFacts,
    nav: OperationNav,
) {
    val placed = facts.placedCount
    val waiting = facts.waitingMembers.size
    val who =
        if (waiting > 0) {
            stringResource(
                R.string.op_dissolve_who,
                pluralStringResource(R.plurals.op_dissolve_members, placed, placed),
                pluralStringResource(R.plurals.op_dissolve_waiting, waiting, waiting),
            )
        } else {
            pluralStringResource(R.plurals.op_dissolve_members, placed, placed)
        }
    AlertDialog(
        onDismissRequest = { session.dialog(null) },
        title = { Text(stringResource(R.string.op_quit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(rich(stringResource(R.string.op_creator_quit_text)))
                Button(
                    onClick = { session.sheet(OpSheet.Successor) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Icon(OperationIcons.Key, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.op_designate), style = MaterialTheme.typography.titleSmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.op_or),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    HorizontalDivider(Modifier.weight(1f))
                }
                Text(rich(stringResource(R.string.op_dissolve_text, who)))
                SlideToDissolve(forced = nav.slide, onDissolve = session::dissolve)
            }
        },
        confirmButton = {
            TextButton(onClick = { session.dialog(null) }) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * « Glisser pour dissoudre » (Q4 A, T8 A, `tour4/09-glissiere`): the round thumb is dragged to the right, the track
 * fills with red; let go before the end, it goes back and nothing happens; at the end the words say « Relâchez pour
 * dissoudre » and letting go dissolves. [forced] holds the thumb where a capture wants it. TalkBack: a double tap
 * on the slide dissolves (the « Maintenez pour envoyer » of the alert does the same).
 */
@Composable
fun SlideToDissolve(
    forced: Float?,
    onDissolve: () -> Unit,
) {
    val night = MaterialTheme.toolMap.panel != Color.White
    val red = if (night) NIGHT_FILL else DAY_FILL
    val track = if (night) NIGHT_TRACK else DAY_TRACK
    val label = if (night) NIGHT_LABEL else DAY_LABEL
    val thumbBack = if (night) NIGHT_THUMB else Color.White
    val scope = rememberCoroutineScope()
    val description = stringResource(R.string.op_slide)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(TRACK_HEIGHT)
            .semantics {
                contentDescription = description
                onClick {
                    onDissolve()
                    true
                }
            },
    ) {
        val density = LocalDensity.current
        val travel = with(density) { (maxWidth - TRACK_HEIGHT).toPx() }.coerceAtLeast(1f)
        val offset = remember { Animatable(0f) }
        var dragging by remember { mutableStateOf(false) }
        LaunchedEffect(forced) { if (forced != null) offset.snapTo(forced * travel) }
        val progress = (offset.value / travel).coerceIn(0f, 1f)
        val atEnd = progress >= END_SHARE
        val state =
            rememberDraggableState { delta ->
                scope.launch { offset.snapTo((offset.value + delta).coerceIn(0f, travel)) }
            }
        Box(
            Modifier
                .fillMaxWidth()
                .height(TRACK_HEIGHT)
                .clip(RoundedCornerShape(TRACK_HEIGHT / 2))
                .background(track)
                .border(1.5.dp, red, RoundedCornerShape(TRACK_HEIGHT / 2))
                .draggable(
                    state = state,
                    orientation = Orientation.Horizontal,
                    onDragStarted = { dragging = true },
                    onDragStopped = {
                        dragging = false
                        if (offset.value / travel >= END_SHARE) {
                            onDissolve()
                        } else {
                            offset.animateTo(0f)
                        }
                    },
                ),
        ) {
            // The red that follows the thumb.
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(with(density) { (offset.value + travel * 0f).toDp() } + TRACK_HEIGHT)
                    .clip(RoundedCornerShape(TRACK_HEIGHT / 2))
                    .background(if (progress > 0f || atEnd) red else Color.Transparent),
            )
            val moved = progress > 0f || dragging || forced != null
            Row(
                Modifier.fillMaxWidth().fillMaxHeight().padding(start = TRACK_HEIGHT, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (atEnd) Arrangement.Start else Arrangement.End,
            ) {
                when {
                    atEnd -> {
                        Text(
                            stringResource(R.string.op_slide_release),
                            color = if (night) NIGHT_THUMB else Color.White,
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.offset(x = -TRACK_HEIGHT * 0.6f),
                        )
                    }

                    moved -> {
                        Text(
                            "${stringResource(R.string.op_slide_more)} ››",
                            color = label,
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }

                    else -> {
                        Spacer(Modifier.weight(1f))
                        Text(
                            stringResource(R.string.op_slide),
                            color = red,
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.weight(1f))
                        Text("››", color = red, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Box(
                Modifier
                    .offset { IntOffset(offset.value.roundToInt(), 0) }
                    .size(TRACK_HEIGHT)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(if (atEnd || moved) thumbBack else red),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    OperationIcons.GroupOff,
                    contentDescription = null,
                    tint = if (atEnd || moved) red else thumbBack,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

/** « Qui vous succède ? » (`tour3/05-successeur`; none: `05-successeur-aucun`). */
@Composable
fun SuccessorSheet(
    session: OperationSession,
    facts: OperationFacts,
) {
    val candidates = facts.successors
    var chosen by remember(candidates) { mutableStateOf(candidates.firstOrNull()) }
    SheetTitle(stringResource(R.string.op_successor_title), stringResource(R.string.op_successor_text))
    if (candidates.isEmpty()) {
        NoOtherAdmin(onName = { session.sheet(OpSheet.NameAdmin) })
        return
    }
    Caption(stringResource(R.string.op_admins_count, candidates.size), start = 16.dp)
    for (id in candidates) {
        RadioLine(selected = chosen == id, onClick = { chosen = id }) { CandidateLine(facts, id) }
    }
    Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(
            OperationIcons.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            stringResource(R.string.op_successor_other),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val pick = chosen
    SheetButton(
        pick?.let { stringResource(R.string.op_successor_button, memberName(facts, it)) }.orEmpty(),
        enabled =
            pick != null,
    ) {
        if (pick != null) session.handOverAndLeave(pick)
    }
    CancelLine(session)
}

private val TRACK_HEIGHT = 56.dp
private const val END_SHARE = 0.97f

// « jusqu'au bout » in dark red by day (7.6:1), light pink by night (8.8:1): `tour4/09-glissiere`, `-nuit`.
private val DAY_FILL = Color(0xFFD32F2F)
private val DAY_TRACK = Color(0xFFFDE7E7)
private val DAY_LABEL = Color(0xFF8C1D18)
private val NIGHT_FILL = Color(0xFFFF6B5E)
private val NIGHT_TRACK = Color(0xFF5A1A16)
private val NIGHT_LABEL = Color(0xFFFFDAD6)
private val NIGHT_THUMB = Color(0xFF3A0B08)
