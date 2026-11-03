package fr.toolmap.core.layers

import fr.toolmap.core.geo.ZoomMapping

/** Translation of the user's [LayerStack] into the [AppliedLayer]s the style should hold. */
object StackToStyle {
    /**
     * [mapping] turns the displayed zoom ranges of the layers into camera zooms (ADR-016, ADR-032). [darkening], from 0
     * to 1, lowers the brightness of every map for the night mode (F14): the maps only, the tracks are other layers.
     */
    fun desired(
        stack: LayerStack,
        mapping: ZoomMapping = ZoomMapping.STANDARD,
        darkening: Float = 0f,
        isKnownSource: (String) -> Boolean,
    ): List<AppliedLayer> {
        val keep = 1f - darkening.coerceIn(0f, 1f)
        val known = LayerStack(stack.layers.filter { isKnownSource(it.sourceId) })
        return known.layers
            .map { layer ->
                val recipe = BlendRecipe.of(known, layer)
                if (recipe != null) return@map blended(layer, recipe, keep, mapping)
                val brightness = RasterAdjust.brightnessRange(layer.brightness, layer.inverted)
                val range =
                    if (LayerElevation.limitsZoom(layer)) {
                        LayerZoom.cameraRange(layer.displayMinZoom, layer.displayMaxZoom, mapping)
                    } else {
                        LayerZoom.CameraRange(null, null)
                    }
                AppliedLayer(
                    id = layer.id,
                    // A layer drawn in an elevation band has its own source: its tiles carry the band (P20).
                    sourceId = StyleSources.id(layer.sourceId, LayerElevation.maskOf(layer)),
                    props =
                        LayerProps(
                            visible = layer.visible,
                            opacity = layer.opacity.coerceIn(0f, 1f),
                            contrast = layer.contrast.coerceIn(-1f, 1f),
                            brightnessMin = brightness.min * keep,
                            brightnessMax = brightness.max * keep,
                            saturation = layer.saturation.coerceIn(-1f, 1f),
                            hueRotate = layer.hue.coerceIn(-RasterAdjust.MAX_HUE, RasterAdjust.MAX_HUE),
                            minZoom = range.minZoom,
                            maxZoom = range.maxZoom,
                        ),
                )
            }
    }

    /**
     * A blended layer (P26): its tiles are drawn with the layers under it, its colours, opacity and band already in
     * them ([BlendRecipe]), so the map only shows them, darkened for the night like any map.
     */
    private fun blended(
        layer: LayerConfig,
        recipe: BlendRecipe,
        keep: Float,
        mapping: ZoomMapping,
    ): AppliedLayer {
        val range =
            if (LayerElevation.limitsZoom(layer)) {
                LayerZoom.cameraRange(layer.displayMinZoom, layer.displayMaxZoom, mapping)
            } else {
                LayerZoom.CameraRange(null, null)
            }
        return AppliedLayer(
            id = layer.id,
            sourceId = StyleSources.id(layer.sourceId, recipe),
            props =
                LayerProps(
                    visible = layer.visible,
                    brightnessMax = keep,
                    minZoom = range.minZoom,
                    maxZoom = range.maxZoom,
                ),
        )
    }
}
