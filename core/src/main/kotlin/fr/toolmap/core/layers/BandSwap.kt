package fr.toolmap.core.layers

/**
 * A layer whose elevation band changes is rebuilt on a new style source (P20, [StyleSources]): until the new tiles are
 * drawn, its previous state (a "ghost") stays below it so that the map does not go blank. Pure rules of which layers
 * get one and which lose it.
 */
object BandSwap {
    /**
     * Layers of [current], as they are now, that [desired] moves to another band of the same map while they are
     * drawn: they get a ghost, unless one is in [ghosted] already (a layer changed again while it loads keeps its
     * first drawing).
     */
    fun newGhosts(
        current: List<AppliedLayer>,
        desired: List<AppliedLayer>,
        ghosted: Set<String>,
    ): List<AppliedLayer> {
        val wanted = desired.associateBy { it.id }
        return current.filter { old ->
            val new = wanted[old.id] ?: return@filter false
            new.sourceId != old.sourceId &&
                StyleSources.sourceOf(new.sourceId) == StyleSources.sourceOf(old.sourceId) &&
                old.props.visible &&
                old.id !in ghosted
        }
    }

    /** Ghosts of [ghosts] to remove at once: their layer is gone, or back on the very source the ghost draws. */
    fun stale(
        ghosts: Map<String, AppliedLayer>,
        desired: List<AppliedLayer>,
    ): Set<String> {
        val wanted = desired.associateBy { it.id }
        return ghosts.filter { (id, ghost) -> wanted[id]?.sourceId.let { it == null || it == ghost.sourceId } }.keys
    }
}
