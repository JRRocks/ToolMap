package fr.toolmap.core.track

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackTimeTest {
    private val noon = 1_784_030_400_000L // 2026-07-14T12:00:00Z

    @Test
    fun `instants, offsets and local times give the same moment`() {
        assertEquals(noon, TrackTime.parse("2026-07-14T12:00:00Z"))
        assertEquals(noon, TrackTime.parse(" 2026-07-14T12:00:00.000Z "))
        assertEquals(noon, TrackTime.parse("2026-07-14T14:00:00+02:00"))
        assertEquals(noon, TrackTime.parse("2026-07-14T07:00:00-05:00"))
        assertEquals(noon + 250, TrackTime.parse("2026-07-14T14:00:00.250+02:00"))
        // No offset: UTC.
        assertEquals(noon, TrackTime.parse("2026-07-14T12:00:00"))
        assertEquals(noon + 123, TrackTime.parse("2026-07-14T12:00:00.123456789"))
        assertEquals("2026-07-14T12:00:00Z", TrackTime.format(noon))
    }

    @Test
    fun `what is not a date-time has no time`() {
        assertNull(TrackTime.parse(null))
        assertNull(TrackTime.parse("  "))
        assertNull(TrackTime.parse("yesterday"))
        assertNull(TrackTime.parse("2026-07-14"))
        assertNull(TrackTime.parse("2026-07-14T25:00:00Z"))
        assertNull(TrackTime.parse("2026-07-14T12:00:00+99:00"))
    }
}
