package fr.toolmap.core.maps

import fr.toolmap.core.download.ZoneShape
import fr.toolmap.core.track.Geodesy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SaveCorridorTest {
    private val line = listOf(ZoneShape.Point(5.9, 45.4), ZoneShape.Point(5.95, 45.45), ZoneShape.Point(6.0, 45.42))

    @Test
    fun `the slider goes from 50 m to 5 km, by 50 m up to 1 km and by 250 m above`() {
        assertEquals(50, CorridorWidth.fromFraction(0.0))
        assertEquals(5_000, CorridorWidth.fromFraction(1.0))
        assertEquals(500, CorridorWidth.fromFraction(0.5))
        for (step in 0..100) {
            val m = CorridorWidth.fromFraction(step / 100.0)
            assertTrue(m in 50..5_000)
            assertEquals(0, m % if (m <= 1_000) 50 else 250, "$m is on its step")
        }
        assertEquals(1_250, CorridorWidth.round(1_180.0))
        assertEquals(750, CorridorWidth.round(760.0))
    }

    @Test
    fun `the slider stands where its width is`() {
        for (m in listOf(50, 100, 500, 1_000, 2_500, 5_000)) {
            assertEquals(m, CorridorWidth.fromFraction(CorridorWidth.toFraction(m)))
        }
        assertEquals(0.0, CorridorWidth.toFraction(10), 1e-9)
        assertEquals(1.0, CorridorWidth.toFraction(9_000), 1e-9)
        assertEquals(50, CorridorWidth.clamp(3))
        assertEquals(777, CorridorWidth.clamp(777))
    }

    @Test
    fun `the band is a corridor of its width, inside its rectangle`() {
        val band = AreaCorridor("Tour du Granier", line, 500)
        val shape = band.toShape()
        assertEquals(line, shape.points)
        assertEquals(500.0, shape.radiusMeters)
        val box = band.bounds()
        // 500 m beyond the southernmost point, and beyond the westernmost one.
        assertEquals(500.0, Geodesy.distance(45.4, 5.9, box.south, 5.9), 1.0)
        assertTrue(Geodesy.distance(45.4, 5.9, 45.4, box.west) >= 499.0)
        assertTrue(box.north > 45.45 && box.east > 6.0)
    }
}
