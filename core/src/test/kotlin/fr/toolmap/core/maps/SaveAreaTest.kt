package fr.toolmap.core.maps

import fr.toolmap.core.download.ZoneShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaveAreaTest {
    private val box = AreaSelection(west = 5.0, south = 44.0, east = 6.0, north = 45.0)

    @Test
    fun `dragging a corner moves it and keeps the opposite one`() {
        val moved = box.drag(AreaCorner.NORTH_EAST, lat = 45.5, lon = 6.5)
        assertEquals(AreaSelection(5.0, 44.0, 6.5, 45.5), moved)
        val other = box.drag(AreaCorner.SOUTH_WEST, lat = 43.5, lon = 4.5)
        assertEquals(AreaSelection(4.5, 43.5, 6.0, 45.0), other)
    }

    @Test
    fun `a corner cannot cross the opposite one`() {
        val squeezed = box.drag(AreaCorner.NORTH_EAST, lat = 40.0, lon = 1.0)
        assertEquals(5.0, squeezed.west)
        assertEquals(44.0, squeezed.south)
        assertEquals(5.0 + AreaSelection.MIN_SPAN, squeezed.east, 1e-12)
        assertEquals(44.0 + AreaSelection.MIN_SPAN, squeezed.north, 1e-12)
        val west = box.drag(AreaCorner.NORTH_WEST, lat = 46.0, lon = 9.0)
        assertEquals(6.0 - AreaSelection.MIN_SPAN, west.west, 1e-12)
        assertEquals(46.0, west.north)
    }

    @Test
    fun `nothing leaves the Web Mercator world`() {
        val far = box.drag(AreaCorner.NORTH_EAST, lat = 89.0, lon = 400.0)
        assertEquals(AreaSelection.MAX_LATITUDE, far.north)
        assertEquals(180.0, far.east)
        val low = box.drag(AreaCorner.SOUTH_WEST, lat = -90.0, lon = -200.0)
        assertEquals(-AreaSelection.MAX_LATITUDE, low.south)
        assertEquals(-180.0, low.west)
    }

    @Test
    fun `the corner accessors agree with the bounds`() {
        assertEquals(45.0, box.latOf(AreaCorner.NORTH_WEST))
        assertEquals(44.0, box.latOf(AreaCorner.SOUTH_EAST))
        assertEquals(5.0, box.lonOf(AreaCorner.SOUTH_WEST))
        assertEquals(6.0, box.lonOf(AreaCorner.NORTH_EAST))
        assertEquals(ZoneShape.Rectangle(5.0, 44.0, 6.0, 45.0), box.toShape())
    }

    @Test
    fun `an empty rectangle is refused`() {
        assertFailsWith<IllegalArgumentException> { AreaSelection(5.0, 44.0, 5.0, 45.0) }
    }

    @Test
    fun `the first rectangle fills 60 percent of what is visible, around its centre`() {
        val visible = ZoneShape.Rectangle(west = 0.0, south = 0.0, east = 10.0, north = 20.0)
        val area = AreaSelection.centered(visible)
        assertEquals(2.0, area.west, 1e-9)
        assertEquals(8.0, area.east, 1e-9)
        assertEquals(4.0, area.south, 1e-9)
        assertEquals(16.0, area.north, 1e-9)
    }

    @Test
    fun `a map saves the zooms it has up to the chosen maximum`() {
        assertEquals(ZoomSpan(1, 14, 17), ZoneStrips.span(sourceMin = 1, sourceMax = 17, chosenMax = 14))
        assertEquals(ZoomSpan(1, 17, 17), ZoneStrips.span(1, 17, chosenMax = 20))
        assertEquals(ZoomSpan(6, 6, 14), ZoneStrips.span(6, 14, chosenMax = 6))
        assertNull(ZoneStrips.span(6, 14, chosenMax = 5))
    }

    @Test
    fun `the bar has one cell per zoom and tells saved, skipped and absent apart`() {
        val cells = ZoneStrips.cells(sourceMin = 2, sourceMax = 6, chosenMax = 4, scaleMax = 8)
        assertEquals(9, cells.size)
        assertEquals(
            listOf(
                ZoomCell.ABSENT,
                ZoomCell.ABSENT,
                ZoomCell.SAVED,
                ZoomCell.SAVED,
                ZoomCell.SAVED,
                ZoomCell.SKIPPED,
                ZoomCell.SKIPPED,
                ZoomCell.ABSENT,
                ZoomCell.ABSENT,
            ),
            cells,
        )
    }

    @Test
    fun `the red marker sits at the current zoom along the bar`() {
        // 19 cells (zoom 0..18): zoom 9.5 is exactly half way.
        assertEquals(0.5f, assertNotNullFloat(ZoneStrips.marker(9.5, scaleMax = 18)), 1e-6f)
        assertEquals(0f, assertNotNullFloat(ZoneStrips.marker(0.0, scaleMax = 18)))
        assertNull(ZoneStrips.marker(25.0, scaleMax = 18))
        assertNull(ZoneStrips.marker(-1.0, scaleMax = 18))
    }

    @Test
    fun `slider bounds`() {
        assertEquals(18, ZoneStrips.sliderMax(listOf(14, 18, 17)))
        assertEquals(1, ZoneStrips.sliderMax(emptyList()))
        assertEquals(15, ZoneStrips.defaultMax(currentTileZoom = 13, extra = 2, sliderMax = 18))
        assertEquals(18, ZoneStrips.defaultMax(currentTileZoom = 17, extra = 2, sliderMax = 18))
        assertTrue(ZoneStrips.defaultMax(currentTileZoom = -3, extra = 0, sliderMax = 18) >= 0)
    }

    private fun assertNotNullFloat(value: Float?): Float = checkNotNull(value) { "expected a marker" }
}
