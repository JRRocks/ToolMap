package fr.toolmap.maps

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.share.ColorWheelDialog
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap
import kotlin.math.roundToInt

/*
 * « Teinter › » of a layer (P26.4, piste T1 of `docs/previews/p26-teinter.html`, chosen on 2026-11-03): a short sheet,
 * the map still in view above it. Its dots (none, six colours, the wheel for another one) and the intensity; the
 * colour applies at a touch and the intensity when the finger lets go, the map redrawn from the stored tiles.
 */

/** The colours of the dots, `0xRRGGBB`, with their names. */
internal enum class TintColor(
    val rgb: Int,
    @param:StringRes val label: Int,
) {
    RED(0xE53935, R.string.tint_red),
    ORANGE(0xFB8C00, R.string.tint_orange),
    YELLOW(0xFDD835, R.string.tint_yellow),
    GREEN(0x43A047, R.string.tint_green),
    BLUE(0x1E88E5, R.string.tint_blue),
    PURPLE(0x8E24AA, R.string.tint_purple),
    ;

    companion object {
        fun of(rgb: Int?): TintColor? = entries.firstOrNull { it.rgb == rgb }
    }
}

/** « bleu · 50 % », « couleur choisie · 30 % », or « aucune ». */
@Composable
internal fun tintText(layer: LayerConfig): String {
    val tint = layer.tint ?: return stringResource(R.string.maps_tint_none)
    val name = TintColor.of(tint)?.let { stringResource(it.label) } ?: stringResource(R.string.maps_tint_custom)
    return stringResource(R.string.maps_tint_value, name, (layer.tintStrength * PERCENT).roundToInt())
}

/** The sheet of the tint of [layer], named [name]; [onTint] receives the colour (or `null`) and the intensity. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TintSheet(
    layer: LayerConfig,
    name: String,
    onTint: (Int?, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.toolMap.panel,
    ) {
        TintSheetContent(layer, name, onTint, onDismiss)
    }
}

/** What the sheet shows: title, what a tint does, the dots, the intensity of a chosen colour, « Retirer la teinte ». */
@Composable
internal fun TintSheetContent(
    layer: LayerConfig,
    name: String,
    onTint: (Int?, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var wheel by remember { mutableStateOf(false) }
    // The intensity follows the finger here and reaches the map when it lets go.
    var strength by remember(layer.tintStrength) { mutableFloatStateOf(layer.tintStrength) }
    val tint = layer.tint
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.maps_tint_title, name),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.maps_tint_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            ValueLabel(stringResource(R.string.maps_tint), tintText(layer.copy(tintStrength = strength)))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(stringResource(R.string.maps_tint_none), selected = tint == null, onClick = {
                    onTint(null, strength)
                }) {
                    NoneMark()
                }
                for (color in TintColor.entries) {
                    Dot(
                        stringResource(color.label),
                        selected = tint == color.rgb,
                        onClick = { onTint(color.rgb, strength) },
                        fill = Color(OPAQUE or color.rgb),
                    )
                }
                Dot(
                    stringResource(R.string.maps_tint_other),
                    selected = tint != null && TintColor.of(tint) == null,
                    onClick = { wheel = true },
                    brush = Brush.sweepGradient(WHEEL_COLORS),
                )
            }
            if (tint != null) {
                ValueLabel(stringResource(R.string.maps_tint_intensity), "${(strength * PERCENT).roundToInt()} %")
                Slider(
                    value = strength,
                    onValueChange = { strength = it },
                    onValueChangeFinished = { onTint(tint, strength) },
                )
                TextButton(onClick = { onTint(null, strength) }) { Text(stringResource(R.string.maps_tint_remove)) }
            }
        }
    }
    if (wheel) {
        ColorWheelDialog(
            title = stringResource(R.string.maps_tint_wheel_title),
            badge = { Box(Modifier.size(28.dp).background(Color(it), CircleShape)) },
            initial = OPAQUE or (tint ?: TintColor.BLUE.rgb),
            palette = TintColor.entries.map { OPAQUE or it.rgb },
            onPick = {
                wheel = false
                onTint(it and RGB, strength)
            },
            onDismiss = { wheel = false },
        )
    }
}

@Composable
private fun ValueLabel(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

/** A round choice, ringed in the accent colour when [selected]. */
@Composable
private fun Dot(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    fill: Color = Color.White,
    brush: Brush? = null,
    content: @Composable () -> Unit = {},
) {
    val accent = MaterialTheme.toolMap.accent
    val ring = MaterialTheme.toolMap.panel
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(DOT)
                .drawBehind {
                    if (selected) {
                        drawCircle(accent, size.minDimension / 2 + 4.dp.toPx())
                        drawCircle(ring, size.minDimension / 2 + 2.dp.toPx())
                    }
                }.then(
                    if (brush !=
                        null
                    ) {
                        Modifier.background(brush, CircleShape)
                    } else {
                        Modifier.background(fill, CircleShape)
                    },
                )
                .border(1.dp, Color.Black.copy(alpha = OUTLINE), CircleShape),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/** « aucune »: a red stroke across the white dot. */
@Composable
private fun NoneMark() {
    Box(
        Modifier.size(DOT).drawBehind {
            val inset = size.minDimension * NONE_INSET
            drawLine(
                NONE_RED,
                Offset(inset, size.height - inset),
                Offset(size.width - inset, inset),
                strokeWidth = 2.dp.toPx(),
            )
        },
    )
}

private val DOT = 28.dp
private const val PERCENT = 100f
private const val OPAQUE = 0xFF shl 24
private const val RGB = 0xFFFFFF
private const val OUTLINE = 0.18f
private const val NONE_INSET = 0.2f
private val NONE_RED = Color(0xFFC0392B)
private val WHEEL_COLORS =
    listOf(0xFFFF1744, 0xFFFF9100, 0xFFFFEA00, 0xFF76FF03, 0xFF1DE9B6, 0xFF29B6F6, 0xFF7C4DFF, 0xFFE040FB, 0xFFFF1744)
        .map { Color(it) }
