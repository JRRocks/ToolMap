package fr.toolmap.core.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BandSwapTest {
    private fun layer(
        id: String,
        source: String,
        visible: Boolean = true,
    ) = AppliedLayer(id, source, LayerProps(visible = visible))

    private val topo = layer("l1", "opentopomap")
    private val snow1500 = layer("l2", "opensnowmap?alt=1500,")
    private val snow1300 = layer("l2", "opensnowmap?alt=1300,")

    @Test
    fun `a layer moved to another band of the same map keeps its old drawing below`() {
        assertEquals(listOf(snow1500), BandSwap.newGhosts(listOf(topo, snow1500), listOf(topo, snow1300), emptySet()))
        // From plain to banded and back: still the same map.
        val plain = layer("l2", "opensnowmap")
        assertEquals(listOf(plain), BandSwap.newGhosts(listOf(plain), listOf(snow1500), emptySet()))
        assertEquals(listOf(snow1500), BandSwap.newGhosts(listOf(snow1500), listOf(plain), emptySet()))
    }

    @Test
    fun `no ghost for another map, a hidden or removed layer, or one that has a ghost already`() {
        val other = layer("l2", "osm?alt=1500,")
        assertTrue(BandSwap.newGhosts(listOf(snow1500), listOf(other), emptySet()).isEmpty())
        val hidden = layer("l2", "opensnowmap?alt=1500,", visible = false)
        assertTrue(BandSwap.newGhosts(listOf(hidden), listOf(snow1300), emptySet()).isEmpty())
        assertTrue(BandSwap.newGhosts(listOf(topo, snow1500), listOf(topo), emptySet()).isEmpty())
        assertTrue(BandSwap.newGhosts(listOf(snow1500), listOf(snow1300), setOf("l2")).isEmpty())
        assertTrue(BandSwap.newGhosts(listOf(snow1500), listOf(snow1500), emptySet()).isEmpty())
    }

    @Test
    fun `a ghost goes at once when its layer is gone or back on the ghost's source`() {
        val ghosts = mapOf("l2" to snow1500)
        assertTrue(BandSwap.stale(ghosts, listOf(topo, snow1300)).isEmpty())
        assertEquals(setOf("l2"), BandSwap.stale(ghosts, listOf(topo, snow1500)))
        assertEquals(setOf("l2"), BandSwap.stale(ghosts, listOf(topo)))
    }
}
