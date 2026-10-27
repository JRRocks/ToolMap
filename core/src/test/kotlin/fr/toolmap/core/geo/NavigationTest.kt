package fr.toolmap.core.geo

import fr.toolmap.core.track.Geodesy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigationTest {
    @Test
    fun `bearings along the axes`() {
        assertEquals(0.0, Geodesy.bearing(45.0, 6.0, 46.0, 6.0), 1e-9)
        assertEquals(180.0, Geodesy.bearing(45.0, 6.0, 44.0, 6.0), 1e-9)
        assertEquals(90.0, Geodesy.bearing(0.0, 6.0, 0.0, 7.0), 1e-9)
        assertEquals(270.0, Geodesy.bearing(0.0, 6.0, 0.0, 5.0), 1e-9)
        // Paris to New York: initial bearing about 291.8 degrees.
        assertEquals(291.8, Geodesy.bearing(48.8566, 2.3522, 40.7128, -74.0060), 0.2)
    }

    @Test
    fun `relative bearing is the short way round`() {
        assertEquals(10.0, Geodesy.relativeBearing(350.0, 0.0), 1e-9)
        assertEquals(-10.0, Geodesy.relativeBearing(10.0, 0.0), 1e-9)
        assertEquals(-180.0, Geodesy.relativeBearing(0.0, 180.0), 1e-9)
        assertEquals(90.0, Geodesy.relativeBearing(0.0, 90.0), 1e-9)
        assertEquals(0.0, Geodesy.relativeBearing(123.0, 123.0), 1e-9)
    }

    @Test
    fun `guidance gives distance bearing and the turn to make`() {
        val target = NavTarget(45.01, 6.0, "Col")
        val g = Navigation.guide(45.0, 6.0, target, headingDegrees = 90.0)
        assertEquals(1112.0, g.distanceMeters, 5.0)
        assertEquals(0.0, g.bearingDegrees, 1e-6)
        assertEquals(-90.0, g.relativeDegrees!!, 1e-6)
        assertNull(Navigation.guide(45.0, 6.0, target).relativeDegrees)
        assertFalse(Navigation.arrived(g))
        assertTrue(Navigation.arrived(Navigation.guide(45.0, 6.0, NavTarget(45.00005, 6.0))))
    }

    @Test
    fun `compass points`() {
        assertEquals("N", Navigation.compassPoint(0.0))
        assertEquals("N", Navigation.compassPoint(359.0))
        assertEquals("N", Navigation.compassPoint(22.4))
        assertEquals("NE", Navigation.compassPoint(22.6))
        assertEquals("E", Navigation.compassPoint(90.0))
        assertEquals("S", Navigation.compassPoint(180.0))
        assertEquals("W", Navigation.compassPoint(270.0))
        assertEquals("NW", Navigation.compassPoint(-45.0))
    }
}
