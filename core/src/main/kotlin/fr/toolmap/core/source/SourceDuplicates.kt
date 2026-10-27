package fr.toolmap.core.source

/**
 * What a map read from a file has in common with the maps already in the catalogue (import preview, ADR-031). Since
 * tiles are stored by [TileSetIdentity], a duplicate costs no second download; the preview still says so, and leaves
 * an exact duplicate unticked so that the catalogue does not list the same map twice.
 */
data class DuplicateInfo(
    /** A catalogue map that shows exactly the same tiles at every zoom of the new one, or `null`. */
    val duplicateOf: TileSource?,
    /** Catalogue maps sharing part of their tiles with the new one (without [duplicateOf]). */
    val sharesWith: List<TileSource>,
) {
    val isEmpty: Boolean get() = duplicateOf == null && sharesWith.isEmpty()

    companion object {
        val NONE = DuplicateInfo(null, emptyList())
    }
}

object SourceDuplicates {
    /** [candidate] compared with every map of [catalogue] but itself (same id: replaced, not duplicated). */
    fun of(
        candidate: TileSource,
        catalogue: List<TileSource>,
    ): DuplicateInfo {
        val identities = TileSetIdentity.allOf(candidate)
        if (identities.isEmpty()) return DuplicateInfo.NONE
        var duplicate: TileSource? = null
        val shares = mutableListOf<TileSource>()
        for (map in catalogue) {
            if (map.id == candidate.id || map.isMultiZoom) continue
            val theirs = TileSetIdentity.allOf(map)
            if (theirs.none { it in identities }) continue
            if (duplicate == null && covers(map, candidate)) duplicate = map else shares += map
        }
        return DuplicateInfo(duplicate, shares)
    }

    /** True when every zoom of [candidate] is served by [map] with the same tiles, as a base map or a layer alike. */
    private fun covers(
        map: TileSource,
        candidate: TileSource,
    ): Boolean {
        if (map.isLayer != candidate.isLayer) return false
        return candidate.levels.all { level ->
            val identity = TileSetIdentity.of(candidate, level) ?: return@all false
            level.zooms.toList().all { z -> map.levelFor(z)?.let { TileSetIdentity.of(map, it) } == identity }
        }
    }
}
