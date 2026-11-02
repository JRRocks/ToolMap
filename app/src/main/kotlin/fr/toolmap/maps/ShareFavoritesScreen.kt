package fr.toolmap.maps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.layers.FavoritesExport
import fr.toolmap.core.layers.Workspace
import fr.toolmap.core.source.TileSource
import fr.toolmap.download.formatBytes
import fr.toolmap.settings.SettingsIcons
import fr.toolmap.ui.GroupBlock
import fr.toolmap.ui.GroupCaption
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.toolMap

/**
 * « Partager des favoris » (P25.2, variant C « Poids » of `docs/previews/p25-partage-sauvegarde.html`): a full screen
 * opened by the « Partager » icon of the band of « Cartes et calques ». The card at the top says the file the ticks
 * make (its name, what it holds, its weight); the favorites to tick; the key of the added maps, removed or kept; and
 * « Partager… » in the bar at the foot, which hands the file to the share sheet of Android.
 */
@Composable
fun ShareFavoritesScreen(
    vm: FavoritesShareViewModel,
    thumbnails: MapThumbnails,
    sources: List<TileSource>,
    onClose: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.messages.collect { snackbar.showSnackbar(resources.getString(it.resId, *it.args.toTypedArray())) }
    }
    val title = stringResource(R.string.fav_share_title)
    ShareFavoritesPage(
        ui = ui,
        byId = remember(sources) { sources.associateBy { it.id } },
        thumbnails = thumbnails,
        snackbar = snackbar,
        onClose = onClose,
        onToggle = vm::toggle,
        onKeepKeys = vm::setKeepKeys,
        onShare = { vm.share(title) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareFavoritesPage(
    ui: ShareFavoritesUi,
    byId: Map<String, TileSource>,
    thumbnails: MapThumbnails,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
    onToggle: (String) -> Unit,
    onKeepKeys: (Boolean) -> Unit,
    onShare: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            ToolMapTopBar(
                title = stringResource(R.string.fav_share_title),
                subtitle =
                    pluralStringResource(
                        R.plurals.fav_share_checked,
                        ui.checked.size,
                        ui.checked.size,
                        ui.favorites.size,
                    ),
                onBack = onClose,
                navigationIcon = Icons.Filled.Close,
                navigationDescription = stringResource(R.string.action_close),
            )
        },
        bottomBar = { ShareFoot(enabled = ui.file != null, onShare = onShare) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            FileCard(ui.file)
            GroupCaption(stringResource(R.string.fav_share_caption))
            GroupBlock(
                modifier = Modifier.padding(horizontal = 16.dp),
                dividerStart = 72.dp,
                rows =
                    ui.favorites.map { favorite ->
                        {
                            FavoriteRow(
                                favorite = favorite,
                                checked = favorite.id in ui.checked,
                                added = ui.addedCount[favorite.id] ?: 0,
                                withKey = favorite.id in ui.withKey,
                                byId = byId,
                                thumbnails = thumbnails,
                                onToggle = { onToggle(favorite.id) },
                            )
                        }
                    },
            )
            val keyed = ui.file?.keyed.orEmpty()
            if (keyed.isNotEmpty()) KeyChoice(keyed, ui.keepKeys, onKeepKeys)
        }
    }
}

/** The file the ticks make: its name, « 3 favoris · 2 cartes ajoutées · 9 Ko ». */
@Composable
private fun FileCard(file: SharedFile?) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
            .fillMaxWidth()
            .background(colors.primaryContainer, MaterialTheme.shapes.medium)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            MapsIcons.Description,
            contentDescription = null,
            tint = colors.onPrimaryContainer,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file?.name ?: stringResource(R.string.fav_share_none),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (file != null) {
                val parts =
                    listOfNotNull(
                        pluralStringResource(R.plurals.fav_share_favorites, file.favorites.size, file.favorites.size),
                        file.addedMaps.size.takeIf { it > 0 }?.let {
                            pluralStringResource(R.plurals.fav_share_added_maps, it, it)
                        },
                        formatBytes(file.bytes),
                    )
                Text(
                    parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onPrimaryContainer.copy(alpha = 0.85f),
                )
            }
        }
    }
}

/** A favorite to tick: its picture, its name, « 5 cartes · 1 ajoutée · clé », its box. */
@Composable
private fun FavoriteRow(
    favorite: Workspace,
    checked: Boolean,
    added: Int,
    withKey: Boolean,
    byId: Map<String, TileSource>,
    thumbnails: MapThumbnails,
    onToggle: () -> Unit,
) {
    val cover = coverOf(favorite, byId)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MapThumbnail(
            cover.orEmpty(),
            byId[cover]?.name ?: favorite.name,
            thumbnails,
            size = 40.dp,
            shape = RoundedCornerShape(10.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                favorite.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    pluralStringResource(R.plurals.fav_share_maps, favorite.layers.size, favorite.layers.size),
                    added.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.fav_share_added_short, it, it) },
                    if (withKey) stringResource(R.string.fav_share_key_short) else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
    }
}

/** « Clé d'accès · 1 carte en utilise une (SnowBase Map) », « Retirer la clé | Garder la clé » and what it means. */
@Composable
private fun KeyChoice(
    keyed: List<TileSource>,
    keep: Boolean,
    onKeep: (Boolean) -> Unit,
) {
    GroupCaption(
        pluralStringResource(
            R.plurals.fav_share_key_caption,
            keyed.size,
            keyed.size,
            keyed.joinToString(", ") {
                it.name
            },
        ),
    )
    val options = listOf(false to R.string.fav_share_key_remove, true to R.string.fav_share_key_keep)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = keep == value,
                onClick = { onKeep(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(stringResource(label)) },
            )
        }
    }
    if (keep) {
        val colors = MaterialTheme.colorScheme
        Row(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                .fillMaxWidth()
                .background(colors.tertiaryContainer, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                SettingsIcons.Warning,
                contentDescription = null,
                tint = colors.onTertiaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Text(
                stringResource(R.string.fav_share_key_kept),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onTertiaryContainer,
            )
        }
    } else {
        Text(
            stringResource(R.string.fav_share_key_removed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
        )
    }
}

/** The bar at the foot: « Envoyé par le partage d'Android » and « Partager… », the only filled button. */
@Composable
private fun ShareFoot(
    enabled: Boolean,
    onShare: () -> Unit,
) {
    Column(Modifier.background(MaterialTheme.toolMap.panel).navigationBarsPadding()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.fav_share_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onShare, enabled = enabled) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.fav_share_button), fontWeight = FontWeight.Medium)
            }
        }
    }
}
