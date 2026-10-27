package fr.toolmap.multizoom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.toolmap.core.source.DraftIssue
import fr.toolmap.core.source.MultiZoomDraft
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomBand
import fr.toolmap.core.tiles.ScalingPolicy
import fr.toolmap.data.sources.SourceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.io.IOException
import javax.inject.Inject
import kotlin.math.abs

/** A multi-zoom map being created or edited. */
data class MultiZoomEditor(
    /** Id of the map being modified, `null` for a new one (or a copy of a built-in one). */
    val editingId: String?,
    val draft: MultiZoomDraft,
    val issues: List<DraftIssue> = emptyList(),
    /** Why the last save failed, if it did. */
    val error: String? = null,
) {
    val canSave: Boolean get() = issues.none { it.kind.blocking }
}

/** What the "Multi-zoom maps" screen shows. */
data class MultiZoomUi(
    /** The multi-zoom maps of the catalogue (built-in ones first). */
    val maps: List<TileSource> = emptyList(),
    /** The maps a band can show. */
    val choices: List<TileSource> = emptyList(),
    /** `null` while the list is shown. */
    val editor: MultiZoomEditor? = null,
)

/**
 * The editor of multi-zoom maps (ADR-031): the AlpineQuest `.aqx` whose levels point to different maps
 * (`USR.PERSONNAL_MULTIZOOM.AQX`), built band by band in the app. A saved map is an ordinary imported source whose
 * levels reference other maps (`toolmap-map:`), so it is shown, downloaded and exported like any other.
 */
