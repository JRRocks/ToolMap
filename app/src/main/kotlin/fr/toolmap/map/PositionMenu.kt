package fr.toolmap.map

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.track.TrackFormat
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.Switch

/** How the map follows the position: the four choices of « La carte suit » (R4). */
enum class FollowChoice {
    /** The position is shown, the map stays where it is put. */
    FREE,

    /** The map follows the position, north up. */
    FOLLOW,

    /** The map follows the position and turns with the compass, flat. */
    COMPASS,

    /** The compass, and the map tilted into perspective (the explorer mode of AlpineQuest). */
    EXPLORER,
}

/** What the positioning panel shows, read from the screen each time it is composed. */
data class PositionMenuState(
    /** The position is shown. */
    val realTime: Boolean,
    /** How the map follows the position; `null` while the position is off. */
    val follow: FollowChoice?,
    /** A recording is running or paused. */
    val recording: Boolean,
    val recordingPaused: Boolean = false,
    val recordedMeters: Double = 0.0,
    val recordedMillis: Long = 0,
    /** The position is shared with a group. */
    val sharing: Boolean = false,
    /** The members of the group, this phone included; 0 out of a group. */
    val groupMembers: Int = 0,
    /** The phone has a barometer: the row is shown only then. */
    val barometerAvailable: Boolean = false,
    /** The barometer gives the altitude (any mode but GPS only). */
    val barometer: Boolean = false,
    /** Horizontal accuracy of the last position, in meters, when known. */
    val accuracyMeters: Double? = null,
    /** Altitude of the last position, when known, and whether the barometer gave it. */
    val altitudeMeters: Double? = null,
    val altitudeFromBarometer: Boolean = false,
)

/*
 * The positioning panel, opened from the position button of the bar, as the « Positioning » menu of AlpineQuest (P11.9,
 * ADR-037). Since P14.10: a tinted block for the real-time position, which says how good it is; under it, « La carte
 * suit » with its four choices (free, following, compass, explorer), which were only reachable by a long press before
 * (R4: the long press stays, as a shortcut); then the recorder, the barometer on a phone that has one (P16.2, ADR-053)
 * and the sharing, each saying where it stands. The other settings are in « Réglages » only (P11.11, ADR-039). The rows
 * do not close the panel, but « Partage de position », which opens its screen; the « ? » opens the help.
 */
@Composable
fun PositionMenuPanel(
    state: PositionMenuState,
    onFollow: (FollowChoice) -> Unit,
    onRealTime: (Boolean) -> Unit,
    onRecorder: (Boolean) -> Unit,
    onBarometer: (Boolean) -> Unit = {},
    onShare: () -> Unit,
    onHelp: () -> Unit,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val fmt = LocalFormats.current
    BarPanel(
        maxHeight = maxHeight,
        modifier = modifier,
        header = { PanelHeader(stringResource(R.string.position_title), onHelp = onHelp) },
    ) {
        val quality =
            listOfNotNull(
                state.accuracyMeters?.let { stringResource(R.string.position_accuracy, fmt.distance(it)) },
                state.altitudeMeters?.let { fmt.elevation(it) },
                if (state.altitudeMeters != null) {
                    val baro = state.altitudeFromBarometer
                    val source = if (baro) R.string.position_source_baro else R.string.position_source_gps
                    stringResource(source)
                } else {
                    null
                },
            ).joinToString(" · ")
        StateCard(
            title = stringResource(R.string.position_real_time),
            subtitle =
                when {
                    !state.realTime -> stringResource(R.string.position_off)
                    quality.isEmpty() -> stringResource(R.string.position_waiting)
                    else -> quality
                },
            leading = { StateIcon(MenuIcons.MyLocation, on = state.realTime) },
            trailing = { Switch(checked = state.realTime, onCheckedChange = null, modifier = Modifier.padding(8.dp)) },
            onClick = { onRealTime(!state.realTime) },
        )
        PanelCaption(stringResource(R.string.position_follow))
        FollowChoices(selected = state.follow, onSelect = onFollow)
        MenuRow(
            stringResource(R.string.position_recorder),
            icon = MenuIcons.RecordTrack,
            iconTint = if (state.recording) MaterialTheme.colorScheme.error else null,
            subtitle =
                when {
                    !state.recording -> {
                        stringResource(R.string.position_recorder_off)
                    }

                    else -> {
                        stringResource(
                            if (state.recordingPaused) {
                                R.string.position_recorder_paused
                            } else {
                                R.string.position_recorder_on
                            },
                            fmt.distance(state.recordedMeters),
                            TrackFormat.duration(state.recordedMillis),
                        )
                    }
                },
            trailing = { PanelSwitch(checked = state.recording) },
        ) {
            onRecorder(!state.recording)
        }
        // The same choice as « Altitude » in the settings, which stays the one place that picks the barometer alone
        // (ADR-053): ticked from « GPS seulement » it gives the automatic mode, cleared it gives GPS.
        if (state.barometerAvailable) {
            MenuRow(
                stringResource(R.string.position_barometer),
                icon = MenuIcons.Gauge,
                subtitle = stringResource(R.string.position_barometer_sub),
                trailing = { PanelSwitch(checked = state.barometer) },
            ) {
                onBarometer(!state.barometer)
            }
        }
        // Opens the sharing screen, where a group is created or joined: a chevron, not a switch that would not switch.
        MenuRow(
            stringResource(R.string.share_title),
            icon = Icons.Filled.Share,
            subtitle =
                when {
                    state.groupMembers == 0 -> {
                        stringResource(R.string.position_share_none)
                    }

                    else -> {
                        val members =
                            pluralStringResource(
                                R.plurals.position_share_members,
                                state.groupMembers,
                                state.groupMembers,
                            )
                        if (state.sharing) stringResource(R.string.position_share_on, members) else members
                    }
                },
            trailing = { PanelChevron() },
        ) {
            onShare()
        }
    }
}

/** « La carte suit »: four segments, none chosen while the position is off; choosing one turns it on. */
@Composable
private fun FollowChoices(
    selected: FollowChoice?,
    onSelect: (FollowChoice) -> Unit,
) {
    val labels =
        listOf(
            FollowChoice.FREE to R.string.position_follow_free,
            FollowChoice.FOLLOW to R.string.position_follow_follow,
            FollowChoice.COMPASS to R.string.position_follow_compass,
            FollowChoice.EXPLORER to R.string.position_follow_explorer,
        )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = PANEL_INSET, vertical = 4.dp)) {
        labels.forEachIndexed { index, (choice, label) ->
            SegmentedButton(
                selected = choice == selected,
                onClick = { onSelect(choice) },
                shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                // No tick: it would take the room of the name, and the colour already says which one is chosen.
                icon = {},
                label = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}
