package fr.toolmap.core.route

import fr.toolmap.core.track.Geodesy
import fr.toolmap.core.track.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HandLegElevationsTest {
    // Chartreuse, then south out of BRouter's E5_N45 tile: about 2.2 km, then 9 km.
    private val a = RouteStop(45.0, 6.08)
    private val b = RouteStop(45.02, 6.08)
    private val c = RouteStop(44.94, 6.08)

    private fun routed(
        from: RouteStop,
        to: RouteStop,
    ) = RouteLeg(
        RoutingProfiles.HIKING,
        LegMode.ROUTED,
        LegState.OK,
        listOf(TrackPoint(from.lat, from.lon, 500.0), TrackPoint(to.lat, to.lon, 700.0)),
        1,
    )

    /** A ridge 400 m above both ends, halfway along. */
    private fun ridge(points: List<TrackPoint>): List<TrackPoint> {
        val total = Geodesy.distance(points.first(), points.last())
        return points.map { p ->
            val t = Geodesy.distance(points.first(), p) / total
            p.copy(ele = 700.0 + 400.0 * (1 - kotlin.math.abs(2 * t - 1)))
        }
    }

    @Test
    fun `samples run straight on the map from one stop to the other`() {
        val s = HandLegElevations.samples(b.point, c.point)
        assertEquals(b.point, s.first())
        assertEquals(c.point, s.last())
        val step = Geodesy.distance(b.point, c.point) / (s.size - 1)
        assertTrue(step <= HandLegElevations.SPACING_METERS, "step $step")
        assertTrue(step > HandLegElevations.SPACING_METERS * 0.9, "step $step")
        s.zipWithNext().forEach { (p, q) -> assertTrue(q.lat < p.lat) }
        assertTrue(s.all { it.lon == 6.08 && it.ele == null })
    }

    @Test
    fun `a very long leg stays a few hundred points`() {
        val far = TrackPoint(44.0, 7.0)
        val s = HandLegElevations.samples(a.point, far)
        assertEquals(HandLegElevations.MAX_POINTS, s.size)
        assertEquals(far, s.last())
    }

    @Test
    fun `a straight leg in a routed route is sampled along its line`() {
        val plan =
            RoutePlan(
                listOf(a, b, c),
                listOf(routed(a, b), RouteLeg.straight(b, c, RoutingProfiles.HIKING, LegMode.ROUTED, LegState.NO_DATA)),
            )
        val e = HandLegElevations()
        val wanted = e.wanted(plan)
        assertEquals(1, wanted.size)
        val (key, points) = wanted.entries.single()
        assertEquals(HandLegElevations.sampleCount(b.point, c.point), points.size)

        e.put(key, ridge(points))
        val filled = e.apply(plan)
        assertSame(plan.legs[0], filled.legs[0])
        assertEquals(LegState.NO_DATA, filled.legs[1].state)
        assertEquals(points.size, filled.legs[1].points.size)
        assertTrue(filled.geometry.all { it.ele != null })
        assertTrue(e.wanted(filled).isEmpty())

        // The ridge counts: from 700 m up to 1100 m and back down, plus the 200 m of the routed leg.
        val s = RouteSummary.of(filled, RoutingProfiles.HIKING)
        assertTrue(s.hasElevation)
        assertTrue(s.ascentMeters > 500.0, "ascent ${s.ascentMeters}")
        assertTrue(s.descentMeters > 300.0, "descent ${s.descentMeters}")
    }

    @Test
    fun `a filled plan goes back and forth through the GPX with its samples`() {
        val plan =
            RoutePlan(
                listOf(a, b, c),
                listOf(routed(a, b), RouteLeg.straight(b, c, RoutingProfiles.HIKING, LegMode.STRAIGHT)),
            )
        val e = HandLegElevations()
        e.wanted(plan).forEach { (k, p) -> e.put(k, ridge(p)) }
        val filled = e.apply(plan)
        val points = RoutePlanGpx.routePoints(filled).map { TrackPoint(it.lat, it.lon, it.ele) }
        val back = RoutePlanGpx.planOf(points, RoutePlanGpx.attach(emptyList(), filled))
        assertEquals(filled, back)
        assertTrue(HandLegElevations().wanted(back!!).isEmpty())
    }

    @Test
    fun `a route wholly by hand only gives its stops an elevation`() {
        val plan =
            RoutePlan(
                listOf(b, c),
                listOf(RouteLeg.straight(b, c, RoutingProfiles.HIKING)),
            )
        val e = HandLegElevations()
        val (key, points) = e.wanted(plan).entries.single()
        assertEquals(listOf(b.point, c.point), points)
        e.put(key, listOf(b.point.copy(ele = 700.0), c.point.copy(ele = 650.0)))
        val filled = e.apply(plan)
        assertEquals(listOf(700.0, 650.0), filled.geometry.map { it.ele })
        assertEquals(listOf(700.0, 650.0), RoutePlanGpx.stopPoints(filled).map { it.ele })
        assertTrue(e.wanted(filled).isEmpty())

        // Followed by a routed leg, the line is then sampled.
        val mixed = RoutePlan(listOf(b, c, a), filled.legs + routed(c, a))
        assertTrue(e.wanted(mixed).values.single().size > 2)
    }

    @Test
    fun `routed legs and legs being computed are left to BRouter`() {
        val plan =
            RoutePlan(
                listOf(a, b, c),
                listOf(routed(a, b), RouteLeg.straight(b, c, RoutingProfiles.HIKING, LegMode.ROUTED, LegState.PENDING)),
            )
        assertTrue(HandLegElevations().wanted(plan).isEmpty())
    }

    @Test
    fun `a leg turned round is known already`() {
        val e = HandLegElevations()
        val there = RoutePlan(listOf(a, b), listOf(RouteLeg.straight(a, b, RoutingProfiles.HIKING)))
        e.wanted(there).forEach { (k, p) -> e.put(k, p.map { it.copy(ele = 600.0 + (it.lat - 45.0) * 1e4) }) }
        val back = RoutePlan(listOf(b, a), listOf(RouteLeg.straight(b, a, RoutingProfiles.HIKING)))
        assertTrue(e.wanted(back).isEmpty())
        assertEquals(listOf(800.0, 600.0), e.apply(back).geometry.map { it.ele!! }.map { kotlin.math.round(it) })
    }

    @Test
    fun `without every tile the leg has no elevation, and is not asked again`() {
        val plan = RoutePlan(listOf(b, c), listOf(RouteLeg.straight(b, c, RoutingProfiles.HIKING)))
        val e = HandLegElevations()
        val (key, points) = e.wanted(plan).entries.single()
        e.put(key, listOf(points[0].copy(ele = 700.0), points[1]))
        assertSame(plan, e.apply(plan))
        assertTrue(e.wanted(plan).isEmpty())
        e.forgetFailures()
        assertEquals(1, e.wanted(plan).size)
    }

    @Test
    fun `the editor takes the elevations without an undo step`() {
        val editor = RoutePlanEditor(RoutePlan(listOf(b, c), listOf(RouteLeg.straight(b, c, RoutingProfiles.HIKING))))
        val e = HandLegElevations()
        assertFalse(editor.applyElevations(e))
        e.wanted(editor.plan).forEach { (k, p) -> e.put(k, p.map { it.copy(ele = 650.0) }) }
        assertTrue(editor.applyElevations(e))
        assertFalse(editor.canUndo)
        assertNull(editor.plan.geometry.firstOrNull { it.ele == null })
    }
}
