package fr.toolmap.core.route

import fr.toolmap.core.track.GpxReader
import fr.toolmap.core.track.GpxWriter
import fr.toolmap.core.track.RawXml
import fr.toolmap.core.track.Route
import fr.toolmap.core.track.TrackDocument
import fr.toolmap.core.track.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutePlanGpxTest {
    private fun plan(): RoutePlan {
        val a = RouteStop(45.9, 6.8, StopKind.NAMED, "Refuge <Nord> & co", "Lodge")
        val b = RouteStop(45.91, 6.81)
        val c = RouteStop(45.92, 6.79)
        val ab =
            RouteLeg(
                RoutingProfiles.HIKING,
                LegMode.ROUTED,
                LegState.OK,
                listOf(
                    TrackPoint(45.9, 6.8, 1200.0),
                    TrackPoint(45.905, 6.807, 1300.0),
                    TrackPoint(45.91, 6.81, 1350.0),
                ),
                3_600_000,
            )
        // The point shared by two legs is written once: the second leg starts on the end of the first.
        val bc = RouteLeg(RoutingProfiles.HIKING, LegMode.ROUTED, LegState.NO_DATA, listOf(ab.points.last(), c.point))
        return RoutePlan(listOf(a, b, c), listOf(ab, bc))
    }

    @Test
    fun `the plan goes through a GPX file and comes back the same`() {
        val p = plan()
        val route =
            Route(name = "Tour", points = RoutePlanGpx.routePoints(p), extras = RoutePlanGpx.attach(emptyList(), p))
        assertEquals("Refuge <Nord> & co", route.points[0].name)
        assertEquals("Lodge", route.points[0].sym)
        assertNull(route.points[1].name)
        val text = GpxWriter.toString(TrackDocument(routes = listOf(route)))
        assertTrue("<tm:plan" in text)
        val back = GpxReader.read(text.toByteArray()).routes.single()
        val read = RoutePlanGpx.planOf(back.points.map { TrackPoint(it.lat, it.lon, it.ele) }, back.extras)
        assertEquals(p, read)
    }

    @Test
    fun `a route changed elsewhere loses its plan`() {
        val p = plan()
        val extras = RoutePlanGpx.attach(emptyList(), p)
        val geometry = p.geometry
        assertNotNull(RoutePlanGpx.planOf(geometry, extras))
        assertNull(RoutePlanGpx.planOf(geometry.drop(1), extras))
        assertNull(RoutePlanGpx.planOf(geometry.map { it.copy(lat = it.lat + 0.001) }, extras))
        assertNull(RoutePlanGpx.planOf(geometry, emptyList()))
    }

    @Test
    fun `other extensions are kept and the plan is replaced, not added`() {
        val color = RawXml("<extensions><color>#FF0000</color></extensions>")
        val once = RoutePlanGpx.attach(listOf(color), plan())
        assertEquals(1, once.size)
        assertTrue(once[0].xml.startsWith("<extensions><color>#FF0000</color><tm:plan"))
        val twice = RoutePlanGpx.attach(once, plan())
        assertEquals(1, Regex("<tm:plan").findAll(twice[0].xml).count())
        assertEquals(listOf(color), RoutePlanGpx.attach(twice, null))
        assertEquals(emptyList(), RoutePlanGpx.attach(RoutePlanGpx.attach(emptyList(), plan()), null))
    }

    @Test
    fun `a single stop is a plan too`() {
        val p = RoutePlan(listOf(RouteStop(45.0, 6.0)))
        assertEquals(p, RoutePlanGpx.planOf(p.geometry, RoutePlanGpx.attach(emptyList(), p)))
    }
}
