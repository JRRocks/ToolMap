package fr.toolmap.operation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.toolmap.R
import fr.toolmap.share.PeerStatusIcon
import fr.toolmap.share.ageText
import fr.toolmap.share.peerStateLabel
import fr.toolmap.ui.theme.toolMap
import kotlinx.coroutines.delay

/** The words between `**` written in bold, as the maquettes write them (texts of `strings_operation.xml`). */
fun rich(text: String): AnnotatedString =
    buildAnnotatedString {
        val parts = text.split("**")
        parts.forEachIndexed { i, part ->
            if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(part) } else append(part)
        }
    }

/** The time of now, again every [periodMs]: « il y a 4 minutes » moves on while the screen is open. */
@Composable
fun rememberNow(periodMs: Long = NOW_PERIOD_MS): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

fun groupColor(palette: Int?): Color =
    palette?.let { Color(OperationFacts.GROUP_COLORS[it % OperationFacts.GROUP_COLORS.size]) } ?: WAITING_GREY

/**
 * The name of a group as lists, headings and menus show it: the name alone, « Nord » (the user's answer N0, P39.13).
 * In a sentence the string itself says « le groupe %1$s » (N1 A).
 */
@Composable
fun groupTitle(name: String?): String = name ?: stringResource(R.string.op_name_unknown)

@Composable
fun memberName(
    facts: OperationFacts,
    id: Long,
): String = facts.name(id) ?: stringResource(R.string.op_name_unknown)

@Composable
fun roleText(word: RoleWord): String =
    stringResource(
        when (word) {
            RoleWord.OP_CHIEF -> R.string.op_role_op_chief
            RoleWord.OP_DEPUTY -> R.string.op_role_op_deputy
            RoleWord.GROUP_CHIEF -> R.string.op_role_group_chief
            RoleWord.GROUP_DEPUTY -> R.string.op_role_group_deputy
            RoleWord.CREATOR -> R.string.op_role_creator
            RoleWord.ADMIN -> R.string.op_role_admin
        },
    )

/**
 * A token of an operation (V4 A, `tour3/index.html` `tok`): white disc, ring and text in the colour of the group, grey
 * dashed ring while waiting; a crown (chief of the operation, hollow for its deputy) and a star (chief of a group,
 * hollow for its deputy) at the top right, the star lower when both.
 */
@Composable
fun OpToken(
    look: TokenLook,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val color = if (look.waiting) WAITING_GREY else groupColor(look.palette)
    Box(modifier.size(size)) {
        Ring(color, dashed = look.waiting, size = size)
        Text(
            look.text,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * if (look.text.length > 1) TEXT_SHARE_LONG else TEXT_SHARE).sp,
            maxLines = 1,
            modifier = Modifier.align(Alignment.Center),
        )
        val mark = (size * MARK_SHARE).coerceAtLeast(MARK_MIN)
        val shift = mark * MARK_OUT
        look.crown?.let {
            TokenMark(
                OperationIcons.Crown,
                PETROL,
                it == Mark.HOLLOW,
                mark,
                Modifier.align(Alignment.TopEnd).offset(shift, -shift),
            )
        }
        look.star?.let {
            val place = if (look.crown != null) Alignment.BottomEnd else Alignment.TopEnd
            val dy = if (look.crown != null) shift else -shift
            TokenMark(Icons.Filled.Star, color, it == Mark.HOLLOW, mark, Modifier.align(place).offset(shift, dy))
        }
    }
}

/** The token of a group without chief (`04-admin-sans-chef`): a dashed ring and a crossed person, in its colour. */
@Composable
fun NoChiefToken(
    palette: Int?,
    size: Dp,
) {
    val color = groupColor(palette)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Ring(color, dashed = true, size = size)
        Icon(
            OperationIcons.PersonOff,
            contentDescription = null,
            tint = color,
            modifier =
                Modifier.size(size * ICON_SHARE),
        )
    }
}

@Composable
private fun Ring(
    color: Color,
    dashed: Boolean,
    size: Dp,
) {
    val ring = (size * RING_SHARE).coerceAtLeast(2.dp)
    Canvas(Modifier.size(size)) {
        val stroke = ring.toPx()
        val radius = (this.size.minDimension - stroke) / 2f
        drawCircle(Color.White, radius = this.size.minDimension / 2f)
        drawCircle(
            color,
            radius = radius,
            style =
                Stroke(
                    width = stroke,
                    pathEffect =
                        if (dashed) {
                            PathEffect.dashPathEffect(
                                floatArrayOf(stroke * 2f, stroke * 1.4f),
                            )
                        } else {
                            null
                        },
                ),
            center = Offset(this.size.width / 2f, this.size.height / 2f),
        )
    }
}

@Composable
private fun TokenMark(
    icon: ImageVector,
    color: Color,
    hollow: Boolean,
    size: Dp,
    modifier: Modifier,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (hollow) Color.White else color)
            .border(1.5.dp, if (hollow) color else Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (hollow) color else Color.White,
            modifier =
                Modifier.size(size * MARK_ICON),
        )
    }
}

