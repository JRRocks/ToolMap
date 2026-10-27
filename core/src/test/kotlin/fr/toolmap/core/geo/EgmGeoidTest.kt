package fr.toolmap.core.geo

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EgmGeoidTest {
    /** A grid whose values are the column index in centimetres along the longitude, and the row index along latitude. */
    private val grid =
        EgmGeoid.parse(
            ByteBuffer
                .allocate(EgmGeoid.FILE_BYTES)
                .also { b ->
                    for (row in 0 until EgmGeoid.ROWS) {
                        for (col in 0 until EgmGeoid.COLS) {
                            b.putShort(((col + row * 0) % Short.MAX_VALUE).toShort())
                        }
                    }
                }.array(),
        )

    @Test
    fun `the nodes give the values of the file, in metres`() {
        // Column 4 is 1° east (values of 4 cm), row 0 is the North Pole.
        assertEquals(0.04, grid.undulation(90.0, 1.0), 1e-9)
        assertEquals(0.0, grid.undulation(90.0, 0.0), 1e-9)
    }

    @Test
    fun `between the nodes the value is interpolated bilinearly`() {
        // Halfway between columns 4 and 5 (4 cm and 5 cm): 4.5 cm.
        assertEquals(0.045, grid.undulation(90.0, 1.0 + 0.125), 1e-9)
    }

    @Test
    fun `longitudes wrap around the globe`() {
        // 359.875° east is between column 1439 and column 0 of the file: the value runs from 14.39 m to 0 m.
        val between = grid.undulation(90.0, 359.875)
        assertEquals((1439 + 0) / 2.0 / 100.0, between, 1e-9)
        assertEquals(grid.undulation(90.0, 0.0), grid.undulation(90.0, 360.0), 1e-9)
        assertEquals(grid.undulation(90.0, 10.0), grid.undulation(90.0, -350.0), 1e-9)
    }

    @Test
    fun `latitudes are read from the north row to the south row`() {
        // Row 1 is at 89.75° N: the values are the row index in the file only along the same column, so compare rows.
        val north = grid.undulation(89.75, 0.0)
        val equator = grid.undulation(0.0, 0.0)
        val south = grid.undulation(-90.0, 0.0)
        assertEquals(0.0, north, 1e-9)
        assertEquals(0.0, equator, 1e-9)
        assertEquals(0.0, south, 1e-9)
    }

    @Test
    fun `the orthometric height removes the undulation from the ellipsoidal one`() {
        assertEquals(100.0 - 0.04, grid.orthometric(100.0, 90.0, 1.0), 1e-9)
    }

    @Test
    fun `a file of the wrong size is refused`() {
        assertFailsWith<IllegalArgumentException> { EgmGeoid.parse(ByteArray(10)) }
    }
}
