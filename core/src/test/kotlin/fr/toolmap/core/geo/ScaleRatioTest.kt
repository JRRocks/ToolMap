package fr.toolmap.core.geo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScaleRatioTest {
    @Test
    fun `at the equator and zoom 0 the ratio is the world over one printed dp`() {
        // 40 075 016.69 m over 256 px, over the 0.158 75 mm of a dp: 156 543.03 m / 0.000 158 75 m.
        assertEquals(986_097_851.5, ScaleBar.denominator(0.0, 0.0, 1.0), 1.0)
    }

    @Test
    fun `each displayed zoom level halves the ratio`() {
        val at16 = ScaleBar.denominator(46.13, 16.0, 2.625)
        assertEquals(at16 / 2, ScaleBar.denominator(46.13, 17.0, 2.625), 0.5)
    }

    @Test
    fun `near 46 degrees at zoom 16 on a 2_625 density screen the ratio is about 1 to 27_400`() {
        val denominator = ScaleBar.denominator(46.13, 16.0, 2.625)
        assertTrue(abs(denominator - 27_410) < 100, "$denominator")
    }

    @Test
    fun `the ratio is written with a comma between thousands and no decimals`() {
        assertEquals("1:27,373", ScaleBar.ratioText(27_372.7))
        assertEquals("1:986,097,852", ScaleBar.ratioText(986_097_851.5))
        assertEquals("1:500", ScaleBar.ratioText(500.0))
    }

    @Test
    fun `the bar at zoom 16 near 46 degrees is 500 m long and about 115 dp wide`() {
        val bar = ScaleBar.forDisplayedZoom(46.13, 16.0, 2.625, maxWidthDp = 120.0)
        assertEquals(500.0, bar.meters)
        assertEquals("500 m", bar.label)
        assertTrue(abs(bar.widthDp - 115) < 1.5, "${bar.widthDp}")
    }

    @Test
    fun `the bar and the ratio agree, its printed length times the ratio is its ground length`() {
        val denominator = ScaleBar.denominator(46.13, 16.0, 2.625)
        val bar = ScaleBar.forDisplayedZoom(46.13, 16.0, 2.625, maxWidthDp = 120.0)
        assertEquals(bar.meters, bar.widthDp * (0.0254 / 160.0) * denominator, 1e-6)
    }
}
