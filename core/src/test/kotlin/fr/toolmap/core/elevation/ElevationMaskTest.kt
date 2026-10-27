package fr.toolmap.core.elevation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElevationMaskTest {
    @Test
    fun `the band travels in the tile URL query and back`() {
        val masks =
            listOf(
                ElevationMask(1500, null),
                ElevationMask(null, 1200),
                ElevationMask(-50, 2000, 8..16),
                ElevationMask(1500, null, 14..30),
            )
        for (mask in masks) assertEquals(mask, ElevationMask.parse(mask.query()))
        assertEquals("alt=1500,", ElevationMask(1500, null).query())
        assertEquals("alt=,1200&whole=8,16", ElevationMask(null, 1200, 8..16).query())
    }

    @Test
    fun `a query without a band or with a broken one is a plain tile`() {
        assertNull(ElevationMask.parse(null))
        assertNull(ElevationMask.parse(""))
        assertNull(ElevationMask.parse("apikey=abc"))
        assertNull(ElevationMask.parse("alt=abc,"))
        assertNull(ElevationMask.parse("alt=1500"))
        assertNull(ElevationMask.parse("alt=1500,&whole=8"))
    }

    @Test
    fun `tiles of the whole zooms are drawn whole`() {
        val mask = ElevationMask(1500, null, 8..16)
        assertTrue(mask.isWholeAt(8))
        assertTrue(mask.isWholeAt(16))
        assertFalse(mask.isWholeAt(7))
        assertFalse(mask.isWholeAt(17))
        assertFalse(ElevationMask(1500, null).isWholeAt(12))
        assertTrue(ElevationMask(null, null).isWholeAt(12))
    }

    @Test
    fun `coverage of a tile from its lowest and highest elevations`() {
        val above = ElevationMask(1500, null)
        assertEquals(ElevationMask.Coverage.NONE, above.coverage(200f, 1499f))
        assertEquals(ElevationMask.Coverage.ALL, above.coverage(1500f, 2800f))
        assertEquals(ElevationMask.Coverage.PART, above.coverage(1200f, 1800f))
        val band = ElevationMask(1000, 2000)
        assertEquals(ElevationMask.Coverage.NONE, band.coverage(2100f, 3000f))
        assertEquals(ElevationMask.Coverage.ALL, band.coverage(1100f, 1900f))
        assertEquals(ElevationMask.Coverage.PART, band.coverage(1900f, 2100f))
    }

    @Test
    fun `the DEM window of a tile finer than the DEM is a cell of a coarser tile`() {
        assertEquals(DemWindow(10, 530, 365, 0, 0, 0), DemWindow.of(10, 530, 365))
        assertEquals(DemWindow(13, 4250, 2925, 0, 0, 0), DemWindow.of(13, 4250, 2925))
        // z16 tile (34003, 23403) lies in z13 tile (4250, 2925), cell (3, 3) of 8 × 8.
        assertEquals(DemWindow(13, 4250, 2925, 3, 3, 3), DemWindow.of(16, 34003, 23403))
    }

    /** A 4 × 4 DEM whose elevation grows by 100 m per pixel to the east: 0, 100, 200, 300 on every row. */
    private val ramp = FloatArray(16) { i -> (i % 4) * 100f }

    @Test
    fun `elevations are interpolated between DEM pixel centres`() {
        val same = MaskRaster.elevations(ramp, 4, DemWindow.of(5, 0, 0, maxDemZoom = 5), 4)
        assertEquals(listOf(0f, 100f, 200f, 300f), same.take(4))
        // An 8 px tile over the same DEM: half-pixel steps, the border pixels repeated beyond the centres.
        val finer = MaskRaster.elevations(ramp, 4, DemWindow.of(5, 0, 0, maxDemZoom = 5), 8)
        assertEquals(listOf(0f, 25f, 75f, 125f, 175f, 225f, 275f, 300f), finer.take(8))
        // The eastern half of the DEM, one zoom finer: 4 px from 150 m to 300 m.
        val cell = MaskRaster.elevations(ramp, 4, DemWindow.of(6, 1, 0, maxDemZoom = 5), 4)
        assertEquals(listOf(175f, 225f, 275f, 300f), cell.take(4))
    }

    @Test
    fun `the range of a window includes the pixels its interpolation reads`() {
        assertEquals(0f to 300f, MaskRaster.range(ramp, 4, DemWindow.of(5, 0, 0, maxDemZoom = 5)))
        // Eastern half (columns 2 and 3) plus one pixel to the west (column 1).
        assertEquals(100f to 300f, MaskRaster.range(ramp, 4, DemWindow.of(6, 1, 0, maxDemZoom = 5)))
    }

    @Test
    fun `alphas are opaque inside the band, clear outside, with a one pixel soft edge`() {
        val elevations = FloatArray(64) { i -> 1460f + (i % 8) * 20f } // 1460 .. 1600 m eastwards, 20 m per pixel
        val alphas = MaskRaster.alphas(elevations, 8, ElevationMask(1500, null)).map { it.toInt() and 0xFF }
        // Column 1 (1480 m) is a pixel below, column 2 (1500 m) on the contour, column 3 (1520 m) a pixel above.
        assertEquals(listOf(0, 0, 128, 255, 255, 255, 255, 255), alphas.take(8))
        assertEquals(alphas.take(8), alphas.takeLast(8))
    }

    @Test
    fun `an upper bound clears the pixels above it`() {
        val elevations = FloatArray(16) { i -> 900f + (i % 4) * 100f } // 900, 1000, 1100, 1200 m eastwards
        val alphas = MaskRaster.alphas(elevations, 4, ElevationMask(null, 1000)).map { it.toInt() and 0xFF }
        assertEquals(255, alphas[0])
        assertEquals(128, alphas[1])
        assertEquals(0, alphas[2])
        assertEquals(0, alphas[3])
    }
}
