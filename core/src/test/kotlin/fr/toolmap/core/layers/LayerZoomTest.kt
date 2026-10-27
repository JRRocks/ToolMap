package fr.toolmap.core.layers

import fr.toolmap.core.geo.ZoomMapping
import fr.toolmap.core.source.BuiltinSources
import fr.toolmap.core.state.WorkState
import fr.toolmap.core.state.WorkStateCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayerZoomTest {
    /** MapLibre's own tile size, and AlpineQuest's sharpness on screens of density 1.5, 2.8125 and 4. */
    private val mappings =
        listOf(
            ZoomMapping.STANDARD,
            ZoomMapping.forScreen(1.5, 1.0),
            ZoomMapping.forScreen(2.8125, 1.0),
            ZoomMapping.forScreen(4.0, 1.0),
        )

    /** MapLibre's rule for `minzoom` / `maxzoom` of a layer at a given camera zoom. */
    private fun visibleInMapLibre(
        props: LayerProps,
        camera: Double,
    ): Boolean =
        (props.minZoom == null || camera >= props.minZoom!!) && (props.maxZoom == null || camera < props.maxZoom!!)

    /** First quarter zoom the camera reaches (camera zoom ≥ 0). */
    private fun firstQuarter(mapping: ZoomMapping): Double = kotlin.math.ceil(mapping.toTileZoom(0.0) * 4) / 4

    /**
     * Camera zoom of a displayed zoom, nudged up by far less than a visible step: MapLibre's bounds are floats, so
     * exactly on an irrational bound either side can win; just above it, an inclusive minimum and an exclusive
     * maximum both have a single answer.
     */
    private fun cameraAt(
        mapping: ZoomMapping,
        displayed: Double,
    ): Double = mapping.toCameraZoom(displayed) + 1e-4

    @Test
    fun `an inclusive display range becomes a camera range with an exclusive upper bound`() {
        // Displayed 8..16 = camera 7 up to (not including) 16.
        assertEquals(LayerZoom.CameraRange(7f, 16f), LayerZoom.cameraRange(8, 16))
        assertEquals(LayerZoom.CameraRange(null, 7f), LayerZoom.cameraRange(1, 7))
        assertEquals(LayerZoom.CameraRange(null, null), LayerZoom.cameraRange(null, null))
        assertEquals(LayerZoom.CameraRange(null, null), LayerZoom.cameraRange(0, null))
        assertEquals(LayerZoom.CameraRange(11f, null), LayerZoom.cameraRange(12, null))
    }

    @Test
    fun `the camera range agrees with the displayed zoom rule at every quarter zoom`() {
        val ranges = listOf(8 to 16, 1 to 7, null to 5, 10 to null, null to null, 3 to 3, 0 to 1)
        for (mapping in mappings) {
            for ((min, max) in ranges) {
                val camera = LayerZoom.cameraRange(min, max, mapping)
                assertTrue(camera.minZoom == null || camera.minZoom!! > 0f)
                assertTrue(camera.maxZoom == null || camera.maxZoom!! >= 0f, "MapLibre refuses a negative maxzoom")
                val props = LayerProps(minZoom = camera.minZoom, maxZoom = camera.maxZoom)
                // Only the displayed zooms the camera can reach (camera zoom ≥ 0).
                var displayed = firstQuarter(mapping)
                while (displayed <= 22.0) {
                    assertEquals(
                        LayerZoom.isActiveAt(min, max, displayed),
                        visibleInMapLibre(props, cameraAt(mapping, displayed)),
                        "range $min..$max at displayed zoom $displayed with $mapping",
                    )
                    displayed += 0.25
                }
            }
        }
    }

    @Test
    fun `the author's workspace shows Topo and slopes from 8 and Plan and slopes below, with no gap at 7_5`() {
        val workspace = topoPlanSlopes
        val stack = workspace.toStack { true }.stack
        for (mapping in mappings) {
            val props = StackToStyle.desired(stack, mapping) { true }.associate { it.sourceId to it.props }
            var displayed = maxOf(1.0, firstQuarter(mapping))
            while (displayed < 17.0) {
                val camera = cameraAt(mapping, displayed)
                val plan = visibleInMapLibre(props.getValue("ign-plan"), camera)
                val topo = visibleInMapLibre(props.getValue("ign-scan25"), camera)
                val slopes = visibleInMapLibre(props.getValue("ign-pentes"), camera)
                assertTrue(plan || topo, "a base map is shown at $displayed")
                assertTrue(plan != topo, "exactly one base map at $displayed")
                assertTrue(slopes, "slopes shown at $displayed")
                assertEquals(displayed >= 8.0, topo, "topo at $displayed")
                assertEquals(displayed < 8.0, plan, "plan at $displayed")
                displayed += 0.25
            }
            // Past 16.999 neither base map nor slopes remain (the ranges end at 16).
            val beyond = cameraAt(mapping, 17.0)
            assertFalse(visibleInMapLibre(props.getValue("ign-scan25"), beyond))
            assertFalse(visibleInMapLibre(props.getValue("ign-pentes"), beyond))
        }
    }

    @Test
    fun `on a sharp phone screen the same displayed range starts at a lower camera zoom`() {
        // Density 2.8125 (samsung SM-S931U), AlpineQuest's sharpness: displayed zoom = camera + 2.49.
        val phone = ZoomMapping.forScreen(2.8125, 1.0)
        val range = LayerZoom.cameraRange(8, 16, phone)
        assertEquals(8 - phone.offset, range.minZoom!!.toDouble(), 1e-5)
        assertEquals(17 - phone.offset, range.maxZoom!!.toDouble(), 1e-5)
    }

    @Test
    fun `setZoomRange clamps and keeps the minimum below the maximum`() {
        val stack = LayerStack().add("osm")
        val id = stack.layers.single().id
        assertEquals(8 to 16, stack.setZoomRange(id, 8, 16)[id]!!.let { it.displayMinZoom to it.displayMaxZoom })
        val reversed = stack.setZoomRange(id, 12, 5)[id]!!
        assertEquals(12, reversed.displayMinZoom)
        assertEquals(12, reversed.displayMaxZoom)
        val clamped = stack.setZoomRange(id, -4, 99)[id]!!
        assertEquals(0, clamped.displayMinZoom)
        assertEquals(ZoomBar.MAX, clamped.displayMaxZoom)
        val cleared = stack.setZoomRange(id, 8, 16).setZoomRange(id, null, null)[id]!!
        assertNull(cleared.displayMinZoom)
        assertNull(cleared.displayMaxZoom)
        assertEquals(stack, stack.setZoomRange("unknown", 1, 2))
    }

    @Test
    fun `zoom ranges are written to the style and survive the saved work state`() {
        val base = LayerStack().add("ign-scan25")
        val id = base.layers.single().id
        val stack = base.setZoomRange(id, 8, 16)
        val applied = StackToStyle.desired(stack) { true }.single().props
        assertEquals(7f, applied.minZoom)
        assertEquals(16f, applied.maxZoom)

        val restored = WorkStateCodec.decodeOrDefault(WorkStateCodec.encode(WorkState(stack = stack)))
        assertEquals(8, restored.stack[id]!!.displayMinZoom)
        assertEquals(16, restored.stack[id]!!.displayMaxZoom)
    }

    @Test
    fun `a change of range is a plain layer update for the style diff`() {
        val stack = LayerStack().add("osm")
        val id = stack.layers.single().id
        val before = StackToStyle.desired(stack) { true }
        val after = StackToStyle.desired(stack.setZoomRange(id, 5, 12)) { true }
        val ops = StyleDiff.compute(before, after)
        assertEquals(1, ops.size)
        assertTrue(ops.single() is StyleOp.UpdateLayer)
    }

    @Test
    fun `the preset only uses sources of the built-in catalogue`() {
        val ids = BuiltinSources.load().map { it.id }.toSet()
        assertTrue(Workspaces.builtin.flatMap { w -> w.layers.map { it.sourceId } }.all { it in ids })
    }
}
