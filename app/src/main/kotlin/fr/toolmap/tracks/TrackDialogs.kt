package fr.toolmap.tracks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.track.PathStyle
import fr.toolmap.ui.CheckRow
import fr.toolmap.ui.theme.TextButton

@Composable
fun RenameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    singleLine: Boolean = true,
    allowBlank: Boolean = false,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = singleLine) },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = allowBlank || text.isNotBlank()) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** One place an item or a folder can be moved to. */
data class MoveTarget(
    /** `null` is the top of the library. */
    val folderId: Long?,
    val label: String,
    val depth: Int,
    val current: Boolean,
)

/** « Déplacer vers… »: the top of the library and every folder that can take what is moved. */
@Composable
fun MoveDialog(
    title: String,
    targets: List<MoveTarget>,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (target in targets) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !target.current) { onPick(target.folderId) }
                                .padding(start = (target.depth * MOVE_INDENT_DP).dp, top = 12.dp, bottom = 12.dp),
                    ) {
                        val color =
                            if (target.current) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
                        Text(target.label, color = color, modifier = Modifier.weight(1f, fill = false))
                        if (target.current) {
                            Text(
                                "  · " + stringResource(R.string.tracks_move_here),
                                style = MaterialTheme.typography.bodySmall,
                                color = color,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val MOVE_INDENT_DP = 16

/** Colour, width, dashes and direction arrows of a line, with a live preview. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleDialog(
    initial: PathStyle,
    onConfirm: (PathStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    var style by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tracks_style_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PathStyle.PALETTE.forEachIndexed { index, argb ->
                        val selected = argb == style.colorArgb
                        val label = stringResource(R.string.a11y_color_choice, index + 1)
                        val scheme = MaterialTheme.colorScheme
                        val ring = if (selected) scheme.onSurface else scheme.outline
                        Box(
                            modifier =
                                Modifier
                                    .minimumInteractiveComponentSize()
                                    .size(36.dp)
                                    .background(Color(argb), CircleShape)
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = ring,
                                        shape = CircleShape,
                                    ).clickable(role = Role.RadioButton) { style = style.copy(colorArgb = argb) }
                                    .semantics {
                                        contentDescription = label
                                        this.selected = selected
                                    },
                        )
                    }
                }
                Text(stringResource(R.string.tracks_style_width, style.widthDp.toInt()))
                Slider(
                    value = style.widthDp,
                    onValueChange = { style = style.copy(widthDp = Math.round(it).toFloat()) },
                    valueRange = MIN_WIDTH..MAX_WIDTH,
                )
                CheckRow(checked = style.dashed, onCheckedChange = { style = style.copy(dashed = it) }) {
                    Text(stringResource(R.string.tracks_style_dashed))
                }
                CheckRow(checked = style.arrows, onCheckedChange = { style = style.copy(arrows = it) }) {
                    Text(stringResource(R.string.tracks_style_arrows))
                }
                // Preview: the line as it will look.
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .size(width = 1.dp, height = style.widthDp.dp)
                            .background(Color(style.colorArgb)),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(style) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val MIN_WIDTH = 1f
private const val MAX_WIDTH = 12f
