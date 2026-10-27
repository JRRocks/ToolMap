package fr.toolmap.core.tiles

import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSource

/**
 * How far the map may use the tiles of another zoom so that it never shows an empty area (ADR-031). AlpineQuest
 * fills a zoom missing from `<zoom-values>` with a neighbouring level; ToolMap does the same, and also below the
 * first zoom of a map (a map that starts at zoom 6 still shows something at zoom 5, 4, 3).
 */
data class ScalingPolicy(
    /**
     * Levels a finer zoom may be shrunk by (4^n tiles for one, so it is bounded): with 3, a map that starts at
     * zoom 6 is shown down to zoom 3. `0` turns all scaling off (empty areas outside the map's zooms).
     */
    val levels: Int = DEFAULT_LEVELS,
) {
    /** Levels a coarser tile may be enlarged by (one tile each, so only bounded by usefulness). */
    val enlargeLevels: Int get() = if (levels <= 0) 0 else MAX_ENLARGE

    val shrinkLevels: Int get() = levels.coerceIn(0, MAX_LEVELS)

    companion object {
        const val DEFAULT_LEVELS = 3
        const val MAX_LEVELS = 4

        /** Beyond 8 levels a 256-pixel tile would be enlarged from a single pixel: pointless. */
        const val MAX_ENLARGE = 8
        val OFF = ScalingPolicy(0)
    }
}

/** How to obtain one displayed tile. */
sealed interface TileRecipe {
    /** A real tile of [source] (the one in the URL of [level]), served or stored as it is. */
    data class Direct(
        val source: TileSource,
        val level: LevelDef,
        val tile: TileId,
    ) : TileRecipe

    /** Part ([col], [row] of a 2^[depth] × 2^[depth] grid) of a coarser tile, enlarged to a full tile. */
    data class Enlarge(
        val from: TileRecipe,
        val depth: Int,
        val col: Int,
        val row: Int,
    ) : TileRecipe

    /** 2^[depth] × 2^[depth] finer tiles, row after row, shrunk into one tile. */
    data class Shrink(
        val parts: List<TileRecipe>,
        val depth: Int,
    ) : TileRecipe

    /** Nothing to show here. */
    data object None : TileRecipe

    /** The real tiles this recipe needs. */
    fun leaves(): List<Direct> =
        when (this) {
            is Direct -> listOf(this)
            is Enlarge -> from.leaves()
            is Shrink -> parts.flatMap { it.leaves() }
            None -> emptyList()
        }
}

/**
 * Decides where a tile shown for a map comes from: the map's own tile, the tile of the map a multi-zoom level points
 * to ([fr.toolmap.core.source.MapRef]), possibly at a forced zoom, or a neighbouring zoom shrunk or enlarged when the
 * map has no level for the displayed zoom. Pure: the tile pipeline executes the recipe.
 */
