package fr.toolmap.core.layers

/** A map whose stored coverage is shown over the displayed zooms [displayMin]..[displayMax] (inclusive, `null` = unbounded). */
data class CoverageSpan(
    val sourceId: String,
    val displayMin: Int?,
    val displayMax: Int?,
)

/**
 * Which map the « Couverture » overlay follows at each zoom: the lowest visible base map drawn at that zoom, so that
 * a stack split by zoom (OSM up to 8, IGN from 12…) shows the coverage of the map actually on screen. A base map drawn
 * only in an elevation band gives way to a whole one.
 */
object CoverageSpans {
    private const val MAX_ZOOM = 24

    fun of(
        stack: LayerStack,
        isBase: (String) -> Boolean,
    ): List<CoverageSpan> {
        val bases = stack.layers.filter { it.visible && isBase(it.sourceId) }
        val chosen =
            (0..MAX_ZOOM).map { z ->
                val drawn = bases.filter { LayerElevation.isDrawnAt(it, z.toDouble()) }
                (drawn.firstOrNull { LayerElevation.maskOf(it) == null } ?: drawn.firstOrNull())?.sourceId
            }
        val spans = mutableListOf<CoverageSpan>()
        var start = 0
        for (z in 1..MAX_ZOOM + 1) {
            if (z <= MAX_ZOOM && chosen[z] == chosen[start]) continue
            chosen[start]?.let { id ->
                spans +=
                    CoverageSpan(id, start.takeIf { start > 0 }, (z - 1).takeIf { z - 1 < MAX_ZOOM })
            }
            start = z
        }
        return spans
    }
}
