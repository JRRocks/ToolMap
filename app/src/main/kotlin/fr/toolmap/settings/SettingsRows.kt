package fr.toolmap.settings

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.settings.RoutingNoData
import fr.toolmap.core.settings.SettingsSearch
import fr.toolmap.core.settings.StorageLocation
import fr.toolmap.storage.TileStorageState
import fr.toolmap.storage.TileStorages
import fr.toolmap.ui.LocalAppSettings
import fr.toolmap.ui.theme.Switch
import fr.toolmap.ui.theme.TextButton
import java.io.File
import kotlin.math.roundToInt

/*
 * The rows of the settings (P29.9): a grey icon, the name and a second line, and the control: a switch, a segmented
 * choice under the name, a slider in the row, or a tap that opens a dialog for the choices that need one.
 */

/** Colours offered for the placemarks (AlpineQuest's colour picker, in a few steps). */
private val PALETTE: List<Int> =
    listOf(
        0xFFE53935,
        0xFFFB8C00,
        0xFFFDD835,
        0xFF43A047,
        0xFF00897B,
        0xFF1E88E5,
        0xFF3949AB,
        0xFF8E24AA,
        0xFFD81B60,
        0xFF6D4C41,
        0xFF757575,
        0xFF212121,
    ).map { it.toInt() }

/** A slider with more steps than this has no tick marks: they would make a grey line. */
private const val MAX_TICKS = 10

/**
 * Where a row is shown: on the page of its category, or in the results of a search, where its second line starts with
 * the name of the [category] and the words searched for ([terms]) are in bold.
 */
internal class RowPlace(
    val category: String?,
    val terms: List<String>,
)

internal val LocalRowPlace = compositionLocalOf { RowPlace(null, emptyList()) }

/** The height of a row: 56 dp, 72 dp in gloves mode. */
@Composable
internal fun rowHeight(): Dp = if (LocalAppSettings.current.gloves) 72.dp else 56.dp

/** [text] with the words of [terms] in bold and in [color], from the character [from] on. */
internal fun bold(
    text: String,
    terms: List<String>,
    color: Color,
    from: Int = 0,
): AnnotatedString =
    buildAnnotatedString {
        append(text)
        if (terms.isEmpty()) return@buildAnnotatedString
        SettingsSearch.highlights(text.substring(from), terms).forEach { range ->
            addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color), from + range.first, from + range.last + 1)
        }
    }

/** The name and the second line of a row; in the results of a search, the category first and the words in bold. */
@Composable
internal fun RowTexts(
    title: String,
    subtitle: String?,
) {
    val place = LocalRowPlace.current
    val strong = MaterialTheme.colorScheme.onSurface
    Text(bold(title, place.terms, strong), style = MaterialTheme.typography.bodyLarge)
    val prefix = place.category?.let { if (subtitle == null) it else "$it · " }.orEmpty()
    val second = prefix + subtitle.orEmpty()
    if (second.isNotEmpty()) {
        Text(
            // The name of the category is not searched in: it only says where the setting is.
            if (subtitle == null) AnnotatedString(second) else bold(second, place.terms, strong, prefix.length),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One setting: a grey icon, its name, its second line, a control on the right or under the texts. */
@Composable
internal fun SettingRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    minHeight: Dp = rowHeight(),
    endPadding: Dp = 16.dp,
    bottomPadding: Dp = 8.dp,
    alignTop: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = minHeight)
                .then(modifier)
                .padding(start = 16.dp, end = endPadding, top = 8.dp, bottom = bottomPadding),
        verticalAlignment = if (alignTop) Alignment.Top else Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            RowTexts(title, subtitle)
            below?.invoke(this)
        }
        trailing?.invoke(this)
    }
}

/** Yes or no, with the switch of the « Crête » style; a tap anywhere on the row toggles it. */
@Composable
internal fun SwitchRow(
    icon: ImageVector,
    title: String,
    hint: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SettingRow(
        icon = icon,
        title = title,
        subtitle = hint,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        trailing = {
            Spacer(Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = null)
        },
    )
}

/**
 * A choice of two or three options: their short names on a segmented button the width of the row, under its name; the
 * full name of the chosen one on the second line when it says more (« Degrés (360° par tour) »).
 */
@Composable
internal fun SegmentedRow(
    icon: ImageVector,
    title: String,
    shorts: List<String>,
    fulls: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val gloves = LocalAppSettings.current.gloves
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingRow(
            icon = icon,
            title = title,
            subtitle = fulls.getOrNull(selected)?.takeIf { it != shorts.getOrNull(selected) },
            minHeight = 44.dp,
            bottomPadding = 6.dp,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
            shorts.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = index == selected,
                    onClick = { onSelect(index) },
                    shape = SegmentedButtonDefaults.itemShape(index, shorts.size),
                    // No tick, so that the names fit: the colour says which one is chosen.
                    icon = {},
                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = if (gloves) Modifier.heightIn(min = 48.dp) else Modifier,
                )
            }
        }
    }
}

/**
 * A value on a slider in the row itself, as « Mode nuit de la carte »: the value under the slider, written by [label];
 * it is saved when the finger leaves the slider, rounded to [step].
 */