class TileResolver(
    private val lookup: (String) -> TileSource?,
) {
    /** Recipe of the tile [x], [y] of zoom [z] of [source]. */
    fun resolve(
        source: TileSource,
        z: Int,
        x: Int,
        y: Int,
        policy: ScalingPolicy,
    ): TileRecipe {
        val recipe = resolve(source, z, x, y, policy, 0, 0)
        return if (recipe.leafCount() > MAX_LEAVES) TileRecipe.None else recipe
    }

    /**
     * The real tile zooms of each map that displaying [source] at zoom [z] reads (what a download must fetch so that
     * the zoom shows offline). Independent of the position: levels only depend on the zoom.
     */
    fun tileZooms(
        source: TileSource,
        z: Int,
        policy: ScalingPolicy,
    ): Map<TileSource, Set<Int>> {
        val out = LinkedHashMap<TileSource, MutableSet<Int>>()
        for (leaf in resolve(source, z, 0, 0, policy).leaves()) {
            out.getOrPut(leaf.source) { sortedSetOf() } += leaf.tile.z
        }
        return out
    }

    /** [shrunk]: shrinking levels already used above this tile (their product is bounded, see [MAX_LEAVES]). */
    private fun resolve(
        source: TileSource,
        z: Int,
        x: Int,
        y: Int,
        policy: ScalingPolicy,
        depth: Int,
        shrunk: Int,
    ): TileRecipe {
        if (depth > MAX_DEPTH || z !in 0..MAX_ZOOM) return TileRecipe.None
        val level = source.levelFor(z) ?: return fallback(source, z, x, y, policy, depth, shrunk)
        val ref = level.mapRef ?: return TileRecipe.Direct(source, level, TileId(z, x, y))
        val member = lookup(ref.sourceId)?.takeIf { it.id != source.id } ?: return TileRecipe.None
        val n = ref.tileZoom ?: z
        return when {
            n == z -> {
                resolve(member, z, x, y, policy, depth + 1, shrunk)
            }

            n < z -> {
                enlarge(z - n, x, y) { px, py -> resolve(member, n, px, py, policy, depth + 1, shrunk) }
            }

            else -> {
                val d = n - z
                if (shrunk + d > ScalingPolicy.MAX_LEVELS) {
                    TileRecipe.None
                } else {
                    shrink(d, x, y) { cx, cy -> resolve(member, n, cx, cy, policy, depth + 1, shrunk + d) }
                }
            }
        }
    }

    /** No level of [source] at [z]: the nearest zoom it has, enlarged first on a tie (one tile instead of four). */
    private fun fallback(
        source: TileSource,
        z: Int,
        x: Int,
        y: Int,
        policy: ScalingPolicy,
        depth: Int,
        shrunk: Int,
    ): TileRecipe {
        val reach = maxOf(policy.enlargeLevels, policy.shrinkLevels)
        for (d in 1..reach) {
            val lower = z - d
            if (d <= policy.enlargeLevels && lower >= 0 && source.levelFor(lower) != null) {
                return enlarge(d, x, y) { px, py -> resolve(source, lower, px, py, policy, depth + 1, shrunk) }
            }
            val higher = z + d
            if (d <= policy.shrinkLevels && shrunk + d <= ScalingPolicy.MAX_LEVELS && higher <= MAX_ZOOM &&
                source.levelFor(higher) != null
            ) {
                return shrink(d, x, y) { cx, cy -> resolve(source, higher, cx, cy, policy, depth + 1, shrunk + d) }
            }
        }
        return TileRecipe.None
    }

    private inline fun enlarge(
        d: Int,
        x: Int,
        y: Int,
        parent: (Int, Int) -> TileRecipe,
    ): TileRecipe {
        val from = parent(x shr d, y shr d)
        if (from == TileRecipe.None) return TileRecipe.None
        val mask = (1 shl d) - 1
        return TileRecipe.Enlarge(from, d, x and mask, y and mask)
    }

    private inline fun shrink(
        d: Int,
        x: Int,
        y: Int,
        child: (Int, Int) -> TileRecipe,
    ): TileRecipe {
        val side = 1 shl d
        val parts = ArrayList<TileRecipe>(side * side)
        for (row in 0 until side) {
            for (col in 0 until side) parts += child((x shl d) + col, (y shl d) + row)
        }
        return if (parts.all { it == TileRecipe.None }) TileRecipe.None else TileRecipe.Shrink(parts, d)
    }

    private fun TileRecipe.leafCount(): Int =
        when (this) {
            is TileRecipe.Direct -> 1
            is TileRecipe.Enlarge -> from.leafCount()
            is TileRecipe.Shrink -> parts.sumOf { it.leafCount() }
            TileRecipe.None -> 0
        }

    companion object {
        /** Longest chain of maps referencing maps (a cycle stops here). */
        const val MAX_DEPTH = 6

        /** Most real tiles one displayed tile may be built from (4 levels of shrinking). */
        const val MAX_LEAVES = 256
        private const val MAX_ZOOM = 30
    }
}
