package fr.toolmap.maps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.toolmap.R
import fr.toolmap.core.aqx.AqxParseException
import fr.toolmap.core.aqx.AqxParser
import fr.toolmap.core.layers.FavoritesFileCodec
import fr.toolmap.core.layers.FavoritesFileException
import fr.toolmap.core.layers.FavoritesFileFailure
import fr.toolmap.core.layers.FavoritesImport
import fr.toolmap.core.layers.FavoritesImportPlan
import fr.toolmap.core.problem.Problems
import fr.toolmap.core.source.TileSource
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.workspaces.WorkspaceStore
import fr.toolmap.map.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A `.toolmap` file being opened: what the sheet « Ajouter N favoris ? » shows (P25.3). */
data class IncomingFavorites(
    /** The name of the file, under the title. */
    val fileName: String,
    val plan: FavoritesImportPlan,
    /** The favorites of [plan] ticked, by index: all of them at first. */
    val checked: Set<Int>,
)

/**
 * Opening a `.toolmap` file (P25.3, ADR-105): the sheet « Ajouter N favoris ? » says what each favorite brings (name
 * taken, map added, key to type); the ticked ones are added to the chips with the maps they need, nothing is applied.
 * Then the key of each added map that came without one is asked.
 */
@HiltViewModel
class FavoritesImportViewModel
    @Inject
    constructor(
        private val repository: SourceRepository,
        private val workspaces: WorkspaceStore,
        private val settingsStore: SettingsStore,
    ) : ViewModel() {
        private val mutableIncoming = MutableStateFlow<IncomingFavorites?>(null)

        /** Non-null while the sheet is open. */
        val incoming: StateFlow<IncomingFavorites?> = mutableIncoming.asStateFlow()

        private val keyQueue = MutableStateFlow<List<TileSource>>(emptyList())

        /** The added maps whose key is still to be typed: the first one is asked. */
        val keysToType: StateFlow<List<TileSource>> = keyQueue.asStateFlow()

        private val mutableMessages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)

        /** For the snackbar of the map. */
        val messages: SharedFlow<UiMessage> = mutableMessages.asSharedFlow()

        /** Reads the text of a `.toolmap` file (from any thread) and opens the sheet, or says why it cannot. */
        fun receive(
            text: String,
            fileName: String?,
        ) {
            val file =
                try {
                    FavoritesFileCodec.decode(text)
                } catch (e: FavoritesFileException) {
                    Problems.expected("favorites.import.decode", e, "failure" to e.failure)
                    mutableMessages.tryEmit(UiMessage(failureOf(e.failure)))
                    return
                }
            val sources =
                try {
                    file.aqx?.let { AqxParser.parse(it).file.sources }.orEmpty()
                } catch (e: AqxParseException) {
                    Problems.expected("favorites.import.aqx", e)
                    mutableMessages.tryEmit(UiMessage(R.string.fav_import_unreadable))
                    return
                }
            val plan =
                FavoritesImport.plan(
                    file,
                    sources,
                    repository.sources.value,
                    workspaces.favorites.value.map { it.name },
                    settingsStore.settings.value.maps.hiddenMaps,
                )
            mutableIncoming.value = IncomingFavorites(fileName.orEmpty(), plan, plan.items.indices.toSet())
        }

        fun toggle(index: Int) =
            mutableIncoming.update { incoming ->
                incoming?.copy(
                    checked = if (index in incoming.checked) incoming.checked - index else incoming.checked + index,
                )
            }

        fun dismiss() {
            mutableIncoming.value = null
        }

        /** Stores the ticked favorites, the maps they need and puts back the maps they show that were hidden. */
        fun add() {
            val incoming = mutableIncoming.value ?: return
            mutableIncoming.value = null
            val chosen = incoming.checked
            if (chosen.isEmpty()) return
            val plan = incoming.plan
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    plan.mapsFor(chosen).filter { repository.find(it.id) == null }.forEach(repository::save)
                    val unhidden = chosen.flatMapTo(HashSet()) { plan.items[it].unhidden }
                    if (unhidden.isNotEmpty()) {
                        settingsStore.update {
                            it.copy(maps = it.maps.copy(hiddenMaps = it.maps.hiddenMaps - unhidden))
                        }
                    }
                    plan.favoritesFor(chosen).forEach { workspaces.save(it.copy(id = WorkspaceStore.newId())) }
                }
                keyQueue.value = chosen.sorted().flatMap { plan.items[it].needKey }.distinctBy { it.id }
                mutableMessages.tryEmit(UiMessage(R.string.fav_import_done, listOf(chosen.size)))
            }
        }

        /** The key typed for the first map of [keysToType]. */
        fun setKey(values: Map<String, String>) {
            val map = keyQueue.value.firstOrNull() ?: return
            keyQueue.update { it.drop(1) }
            viewModelScope.launch(Dispatchers.IO) {
                repository.find(map.id)?.let { repository.save(it.copy(params = it.params + values)) }
            }
        }

        /** « Annuler » on the key of the first map: it stays without, its tiles do not load until one is set. */
        fun skipKey() = keyQueue.update { it.drop(1) }

        private fun failureOf(failure: FavoritesFileFailure) =
            when (failure) {
                FavoritesFileFailure.UNSUPPORTED_VERSION -> R.string.fav_import_newer
                FavoritesFileFailure.EMPTY -> R.string.fav_import_empty
                FavoritesFileFailure.NOT_FAVORITES -> R.string.fav_import_unreadable
            }
    }
