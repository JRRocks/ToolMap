package fr.toolmap.maps

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.layers.Blend
import fr.toolmap.core.layers.BlendMode
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.layers.RasterAdjust
import fr.toolmap.ui.theme.toolMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * « Fusion › » of a layer (P26.4, variant E « Feuille expliquée », chosen on 2026-11-03): a sheet of the 16 modes in
 * six families, each with its French name, its English one in grey, a sentence saying what it does and a vignette of
 * the map where it is, drawn with that mode over the layers under it. A touch chooses the mode and closes the sheet;
 * the map is redrawn from the stored tiles (ADR-111).
 */

/** A family of modes and the modes in it, in the order of the sheet. */
internal enum class BlendFamily(
    @param:StringRes val label: Int,
    val modes: List<BlendMode>,
) {
    NORMAL(R.string.blend_family_normal, listOf(BlendMode.NORMAL)),
    DARKEN(R.string.blend_family_darken, listOf(BlendMode.MULTIPLY, BlendMode.DARKEN, BlendMode.COLOR_BURN)),
    LIGHTEN(R.string.blend_family_lighten, listOf(BlendMode.SCREEN, BlendMode.LIGHTEN, BlendMode.COLOR_DODGE)),
    CONTRAST(R.string.blend_family_contrast, listOf(BlendMode.OVERLAY, BlendMode.SOFT_LIGHT, BlendMode.HARD_LIGHT)),
    COMPARE(R.string.blend_family_compare, listOf(BlendMode.DIFFERENCE, BlendMode.EXCLUSION)),
    COLOR(
        R.string.blend_family_color,
        listOf(BlendMode.HUE, BlendMode.SATURATION, BlendMode.COLOR, BlendMode.LUMINOSITY),
    ),
}

/** The French name of a mode. */
@StringRes
internal fun BlendMode.label(): Int =
    when (this) {
        BlendMode.NORMAL -> R.string.blend_normal
        BlendMode.MULTIPLY -> R.string.blend_multiply
        BlendMode.SCREEN -> R.string.blend_screen
        BlendMode.OVERLAY -> R.string.blend_overlay
        BlendMode.DARKEN -> R.string.blend_darken
        BlendMode.LIGHTEN -> R.string.blend_lighten
        BlendMode.COLOR_DODGE -> R.string.blend_color_dodge
        BlendMode.COLOR_BURN -> R.string.blend_color_burn
        BlendMode.HARD_LIGHT -> R.string.blend_hard_light
        BlendMode.SOFT_LIGHT -> R.string.blend_soft_light
        BlendMode.DIFFERENCE -> R.string.blend_difference
        BlendMode.EXCLUSION -> R.string.blend_exclusion
        BlendMode.HUE -> R.string.blend_hue
        BlendMode.SATURATION -> R.string.blend_saturation
        BlendMode.COLOR -> R.string.blend_color
        BlendMode.LUMINOSITY -> R.string.blend_luminosity
    }

/** What a mode does, in one sentence. */
@StringRes
private fun BlendMode.about(): Int =
    when (this) {
        BlendMode.NORMAL -> R.string.blend_normal_about
        BlendMode.MULTIPLY -> R.string.blend_multiply_about
        BlendMode.SCREEN -> R.string.blend_screen_about
        BlendMode.OVERLAY -> R.string.blend_overlay_about
        BlendMode.DARKEN -> R.string.blend_darken_about
        BlendMode.LIGHTEN -> R.string.blend_lighten_about
        BlendMode.COLOR_DODGE -> R.string.blend_color_dodge_about
        BlendMode.COLOR_BURN -> R.string.blend_color_burn_about
        BlendMode.HARD_LIGHT -> R.string.blend_hard_light_about
        BlendMode.SOFT_LIGHT -> R.string.blend_soft_light_about
        BlendMode.DIFFERENCE -> R.string.blend_difference_about
        BlendMode.EXCLUSION -> R.string.blend_exclusion_about
        BlendMode.HUE -> R.string.blend_hue_about
        BlendMode.SATURATION -> R.string.blend_saturation_about
        BlendMode.COLOR -> R.string.blend_color_about
        BlendMode.LUMINOSITY -> R.string.blend_luminosity_about
    }

/** The W3C name of a mode, as image editors show it: « Color burn », « Soft light ». */
internal val BlendMode.englishName: String
    get() = key.replace('-', ' ').replaceFirstChar { it.uppercase() }

