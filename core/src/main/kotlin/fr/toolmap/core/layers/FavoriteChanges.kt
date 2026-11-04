package fr.toolmap.core.layers

/** A setting of a layer that a favorite records (P25.8): what « Mettre à jour » would change. */
enum class LayerField { VISIBILITY, OPACITY, ZOOM, ELEVATION, COLOURS }

/** One difference between the stack on screen and the favorite it came from (P25.8). */
sealed interface FavoriteChange {
    /** The layer [layerId] of the stack is in the favorite, with other [fields] than [was]. */
    data class Changed(
        val layerId: String,
        val sourceId: String,
        val was: WorkspaceLayer,
        val fields: Set<LayerField>,
    ) : FavoriteChange

    /** The layer [layerId] of the stack is not in the favorite. */
    data class Added(
        val layerId: String,
        val sourceId: String,
    ) : FavoriteChange

    /** The favorite has the layer [was], which the stack no longer shows. */
    data class Removed(
        val was: WorkspaceLayer,
    ) : FavoriteChange

    /** The maps both share are not in the same order. */
    data object Reordered : FavoriteChange
}

object FavoriteChanges {
    /**
     * What [stack] changes in [favorite]: the layers changed or added, top first as the panel lists them, then the
     * layers removed, then a new order. The k-th layer of a map in the favorite goes with the k-th layer of the same
     * map in the stack (a favorite can show a map twice, at two zoom ranges). Empty when [Workspace.matches] is true.
     */
    fun of(
        favorite: Workspace,
        stack: LayerStack,
    ): List<FavoriteChange> {
        val wanted = favorite.layers.keyed { it.sourceId }
        val shown = stack.layers.keyed { it.sourceId }
        val byKey = wanted.toMap()
        val shownKeys = shown.map { it.first }.toSet()
        val changes = mutableListOf<FavoriteChange>()
        for ((key, layer) in shown.asReversed()) {
            val was = byKey[key]
            if (was == null) {
                changes += FavoriteChange.Added(layer.id, layer.sourceId)
            } else {
                val fields = Workspace.changedFields(was, layer)
                if (fields.isNotEmpty()) changes += FavoriteChange.Changed(layer.id, layer.sourceId, was, fields)
            }
        }
        wanted.asReversed().filter { it.first !in shownKeys }.forEach { changes += FavoriteChange.Removed(it.second) }
        val inBoth = wanted.map { it.first }.filter { it in shownKeys }
        if (inBoth != shown.map { it.first }.filter { it in byKey }) changes += FavoriteChange.Reordered
        return changes
    }

    /** Each item with its key: its map and how many layers of that map come before it. */
    private fun <T> List<T>.keyed(sourceOf: (T) -> String): List<Pair<Pair<String, Int>, T>> {
        val seen = mutableMapOf<String, Int>()
        return map { item ->
            val source = sourceOf(item)
            val rank = seen.getOrDefault(source, 0)
            seen[source] = rank + 1
            (source to rank) to item
        }
    }
}

/** The favorites as the panel shows them, and which one the stack on screen comes from (P25.8, ADR-108). */
object Favorites {
    /**
     * The built-in presets not deleted ([hidden]), each replaced by the user's version in [overrides] when there is
     * one, then the user's own favorites ([saved]).
     */
    fun list(
        hidden: Set<String>,
        overrides: Map<String, Workspace>,
        saved: List<Workspace>,
    ): List<Workspace> = Workspaces.builtin.filterNot { it.id in hidden }.map { overrides[it.id] ?: it } + saved

    /** The built-in preset [id] as shipped, or null for a favorite of the user. */
    fun original(id: String): Workspace? = Workspaces.builtin.firstOrNull { it.id == id }

    /** True for a built-in preset the user updated: « Rétablir l'original » brings back the shipped one. */
    fun isOverridden(favorite: Workspace): Boolean = original(favorite.id)?.let { it != favorite } ?: false

    /**
     * The favorite shown as applied: the [origin] (the last one applied or saved) while the stack is still it, else
     * the first favorite the stack matches.
     */
    fun applied(
        favorites: List<Workspace>,
        origin: String?,
        stack: LayerStack,
    ): Workspace? =
        favorites.firstOrNull { it.id == origin }?.takeIf { it.matches(stack) }
            ?: favorites.firstOrNull { it.matches(stack) }

    /**
     * The favorite [origin] when the stack came from it and was changed since: what « Mettre à jour » replaces. Null
     * without an origin, with an empty stack, or when the stack matches a favorite.
     */
    fun modified(
        favorites: List<Workspace>,
        origin: String?,
        stack: LayerStack,
    ): Workspace? {
        if (origin == null || stack.isEmpty || favorites.any { it.matches(stack) }) return null
        return favorites.firstOrNull { it.id == origin }
    }

    /**
     * True when a favorite other than [id] is called [name] (spaces around and case left aside): two chips of the same
     * name could not be told apart, so « Renommer » refuses it (P25.5).
     */
    fun nameTaken(
        favorites: List<Workspace>,
        id: String,
        name: String,
    ): Boolean = favorites.any { it.id != id && it.name.trim().equals(name.trim(), ignoreCase = true) }

    /**
     * [favorite] under [name]: what « Renommer » saves. Its maps and settings do not change; for a built-in preset it
     * becomes the user's version (ADR-108), which « Rétablir l'original » undoes with the shipped name.
     */
    fun renamed(
        favorite: Workspace,
        name: String,
    ): Workspace = favorite.copy(name = name.trim())
}
