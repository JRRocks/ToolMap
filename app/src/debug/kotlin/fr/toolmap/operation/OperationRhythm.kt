package fr.toolmap.operation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.hierarchy.Change
import fr.toolmap.core.hierarchy.HierarchyState
import fr.toolmap.core.settings.ValueEntry
import fr.toolmap.download.DownloadIcons
import fr.toolmap.draw.RouteIcons
import fr.toolmap.share.PresetSlider
import fr.toolmap.ui.EntryField
import fr.toolmap.ui.ValueDialog
import fr.toolmap.ui.durationText

/** The rhythm of the positions of the operation (P39.15), one minute until someone changes it. */
val OperationFacts.positionIntervalS: Int get() = state?.positionIntervalS ?: HierarchyState.DEFAULT_POSITION_INTERVAL_S

/**
 * Whether this phone may change the rhythm (F3 A): the creator, the administrators, the chief of the operation and its
 * deputy. Asked with another value than the current one, which would be refused as no change.
 */
val OperationFacts.mayChangeRhythm: Boolean
    get() {
        val other =
            if (positionIntervalS == HierarchyState.MIN_POSITION_INTERVAL_S) {
                HierarchyState.MIN_POSITION_INTERVAL_S + 1
            } else {
                HierarchyState.MIN_POSITION_INTERVAL_S
            }
        return may(Change.SetPositionInterval(other))
    }

/** « Toutes les minutes », « Toutes les 45 s » (F1 A). */
@Composable
fun rhythmText(seconds: Int): String =
    if (seconds == HierarchyState.DEFAULT_POSITION_INTERVAL_S) {
        stringResource(R.string.op_freq_every_minute)
    } else {
        stringResource(R.string.op_freq_every, durationText(seconds))
    }

/**
 * « Fréquence d'envoi · Toutes les minutes » under « Partager ma position » (F1 A, `tour6/img/02-q1-A`): an arrow to
 * who may change it, which opens « Réglages d'envoi »; to the others a lock and « Réglée par le chef de l'opération »,
 * and nothing opens.
 */
@Composable
fun FrequencyLine(
    facts: OperationFacts,
    onOpen: () -> Unit,
) {
    val may = facts.mayChangeRhythm
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = may, onClick = onOpen)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
            Icon(DownloadIcons.Schedule, contentDescription = null, tint = grey, modifier = Modifier.size(26.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(stringResource(R.string.share_interval), style = MaterialTheme.typography.bodyLarge)
            Text(rhythmText(facts.positionIntervalS), style = MaterialTheme.typography.bodySmall, color = grey)
            if (!may) {
                Text(stringResource(R.string.op_freq_locked), style = MaterialTheme.typography.bodySmall, color = grey)
            }
        }
        if (may) {
            Icon(RouteIcons.ChevronRight, contentDescription = null, tint = grey)
        } else {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = grey)
        }
    }
}

/**
 * « Réglages d'envoi » of the operation (F2 B modifié, `tour6/img/05-q2-B-modifie`, `-2`, `-3`): the slider of
 * « Entre amis » from 1 to 10 min, one step a minute, the minutes under the steps; its pill opens the window of
 * « Entre amis » (1 s to 24 h, without « Déplacement minimal »), with its own warning under 30 s (2 a). Nothing
 * changes until « Appliquer ».
 */
@Composable
fun FrequencySheet(
    session: OperationSession,
    facts: OperationFacts,
    sheet: OpSheet.Frequency,
) {
    val current = facts.positionIntervalS
    var draft by remember(sheet) { mutableIntStateOf(sheet.draft ?: current) }
    var typing by remember(sheet) { mutableStateOf(sheet.typing) }
    SheetTitle(stringResource(R.string.share_send_settings))
    Box(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp)) {
        PresetSlider(
            label = stringResource(R.string.share_interval),
            presets = RHYTHM_PRESETS_S,
            value = draft,
            format = { durationText(it) },
            onChange = { draft = it },
            onType = { typing = true },
            stepLabels = rhythmStepLabels(),
        )
    }
    InfoBox(
        OperationIcons.Group,
        rich(stringResource(R.string.op_freq_box)),
        BoxTone.TEAL,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
    )
    SheetButton(stringResource(R.string.op_freq_apply), enabled = true) {
        if (draft == current) session.sheet(null) else session.setPositionInterval(draft)
    }
    CancelLine(session)
    if (typing) {
        val warn = stringResource(R.string.op_freq_warn)
        ValueDialog(
            title = stringResource(R.string.share_send_settings),
            fields =
                listOf(
                    EntryField(
                        stringResource(R.string.share_interval),
                        draft,
                        HierarchyState.MIN_POSITION_INTERVAL_S,
                        HierarchyState.MAX_POSITION_INTERVAL_S,
                        timeUnit = ValueEntry.TimeUnit.SECONDS,
                    ),
                ),
            warning = { values -> warn.takeIf { (values[0] ?: Int.MAX_VALUE) < WARN_BELOW_S } },
            onDone = { values ->
                draft = values[0] ?: draft
                typing = false
            },
            onDismiss = { typing = false },
        )
    }
}

/** « 1 min », « 2 », « 3 », « 4 », « 5 min », … « 9 », « 10 min » under the steps (F2 B modifié). */
@Composable
private fun rhythmStepLabels(): List<String> {
    val minutes = stringResource(R.string.value_unit_min)
    return (1..RHYTHM_STEPS).map { m -> if (m in WITH_UNIT) "$m $minutes" else "$m" }
}

private const val MINUTE_S = 60
private const val RHYTHM_STEPS = 10

/** The steps of the slider: one a minute, from 1 to 10 min. */
private val RHYTHM_PRESETS_S = (1..RHYTHM_STEPS).map { it * MINUTE_S }

/** The steps written with their unit, as in the maquette. */
private val WITH_UNIT = setOf(1, 5, 10)

/** Under 30 s, the window of the operation warns (2 a); « Entre amis » keeps its own 5 s. */
private const val WARN_BELOW_S = 30
