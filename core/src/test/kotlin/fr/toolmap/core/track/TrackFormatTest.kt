package fr.toolmap.core.track

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackFormatTest {
    private val fr = Locale.FRANCE

    @Test
    fun `distances switch to kilometres at 1 km and lose a decimal at 10 km`() {
        assertEquals("0 m", TrackFormat.distance(0.0, fr))
        assertEquals("850 m", TrackFormat.distance(849.6, fr))
        assertEquals("1,00 km", TrackFormat.distance(1000.0, fr))
        assertEquals("9,99 km", TrackFormat.distance(9994.0, fr))
        assertEquals("12,3 km", TrackFormat.distance(12_345.0, fr))
    }

    @Test
    fun `durations are hours and minutes`() {
        assertEquals("0 min", TrackFormat.duration(10_000))
        assertEquals("45 min", TrackFormat.duration(45 * 60_000L))
        assertEquals("2 h 05", TrackFormat.duration((2 * 60 + 5) * 60_000L))
        assertEquals("26 h 10", TrackFormat.duration((26 * 60 + 10) * 60_000L))
        // 59 min 40 s rounds up to the hour.
        assertEquals("1 h 00", TrackFormat.duration(59 * 60_000L + 40_000L))
    }

    @Test
    fun `speeds, elevations and grades`() {
        assertEquals("3,6 km/h", TrackFormat.speed(1.0, fr))
        assertEquals("1234 m", TrackFormat.elevation(1233.6, fr))
        assertEquals("+12 %", TrackFormat.grade(12.4, fr))
        assertEquals("-4 %", TrackFormat.grade(-4.0, Locale.ROOT))
    }
}
