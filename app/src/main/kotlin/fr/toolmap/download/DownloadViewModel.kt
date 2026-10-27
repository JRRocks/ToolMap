package fr.toolmap.download

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.toolmap.core.download.ZoneJob
import fr.toolmap.core.download.ZoneRequest
import fr.toolmap.core.download.ZoneShape
import fr.toolmap.core.download.ZoomLimit
import fr.toolmap.core.source.TileSource
import fr.toolmap.data.download.ZoneJobStore
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.tiles.OfflineMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject

/** What the user is choosing on the "new download" form. */
data class ZoneDraft(
    val shape: ZoneShape,
    val sourceIds: Set<String>,
    val minZoom: Int,
    val maxZoom: Int,
    /** Download each map only for the zooms its layer is displayed at (the layer display ranges). */
    val respectLayerRanges: Boolean = false,
    val layerLimits: Map<String, ZoomLimit> = emptyMap(),
)

/** The zone and defaults handed over by the screen that opened the download form (map, track...). */
data class PendingZone(
    val shape: ZoneShape,
    val sourceIds: List<String>,
    val suggestedMinZoom: Int,
    val suggestedMaxZoom: Int,
    /** Display range of each layer on the map (only those that have one), in tile zoom. */
    val layerLimits: Map<String, ZoomLimit> = emptyMap(),
)

sealed interface Estimate {
    /** [tiles] in all, [stored] of them already on the device; [bytes] is the guessed size of the others. */
    data class Ready(
        val tiles: Long,
        val bytes: Long,
        val stored: Long = 0L,
    ) : Estimate

    /** The chosen zooms hold no tile for the chosen sources. */
    data object Empty : Estimate

    data object TooMany : Estimate

    data class Restricted(
        val sourceName: String,
        val tiles: Long,
        val limit: Long,
    ) : Estimate
}

@HiltViewModel
class DownloadViewModel
    @Inject
    constructor(
        private val estimator: ZoneEstimator,
        private val scheduler: DownloadScheduler,
        private val repository: SourceRepository,
        private val offlineMode: OfflineMode,
        jobStore: ZoneJobStore,
    ) : ViewModel() {
        val jobs: StateFlow<List<ZoneJob>> = jobStore.jobs
        val sources: StateFlow<List<TileSource>> = repository.sources
        val offline: StateFlow<Boolean> = offlineMode.enabled

        private val mutablePending = MutableStateFlow<PendingZone?>(null)
        val pending: StateFlow<PendingZone?> = mutablePending.asStateFlow()

        fun prepare(zone: PendingZone) {
            mutablePending.value = zone
        }

        fun clearPending() {
            mutablePending.value = null
        }

        /** Tile count and size of [draft]. */
        suspend fun estimate(draft: ZoneDraft): Estimate {
            if (draft.sourceIds.isEmpty()) return Estimate.Empty
            return when (val result = estimator.estimate(request(draft, "estimate"))) {
                is ZoneEstimate.Ready -> Estimate.Ready(result.tiles, result.bytes, result.stored)
                ZoneEstimate.Empty -> Estimate.Empty
                ZoneEstimate.TooMany -> Estimate.TooMany
                is ZoneEstimate.Restricted -> Estimate.Restricted(result.sourceName, result.tiles, result.limit)
            }
        }

        /** Queues the download; returns false when the app is in "local storage only" mode. */
        fun start(
            draft: ZoneDraft,
            name: String,
        ): Boolean {
            if (offlineMode.isOffline()) return false
            scheduler.start(request(draft, name.ifBlank { "Zone" }))
            return true
        }

        fun setOffline(value: Boolean) = offlineMode.set(value)

        fun pause(id: String) = scheduler.pause(id)

        fun resume(id: String) = scheduler.resume(id)

        fun remove(id: String) = scheduler.remove(id)

        private fun request(
            draft: ZoneDraft,
            name: String,
        ) = ZoneRequest(
            id = UUID.randomUUID().toString(),
            name = name,
            sourceIds = draft.sourceIds.toList(),
            minZoom = draft.minZoom,
            maxZoom = draft.maxZoom,
            shape = draft.shape,
            createdAtMillis = System.currentTimeMillis(),
            zoomLimits =
                if (draft.respectLayerRanges) {
                    draft.layerLimits.filterKeys {
                        it in draft.sourceIds
                    }
                } else {
                    emptyMap()
                },
        )
    }
