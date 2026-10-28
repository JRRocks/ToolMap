package fr.toolmap.core.route

import fr.toolmap.core.track.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoToTest {
    private val rules = GoToRules()

    /** About 1.1 km due north, a point every 0.001° (111 m), climbing 10 m from one to the next. */
    private fun north(
        profile: String = RoutingProfiles.HIKING,
        time: Long? = null,
    ) = GoToWay(
        List(11) { TrackPoint(45.0 + it * 0.001, 6.0, 1000.0 + it * 10) },
        profile,
        time,
        computedAtMillis = 0L,
    )

    @Test
    fun `on the way, what remains is measured along it and the arrow leads ahead`() {
        val way = north()
        val start = assertNotNull(GoTo.progress(way, 45.0, 6.0, rules))
        assertEquals(way.totalMeters, start.remainingMeters, 1.0)
        assertEquals(100.0, assertNotNull(start.remainingAscentMeters), 15.0)
        assertTrue(assertNotNull(start.remainingMillis) > 0)
        assertFalse(start.offRoute)
        assertFalse(start.arrived)
        assertTrue(start.headingDegrees < 5 || start.headingDegrees > 355, "north: ${start.headingDegrees}")

        val half = assertNotNull(GoTo.progress(way, 45.005, 6.0, rules))
        assertEquals(way.totalMeters / 2, half.remainingMeters, 2.0)
        assertTrue(assertNotNull(half.remainingAscentMeters) < start.remainingAscentMeters!!)
    }

    @Test
    fun `off the way, it says so and the arrow leads back to it`() {
        // 0.001° of longitude at 45° is about 79 m east of the way.
        val off = assertNotNull(GoTo.progress(north(), 45.005, 6.001, rules))
        assertEquals(79.0, off.offMeters, 2.0)
        assertTrue(off.offRoute)
        assertEquals(270.0, off.headingDegrees, 5.0)
        assertFalse(assertNotNull(GoTo.progress(north(), 45.005, 6.0003, rules)).offRoute, "24 m off is still on it")
    }

    @Test
    fun `a profile that is not walking takes BRouter's time, in proportion`() {
        val way = north(RoutingProfiles.BIKE, time = 600_000L)
        val half = assertNotNull(GoTo.progress(way, 45.005, 6.0, rules))
        assertEquals(300_000.0, assertNotNull(half.remainingMillis).toDouble(), 5_000.0)
        assertNull(assertNotNull(GoTo.progress(north(RoutingProfiles.BIKE), 45.005, 6.0, rules)).remainingMillis)
    }

    @Test
    fun `BRouter is asked again at the interval, at once off the way, and never once arrived`() {
        val way = north()
        val on = GoTo.progress(way, 45.005, 6.0, rules)
        val off = GoTo.progress(way, 45.005, 6.001, rules)
        val end = assertNotNull(GoTo.progress(way, 45.01, 6.0, rules))
        assertTrue(end.arrived)
        assertTrue(GoTo.needsWay(null, null, null, 0L, rules), "no way yet")
        assertFalse(GoTo.needsWay(way, on, 0L, 60_000L, rules))
        assertTrue(GoTo.needsWay(way, on, 0L, 120_000L, rules), "the interval elapsed")
        assertFalse(GoTo.needsWay(way, off, 0L, 10_000L, rules), "off the way, but just asked")
        assertTrue(GoTo.needsWay(way, off, 0L, 20_000L, rules), "off the way")
        assertFalse(GoTo.needsWay(way, end, 0L, 999_000L, rules), "arrived")
        // A question that gave no way (no data here) is asked again at the next interval, not at each position.
        assertFalse(GoTo.needsWay(null, null, 0L, 60_000L, rules))
        assertTrue(GoTo.needsWay(null, null, 0L, 120_000L, rules))
    }

    @Test
    fun `a walker far from any path when the way was computed is not off it`() {
        // Computed from 79 m east of the way: off the way only 50 m beyond that.
        val way = GoToWay(north().points, RoutingProfiles.HIKING, null, 0L, from = TrackPoint(45.0, 6.001))
        assertEquals(79.0, way.startOffMeters, 2.0)
        assertFalse(assertNotNull(GoTo.progress(way, 45.0, 6.001, rules)).offRoute)
        assertTrue(assertNotNull(GoTo.progress(way, 45.0, 6.0018, rules)).offRoute)
    }

    @Test
    fun `the settings give the rules, and the time before the next question reads in minutes and seconds`() {
        assertEquals(GoToRules(300_000L, 100.0), GoToRules.of(5, 100))
        assertEquals(GoToRules(120_000L, 50.0), GoToRules.of(3, 60), "the nearest values offered")
        assertEquals("1 min 40", GoTo.countdown(99_500L))
        assertEquals("40 s", GoTo.countdown(40_000L))
        assertEquals("2 min", GoTo.countdown(120_000L))
        assertEquals("0 s", GoTo.countdown(-5L))
    }

    @Test
    fun `the bearing is counted clockwise from the north`() {
        assertEquals(90.0, GoTo.bearing(45.0, 6.0, 45.0, 6.01), 0.5)
        assertEquals(180.0, GoTo.bearing(45.0, 6.0, 44.99, 6.0), 0.5)
        assertEquals(315.0, GoTo.bearing(45.0, 6.0, 45.001, 6.0 - 0.001 / Math.cos(Math.toRadians(45.0))), 1.0)
    }
}