/** The colour dot of a group. */
@Composable
fun GroupDot(
    palette: Int?,
    size: Dp = 12.dp,
) {
    Box(Modifier.size(size).clip(CircleShape).background(groupColor(palette)))
}

/** The title over a block: « Mon groupe · Groupe Nord » with its dot, « Toute l'opération », and what it counts. */
@Composable
fun SectionHeader(
    title: String,
    meta: String? = null,
    dot: Int? = null,
    metaColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            GroupDot(dot)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            // Petrol by day, light turquoise by night (`head()` of the maquettes).
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (meta != null) Text(meta, style = MaterialTheme.typography.bodySmall, color = metaColor)
    }
}

/** The tone of an information box of the maquettes. */
enum class BoxTone { TEAL, ORANGE, GREY }

/** A box of the maquettes: an icon, a text with its bold words, on a light colour. */
@Composable
fun InfoBox(
    icon: ImageVector,
    text: AnnotatedString,
    tone: BoxTone,
    modifier: Modifier = Modifier,
    iconTint: Color? = null,
) {
    val (back, fore) =
        when (tone) {
            BoxTone.TEAL -> {
                MaterialTheme.colorScheme.secondaryContainer to
                    MaterialTheme.colorScheme.onSecondaryContainer
            }

            BoxTone.ORANGE -> {
                MaterialTheme.colorScheme.tertiaryContainer to
                    MaterialTheme.colorScheme.onTertiaryContainer
            }

            BoxTone.GREY -> {
                MaterialTheme.toolMap.tile to MaterialTheme.colorScheme.onSurface
            }
        }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(back)
            .padding(start = 14.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = iconTint ?: fore, modifier = Modifier.size(24.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = fore)
    }
}

/**
 * A member in a list of an operation (`tour3/index.html` `mrow`): token, name with the shield of an administrator or
 * the key of the creator, its post and its state, then [trailing] (its state icon by default).
 */
@Composable
fun MemberLine(
    facts: OperationFacts,
    id: Long,
    modifier: Modifier = Modifier,
    tokenSize: Dp = 40.dp,
    you: Boolean = false,
    subtitle: String? = null,
    showAge: Boolean = false,
    minHeight: Dp = 56.dp,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpToken(facts.token(id), tokenSize)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (you) stringResource(R.string.op_self, memberName(facts, id)) else memberName(facts, id),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                RoleSign(facts, id)
            }
            val sub = subtitle ?: memberSubtitle(facts, id, showAge)
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) trailing() else StateIcon(facts, id)
    }
}

/** The key of the creator or the shield of an administrator, after the name. */
@Composable
fun RoleSign(
    facts: OperationFacts,
    id: Long,
) {
    val icon =
        when {
            facts.isCreator(id) -> OperationIcons.Key
            facts.isAdmin(id) -> OperationIcons.Shield
            else -> return
        }
    Icon(
        icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp).size(18.dp),
    )
}

/** « Chef de groupe · En ligne », « Synchronisation perdue · il y a 4 minutes ». */
@Composable
fun memberSubtitle(
    facts: OperationFacts,
    id: Long,
    showAge: Boolean,
): String {
    val post = facts.postWord(id)
    val state = facts.peerState(id)
    val online = state == fr.toolmap.core.p2p.PeerState.ONLINE
    if (post == RoleWord.OP_DEPUTY && online) return roleText(post)
    val since = facts.silentSince(id)
    return listOfNotNull(
        post?.let { roleText(it) },
        peerStateLabel(state),
        since?.takeIf { showAge }?.let { ageText(it, facts.nowMs) },
    ).joinToString(" · ")
}

@Composable
fun StateIcon(
    facts: OperationFacts,
    id: Long,
) {
    Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) { PeerStatusIcon(facts.peerState(id)) }
}

/** A row with a radio button (the sheets that choose a person or a group). */
@Composable
fun RadioLine(
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

/** The title of a sheet and the line under it. */
@Composable
fun SheetTitle(
    title: String,
    subtitle: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** A centred text under a picture (the windows of the maquettes with an icon on top). */
@Composable
fun CenteredTitle(
    icon: ImageVector?,
    title: String,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp).padding(bottom = 4.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    }
}

/** Grey of the members waiting for a group, and petrol of the crown (`tour3/index.html`). */
val WAITING_GREY = Color(OperationFacts.WAITING_COLOR)
val PETROL = Color(0xFF163A4A)

private const val NOW_PERIOD_MS = 15_000L
private const val TEXT_SHARE = 0.44f
private const val TEXT_SHARE_LONG = 0.38f
private const val RING_SHARE = 0.09f
private const val ICON_SHARE = 0.55f
private const val MARK_SHARE = 0.44f
private const val MARK_ICON = 0.66f
private const val MARK_OUT = 0.28f
private val MARK_MIN = 14.dp
