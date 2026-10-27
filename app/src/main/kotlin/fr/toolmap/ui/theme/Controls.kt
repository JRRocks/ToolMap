package fr.toolmap.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button as M3Button
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
