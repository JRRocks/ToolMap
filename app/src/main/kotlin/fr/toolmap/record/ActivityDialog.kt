package fr.toolmap.record

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.record.TrackActivity
import fr.toolmap.ui.theme.TextButton

/** The name of the activity in the grid of the dialog and in the name of the track. */
@StringRes
fun TrackActivity.labelRes(): Int = ActivityRes.label(this)

/**
 * « Quelle activité ? » before a recording starts (« Demander l'activité »): a grid of the activities of AlpineQuest,
 * each with its coloured icon; the answer is put in the name of the track.
 */
@Composable
fun ActivityDialog(
    onSelect: (TrackActivity) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_activity_title)) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(COLUMNS),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(TrackActivity.entries) { activity ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { onSelect(activity) }.padding(vertical = 4.dp),
                    ) {
                        Icon(
                            painter = painterResource(ActivityRes.icon(activity)),
                            contentDescription = null,
                            tint = Color(activity.colorArgb),
                            modifier = Modifier.size(34.dp),
                        )
                        Text(
                            stringResource(activity.labelRes()),
                            // Hyphenation on, so that Android breaks after the hyphen of « Pousse-pousse » (it never
                            // does otherwise) instead of cutting the word anywhere.
                            style = MaterialTheme.typography.labelSmall.copy(hyphens = Hyphens.Auto),
                            textAlign = TextAlign.Center,
                            // Two lines at most (P14.11): « Ski de randonnée » and « Pousse-pousse » are no longer cut.
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private const val COLUMNS = 4
