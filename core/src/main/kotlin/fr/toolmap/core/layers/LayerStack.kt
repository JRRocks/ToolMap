package fr.toolmap.core.layers

import kotlinx.serialization.Serializable

/**
 * The ordered layers shown on the map. Index 0 is the bottom layer, the last one is drawn on top.
 * Immutable: every operation returns a new stack (an unknown id leaves the stack unchanged).
 */
@Serializable
data class LayerStack(
    val layers: List<LayerConfig> = emptyList(),
) {
    val isEmpty: Boolean get() = layers.isEmpty()

    operator fun get(id: String): LayerConfig? = layers.firstOrNull { it.id == id }

    fun indexOf(id: String): Int = layers.indexOfFirst { it.id == id }

    /** Layers from the top of the screen down: the order users see in the list. */
    fun topToBottom(): List<LayerConfig> = layers.asReversed()

    /** Adds [sourceId] on top (or at [index]) with a fresh id. */
    fun add(
        sourceId: String,
        opacity: Float = 1f,
        index: Int = layers.size,
    ): LayerStack {
        val config = LayerConfig(id = nextId(), sourceId = sourceId, opacity = opacity)
        val at = index.coerceIn(0, layers.size)
        return LayerStack(layers.toMutableList().apply { add(at, config) })
    }

    fun remove(id: String): LayerStack = LayerStack(layers.filterNot { it.id == id })

    /** Keeps only [id] ("Undisplay others"); an unknown id leaves the stack unchanged. */
    fun removeOthers(id: String): LayerStack = if (indexOf(id) < 0) this else LayerStack(layers.filter { it.id == id })

    /** Moves [id] to position [toIndex] (clamped). */
    fun move(
        id: String,
        toIndex: Int,
    ): LayerStack {
        val from = indexOf(id)
        if (from < 0) return this
        val mutable = layers.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(toIndex.coerceIn(0, mutable.size), item)
        return LayerStack(mutable)
    }

    /** One step towards the top of the screen. */
    fun moveUp(id: String): LayerStack = indexOf(id).let { if (it < 0) this else move(id, it + 1) }

    /** One step towards the bottom of the screen. */
    fun moveDown(id: String): LayerStack = indexOf(id).let { if (it < 0) this else move(id, it - 1) }

    fun update(
        id: String,
        transform: (LayerConfig) -> LayerConfig,
    ): LayerStack = LayerStack(layers.map { if (it.id == id) transform(it) else it })

    fun setVisible(
        id: String,
        visible: Boolean,
    ): LayerStack = update(id) { it.copy(visible = visible) }

    fun setOpacity(
        id: String,
        opacity: Float,
    ): LayerStack = update(id) { it.copy(opacity = opacity.coerceIn(0f, 1f)) }

    /**
     * Sets the display range of [id] (displayed zoom, inclusive; `null` = unbounded). Out-of-range values are
     * clamped to 0..[ZoomBar.MAX] and a minimum above the maximum raises the maximum to it.
     */
    fun setZoomRange(
        id: String,
        min: Int?,
        max: Int?,
    ): LayerStack {
        val low = min?.coerceIn(0, ZoomBar.MAX)
        val high = max?.coerceIn(0, ZoomBar.MAX)?.let { if (low != null && it < low) low else it }
        return update(id) { it.copy(displayMinZoom = low, displayMaxZoom = high) }
    }

    /**
     * Sets the elevation band of layer [id] (metres, inclusive, `null` = unbounded, P20) and how it combines with the
     * zoom range; a minimum above the maximum raises the maximum to it.
     */
    fun setElevationRange(
        id: String,
        min: Int?,
        max: Int?,
        logic: RangeLogic,
    ): LayerStack {
        val high = max?.let { if (min != null && it < min) min else it }
        return update(id) { it.copy(displayMinElevation = min, displayMaxElevation = high, rangeLogic = logic) }
    }

    fun setAdjustments(
        id: String,
        contrast: Float,
        brightness: Float,
        saturation: Float,
    ): LayerStack =
        update(id) {
            it.copy(
                contrast = contrast.coerceIn(-1f, 1f),
                brightness = brightness.coerceIn(-1f, 1f),
                saturation = saturation.coerceIn(-1f, 1f),
            )
        }

    fun setInverted(
        id: String,
        inverted: Boolean,
    ): LayerStack = update(id) { it.copy(inverted = inverted) }

    /**
     * Swaps the source of the lowest base-map layer (a layer for which [isBase] holds) for [sourceId], keeping
     * its position, opacity and adjustments. Adds a new bottom layer when the stack has no base map.
     */
    fun replaceBase(
        sourceId: String,
        isBase: (String) -> Boolean,
    ): LayerStack {
        val target = layers.firstOrNull { isBase(it.sourceId) } ?: return add(sourceId, index = 0)
        return update(target.id) { it.copy(sourceId = sourceId) }
    }

    /** Lowest layer showing a base map, if any. */
    fun baseLayer(isBase: (String) -> Boolean): LayerConfig? = layers.firstOrNull { isBase(it.sourceId) }

    /** Deterministic next id: `l<max number in use + 1>`. */
    fun nextId(): String {
        val max =
            layers
                .mapNotNull { it.id.removePrefix(ID_PREFIX).takeIf { rest -> rest != it.id }?.toIntOrNull() }
                .maxOrNull() ?: 0
        return "$ID_PREFIX${max + 1}"
    }

    companion object {
        const val ID_PREFIX = "l"
    }
}
