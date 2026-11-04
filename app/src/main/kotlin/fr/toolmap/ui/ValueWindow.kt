package fr.toolmap.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.settings.ValueEntry
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap

/**
 * A value that can be typed (P23, variant A): the value in an outlined pill with a pencil, in the accent colour; a
 * touch opens its [ValueDialog]. TalkBack reads « Modifier [label] : [text] ».
 */
@Composable
fun ValuePill(
    text: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false,
) {
    val accent = MaterialTheme.toolMap.accent
    val description = stringResource(R.string.value_edit_desc, label, text)
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .border(BorderStroke(1.dp, accent), RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = if (small) 8.dp else 10.dp, vertical = if (small) 1.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text,
            color = accent,
            fontWeight = FontWeight.SemiBold,
            style = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
        Icon(
            Icons.Filled.Edit,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(if (small) 12.dp else 14.dp),
        )
    }
}

/**
 * One field of a [ValueDialog] ([label] `null` when the title already names it): [min]..[max] in the field's base
 * unit (seconds for a duration), [initial] `null` for an empty field. [optional] lets it stay empty (« sans limite »);
 * [timeUnit] shows the s / min / h choice under it.
 */
class EntryField(
    val label: String?,
    val initial: Int?,
    val min: Int,
    val max: Int,
    val suffix: String? = null,
    val optional: Boolean = false,
    val timeUnit: ValueEntry.TimeUnit? = null,
)

/**
 * The window of a typed value (P23, variant A): a title, its fields with the numeric keyboard, the limits under each
 * field, an optional [warning] computed from the values, and Annuler / OK. OK stays greyed while a field is wrong;
 * [onDone] gets one value per field (`null` for an empty optional field). [sideBySide] puts the fields on one row
 * (De / À) with a single line of limits under them.
 */
@Composable
fun ValueDialog(
    title: String,
    fields: List<EntryField>,
    onDone: (List<Int?>) -> Unit,
    onDismiss: () -> Unit,
    focus: Int = 0,
    hint: String? = null,
    sideBySide: Boolean = false,
    warning: (List<Int?>) -> String? = { null },
) {
    // State-based fields, as in the record dialog: in a dialog, the value-based field is laid out again and again under
    // Robolectric until the tests run out of memory.
    val texts =
        fields.map { f ->
            val shown = f.initial?.let { v -> f.timeUnit?.let { v / ValueEntry.unitOf(v).seconds } ?: v }
            val s = shown?.toString().orEmpty()
            // All selected: the first key typed replaces the value.
            rememberTextFieldState(s, TextRange(0, s.length))
        }
    val units =
        remember {
            mutableStateListOf(
                *fields.map { f ->
                    f.initial?.takeIf { f.timeUnit != null }?.let(ValueEntry::unitOf)
                        ?: f.timeUnit
                }.toTypedArray(),
            )
        }
    val results =
        fields.mapIndexed { i, f ->
            val unit = units[i]
            if (unit != null) {
                ValueEntry.parseSeconds(texts[i].text.toString(), unit, f.min, f.max)
            } else {
                ValueEntry.parse(texts[i].text.toString(), f.min, f.max)
            }
        }
    val ok =
        results.zip(fields).all { (r, f) ->
            r is ValueEntry.Result.Value || (r == ValueEntry.Result.Empty && f.optional)
        }
    val values = results.map { (it as? ValueEntry.Result.Value)?.value }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val wrong = results.map { !(it is ValueEntry.Result.Value || it == ValueEntry.Result.Empty) }
                val field: @Composable (Int, Modifier) -> Unit = { i, modifier ->
                    val f = fields[i]
                    val last = i == fields.lastIndex
                    OutlinedTextField(
                        state = texts[i],
                        inputTransformation = NumberOnly,
                        label = f.label?.let { l -> { Text(l) } },
                        suffix = f.suffix?.let { s -> { Text(s) } },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        isError = wrong[i],
                        supportingText = if (sideBySide) null else ({ Text(limitsText(f, results[i], units[i])) }),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = if (last) ImeAction.Done else ImeAction.Next,
                            ),
                        onKeyboardAction = { default -> if (last && ok) onDone(values) else default() },
                        modifier = modifier.let { if (i == focus) it.focusRequester(requester) else it },
                    )
                }
                // The warning goes under the units of the duration it is about, else under the fields.
                val warnAfter = fields.indexOfFirst { it.timeUnit != null }.takeIf { it >= 0 && !sideBySide }
                if (sideBySide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        fields.indices.forEach { i -> field(i, Modifier.weight(1f)) }
                    }
                    // One line for both fields: their limits, or what is wrong with the first wrong one.
                    val shown = wrong.indexOf(true).takeIf { it >= 0 } ?: 0
                    val colors = MaterialTheme.colorScheme
                    Text(
                        limitsText(fields[shown], results[shown], units[shown]),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (wrong[shown]) colors.error else colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                } else {
                    fields.indices.forEach { i ->
                        field(i, Modifier.fillMaxWidth())
                        units[i]?.let { unit -> TimeUnitChoice(unit) { units[i] = it } }
                        if (i == warnAfter) WarningBox(warning(values))
                    }
                }
                if (warnAfter == null) WarningBox(warning(values))
                hint?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(values) }, enabled = ok) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun WarningBox(text: String?) {
    if (text == null) return
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}

