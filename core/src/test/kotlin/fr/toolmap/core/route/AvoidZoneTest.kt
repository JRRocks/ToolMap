package fr.toolmap.core.route

import fr.toolmap.core.track.Geodesy
import fr.toolmap.core.track.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvoidZoneTest {
    private val zone = AvoidZone(45.0, 6.0, 250)

    @Test
    fun `the nogos of BRouter are lon, lat and a whole radius, joined by a bar`() {
        assertEquals("", AvoidZone.nogos(emptyList()))
        assertEquals(
            "6.0000000,45.0000000,250|5.9876543,45.1234568,1000",
            AvoidZone.nogos(listOf(zone, AvoidZone(45.12345678, 5.98765432, 1000))),
        )
    }

    @Test
    fun `a way crosses the zone when one of its segments comes within the radius`() {
        // 0.002° of latitude is about 222 m: inside; 0.003° is about 333 m: outside.
        val through = listOf(TrackPoint(45.002, 5.99), TrackPoint(45.002, 6.01))
        val beside = listOf(TrackPoint(45.003, 5.99), TrackPoint(45.003, 6.01))
        assertTrue(zone.crosses(through))
        assertFalse(zone.crosses(beside))
        assertTrue(zone.crosses(listOf(TrackPoint(45.0, 6.001))))
        assertTrue(zone.contains(45.002, 6.0))
        assertFalse(zone.contains(45.003, 6.0))
    }

    @Test
    fun `a bigger circle around the same place covers the smaller one`() {
        assertTrue(AvoidZone(45.0, 6.0, 500).covers(zone))
        assertTrue(AvoidZone(45.001, 6.0, 500).covers(zone))
        assertFalse(AvoidZone(45.003, 6.0, 500).covers(zone))
        assertFalse(zone.covers(AvoidZone(45.0, 6.0, 500)))
    }

    @Test
    fun `the handle gives sizes rounded to 50 m, from 50 m to 2 km`() {
        assertEquals(250, AvoidZone.resized(262.0))
        assertEquals(300, AvoidZone.resized(276.0))
        assertEquals(50, AvoidZone.resized(3.0))
        assertEquals(2_000, AvoidZone.resized(5_000.0))
    }

    @Test
    fun `the handle sits on the circle, due east of the centre`() {
        val east = zone.east()
        assertEquals(zone.lat, east.lat, 1e-9)
        assertTrue(east.lon > zone.lon)
        assertEquals(250.0, Geodesy.distance(zone.lat, zone.lon, east.lat, east.lon), 1.0)
        val far = zone.east(1_500)
        assertEquals(1_500.0, Geodesy.distance(zone.lat, zone.lon, far.lat, far.lon), 5.0)
    }

    @Test
    fun `the zone under the crosshair is the one whose centre is nearest`() {
        val plan = RoutePlan(zones = listOf(AvoidZone(45.0, 6.0, 1000), AvoidZone(45.004, 6.0, 1000)))
        assertEquals(0, plan.zoneAt(45.001, 6.0))
        assertEquals(1, plan.zoneAt(45.003, 6.0))
        assertNull(plan.zoneAt(45.1, 6.0))
    }

    @Test
    fun `the zones are part of the key of a leg`() {
        val a = RouteStop(45.0, 6.0)
        val b = RouteStop(45.01, 6.0)
        val between = AvoidZone(45.005, 6.0, 250)
        assertEquals(LegKey.of(a, b, "p"), LegKey.of(a, b, "p", emptyList()))
        assertTrue(LegKey.of(a, b, "p") != LegKey.of(a, b, "p", listOf(between)))
    }

    @Test
    fun `a zone that holds an end of the leg is left out of it`() {
        val a = RouteStop(45.0, 6.0)
        val b = RouteStop(45.01, 6.0)
        val between = AvoidZone(45.005, 6.0, 250)
        val atB = AvoidZone(45.0101, 6.0, 100)
        // `zone` holds a: BRouter would find no way out of it.
        assertEquals(listOf(between), AvoidZone.around(listOf(zone, between, atB), a, b))
        assertEquals(LegKey.of(a, b, "p", listOf(between)), LegKey.of(a, b, "p", listOf(zone, between, atB)))
        val leg =
            RouteLeg("p", LegMode.ROUTED, LegState.PENDING, listOf(TrackPoint(a.lat, a.lon), TrackPoint(b.lat, b.lon)))
        val plan = RoutePlan(listOf(a, b), listOf(leg), listOf(zone, between))
        assertEquals(listOf(between), plan.legZones(0))
    }
}
