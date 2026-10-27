package fr.toolmap.data.tiles

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps whose stored tiles are fetched again during this session (AlpineQuest's "Force update local data (this
 * session only)"). Only the first request of each tile counts, so panning back and forth does not hit the server
 * over and over; the tile already stored is served if the server fails. Nothing is written to disk: the choice is
 * forgotten when the app stops.
 */
class ForcedRefresh {
    private val maps = MutableStateFlow<Set<String>>(emptySet())

    /** The maps being refreshed now. */
    val sourceIds: StateFlow<Set<String>> = maps.asStateFlow()

    private val refreshed = ConcurrentHashMap.newKeySet<String>()

    fun isForced(sourceId: String): Boolean = sourceId in maps.value

    /** Turns the refresh of [sourceId] on or off; turning it on again refetches every tile once more. */
    fun set(
        sourceId: String,
        forced: Boolean,
    ) {
        refreshed.removeIf { it.startsWith("$sourceId/") }
        maps.update { if (forced) it + sourceId else it - sourceId }
    }

    /** True the first time [key] is asked for while its map is being refreshed. */
    fun shouldRefresh(key: TileKey): Boolean {
        if (key.sourceId !in maps.value) return false
        if (refreshed.size >= MAX_REMEMBERED) refreshed.clear()
        return refreshed.add("${key.sourceId}/${key.z}/${key.x}/${key.y}")
    }

    private companion object {
        /** Bounds the memory used; the worst case after clearing is a few tiles fetched twice. */
        const val MAX_REMEMBERED = 50_000
    }
}