/** « Fusion de Sentiers », « Fusion d'IGN Plan v2 »: French drops the vowel of « de » before a vowel. */
@Composable
private fun sheetTitle(name: String): String =
    stringResource(
        if (name.firstOrNull()?.lowercaseChar()?.let { it in ELIDING } == true) {
            R.string.maps_blend_title_elided
        } else {
            R.string.maps_blend_title
        },
        name,
    )

/**
 * The sheet of the modes of [layer], named [name]; [below] are the layers drawn under it at this zoom, top first.
 * [onPick] receives the mode touched; the sheet closes after it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BlendSheet(
    layer: LayerConfig,
    name: String,
    below: List<LayerConfig>,
    thumbnails: MapThumbnails,
    onPick: (BlendMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.toolMap.panel,
    ) {
        val vignettes by produceState<Map<BlendMode, ImageBitmap>>(emptyMap(), layer, below) {
            value = vignettes(layer, below.filter { it.visible && it.opacity > 0f }, thumbnails)
        }
        BlendSheetContent(layer.blend, sheetTitle(name), vignettes, onPick, onDismiss)
    }
}

/** What the sheet shows: its title, then each family and its modes; [selected] is checked. */
@Composable
internal fun BlendSheetContent(
    selected: BlendMode,
    title: String,
    vignettes: Map<BlendMode, ImageBitmap>,
    onPick: (BlendMode) -> Unit,
    onDismiss: () -> Unit,
) {
    // The map stays in view above the sheet, as in the preview.
    Column(Modifier.fillMaxWidth().fillMaxHeight(SHEET_HEIGHT).navigationBarsPadding()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
            }
        }
        LazyColumn {
            for (family in BlendFamily.entries) {
                item(key = family.name) {
                    Text(
                        stringResource(family.label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.toolMap.accent,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
                    )
                }
                items(family.modes, key = { it.key }) { mode ->
                    ModeRow(mode, mode == selected, vignettes[mode]) { onPick(mode) }
                }
            }
        }
    }
}

@Composable
private fun ModeRow(
    mode: BlendMode,
    selected: Boolean,
    vignette: ImageBitmap?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.toolMap.panel)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.toolMap.tile),
        ) {
            vignette?.let {
                Image(
                    it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(
                buildAnnotatedString {
                    append(stringResource(mode.label()))
                    append(' ')
                    withStyle(
                        SpanStyle(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Normal,
                            fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        ),
                    ) { append(mode.englishName) }
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(mode.about()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.toolMap.accent)
    }
}

/**
 * The vignette of each mode: the middle of the tile where the map is, the layers under [layer] drawn bottom first
 * as the tiles of the map draw them (`BitmapTileBlender`), then [layer] with each mode. Empty without its tile.
 */
private suspend fun vignettes(
    layer: LayerConfig,
    below: List<LayerConfig>,
    thumbnails: MapThumbnails,
): Map<BlendMode, ImageBitmap> {
    val own = thumbnails.tileHere(layer.sourceId) ?: return emptyMap()
    val under = below.asReversed().mapNotNull { part -> thumbnails.tileHere(part.sourceId)?.let { part to it } }
    return withContext(Dispatchers.Default) {
        val canvas = IntArray(VIGNETTE_PX * VIGNETTE_PX)
        for ((part, bitmap) in under) Blend.composite(canvas, prepared(part, bitmap), part.blend, part.opacity)
        val top = prepared(layer, own)
        BlendMode.entries.associateWith { mode ->
            val pixels = canvas.copyOf()
            Blend.composite(pixels, top, mode, layer.opacity)
            Bitmap.createBitmap(pixels, VIGNETTE_PX, VIGNETTE_PX, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
}

/** The middle half of a tile at the vignette size, with the colours and tint of [part]. */
private fun prepared(
    part: LayerConfig,
    tile: Bitmap,
): IntArray {
    val side = tile.width.coerceAtMost(tile.height) / 2
    val middle = Bitmap.createBitmap(tile, (tile.width - side) / 2, (tile.height - side) / 2, side, side)
    val scaled = Bitmap.createScaledBitmap(middle, VIGNETTE_PX, VIGNETTE_PX, true)
    val pixels = IntArray(VIGNETTE_PX * VIGNETTE_PX)
    scaled.getPixels(pixels, 0, VIGNETTE_PX, 0, 0, VIGNETTE_PX, VIGNETTE_PX)
    RasterAdjust.colorMatrix(part)?.let { Blend.adjust(pixels, it) }
    part.tint?.let { Blend.tint(pixels, it, part.tintStrength) }
    return pixels
}

private const val VIGNETTE_PX = 96
private const val SHEET_HEIGHT = 0.8f
private const val ELIDING = "aeiouyàâéèêëîïôûüœ"
