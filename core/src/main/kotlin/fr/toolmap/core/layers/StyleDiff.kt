package fr.toolmap.core.layers

/** Everything MapLibre needs to draw one raster layer; compared to decide what to change in the style. */
data class LayerProps(
    val visible: Boolean = true,
    val opacity: Float = 1f,
    val contrast: Float = 0f,
    val brightnessMin: Float = 0f,
    val brightnessMax: Float = 1f,
    val saturation: Float = 0f,
    /** MapLibre `minzoom` / `maxzoom` of the layer (camera zoom); `null` = not set. */
    val minZoom: Float? = null,
    val maxZoom: Float? = null,
)

/** A raster layer as it exists (or should exist) in the MapLibre style. */
data class AppliedLayer(
    val id: String,
    val sourceId: String,
    val props: LayerProps,
)

sealed interface StyleOp {
    data class AddSource(
        val sourceId: String,
    ) : StyleOp

    data class RemoveSource(
        val sourceId: String,
    ) : StyleOp

    /** Insert [layer] right below the layer [belowId]; `null` means on top of every raster layer. */
    data class AddLayer(
        val layer: AppliedLayer,
        val belowId: String?,
    ) : StyleOp

    data class RemoveLayer(
        val id: String,
    ) : StyleOp

    data class UpdateLayer(
        val id: String,
        val props: LayerProps,
    ) : StyleOp
}

/**
 * Pure difference between the layers currently in the style and the desired ones (bottom to top), expressed
 * as the minimum sensible list of operations. [apply] replays operations on a plain list, which lets tests
 * check that any reordering ends exactly in the desired state.
 */
object StyleDiff {
    fun compute(
        current: List<AppliedLayer>,
        desired: List<AppliedLayer>,
    ): List<StyleOp> {
        val ops = mutableListOf<StyleOp>()
        val desiredById = desired.associateBy { it.id }

        // 1. Layers that disappear, or whose source changed (the layer is rebuilt).
        val sim = ArrayList<AppliedLayer>()
        for (layer in current) {
            val wanted = desiredById[layer.id]
            if (wanted == null || wanted.sourceId != layer.sourceId) {
                ops += StyleOp.RemoveLayer(layer.id)
            } else {
                sim += layer
            }
        }
        // 2. Sources nobody will use any more.
        val neededSources = desired.map { it.sourceId }.toSet()
        val presentSources = current.map { it.sourceId }.toSet()
        for (sourceId in presentSources - neededSources) ops += StyleOp.RemoveSource(sourceId)
        // 3. New sources.
        for (sourceId in neededSources - presentSources) ops += StyleOp.AddSource(sourceId)

        // 4. Walk the desired order, fixing the position of each layer.
        desired.forEachIndexed { index, want ->
            val here = sim.getOrNull(index)
            if (here?.id == want.id) {
                if (here.props != want.props) {
                    ops += StyleOp.UpdateLayer(want.id, want.props)
                    sim[index] = here.copy(props = want.props)
                }
                return@forEachIndexed
            }
            val existing = sim.indexOfFirst { it.id == want.id }
            if (existing >= 0) {
                ops += StyleOp.RemoveLayer(want.id)
                sim.removeAt(existing)
            }
            ops += StyleOp.AddLayer(want, belowId = sim.getOrNull(index)?.id)
            sim.add(index, want)
        }
        return ops
    }

    /** Replays [ops] on [current]; used by tests and as documentation of the operations' meaning. */
    fun apply(
        current: List<AppliedLayer>,
        ops: List<StyleOp>,
    ): List<AppliedLayer> {
        val result = current.toMutableList()
        for (op in ops) {
            when (op) {
                is StyleOp.AddSource, is StyleOp.RemoveSource -> {
                    Unit
                }

                is StyleOp.RemoveLayer -> {
                    result.removeAll { it.id == op.id }
                }

                is StyleOp.UpdateLayer -> {
                    val i = result.indexOfFirst { it.id == op.id }
                    if (i >= 0) result[i] = result[i].copy(props = op.props)
                }

                is StyleOp.AddLayer -> {
                    val at = op.belowId?.let { id -> result.indexOfFirst { it.id == id } } ?: -1
                    if (at >= 0) result.add(at, op.layer) else result.add(op.layer)
                }
            }
        }
        return result
    }
}
