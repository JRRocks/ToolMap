package fr.toolmap.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import androidx.compose.material3.AlertDialog as M3AlertDialog
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.DropdownMenu as M3DropdownMenu
import androidx.compose.material3.FilledTonalButton as M3FilledTonalButton
import androidx.compose.material3.OutlinedButton as M3OutlinedButton
import androidx.compose.material3.Switch as M3Switch
import androidx.compose.material3.TextButton as M3TextButton

// Material's buttons are pills whatever the theme says; the "Crête" style (P29.0) wants the 12 dp corner of the
// theme's small shape. These keep Material's signatures, so a screen switches by its imports only.

/** Disabled outline: Material's own (onSurface at 12 %). */
private const val DISABLED_BORDER_ALPHA = 0.12f

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = M3Button(
    onClick,
    modifier,
    enabled,
    shape,
    colors,
    elevation,
    border,
    contentPadding,
    interactionSource,
    content,
)

/** The secondary button: a 1.5 dp outline in the primary colour. */
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = outlinedBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = M3OutlinedButton(
    onClick,
    modifier,
    enabled,
    shape,
    colors,
    elevation,
    border,
    contentPadding,
    interactionSource,
    content,
)

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = M3FilledTonalButton(
    onClick,
    modifier,
    enabled,
    shape,
    colors,
    elevation,
    border,
    contentPadding,
    interactionSource,
    content,
)

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = M3TextButton(
    onClick,
    modifier,
    enabled,
    shape,
    colors,
    elevation,
    border,
    contentPadding,
    interactionSource,
    content,
)

/** A switch that is on shows the turquoise accent, not the petrol primary (which reads as "off" next to the shell). */
@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: SwitchColors = switchColors(),
    interactionSource: MutableInteractionSource? = null,
) = M3Switch(
    checked,
    onCheckedChange,
    modifier,
    thumbContent,
    enabled,
    colors,
    interactionSource,
)

@Composable
private fun outlinedBorder(enabled: Boolean): BorderStroke {
    val scheme = MaterialTheme.colorScheme
    return if (enabled) {
        BorderStroke(1.5.dp, scheme.primary)
    } else {
        BorderStroke(1.dp, scheme.onSurface.copy(alpha = DISABLED_BORDER_ALPHA))
    }
}

@Composable
private fun switchColors(): SwitchColors {
    val scheme = MaterialTheme.colorScheme
    val tm = MaterialTheme.toolMap
    return SwitchDefaults.colors(
        checkedThumbColor = tm.onAccent,
        checkedTrackColor = tm.accent,
        checkedBorderColor = tm.accent,
        checkedIconColor = tm.accent,
        uncheckedThumbColor = scheme.onSurfaceVariant,
        uncheckedTrackColor = tm.tile,
        uncheckedBorderColor = scheme.onSurfaceVariant,
    )
}

/**
 * A dialog of the « Crête » style (P29.10): on the white of the panels by day ([ToolMapColors.popup]), with the 18 dp
 * corners of the panels, without the tint of Material's elevation. Material's signature, so that a screen switches by
 * its imports only.
 */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    containerColor: Color = MaterialTheme.toolMap.popup,
    properties: DialogProperties = DialogProperties(),
) = M3AlertDialog(
    onDismissRequest = onDismissRequest,
    confirmButton = confirmButton,
    modifier = modifier,
    dismissButton = dismissButton,
    icon = icon,
    title = title,
    text = text,
    shape = shape,
    containerColor = containerColor,
    tonalElevation = 0.dp,
    properties = properties,
)

/** A menu of the « Crête » style (P29.10): white by day, 12 dp corners, lifted from a white panel by its shadow. */
@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) = M3DropdownMenu(
    expanded = expanded,
    onDismissRequest = onDismissRequest,
    modifier = modifier,
    offset = offset,
    scrollState = scrollState,
    properties = properties,
    shape = MaterialTheme.shapes.small,
    containerColor = MaterialTheme.toolMap.popup,
    tonalElevation = 0.dp,
    shadowElevation = MenuDefaults.ShadowElevation,
    content = content,
)
