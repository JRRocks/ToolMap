package fr.toolmap.download

import fr.toolmap.core.download.ZoneRequest
import fr.toolmap.core.tiles.TilePlanner
import fr.toolmap.core.tiles.TooManyTilesException
import fr.toolmap.data.download.ZoneDownloader
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.tiles.MbTilesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tiles one map adds to a download, with the tile count of each zoom that adds tiles; [stored] of them are already
 * on the device and skipped, so [bytes] is the guessed size of the [missing] ones only.
 */
data class SourceEstimate(
    val tiles: Long,
    val bytes: Long,
    val perZoom: Map<Int, Long>,
    val stored: Long = 0L,
) {
    val missing: Long get() = (tiles - stored).coerceAtLeast(0L)
}

/** What a [ZoneRequest] would cost, map by map. */
sealed interface ZoneEstimate {
    data class Ready(
        val perSource: Map<String, SourceEstimate>,
    ) : ZoneEstimate {
        val tiles: Long get() = perSource.values.sumOf { it.tiles }
        val bytes: Long get() = perSource.values.sumOf { it.bytes }
        val stored: Long get() = perSource.values.sumOf { it.stored }
        val missing: Long get() = perSource.values.sumOf { it.missing }
    }

    /** The chosen zooms hold no tile for the chosen maps. */
    data object Empty : ZoneEstimate

    data object TooMany : ZoneEstimate

    /** A community server that limits bulk downloads would be asked for too much. */
    data class Restricted(
        val sourceName: String,
        val tiles: Long,
        val limit: Long,
    ) : ZoneEstimate
}

/**
 * Counts the tiles of a download and guesses its size: with the average tile size observed in what is already
 * stored for the map, else a typical figure. Shared by the download form and the "Select and save an area" panel.
 */
@Singleton
class ZoneEstimator
    @Inject
    constructor(
        private val downloader: ZoneDownloader,
        private val repository: SourceRepository,
        private val store: MbTilesStore,
    ) {
        private val averageBytes = HashMap<String, Long>()

        /** Heavy for big zones, so it runs on a background dispatcher. */
        suspend fun estimate(request: ZoneRequest): ZoneEstimate =
            withContext(Dispatchers.Default) {
                val plan =
                    try {
                        downloader.plan(request, withStored = true)
                    } catch (e: TooManyTilesException) {
                        return@withContext ZoneEstimate.TooMany
                    }
                // The limits apply to the servers really asked (the maps a multi-zoom map shows).
                downloader.restrictedIn(plan)?.let {
                    return@withContext ZoneEstimate.Restricted(it.source.name, it.tiles, it.limit)
                }
                val perSource = LinkedHashMap<String, SourceEstimate>()
                for ((id, counts) in plan.perSource) {
                    if (repository.find(id) == null) continue
                    val tiles = counts.values.sum()
                    // A zoom drawn from its neighbours (ADR-031) adds no tile of its own: not listed.
                    val perZoom = counts.filterValues { it > 0 }
                    val stored = (plan.stored[id] ?: 0L).coerceAtMost(tiles)
                    val bytes = TilePlanner.estimateBytes(tiles - stored, averageTileBytes(id))
                    perSource[id] = SourceEstimate(tiles, bytes, perZoom, stored)
                }
                if (plan.total == 0L) ZoneEstimate.Empty else ZoneEstimate.Ready(perSource)
            }

        /** Average size of the tiles already stored for the map (in the files of the tile sets it shows). */
        private fun averageTileBytes(sourceId: String): Long =
            synchronized(averageBytes) {
                averageBytes.getOrPut(sourceId) {
                    val files = store.storageIdsOf(sourceId).mapNotNull(store::peek)
                    val count = files.sumOf { it.tileCount() }
                    if (count >=
                        MIN_SAMPLE
                    ) {
                        files.sumOf { it.payloadBytes() } / count
                    } else {
                        TilePlanner.DEFAULT_TILE_BYTES
                    }
                }
            }

        private companion object {
            const val MIN_SAMPLE = 20L
        }
    }
