package fr.toolmap.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.toolmap.R
import fr.toolmap.ui.theme.Switch
import fr.toolmap.ui.theme.toolMap

/*
 * The parts the panels of the bar are built from (P14.10), in the « Crête » style since P29.0 (docs/previews/
 * design-00-style.html, variant E): a petrol band that carries the title and stays while the panel scrolls, a block
 * that says how things are, tiles for the frequent commands, and rows whose second line gives the current value. The
 * main menu, the tracks, positioning and maps panels and the centre point's menu all use them, so that they look alike.
 */

/**
 * The frame of a panel of the bar: white, 18 dp corners, raised, at most [maxHeight]. The [header] stays at the top and
 * the [footer] at the bottom, next to the bar (the main menu's tiles, P29.2); the [content] scrolls between them when
 * the panel is taller.
 */
@Composable
internal fun BarPanel(
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                // A tap on the panel itself must not reach the scrim behind it, which closes the panel.
                .pointerInput(Unit) { detectTapGestures { } },
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.toolMap.panel,
        shadowElevation = 8.dp,
    ) {
        Column {
            header?.invoke()
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                content = content,
            )
            footer?.invoke()
        }
    }
}

/**
 * The petrol band at the top of a panel or a screen: [leading] (the logo, a back arrow), the [title] in white with a
 * [subtitle], the « ? » or other [actions] at the end.
 */
@Composable
internal fun PanelBand(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val tm = MaterialTheme.toolMap
    Row(
        Modifier
            .fillMaxWidth()
            .background(tm.shell)
            .heightIn(min = BAND_HEIGHT)
            .padding(start = if (leading == null) 16.dp else 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f).padding(start = if (leading == null) 0.dp else 8.dp, end = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp),
                color = tm.onShell,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = tm.onShellVariant, maxLines = 1)
            }
        }
        CompositionLocalProvider(LocalContentColor provides tm.onShell) { actions() }
    }
}

/** The title band of a panel of the bar, with the « ? » at the end, as in AlpineQuest. */
@Composable
internal fun PanelHeader(
    title: String,
    onHelp: () -> Unit,
) {
    PanelBand(title = title) {
        IconButton(onClick = onHelp) {
            Icon(MenuIcons.Help, contentDescription = stringResource(R.string.menu_help))
        }
    }
}

/** A small grey title inside a panel, over the group of rows or tiles it names. */
@Composable
internal fun PanelCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

/**
 * The block of a panel that says what is shown or going on now: a [title], a grey line under it, something at the
 * start (an icon, thumbnails) and at the end (a switch, a chevron). The [overline] says what the block is about; since
 * P29.0 it opens the grey line (« Carte affichée · IGN Cartes ») instead of standing in capitals over the title. With
 * [onClick] the whole block is a button.
 */
@Composable
internal fun StateCard(
    title: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = PANEL_INSET, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.toolMap.tile)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 64.dp)
            .padding(start = 10.dp, end = if (trailing == null) 12.dp else 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val line = listOfNotNull(overline, subtitle).joinToString(" · ")
            if (line.isNotEmpty()) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/** The icon of a [StateCard]: a disc of the primary colour, lighter when what it stands for is off. */
@Composable
internal fun StateIcon(
    icon: ImageVector,
    on: Boolean = true,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** A row of [ActionTile]s, which share its width. */
@Composable
internal fun ActionTiles(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PANEL_INSET, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** A frequent command as a tile: its petrol icon over a short name. */
@Composable
internal fun RowScope.ActionTile(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    minHeight: Dp = TILE_HEIGHT,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.toolMap.tile,
        contentColor = MaterialTheme.toolMap.onTile,
        modifier = Modifier.weight(1f).heightIn(min = minHeight).alpha(if (enabled) 1f else DISABLED_ALPHA),
    ) {
        Column(
            Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.height(5.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

/**
 * One line of a panel: an icon or a tick box, the name, a [subtitle] in grey that gives the current value, and what
 * goes at the end ([PanelSwitch], [PanelChevron]). With [switchOn] the row is a switch: it ends with one, and TalkBack
 * reads it as a switch, on or off (P29.2).
 */
@Composable
internal fun MenuRow(
    label: String,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    checked: Boolean? = null,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    indented: Boolean = false,
    /** The colour of [icon] when it shows a state (the recorder in red while it records); the text's otherwise. */
    iconTint: Color? = null,
    switchOn: Boolean? = null,
    minHeight: Dp = ROW_HEIGHT,
    /** What TalkBack says a tap does, and the state it reads, for a row that unfolds in place. */
    clickLabel: String? = null,
    stateText: String? = null,
    onClick: () -> Unit,
) {
    val color =
        if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
        }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .then(
                if (switchOn != null) {
                    Modifier.toggleable(
                        value = switchOn,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = { onClick() },
                    )
                } else {
                    Modifier.clickable(enabled = enabled, onClickLabel = clickLabel, onClick = onClick)
                },
            ).then(if (stateText != null) Modifier.semantics { stateDescription = stateText } else Modifier)
            .padding(
                start = if (indented) 40.dp else 16.dp,
                end = if (trailing == null && switchOn == null) 16.dp else 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterStart) {
            if (checked != null) {
                Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
            } else if (icon != null) {
                Icon(icon, contentDescription = null, tint = if (enabled && iconTint != null) iconTint else color)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (enabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
                        },
                )
            }
        }
        if (switchOn != null) PanelSwitch(checked = switchOn) else trailing?.invoke()
    }
}

/** The end of a row with a switch, which only shows the state (the row toggles it). */
@Composable
internal fun PanelSwitch(checked: Boolean) {
    Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 8.dp))
}

/** The end of a row that opens a screen or another panel. */
@Composable
internal fun PanelChevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** The side margin of the blocks and tiles inside a panel. */
internal val PANEL_INSET = 12.dp

private val ROW_HEIGHT = 56.dp
private val TILE_HEIGHT = 70.dp
private val BAND_HEIGHT = 56.dp
private const val DISABLED_ALPHA = 0.38f
