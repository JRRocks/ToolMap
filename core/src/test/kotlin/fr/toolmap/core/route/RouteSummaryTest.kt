package fr.toolmap.core.route

import fr.toolmap.core.track.TrackPoint
import fr.toolmap.core.track.TrackStatsCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteSummaryTest {
    private val a = RouteStop(45.0, 6.0)
    private val b = RouteStop(45.01, 6.0)
    private val c = RouteStop(45.02, 6.0)

    private fun routed(
        from: RouteStop,
        to: RouteStop,
        profile: String,
        time: Long?,
        state: LegState = LegState.OK,
    ) = RouteLeg(profile, LegMode.ROUTED, state, listOf(from.point, to.point), time)

    @Test
    fun `an empty route has no figures`() {
        assertEquals(RouteSummary.EMPTY, RouteSummary.of(RoutePlan(), RoutingProfiles.HIKING))
    }

    @Test
    fun `walking takes the DIN time of the whole route`() {
        val plan =
            RoutePlan(
                listOf(a, b, c),
                listOf(routed(a, b, RoutingProfiles.HIKING, 1), routed(b, c, RoutingProfiles.HIKING, 1)),
            )
        val s = RouteSummary.of(plan, RoutingProfiles.HIKING)
        val expected = TrackStatsCalculator.compute(listOf(plan.geometry)).estimatedWalkMillis
        assertEquals(expected, s.durationMillis)
        assertFalse(s.durationIsMinimum)
        assertFalse(s.hasElevation)
        assertEquals(TrackStatsCalculator.compute(listOf(plan.geometry)).distance2dMeters, s.lengthMeters, 1e-6)
    }

    @Test
    fun `other profiles add up BRouter's times, a minimum when a leg has none`() {
        val full =
            RoutePlan(
                listOf(a, b, c),
                listOf(routed(a, b, RoutingProfiles.BIKE, 600_000), routed(b, c, RoutingProfiles.BIKE, 300_000)),
            )
        val s = RouteSummary.of(full, RoutingProfiles.BIKE)
        assertEquals(900_000L, s.durationMillis)
        assertFalse(s.durationIsMinimum)

        val partial = full.copy(legs = listOf(full.legs[0], RouteLeg.straight(b, c, RoutingProfiles.BIKE)))
        val p = RouteSummary.of(partial, RoutingProfiles.BIKE)
        assertEquals(600_000L, p.durationMillis)
        assertTrue(p.durationIsMinimum)
    }

    @Test
    fun `no time at all is no duration, not a minimum`() {
        val plan = RoutePlan(listOf(a, b), listOf(RouteLeg.straight(a, b, RoutingProfiles.BIKE)))
        val s = RouteSummary.of(plan, RoutingProfiles.BIKE)
        assertNull(s.durationMillis)
        assertFalse(s.durationIsMinimum)
    }

    @Test
    fun `legs being computed are counted`() {
        val plan =
            RoutePlan(
                listOf(a, b, c),
                listOf(
                    routed(a, b, RoutingProfiles.HIKING, null, LegState.PENDING),
                    routed(b, c, RoutingProfiles.HIKING, 1),
                ),
            )
        assertEquals(1, RouteSummary.of(plan, RoutingProfiles.HIKING).pendingLegs)
    }

    @Test
    fun `climb and descent come from the elevations`() {
        val up = listOf(TrackPoint(45.0, 6.0, 1000.0), TrackPoint(45.005, 6.0, 1200.0), TrackPoint(45.01, 6.0, 1400.0))
        val plan = RoutePlan(listOf(a, b), listOf(RouteLeg(RoutingProfiles.HIKING, LegMode.ROUTED, LegState.OK, up, 1)))
        val s = RouteSummary.of(plan, RoutingProfiles.HIKING)
        assertTrue(s.hasElevation)
        assertTrue(s.ascentMeters > 300.0)
        assertEquals(0.0, s.descentMeters, 1e-6)
    }

    @Test
    fun `a saved route gives its plan's profile, a route by hand only its length`() {
        val plan =
            RoutePlan(
                listOf(a, b, c),
                listOf(routed(a, b, RoutingProfiles.HIKING, 1), routed(b, c, RoutingProfiles.BIKE, 60_000)),
            )
        val saved = SavedRouteInfo.of(plan.geometry, RoutePlanGpx.attach(emptyList(), plan))
        assertEquals(RoutingProfiles.BIKE, saved.profile)
        assertEquals(RouteSummary.of(plan, RoutingProfiles.BIKE), saved.summary)

        val byHand = SavedRouteInfo.of(listOf(a.point, b.point, c.point), emptyList())
        assertNull(byHand.profile)
        assertNull(byHand.summary.durationMillis)
        assertEquals(
            TrackStatsCalculator.compute(listOf(listOf(a.point, b.point, c.point))).distance2dMeters,
            byHand.summary.lengthMeters,
            1e-6,
        )
    }
}
