package fr.toolmap.core.layers

import kotlin.test.Test
import kotlin.test.assertEquals

class CoverageSpansTest {
    private val bases = setOf("osm", "opentopomap", "ign-scan25", "ign-ortho", "snow")
    private val isBase: (String) -> Boolean = { it in bases }

    private fun layer(
        id: String,
        source: String,
        min: Int? = null,
        max: Int? = null,
        visible: Boolean = true,
        minElevation: Int? = null,
    ) = LayerConfig(
        id = id,
        sourceId = source,
        visible = visible,
        displayMinZoom = min,
        displayMaxZoom = max,
        displayMinElevation = minElevation,
    )

    @Test
    fun `a stack split by zoom shows the coverage of the map drawn at each zoom`() {
        val stack =
            LayerStack(
                listOf(
                    layer("l1", "osm", 1, 8),
                    layer("l2", "opentopomap", 9, 11),
                    layer("l3", "ign-scan25", 12, 17),
                    layer("l4", "osm", 18, 18),
                    layer("l5", "ign-ortho", min = 19),
                    layer("l6", "pentes"),
                ),
            )
        assertEquals(
            listOf(
                CoverageSpan("osm", 1, 8),
                CoverageSpan("opentopomap", 9, 11),
                CoverageSpan("ign-scan25", 12, 17),
                CoverageSpan("osm", 18, 18),
                CoverageSpan("ign-ortho", 19, null),
            ),
            CoverageSpans.of(stack, isBase),
        )
    }

    @Test
    fun `the lowest whole base map wins over one drawn in an elevation band`() {
        val stack =
            LayerStack(
                listOf(
                    layer("l1", "snow", minElevation = 1550),
                    layer("l2", "ign-scan25"),
                    layer("l3", "osm"),
                ),
            )
        assertEquals(listOf(CoverageSpan("ign-scan25", null, null)), CoverageSpans.of(stack, isBase))
    }

    @Test
    fun `a hidden base map is skipped and a stack without base map shows nothing`() {
        val stack = LayerStack(listOf(layer("l1", "osm", visible = false), layer("l2", "ign-scan25", max = 15)))
        assertEquals(listOf(CoverageSpan("ign-scan25", null, 15)), CoverageSpans.of(stack, isBase))
        assertEquals(emptyList(), CoverageSpans.of(LayerStack(listOf(layer("l1", "pentes"))), isBase))
    }
}