@Composable
internal fun SliderRow(
    icon: ImageVector,
    title: String,
    hint: String?,
    value: Int,
    range: IntRange,
    step: Int,
    label: @Composable (Int) -> String,
    onChange: (Int) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value.coerceIn(range).toFloat()) }
    val snap = { v: Float -> ((v / step).roundToInt() * step).coerceIn(range) }
    val intervals = (range.last - range.first) / step
    SettingRow(
        icon = icon,
        title = title,
        subtitle = hint,
        alignTop = true,
        below = {
            Slider(
                value = current,
                onValueChange = { current = it },
                onValueChangeFinished = { onChange(snap(current)) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = if (intervals <= MAX_TICKS) intervals - 1 else 0,
            )
            Text(
                label(snap(current)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/** A choice that keeps its dialog (formats with their examples, apps, altitude…): the row shows the value. */
@Composable
internal fun <T> ChoiceRow(
    icon: ImageVector,
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    value: @Composable (T) -> String = label,
    detail: (@Composable (T) -> String?)? = null,
    enabled: (T) -> Boolean = { true },
) {
    var open by remember { mutableStateOf(false) }
    SettingRow(
        icon = icon,
        title = title,
        subtitle = value(selected),
        modifier = Modifier.clickable { open = true },
    )
    if (open) {
        SettingsDialog(title = title, onDismiss = { open = false }) {
            ChoiceList(options, selected, label, detail, enabled) { choice ->
                onSelect(choice)
                open = false
            }
        }
    }
}

/** A colour, shown as a disc; a tap opens the palette. */
@Composable
internal fun ColorRow(
    icon: ImageVector,
    title: String,
    hint: String,
    color: Int,
    onChange: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    SettingRow(
        icon = icon,
        title = title,
        subtitle = hint,
        modifier = Modifier.clickable { open = true },
        trailing = {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier =
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(color))
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
        },
    )
    if (open) {
        SettingsDialog(title = title, onDismiss = { open = false }) {
            PALETTE.chunked(6).forEach { swatches ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    swatches.forEach { swatch ->
                        Box(
                            modifier =
                                Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color(swatch))
                                    .clickable {
                                        onChange(swatch)
                                        open = false
                                    },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (swatch == color) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * What a route does when BRouter cannot compute a leg (P19, screen 7 of `docs/previews/p19-routing.html`): the leg by
 * hand and the routing goes on, or the whole route by hand. The dialog asks the question; the choice applies at the tap.
 */
@Composable
internal fun RoutingNoDataRow(
    selected: RoutingNoData,
    onSelect: (RoutingNoData) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val title = stringResource(R.string.settings_routing_no_data)
    SettingRow(
        icon = SettingsIcons.AltRoute,
        title = title,
        subtitle = stringResource(selected.labelRes()),
        modifier = Modifier.clickable { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.settings_routing_no_data_ask))
                    ChoiceList(
                        options = RoutingNoData.entries,
                        selected = selected,
                        label = { stringResource(it.labelRes()) },
                        detail = { stringResource(it.detailRes()) },
                    ) { choice ->
                        onSelect(choice)
                        open = false
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * Where the offline maps are kept, moved between the phone and a removable card (ADR-042). A move is asked for after a
 * confirmation: [onMoveTiles] closes the app, which then runs it.
 */
@Composable
internal fun StorageRow(onMoveTiles: (File) -> Unit) {
    val context = LocalContext.current
    val storage = remember { TileStorages.state(context) }
    var asked by remember { mutableStateOf<StorageLocation?>(null) }
    ChoiceRow(
        icon = SettingsIcons.Storage,
        title = stringResource(R.string.settings_storage),
        options = StorageLocation.entries,
        selected = storage.inUse,
        label = { stringResource(it.labelRes()) },
        detail = { storageDetail(it, storage) },
        enabled = { choice -> choice == storage.inUse || storage.moveTarget(choice) != null },
        onSelect = { choice -> if (choice != storage.inUse) asked = choice },
    )
    asked?.let { choice ->
        val target = storage.moveTarget(choice)
        AlertDialog(
            onDismissRequest = { asked = null },
            title = { Text(stringResource(R.string.settings_storage_move_title)) },
            text = {
                val destination =
                    stringResource(
                        if (choice == StorageLocation.EXTERNAL) {
                            R.string.settings_storage_dest_card
                        } else {
                            R.string.settings_storage_dest_phone
                        },
                    )
                Text(
                    stringResource(
                        R.string.settings_storage_move_text,
                        Formatter.formatShortFileSize(context, storage.tilesBytes),
                        destination,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = target != null,
                    onClick = {
                        asked = null
                        target?.let(onMoveTiles)
                    },
                ) { Text(stringResource(R.string.settings_storage_move_confirm)) }
            },
            dismissButton = { TextButton(onClick = { asked = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** What an option of the storage choice says under it: why it cannot be picked, or what picking it does. */
@Composable
private fun storageDetail(
    choice: StorageLocation,
    storage: TileStorageState,
): String? =
    when {
        choice == storage.inUse && !storage.activeUsable -> stringResource(R.string.settings_storage_card_absent)

        choice == storage.inUse -> null

        storage.moveTarget(
            choice,
        ) != null && choice == StorageLocation.EXTERNAL -> stringResource(R.string.settings_storage_note)

        storage.moveTarget(choice) != null -> stringResource(R.string.settings_storage_note_back)

        choice == StorageLocation.EXTERNAL -> stringResource(R.string.settings_storage_no_card)

        else -> stringResource(R.string.settings_storage_card_absent)
    }

@Composable
private fun SettingsDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { content() } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) } },
    )
}

@Composable
private fun <T> ChoiceList(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    detail: (@Composable (T) -> String?)?,
    enabled: (T) -> Boolean = { true },
    onSelect: (T) -> Unit,
) {
    options.forEach { option ->
        val available = enabled(option)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .selectable(
                        selected = option == selected,
                        enabled = available,
                        onClick = { onSelect(option) },
                        role = Role.RadioButton,
                    ).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = option == selected, onClick = null, enabled = available)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    label(option),
                    style = MaterialTheme.typography.bodyLarge,
                    color =
                        if (available) {
                            Color.Unspecified
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        },
                )
                detail?.invoke(option)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