/** Digits, a minus sign and spaces only: anything else typed or pasted is refused. */
private val NumberOnly =
    InputTransformation {
        if (asCharSequence().any { !(it.isDigit() || it == '-' || it == '−' || it == ' ') }) revertAllChanges()
    }

/** Under a field: its limits, or what is wrong with what was typed. */
@Composable
private fun limitsText(
    field: EntryField,
    result: ValueEntry.Result,
    unit: ValueEntry.TimeUnit?,
): String {
    val limits =
        if (unit != null) {
            stringResource(R.string.value_limits, durationText(field.min), durationText(field.max))
        } else {
            val suffix = field.suffix?.let { " $it" }.orEmpty()
            stringResource(R.string.value_limits, numberText(field.min), numberText(field.max) + suffix)
        }
    return when (result) {
        ValueEntry.Result.Invalid -> stringResource(R.string.value_whole)
        is ValueEntry.Result.OutOfRange -> stringResource(R.string.value_out_of_range, limits)
        else -> if (field.optional) stringResource(R.string.value_optional, limits) else limits
    }
}

@Composable
private fun TimeUnitChoice(
    unit: ValueEntry.TimeUnit,
    onChoose: (ValueEntry.TimeUnit) -> Unit,
) {
    val all = ValueEntry.TimeUnit.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        all.forEachIndexed { i, u ->
            SegmentedButton(
                selected = u == unit,
                onClick = { onChoose(u) },
                shape = SegmentedButtonDefaults.itemShape(i, all.size),
                icon = {},
            ) { Text(stringResource(unitName(u))) }
        }
    }
}

private fun unitName(unit: ValueEntry.TimeUnit) =
    when (unit) {
        ValueEntry.TimeUnit.SECONDS -> R.string.value_unit_s
        ValueEntry.TimeUnit.MINUTES -> R.string.value_unit_min
        ValueEntry.TimeUnit.HOURS -> R.string.value_unit_h
    }

/** A whole number with a thin space between thousands and a true minus sign: « −500 », « 10 000 ». */
fun numberText(n: Int): String {
    val digits = kotlin.math.abs(n.toLong()).toString().reversed().chunked(THOUSANDS).joinToString(" ").reversed()
    return if (n < 0) "−$digits" else digits
}

/** A duration in its largest whole units: « 45 s », « 1 min 30 s », « 2 h », « 1 h 30 ». */
fun durationText(seconds: Int): String {
    val h = seconds / ValueEntry.TimeUnit.HOURS.seconds
    val m = seconds % ValueEntry.TimeUnit.HOURS.seconds / ValueEntry.TimeUnit.MINUTES.seconds
    val s = seconds % ValueEntry.TimeUnit.MINUTES.seconds
    return when {
        h > 0 && m == 0 && s == 0 -> "$h h"
        h > 0 && s == 0 -> "$h h %02d".format(m)
        h > 0 -> "$h h %02d min %02d s".format(m, s)
        m > 0 && s == 0 -> "$m min"
        m > 0 -> "$m min $s s"
        else -> "$s s"
    }
}

private const val THOUSANDS = 3
