package fr.toolmap.map

import fr.toolmap.core.geo.ZoomMapping
import fr.toolmap.core.source.BuiltinSources
import fr.toolmap.core.tiles.ScalingPolicy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StyleApplierTest {
    private val scan25 = BuiltinSources.load().first { it.id == "ign-scan25" }
    private val phone = ZoomMapping.forScreen(2.8125, 1.0)

    @Test
    fun `on a dense screen MapLibre gets smaller tiles, so it takes deeper ones`() {
        assertEquals(256, StyleApplier.tileSize(scan25, ZoomMapping.STANDARD))
        assertEquals(91, StyleApplier.tileSize(scan25, phone))
        val big = scan25.copy(levels = scan25.levels.map { it.copy(tileSize = 512) })
        assertEquals(182, StyleApplier.tileSize(big, phone))
    }

    @Test
    fun `another sharpness changes the signature, so the style source is rebuilt`() {
        val scaling = ScalingPolicy()
        assertNotEquals(
            StyleApplier.signature(scan25, scaling, ZoomMapping.STANDARD),
            StyleApplier.signature(scan25, scaling, phone),
        )
        assertEquals(StyleApplier.signature(scan25, scaling, phone), StyleApplier.signature(scan25, scaling, phone))
    }

    @Test
    fun `rebuilding the maps keeps the grid (a previous drawing used the grid's prefix)`() {
        assertTrue(StyleApplier.isMapLayer(StyleApplier.layerId("l2")))
        assertTrue(StyleApplier.isMapLayer(StyleApplier.ghostId("l2")))
        assertFalse(StyleApplier.isMapLayer(GridLayers.LAYER))
        assertFalse(StyleApplier.isMapLayer(GridLayers.DASHED_LAYER))
        assertFalse(StyleApplier.isMapLayer(GridLayers.EDGE_LAYER))
        assertFalse(StyleApplier.isMapLayer(StyleApplier.ANCHOR_LAYER))
        assertFalse(StyleApplier.isMapLayer(StyleApplier.COVERAGE_LAYER))
    }
}
