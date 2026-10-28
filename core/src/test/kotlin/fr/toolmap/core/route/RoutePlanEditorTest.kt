package fr.toolmap.core.route

import fr.toolmap.core.track.Geodesy
import fr.toolmap.core.track.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutePlanEditorTest {
    private fun stop(
        i: Int,
        name: String? = null,
    ) = RouteStop(45.0 + i * 0.01, 6.0 + i * 0.005, if (name != null) StopKind.NAMED else StopKind.PASSAGE, name)

    /** A way through a point beside the straight line, as BRouter would give it (ends a few cm off the stops). */
    private fun way(
        from: RouteStop,
        to: RouteStop,
    ) = LegResult.Ok(
        listOf(
            TrackPoint(from.lat + 0.000001, from.lon, 1000.0),
            TrackPoint((from.lat + to.lat) / 2, (from.lon + to.lon) / 2 + 0.002, 1100.0),
            TrackPoint(to.lat, to.lon + 0.000001, 1200.0),
        ),
        60_000,
    )

    /** Answers every pending leg as BRouter would; returns how many were asked. */
    private fun RoutePlanEditor.computeAll(): Int {
        var asked = 0
        while (true) {
            val i = plan.pending.firstOrNull() ?: return asked
            asked++
            applyResult(plan.key(i), way(plan.stops[i], plan.stops[i + 1]))
        }
    }

    /** The invariant of every plan: one leg fewer than stops, legs meeting exactly on the stops. */
    private fun RoutePlan.check() {
        assertEquals(maxOf(0, stops.size - 1), legs.size)
        legs.forEachIndexed { i, leg ->
            assertEquals(stops[i].lat to stops[i].lon, leg.points.first().lat to leg.points.first().lon)
            assertEquals(stops[i + 1].lat to stops[i + 1].lon, leg.points.last().lat to leg.points.last().lon)
        }
        stopIndices.forEachIndexed { i, at ->
            assertEquals(stops[i].lat, geometry[at].lat)
            assertEquals(stops[i].lon, geometry[at].lon)
        }
    }

    private fun editorWith(count: Int): RoutePlanEditor {
        val e = RoutePlanEditor()
        repeat(count) { e.add(stop(it)) }
        e.computeAll()
        e.select(null)
        return e
    }

    @Test
    fun `adding at the end makes one new leg to compute`() {
        val e = RoutePlanEditor()
        assertTrue(e.add(stop(0)))
        assertTrue(e.plan.legs.isEmpty())
        assertEquals(1, e.plan.geometry.size)
        e.add(stop(1))
        assertEquals(listOf(0), e.plan.pending)
        assertEquals(1, e.computeAll())
        e.add(stop(2))
        assertEquals(listOf(1), e.plan.pending)
        e.computeAll()
        e.plan.check()
        assertEquals(2, e.selected)
        // The way of BRouter is kept and joined to the stops: 3 points per leg, the shared one once.
        assertEquals(5, e.plan.geometry.size)
        assertEquals(1100.0, e.plan.geometry[1].ele)
    }

    @Test
    fun `adding at the start puts the stop before the first`() {
        val e = editorWith(2)
        e.add(stop(9), atStart = true)
        assertEquals(stop(9), e.plan.stops[0])
        assertEquals(listOf(0), e.plan.pending)
        assertEquals(0, e.selected)
        // The next one goes before it again.
        e.add(stop(8), atStart = true)
        assertEquals(listOf(stop(8), stop(9), stop(0), stop(1)), e.plan.stops)
        e.computeAll()
        e.plan.check()
    }

    @Test
    fun `inserting on a leg splits it in two with its profile and mode`() {
        val e = editorWith(3)
        e.setLegProfile(1, RoutingProfiles.MTB)
        e.computeAll()
        val detour = RouteStop(45.016, 6.0)
        assertTrue(e.insertOnLeg(1, detour))
        assertEquals(listOf(stop(0), stop(1), detour, stop(2)), e.plan.stops)
        assertEquals(listOf(1, 2), e.plan.pending)
        assertEquals(RoutingProfiles.MTB, e.plan.legs[1].profile)
        assertEquals(RoutingProfiles.MTB, e.plan.legs[2].profile)
        assertEquals(LegState.OK, e.plan.legs[0].state)
        e.computeAll()
        e.plan.check()
    }

    @Test
    fun `the leg nearest to the crosshair is found along the way`() {
        val e = editorWith(3)
        val mid = e.plan.legs[1].points[1]
        val (leg, meters) = e.plan.nearestLeg(mid.lat, mid.lon)!!
        assertEquals(1, leg)
        assertTrue(meters < 1.0)
    }

    @Test
    fun `moving a stop computes both neighbours again`() {
        val e = editorWith(3)
        e.select(1)
        assertTrue(e.moveSelected(45.02, 6.02))
        assertEquals(listOf(0, 1), e.plan.pending)
        e.computeAll()
        e.plan.check()
        // The end has one neighbour only.
        e.select(2)
        e.moveSelected(45.05, 6.05)
        assertEquals(listOf(1), e.plan.pending)
    }

    @Test
    fun `a stop taken at the crosshair is put down in one undoable step`() {
        val e = editorWith(3)
        e.computeAll()
        val before = e.plan
        assertTrue(e.move(1, 45.04, 6.04))
        assertEquals(45.04, e.plan.stops[1].lat)
        assertEquals(1, e.selected)
        assertEquals(listOf(0, 1), e.plan.pending)
        assertFalse(e.move(3, 45.0, 6.0))
        assertTrue(e.undo())
        assertEquals(before, e.plan)
    }

    @Test
    fun `deleting a stop merges the two legs into one`() {
        val e = editorWith(4)
        e.select(2)
        assertTrue(e.deleteSelected())
        assertEquals(listOf(stop(0), stop(1), stop(3)), e.plan.stops)
        assertEquals(listOf(1), e.plan.pending)
        e.computeAll()
        e.plan.check()
        // The ends just lose their leg.
        assertTrue(e.delete(0))
        assertEquals(listOf(stop(1), stop(3)), e.plan.stops)
        assertTrue(e.plan.pending.isEmpty())
        assertTrue(e.delete(1))
        assertTrue(e.delete(0))
        assertTrue(e.plan.stops.isEmpty())
        assertNull(e.selected)
    }

    @Test
    fun `a leg drawn by hand is a straight line and can be routed again`() {
        val e = editorWith(3)
        assertTrue(e.setLegMode(0, LegMode.STRAIGHT))
        assertEquals(2, e.plan.legs[0].points.size)
        assertTrue(e.plan.legs[0].isByHand)
        assertTrue(e.plan.pending.isEmpty())
        assertFalse(e.setLegMode(0, LegMode.STRAIGHT))
        // Routed again: the way computed before comes back from the cache, BRouter is not asked.
        assertTrue(e.setLegMode(0, LegMode.ROUTED))
        assertTrue(e.plan.pending.isEmpty())
        assertEquals(3, e.plan.legs[0].points.size)
    }

    @Test
    fun `changing the profile of all legs computes the routed ones only`() {
        val e = editorWith(3)
        e.setLegMode(1, LegMode.STRAIGHT)
        assertTrue(e.setProfile(RoutingProfiles.BIKE, allLegs = true))
        assertEquals(listOf(0), e.plan.pending)
        assertEquals(LegMode.STRAIGHT, e.plan.legs[1].mode)
        assertEquals(RoutingProfiles.BIKE, e.profile)
        // Only the new legs: nothing changes now, the next leg takes it.
        assertFalse(e.setProfile(RoutingProfiles.CAR, allLegs = false))
        e.computeAll()
        e.add(stop(3))
        assertEquals(RoutingProfiles.CAR, e.plan.legs.last().profile)
    }

    @Test
    fun `reversing turns the stops and computes the routed legs again`() {
        val e = editorWith(3)
        e.setLegMode(1, LegMode.STRAIGHT)
        e.select(0)
        assertTrue(e.reverse())
        assertEquals(listOf(stop(2), stop(1), stop(0)), e.plan.stops)
        assertEquals(2, e.selected)
        assertEquals(LegMode.STRAIGHT, e.plan.legs[0].mode)
        assertEquals(listOf(1), e.plan.pending)
        e.computeAll()
        e.plan.check()
        // Twice: back to the ways already known, nothing asked.
        e.reverse()
        assertTrue(e.plan.pending.isEmpty())
    }

    @Test
    fun `loop and out and back add the way home`() {
        val e = editorWith(3)
        assertTrue(e.loopToStart())
        assertEquals(4, e.plan.stops.size)
        assertTrue(e.plan.isLoop)
        assertEquals(listOf(2), e.plan.pending)
        assertFalse(e.loopToStart())

        val o = editorWith(3)
        o.setLegMode(0, LegMode.STRAIGHT)
        assertTrue(o.outAndBack())
        assertEquals(listOf(stop(0), stop(1), stop(2), stop(1), stop(0)), o.plan.stops)
        assertEquals(LegMode.STRAIGHT, o.plan.legs[3].mode)
        assertEquals(listOf(2), o.plan.pending)
        o.computeAll()
        o.plan.check()
    }

    @Test
    fun `starting from a stop drops what is before, or turns a loop`() {
        val e = editorWith(4)
        assertEquals(2, e.stopsDroppedBy(2))
        assertTrue(e.startFrom(2))
        assertEquals(listOf(stop(2), stop(3)), e.plan.stops)
        assertTrue(e.plan.pending.isEmpty())

        val loop = editorWith(4)
        loop.loopToStart()
        loop.computeAll()
        val length = loop.plan.stopDistances().last()
        assertEquals(0, loop.stopsDroppedBy(2))
        assertTrue(loop.startFrom(2))
        assertEquals(listOf(stop(2), stop(3), stop(0), stop(1), stop(2)), loop.plan.stops)
        assertTrue(loop.plan.pending.isEmpty())
        loop.plan.check()
        assertEquals(length, loop.plan.stopDistances().last(), 1e-6)
    }

    @Test
    fun `undo and redo cover everything and never ask again`() {
        val e = editorWith(3)
        e.select(1)
        e.moveSelected(45.03, 6.03)
        e.computeAll()
        val moved = e.plan
        assertTrue(e.undo())
        assertTrue(e.plan.pending.isEmpty())
        assertEquals(stop(1), e.plan.stops[1])
        assertTrue(e.redo())
        assertEquals(moved, e.plan)
        e.setProfile(RoutingProfiles.BIKE, allLegs = true)
        assertEquals(2, e.computeAll())
        e.undo()
        assertEquals(RoutingProfiles.HIKING, e.plan.legs[0].profile)
        e.redo()
        assertEquals(0, e.plan.pending.size)
    }

    @Test
    fun `a pending leg restored by undo is filled from the cache`() {
        val e = editorWith(2)
        e.select(1)
        e.moveSelected(45.05, 6.05)
        // Moved back before BRouter answered, then forward again: that way was never computed.
        e.undo()
        e.redo()
        assertEquals(listOf(0), e.plan.pending)
        e.computeAll()
        e.undo()
        e.undo()
        e.redo()
        e.redo()
        assertFalse(e.fillFromCache())
        assertTrue(e.plan.pending.isEmpty())
    }

    @Test
    fun `an answer for a leg changed meanwhile is not applied`() {
        val e = editorWith(2)
        e.add(stop(2))
        val key = e.plan.key(1)
        e.select(2)
        e.moveSelected(45.09, 6.09)
        assertFalse(e.applyResult(key, way(stop(1), stop(2))))
        assertEquals(listOf(1), e.plan.pending)
    }

    @Test
    fun `no data turns the leg into a straight line that can be recomputed`() {
        val e = editorWith(2)
        e.add(stop(2))
        e.applyResult(e.plan.key(1), LegResult.Failed(RoutingError.NO_DATA, "datafile E5_N45.rd5 not found"))
        assertEquals(LegState.NO_DATA, e.plan.legs[1].state)
        assertTrue(e.plan.legs[1].isByHand)
        assertTrue(e.recompute(1))
        assertEquals(listOf(1), e.plan.pending)
        assertFalse(e.recompute(0))
    }

    @Test
    fun `new legs are drawn by hand when the routing is off`() {
        val e = editorWith(2)
        e.routing = false
        e.add(stop(2))
        assertEquals(LegMode.STRAIGHT, e.plan.legs[1].mode)
        assertTrue(e.plan.pending.isEmpty())
        assertFalse(e.plan.isManual)
        e.routing = true
        // A route drawn by hand all along follows the ways when asked.
        val m = RoutePlanEditor()
        m.routing = false
        repeat(3) { m.add(stop(it)) }
        assertTrue(m.plan.isManual)
        assertTrue(m.routeAll(RoutingProfiles.BIKE))
        assertEquals(listOf(0, 1), m.plan.pending)
    }

    @Test
    fun `an unknown profile falls back to the previous one`() {
        val e = editorWith(2)
        e.setProfile("vtt-perso", allLegs = true)
        assertTrue(e.replaceProfile("vtt-perso", RoutingProfiles.HIKING))
        assertEquals(RoutingProfiles.HIKING, e.profile)
        assertEquals(RoutingProfiles.HIKING, e.plan.legs[0].profile)
        // The hiking way is known: nothing to ask.
        assertTrue(e.plan.pending.isEmpty())
    }

    @Test
    fun `free hand strokes become passages linked by straight lines`() {
        val e = RoutePlanEditor()
        e.routing = false
        val stroke = List(50) { TrackPoint(45.0 + it * 0.0002, 6.0 + (it % 2) * 0.00001) }
        assertTrue(e.addStroke(stroke, 5.0))
        assertTrue(e.plan.stops.size >= 2)
        assertTrue(e.plan.isManual)
        e.plan.check()
    }

    @Test
    fun `simplify keeps the ends and the named stops`() {
        val e = RoutePlanEditor()
        e.routing = false
        repeat(10) {
            val named = it == 4
            e.add(
                RouteStop(
                    45.0 + it * 0.001,
                    6.0,
                    if (named) StopKind.NAMED else StopKind.PASSAGE,
                    "Col".takeIf {
                        named
                    },
                ),
            )
        }
        assertTrue(e.simplify(5.0))
        assertEquals(3, e.plan.stops.size)
        assertEquals("Col", e.plan.stops[1].name)
        e.plan.check()
        assertTrue(e.endAt(1))
        assertEquals(2, e.plan.stops.size)
    }

    @Test
    fun `distances to the stops follow the way`() {
        val e = editorWith(3)
        val d = e.plan.stopDistances()
        assertEquals(0.0, d[0])
        val straight = Geodesy.distance(stop(0).lat, stop(0).lon, stop(1).lat, stop(1).lon)
        assertTrue(d[1] > straight)
        assertTrue(d[2] > d[1])
    }

    @Test
    fun `the cache forgets the oldest legs`() {
        val cache = LegCache(capacity = 2)
        val ok = LegResult.Ok(listOf(TrackPoint(0.0, 0.0)), null)
        repeat(3) { cache.put(LegKey.of(stop(it), stop(it + 1), "p"), ok) }
        assertEquals(2, cache.size())
        assertNull(cache.get(LegKey.of(stop(0), stop(1), "p")))
    }
}
