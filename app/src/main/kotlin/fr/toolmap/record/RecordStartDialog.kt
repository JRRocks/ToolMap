package fr.toolmap.record

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.record.RecorderProfile
import fr.toolmap.core.record.TrackActivity
import fr.toolmap.core.track.NameDates
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap

/** The name of the activity in the start dialog and in the name of the track. */
@StringRes
fun TrackActivity.labelRes(): Int = ActivityRes.label(this)

/**
 * « Enregistrer une trace » (P29.5, « Sentier »): one dialog where there were two. The activity (when « Demander
 * l'activité » is on) is picked among the four last ones, « Toutes… » unfolding the 33 of AlpineQuest; the field shows
 * the final name of the track; the precision of the points says how often a point is kept. « Démarrer » starts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordStartDialog(
    initialProfile: RecorderProfile,
    onStart: (name: String, profile: RecorderProfile, activity: TrackActivity?) -> Unit,
    onDismiss: () -> Unit,
    askActivity: Boolean = false,
    recentActivities: List<TrackActivity> = emptyList(),
    /** The activity chosen in advance: the last one used, `null` before any. */
    initialActivity: TrackActivity? = null,
) {
    val date = NameDates.format(System.currentTimeMillis())
    val nameState = rememberTextFieldState()
    val name = nameState.text.toString()
    var profile by remember { mutableStateOf(initialProfile) }
    var activity by remember { mutableStateOf(if (askActivity) initialActivity else null) }
    var allActivities by remember { mutableStateOf(false) }
    val defaultName = stringResource(R.string.record_default_name, date)
    val baseName = name.ifBlank { defaultName }
    val finalName = activity?.let { stringResource(R.string.record_named, stringResource(it.labelRes()), baseName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogColor(),
        title = { Text(stringResource(R.string.record_start_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (askActivity) {
                    Caption(stringResource(R.string.record_activity_caption))
                    // The chosen activity stays among the chips, even when it came from the full list.
                    val chips = (listOfNotNull(activity) + recentActivities).distinct().take(CHIPS)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        chips.forEach { option ->
                            ActivityChip(option, selected = option == activity) {
                                activity = if (activity == option) null else option
                            }
                        }
                        FilterChip(
                            selected = allActivities,
                            onClick = { allActivities = !allActivities },
                            label = {
                                Text(stringResource(allLabel(allActivities)))
                            },
                            leadingIcon = {
                                Icon(AppsIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                        )
                    }
                    if (allActivities) {
                        ActivityGrid(selected = activity) {
                            activity = it
                            allActivities = false
                        }
                    }
                }
                OutlinedTextField(
                    state = nameState,
                    // The label stays up, so that the name the track will get (activity included) is always read.
                    labelPosition = TextFieldLabelPosition.Attached(alwaysMinimize = true),
                    label = { Text(stringResource(R.string.record_name_label)) },
                    placeholder = { Text(finalName ?: defaultName) },
                    modifier = Modifier.fillMaxWidth().padding(top = if (askActivity) 20.dp else 0.dp),
                )
                Caption(stringResource(R.string.record_profile_title), top = 16.dp, bottom = 2.dp)
                Column(Modifier.selectableGroup()) {
                    PROFILES.forEach { (option, label, detail) ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .selectable(selected = option == profile, role = Role.RadioButton) {
                                        profile = option
                                    },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == profile, onClick = null)
                            Column(Modifier.padding(start = 14.dp)) {
                                Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(detail),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onStart(baseName, profile, activity) }) { Text(stringResource(R.string.record_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** « Toutes… », or « Moins d'activités » once the list is unfolded. */
private fun allLabel(unfolded: Boolean): Int =
    if (unfolded) R.string.record_activity_fold else R.string.record_activity_all

/** A small grey title over a part of the dialog. */
@Composable
private fun Caption(
    text: String,
    top: Dp = 0.dp,
    bottom: Dp = 8.dp,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = top, bottom = bottom),
    )
}

/** An activity as a filter chip: its coloured icon, which the tick replaces once chosen. */
@Composable
private fun ActivityChip(
    activity: TrackActivity,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(stringResource(activity.labelRes())) },
        leadingIcon = {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            } else {
                Icon(
                    painterResource(ActivityRes.icon(activity)),
                    contentDescription = null,
                    tint = Color(activity.colorArgb),
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        },
    )
}

/** The 33 activities of AlpineQuest, four a row, in their order and their colours; names in 12 sp. */
@Composable
private fun ActivityGrid(
    selected: TrackActivity?,
    onSelect: (TrackActivity) -> Unit,
) {
    Column(Modifier.padding(top = 8.dp)) {
        TrackActivity.entries.chunked(GRID_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { activity ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier =
                            Modifier
                                .weight(1f)
                                .clip(MaterialTheme.shapes.medium)
                                .background(
                                    if (activity == selected) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        Color.Transparent
                                    },
                                ).clickable(role = Role.Button) { onSelect(activity) }
                                .padding(vertical = 6.dp, horizontal = 2.dp),
                    ) {
                        Icon(
                            painter = painterResource(ActivityRes.icon(activity)),
                            contentDescription = null,
                            tint = Color(activity.colorArgb),
                            modifier = Modifier.size(34.dp),
                        )
                        Text(
                            stringResource(activity.labelRes()),
                            // Hyphenation on, so that Android breaks after the hyphen of « Pousse-pousse ».
                            style = MaterialTheme.typography.labelMedium.copy(hyphens = Hyphens.Auto),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(GRID_COLUMNS - row.size) { Column(Modifier.weight(1f)) {} }
            }
        }
    }
}

/** White by day, as the dialogs of the « Crête » previews; the theme's dialog colour by night. */
@Composable
private fun dialogColor() =
    if (MaterialTheme.colorScheme.background.luminance() < NIGHT_LUMINANCE) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.toolMap.panel
    }

/** The four precisions, from the densest, each with its name and how often a point is kept. */
private val PROFILES =
    listOf(
        Triple(RecorderProfile.PRECISE, R.string.record_profile_precise_name, R.string.record_profile_precise_detail),
        Triple(RecorderProfile.WALK, R.string.record_profile_walk_name, R.string.record_profile_walk_detail),
        Triple(RecorderProfile.BIKE, R.string.record_profile_bike_name, R.string.record_profile_bike_detail),
        Triple(RecorderProfile.SAVER, R.string.record_profile_saver_name, R.string.record_profile_saver_detail),
    )

/** Material « apps »: the grid of « Toutes… ». */
private val AppsIcon: ImageVector by lazy {
    ImageVector
        .Builder("Apps", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData =
                addPathNodes(
                    "M4 8h4V4H4v4zm6 12h4v-4h-4v4zm-6 0h4v-4H4v4zm0-6h4v-4H4v4zm6 0h4v-4h-4v4zm6-10v4h4V4h-4z " +
                        "M10 8h4V4h-4v4zm6 6h4v-4h-4v4zm0 6h4v-4h-4v4z",
                ),
            fill = SolidColor(Color.Black),
        ).build()
}

private const val CHIPS = 4
private const val GRID_COLUMNS = 4
private const val NIGHT_LUMINANCE = 0.5f
