package fr.toolmap.maps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.map.MapViewModel
import fr.toolmap.map.MenuRow
import fr.toolmap.map.PanelChevron
import fr.toolmap.ui.numberText
import fr.toolmap.ui.theme.Switch
import fr.toolmap.ui.theme.TextButton
import kotlin.math.roundToInt

/*
 * The « Couleurs » page of the sheet of a layer (P34, choice D of docs/previews/p34-complements.html, validated on
 * 2026-11-05), ← back to the sheet: what the unfolded line held before, unchanged. « Fusion » and « Teinter » open
 * their sheets (P26); the four corrections are sliders with the pill where they are typed (P23); then the inverted
 * colours and « Réinitialiser les couleurs ». While a slider is held, the rest fades away (P26.6).
 */
@Composable
internal fun ColorsPage(
    layer: LayerConfig,
    name: String,
    /** The layers under [layer], top first: the « Fusion » sheet shows each mode over them. */
    below: List<LayerConfig>,
    thumbnails: MapThumbnails,
    fade: PanelFade,
    vm: MapViewModel,
) {
    var blendSheet by remember(layer.id) { mutableStateOf(false) }
    var tintSheet by remember(layer.id) { mutableStateOf(false) }
    var typing by remember(layer.id) { mutableStateOf<Adjust?>(null) }
    val rest = Modifier.alpha(fade.shown)
    Column(rest) {
        MenuRow(
            label = stringResource(R.string.maps_blend),
            icon = MapsIcons.Blend,
            subtitle = stringResource(layer.blend.label()),
            trailing = { PanelChevron() },
            onClick = { blendSheet = true },
        )
        RowDivider()
        MenuRow(
            label = stringResource(R.string.maps_tint),
            icon = MapsIcons.Tint,
            // « Aucune », « Bleu · 50 % »: a subtitle starts with a capital, as in the preview.
            subtitle = tintText(layer).replaceFirstChar { it.uppercase() },
            trailing = { PanelChevron() },
            onClick = { tintSheet = true },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    val hue = layer.hue.roundToInt()
    Column(Modifier.padding(vertical = 4.dp)) {
        PageSlider {
            AdjustSlider(
                key = "contrast",
                label = stringResource(R.string.layers_contrast_name),
                valuePill = signedPercent(layer.contrast),
                onType = { typing = Adjust.CONTRAST },
                value = layer.contrast,
                range = -1f..1f,
                fade = fade,
                onChange = { vm.setAdjustments(layer.id, it, layer.brightness, layer.saturation) },
                onPage = true,
            )
        }
        PageSlider {
            AdjustSlider(
                key = "brightness",
                label = stringResource(R.string.layers_brightness_name),
                valuePill = signedPercent(layer.brightness),
                onType = { typing = Adjust.BRIGHTNESS },
                value = layer.brightness,
                range = -1f..1f,
                fade = fade,
                onChange = { vm.setAdjustments(layer.id, layer.contrast, it, layer.saturation) },
                onPage = true,
            )
        }
        PageSlider {
            AdjustSlider(
                key = "saturation",
                label = stringResource(R.string.layers_saturation_name),
                valuePill = signedPercent(layer.saturation),
                onType = { typing = Adjust.SATURATION },
                value = layer.saturation,
                range = -1f..1f,
                fade = fade,
                onChange = { vm.setAdjustments(layer.id, layer.contrast, layer.brightness, it) },
                onPage = true,
            )
        }
        PageSlider {
            AdjustSlider(
                key = "hue",
                label = stringResource(R.string.layers_hue_name),
                valuePill = "${numberText(hue)}°",
                onType = { typing = Adjust.HUE },
                value = layer.hue,
                range = HUE_RANGE,
                fade = fade,
                // Whole degrees: a favourite keeps the value its pill shows.
                onChange = { vm.setHue(layer.id, it.roundToInt().toFloat()) },
                hueTrack = true,
                valueText = "$hue°",
                onPage = true,
            )
        }
    }
    Column(rest) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(value = layer.inverted, role = Role.Switch, onValueChange = {
                    vm.setInverted(layer.id, it)
                })
                .padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.maps_layer_inverted),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = layer.inverted, onCheckedChange = null)
        }
        TextButton(onClick = { vm.resetColors(layer.id) }, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)) {
            Text(stringResource(R.string.layers_reset_adjustments))
        }
    }
    if (blendSheet) {
        BlendSheet(
            layer = layer,
            name = name,
            below = below,
            thumbnails = thumbnails,
            onPick = {
                blendSheet = false
                vm.setBlend(layer.id, it)
            },
            onDismiss = { blendSheet = false },
        )
    }
    if (tintSheet) {
        TintSheet(
            layer = layer,
            name = name,
            onTint = { tint, strength -> vm.setTint(layer.id, tint, strength) },
            onDismiss = { tintSheet = false },
        )
    }
    typing?.let { adjust -> AdjustDialog(adjust, layer, vm) { typing = null } }
}

/** A slider of the page, as in the preview: 8 dp above, 6 dp under. */
@Composable
private fun PageSlider(content: @Composable () -> Unit) {
    Box(Modifier.padding(top = 4.dp, bottom = 2.dp)) { content() }
}

/** « −20 % », « 0 % », « +35 % »: a colour correction of −1..1 as its pill shows it. */
private fun signedPercent(fraction: Float): String {
    val p = percentOf(fraction)
    return (if (p > 0) "+" else "") + numberText(p) + " %"
}
