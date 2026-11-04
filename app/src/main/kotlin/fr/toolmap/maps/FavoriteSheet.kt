package fr.toolmap.maps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.layers.Favorites
import fr.toolmap.core.layers.Workspace
import fr.toolmap.draw.RouteIcons
import fr.toolmap.share.SheetRow
import fr.toolmap.sketch.SketchIcons
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.TextButton

/** What the sheet of a favourite asks the panel to do. */
internal class FavoriteSheetActions(
    val onApply: (Workspace) -> Unit,
    val onRename: (Workspace) -> Unit,
    val onShare: (Workspace) -> Unit,
    val onRestoreOriginal: (Workspace) -> Unit,
    val onDelete: (Workspace) -> Unit,
)

/**
 * The sheet of a favourite, opened by a long press on its chip (P25.5, variant C of
 * `docs/previews/p25-renommer-groupe.html`, validated 2026-11-04): the favourite at the head (picture, name, maps), then
 * Appliquer, Renommer…, Partager…, Rétablir l'original for a provided one the user changed, Supprimer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FavoriteSheet(
    favorite: Workspace,
    modified: Boolean,
    cover: String?,
    coverName: String,
    thumbnails: MapThumbnails,
    actions: FavoriteSheetActions,
    onClose: () -> Unit,
) {
    val state =
        listOfNotNull(
            stringResource(R.string.maps_favorite_builtin).takeIf { favorite.isBuiltin },
            stringResource(R.string.maps_favorite_modified_state).takeIf { modified },
        ).joinToString(", ")
    val summary =
        listOf(pluralStringResource(R.plurals.fav_share_maps, favorite.layers.size, favorite.layers.size), state)
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
    val act = { action: (Workspace) -> Unit ->
        {
            onClose()
            action(favorite)
        }
    }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MapThumbnail(cover.orEmpty(), coverName, thumbnails, size = 40.dp)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(favorite.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SheetRow(MapsIcons.Layers, stringResource(R.string.maps_favorite_apply), act(actions.onApply))
            SheetRow(SketchIcons.Pen, stringResource(R.string.maps_favorite_rename), act(actions.onRename))
            SheetRow(
                Icons.Filled.Share,
                stringResource(R.string.maps_favorite_share),
                act(actions.onShare),
                stringResource(R.string.maps_favorite_share_sub),
            )
            if (Favorites.isOverridden(favorite)) {
                SheetRow(
                    MapsIcons.RestartAlt,
                    stringResource(R.string.maps_favorite_restore_original),
                    act(actions.onRestoreOriginal),
                    stringResource(R.string.maps_favorite_restore_original_sub),
                )
            }
            SheetRow(
                RouteIcons.Delete,
                stringResource(R.string.layers_remove),
                act(actions.onDelete),
                textColor = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * « Renommer le favori »: the name, all selected so that typing replaces it; « Renommer » stays off while the name is
 * empty, unchanged or the one of another favourite ([Favorites.nameTaken]).
 */
@Composable
internal fun RenameFavoriteDialog(
    favorite: Workspace,
    favorites: List<Workspace>,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // A state-based field, as in the windows of P23: the value-based one is laid out again and again under Robolectric.
    val text = rememberTextFieldState(favorite.name, TextRange(0, favorite.name.length))
    val name = text.text.toString().trim()
    val taken = Favorites.nameTaken(favorites, favorite.id, name)
    val ok = name.isNotEmpty() && name != favorite.name && !taken
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.maps_favorite_rename_title)) },
        text = {
            OutlinedTextField(
                state = text,
                label = { Text(stringResource(R.string.maps_favorite_name)) },
                lineLimits = TextFieldLineLimits.SingleLine,
                isError = taken,
                supportingText =
                    if (taken) {
                        { Text(stringResource(R.string.maps_favorite_name_taken)) }
                    } else {
                        null
                    },
                onKeyboardAction = { default -> if (ok) onRename(name) else default() },
                modifier = Modifier.fillMaxWidth().focusRequester(requester),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = ok) {
                Text(stringResource(R.string.maps_favorite_rename_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