@HiltViewModel
class MultiZoomViewModel
    @Inject
    constructor(
        private val repository: SourceRepository,
    ) : ViewModel() {
        private val editor = MutableStateFlow<MultiZoomEditor?>(null)

        val ui: StateFlow<MultiZoomUi> =
            combine(repository.sources, editor) { sources, edited ->
                MultiZoomUi(
                    maps = sources.filter { it.isMultiZoom },
                    choices = sources.filter { it.isSupported },
                    editor = edited,
                )
            }.stateIn(viewModelScope, SharingStarted.Eagerly, MultiZoomUi())

        val editing: StateFlow<MultiZoomEditor?> = editor.asStateFlow()

        fun isImported(id: String): Boolean = repository.isImported(id)

        /** A new map: one band over the first zooms of the first base map, to be split from there. */
        fun startNew(name: String = "") {
            val base = repository.sources.value.firstOrNull { it.isSupported && !it.isLayer && !it.isMultiZoom }
            val bands = listOfNotNull(base?.let { ZoomBand(it.minZoom, it.maxZoom.coerceAtMost(DEFAULT_TOP), it.id) })
            open(null, MultiZoomDraft(name, bands))
        }

        /** Edits the multi-zoom map [id]; a built-in one is copied (it cannot be modified). */
        fun edit(id: String) {
            val source = repository.find(id) ?: return
            val draft = MultiZoomDraft.of(source) ?: return derive(id)
            if (repository.isImported(id)) open(id, draft) else open(null, draft.copy(name = copyName(source)))
        }

        /**
         * A new multi-zoom map starting from [id]: its levels become bands showing the catalogue maps that serve the
         * same tiles, and the zooms missing from its list become forced zooms (what AlpineQuest shows there).
         */
        fun derive(id: String) {
            val source = repository.find(id) ?: return
            val draft = MultiZoomDraft.derivedFrom(source, repository.sources.value, copyName(source))
            open(null, draft)
        }

        fun cancel() {
            editor.value = null
        }

        fun setName(name: String) = change { it.copy(name = name) }

        /** Adds a band just above the last one, showing the same map. */
        fun addBand() =
            change { draft ->
                val last = draft.bands.maxByOrNull { it.to }
                val band =
                    if (last == null) {
                        val first = repository.sources.value.firstOrNull { it.isSupported && !it.isMultiZoom }
                        ZoomBand(0, DEFAULT_TOP, first?.id ?: "")
                    } else {
                        val from = (last.to + 1).coerceAtMost(MAX_EDIT_ZOOM)
                        ZoomBand(from, (from + NEW_BAND_SPAN).coerceAtMost(MAX_EDIT_ZOOM), last.mapId)
                    }
                draft.copy(bands = draft.bands + band)
            }

        /**
         * « Combler » (P29.6): a band over the zooms [from] to [to] left without one, showing the map of the band
         * just below them (the one above when there is none below). Returns the index of the new band.
         */
        fun fillGap(
            from: Int,
            to: Int,
        ): Int? {
            val bands = editor.value?.draft?.bands ?: return null
            val neighbour =
                bands.filter { it.to < from }.maxByOrNull { it.to } ?: bands.minByOrNull { it.from } ?: return null
            change { draft -> draft.copy(bands = draft.bands + ZoomBand(from, to, neighbour.mapId)) }
            return bands.size
        }

        fun removeBand(index: Int) =
            change { draft -> draft.copy(bands = draft.bands.filterIndexed { i, _ -> i != index }) }

        fun updateBand(
            index: Int,
            band: ZoomBand,
        ) = change { draft -> draft.copy(bands = draft.bands.mapIndexed { i, b -> if (i == index) band else b }) }

        /** Sets the zooms of a band; a forced zoom follows when it would end up too far above it. */
        fun setRange(
            index: Int,
            from: Int,
            to: Int,
        ) = change { draft ->
            draft.copy(
                bands =
                    draft.bands.mapIndexed { i, b ->
                        if (i != index) return@mapIndexed b
                        val forced = b.forcedZoom?.coerceAtMost(from + FORCE_REACH)
                        b.copy(from = from, to = to, forcedZoom = forced)
                    },
            )
        }

        fun setMap(
            index: Int,
            mapId: String,
        ) = change { draft ->
            draft.copy(bands = draft.bands.mapIndexed { i, b -> if (i == index) b.copy(mapId = mapId) else b })
        }

        /** Turns the forced zoom of a band on (at the zoom of the map closest to the band) or off. */
        fun setForced(
            index: Int,
            on: Boolean,
        ) = change { draft ->
            draft.copy(
                bands =
                    draft.bands.mapIndexed { i, b ->
                        if (i != index) b else b.copy(forcedZoom = if (on) defaultForcedZoom(b) else null)
                    },
            )
        }

        fun setForcedZoom(
            index: Int,
            zoom: Int,
        ) = change { draft ->
            draft.copy(bands = draft.bands.mapIndexed { i, b -> if (i == index) b.copy(forcedZoom = zoom) else b })
        }

        /** Stores the map being edited; returns its id, or `null` when it cannot be saved yet. */
        fun save(): String? {
            val current = editor.value ?: return null
            if (!current.canSave) return null
            val id = current.editingId ?: MultiZoomDraft.newId(current.draft.name) { repository.find(it) != null }
            return try {
                repository.save(current.draft.toSource(id, repository::find))
                editor.value = null
                id
            } catch (e: IOException) {
                editor.update { it?.copy(error = e.message ?: e.javaClass.simpleName) }
                null
            } catch (e: IllegalStateException) {
                editor.update { it?.copy(error = e.message ?: e.javaClass.simpleName) }
                null
            } catch (e: IllegalArgumentException) {
                editor.update { it?.copy(error = e.message ?: e.javaClass.simpleName) }
                null
            }
        }

        /** Removes an imported multi-zoom map (its tiles belong to the maps it shows and stay). */
        fun delete(id: String): Boolean = repository.isImported(id) && repository.delete(id)

        /** Maps whose band references [id]: deleting it would leave them a band with no map. */
        fun usersOf(id: String): List<TileSource> =
            repository.sources.value.filter { map -> map.id != id && map.levels.any { it.mapRef?.sourceId == id } }

        private fun open(
            editingId: String?,
            draft: MultiZoomDraft,
        ) {
            editor.value = MultiZoomEditor(editingId, draft, draft.issues(editingId, repository::find))
        }

        private fun change(edit: (MultiZoomDraft) -> MultiZoomDraft) =
            editor.update { current ->
                current?.let {
                    val draft = edit(it.draft)
                    it.copy(draft = draft, issues = draft.issues(it.editingId, repository::find), error = null)
                }
            }

        /** The zoom of the map nearest to the band (its first zoom when it serves the band). */
        private fun defaultForcedZoom(band: ZoomBand): Int {
            val map = repository.find(band.mapId) ?: return band.from
            val served = (0..MAX_EDIT_ZOOM).filter { map.levelFor(it) != null }
            val best = served.minByOrNull { z -> distance(z, band) }
            return (best ?: band.from).coerceAtMost(band.from + FORCE_REACH)
        }

        /** 0 inside the band, else the number of zooms to its nearest end. */
        private fun distance(
            zoom: Int,
            band: ZoomBand,
        ): Int = if (zoom in band.zooms) 0 else minOf(abs(zoom - band.from), abs(zoom - band.to))

        private fun copyName(source: TileSource): String =
            if (source.isMultiZoom) "${source.name} (copie)" else "${source.name} (multi-zoom)"

        companion object {
            private const val DEFAULT_TOP = 18
            private const val NEW_BAND_SPAN = 2

            /** Highest zoom offered by the editor (beyond, MapLibre shows nothing useful). */
            const val MAX_EDIT_ZOOM = 22

            /** A forced zoom may be at most this many levels above the first zoom of its band. */
            const val FORCE_REACH = ScalingPolicy.MAX_LEVELS
        }
    }
