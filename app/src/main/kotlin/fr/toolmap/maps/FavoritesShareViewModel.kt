package fr.toolmap.maps

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.toolmap.BuildConfig
import fr.toolmap.R
import fr.toolmap.core.layers.FavoritesExport
import fr.toolmap.core.layers.FavoritesFileCodec
import fr.toolmap.core.layers.Workspace
import fr.toolmap.core.problem.Problems
import fr.toolmap.core.source.TileSource
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.workspaces.WorkspaceStore
import fr.toolmap.map.UiMessage
import fr.toolmap.tracks.TrackSharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** What « Partager des favoris » shows (P25.2, variant C of `docs/previews/p25-partage-sauvegarde.html`). */
data class ShareFavoritesUi(
    /** Every favorite of the panel, in its order. */
    val favorites: List<Workspace> = emptyList(),
    val checked: Set<String> = emptySet(),
    /** « Garder la clé »: the keys of the added maps go in the file. */
    val keepKeys: Boolean = false,
    /** How many added maps each favorite shows (by id), and the favorites whose added maps carry a key. */
    val addedCount: Map<String, Int> = emptyMap(),
    val withKey: Set<String> = emptySet(),
    /** The file the ticks make, what the card at the top says; `null` while nothing is ticked. */
    val file: SharedFile? = null,
)

/** The `.toolmap` file of the ticked favorites, as « Partager… » would send it. */
data class SharedFile(
    /** « Sortie Ski.toolmap », « Sortie Ski et 2 autres.toolmap »: the first favorite ticked in the list. */
    val name: String,
    val favorites: List<Workspace>,
    /** The maps the user added that the favorites show: they go in the file. */
    val addedMaps: List<TileSource>,
    /** Of [addedMaps], the ones that carry a key: the choice of the key is shown for them. */
    val keyed: List<TileSource>,
    val text: String,
) {
    val bytes: Long get() = text.toByteArray(Charsets.UTF_8).size.toLong()
}

/**
 * « Partager des favoris » (P25.2, ADR-105): the favorites to tick, the file they make, the key kept or removed, then
 * the share sheet of Android with the `.toolmap` file written in the cache.
 */
@HiltViewModel
class FavoritesShareViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: SourceRepository,
        workspaces: WorkspaceStore,
    ) : ViewModel() {
        private val choice = MutableStateFlow(ShareFavoritesUi())

        val ui: StateFlow<ShareFavoritesUi> =
            combine(choice, workspaces.favorites, repository.sources) { state, favorites, _ ->
                val checked = state.checked.filterTo(LinkedHashSet()) { id -> favorites.any { it.id == id } }
                val added = favorites.associate { it.id to addedMaps(listOf(it)) }
                state.copy(
                    favorites = favorites,
                    checked = checked,
                    addedCount = added.mapValues { it.value.size },
                    withKey = added.filterValues { FavoritesExport.keyed(it).isNotEmpty() }.keys,
                    file = fileOf(favorites, checked, state.keepKeys),
                )
            }.stateIn(viewModelScope, SharingStarted.Eagerly, ShareFavoritesUi())

        private val mutableMessages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)
        val messages: SharedFlow<UiMessage> = mutableMessages.asSharedFlow()

        /** Opens the screen with the favorite on the map ticked, if one is; the key is removed by default. */
        fun open(applied: String?) {
            choice.value = ShareFavoritesUi(checked = setOfNotNull(applied))
        }

        fun toggle(id: String) =
            choice.update { it.copy(checked = if (id in it.checked) it.checked - id else it.checked + id) }

        fun setKeepKeys(keep: Boolean) = choice.update { it.copy(keepKeys = keep) }

        /** Writes the file in the cache and opens the share sheet of Android, headed by [title]. */
        fun share(title: String) {
            val file = ui.value.file ?: return
            viewModelScope.launch {
                try {
                    val written =
                        withContext(Dispatchers.IO) {
                            val directory = TrackSharing.directory(context).also { it.mkdirs() }
                            File(directory, file.name).also { it.writeText(file.text, Charsets.UTF_8) }
                        }
                    TrackSharing.share(context, written, title, MIME)
                } catch (e: IOException) {
                    Problems.expected("favorites.share.write", e)
                    mutableMessages.tryEmit(UiMessage(R.string.fav_share_failed, listOf(e.message.orEmpty())))
                }
            }
        }

        private fun fileOf(
            favorites: List<Workspace>,
            checked: Set<String>,
            keepKeys: Boolean,
        ): SharedFile? {
            val chosen = favorites.filter { it.id in checked }
            if (chosen.isEmpty()) return null
            val added = addedMaps(chosen)
            val others = chosen.size - 1
            val base =
                if (others == 0) {
                    chosen.first().name
                } else {
                    context.resources.getQuantityString(
                        R.plurals.fav_share_file_name,
                        others,
                        chosen.first().name,
                        others,
                    )
                }
            val file =
                FavoritesExport.build(
                    chosen,
                    repository::find,
                    repository::isBuiltin,
                    keepKeys,
                    BuildConfig.VERSION_NAME,
                )
            return SharedFile(
                name = FavoritesExport.fileName(base),
                favorites = chosen,
                addedMaps = added,
                keyed = FavoritesExport.keyed(added),
                text = FavoritesFileCodec.encode(file),
            )
        }

        private fun addedMaps(favorites: List<Workspace>) =
            FavoritesExport.addedMaps(favorites, repository::find, repository::isBuiltin)

        companion object {
            /** The type given to the share sheet: messaging apps take it, and ToolMap opens it back (manifest). */
            const val MIME = "application/octet-stream"
        }
    }
