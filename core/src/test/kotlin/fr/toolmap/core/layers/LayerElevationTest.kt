package fr.toolmap.core.layers

import fr.toolmap.core.elevation.ElevationMask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayerElevationTest {
    private fun layer(
        zooms: Pair<Int?, Int?> = null to null,
        band: Pair<Int?, Int?> = null to null,
        logic: RangeLogic = RangeLogic.AND,
    ) = LayerConfig(
        id = "l1",
        sourceId = "opensnowmap",
        displayMinZoom = zooms.first,
        displayMaxZoom = zooms.second,
        displayMinElevation = band.first,
        displayMaxElevation = band.second,
        rangeLogic = logic,
    )

    @Test
    fun `a layer without a band is drawn whole`() {
        assertNull(LayerElevation.maskOf(layer()))
        assertNull(LayerElevation.maskOf(layer(zooms = 8 to 16, logic = RangeLogic.OR)))
    }

    @Test
    fun `with ET the band holds at every zoom of the range`() {
        val mask = LayerElevation.maskOf(layer(zooms = 8 to 16, band = 1500 to null))
        assertEquals(ElevationMask(1500, null), mask)
        assertTrue(LayerElevation.limitsZoom(layer(zooms = 8 to 16, band = 1500 to null)))
    }

    @Test
    fun `with OU the zoom range is drawn whole and the band elsewhere`() {
        val or = layer(zooms = 8 to 16, band = 1500 to null, logic = RangeLogic.OR)
        assertEquals(ElevationMask(1500, null, 8..16), LayerElevation.maskOf(or))
        assertFalse(LayerElevation.limitsZoom(or))
        // An open end of the range stands for every deeper zoom.
        val from14 = layer(zooms = 14 to null, band = null to 1200, logic = RangeLogic.OR)
        assertEquals(14..30, LayerElevation.maskOf(from14)?.whole)
        // Without a zoom range, OU is the band alone.
        assertEquals(
            ElevationMask(1500, null),
            LayerElevation.maskOf(layer(band = 1500 to null, logic = RangeLogic.OR)),
        )
    }

    @Test
    fun `a layer is drawn outside its zoom range only with OU and a band`() {
        val and = layer(zooms = 8 to 16, band = 1500 to null)
        assertFalse(LayerElevation.isDrawnAt(and, 5.0))
        assertTrue(LayerElevation.isDrawnAt(and, 10.5))
        assertTrue(LayerElevation.isDrawnAt(and.copy(rangeLogic = RangeLogic.OR), 5.0))
        val zoomOnly = layer(zooms = 8 to 16, logic = RangeLogic.OR)
        assertTrue(LayerElevation.limitsZoom(zoomOnly))
        assertFalse(LayerElevation.isDrawnAt(zoomOnly, 17.0))
    }

    @Test
    fun `the elevation bar works in 50 m steps and its ends mean no limit`() {
        val all = ZoomBar.Range(0, ElevationBar.STEPS)
        assertEquals(96, ElevationBar.STEPS)
        assertEquals(all, ElevationBar.rangeOf(null, null))
        assertEquals(null to null, ElevationBar.toBounds(all))
        assertEquals(ZoomBar.Range(30, ElevationBar.STEPS), ElevationBar.rangeOf(1500, null))
        assertEquals(1500 to null, ElevationBar.toBounds(ZoomBar.Range(30, ElevationBar.STEPS)))
        assertEquals(null to 2000, ElevationBar.toBounds(ZoomBar.Range(0, 40)))
        // A bound set elsewhere snaps to the nearest step; beyond the bar it stays on the bar.
        assertEquals(ZoomBar.Range(30, 50), ElevationBar.rangeOf(1520, 2480))
        assertEquals(ZoomBar.Range(0, ElevationBar.STEPS), ElevationBar.rangeOf(-200, 9000))
    }

    @Test
    fun `the centre mark sits on the bar`() {
        assertEquals(24.68, ElevationBar.positionOf(1234.0), 1e-9)
        assertEquals(0.0, ElevationBar.positionOf(-20.0))
        assertEquals(96.0, ElevationBar.positionOf(8848.0))
    }

    @Test
    fun `a banded layer has its own style source named after the band`() {
        val mask = ElevationMask(1500, null)
        assertEquals("osm", StyleSources.id("osm", null))
        assertEquals("osm?alt=1500,", StyleSources.id("osm", mask))
        assertEquals("osm", StyleSources.sourceOf("osm?alt=1500,"))
        assertEquals("osm", StyleSources.sourceOf("osm"))
        assertEquals("alt=1500,", StyleSources.queryOf("osm?alt=1500,"))
        assertNull(StyleSources.queryOf("osm"))
        assertEquals(mask, ElevationMask.parse(StyleSources.queryOf(StyleSources.id("osm", mask))))
    }

    @Test
    fun `setting a band keeps its bounds in order and resets with two nulls`() {
        val stack = LayerStack().add("osm")
        val raised = stack.setElevationRange("l1", 2000, 1500, RangeLogic.OR).layers.single()
        assertEquals(2000, raised.displayMinElevation)
        assertEquals(2000, raised.displayMaxElevation)
        assertEquals(RangeLogic.OR, raised.rangeLogic)
        val reset =
            stack.setElevationRange(
                "l1",
                1500,
                null,
                RangeLogic.AND,
            ).setElevationRange("l1", null, null, RangeLogic.AND)
        assertFalse(reset.layers.single().hasElevationRange)
        assertEquals(stack, stack.setElevationRange("l9", 1500, null, RangeLogic.AND))
    }

    @Test
    fun `the style of a banded layer uses its band source and drops the camera range with OU`() {
        val stack =
            LayerStack()
                .add("opentopomap")
                .add("opensnowmap")
                .setZoomRange("l2", 8, 16)
                .setElevationRange("l2", 1500, null, RangeLogic.AND)
        val and = StackToStyle.desired(stack) { true }
        assertEquals(listOf("opentopomap", "opensnowmap?alt=1500,"), and.map { it.sourceId })
        assertEquals(LayerZoom.cameraRange(8, 16).minZoom, and.last().props.minZoom)
        val or = StackToStyle.desired(stack.setElevationRange("l2", 1500, null, RangeLogic.OR)) { true }.last()
        assertEquals("opensnowmap?alt=1500,&whole=8,16", or.sourceId)
        assertNull(or.props.minZoom)
        assertNull(or.props.maxZoom)
        // Unknown maps are left out by their own id, not by the band's.
        assertEquals(listOf("l1"), StackToStyle.desired(stack) { it == "opentopomap" }.map { it.id })
    }
}
