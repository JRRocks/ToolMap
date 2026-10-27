package fr.toolmap.core.alert

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackFollowerTest {
    private val line = TrackFollower(Field.line(0.0, 1000.0))

    @Test
    fun `length of the line is its geodesic length`() {
        assertEquals(1000.0, line.totalMeters, 1.0)
    }

    @Test
    fun `a point beside the line is off by its distance and along at its foot`() {
        val p = assertNotNull(line.project(Field.lat(20.0), Field.lon(500.0)))
        assertEquals(20.0, p.offMeters, 0.5)
        assertEquals(500.0, p.alongMeters, 1.5)
        // Vertex 5 is at 500 m: the point is as near the segment before it as the one after it.
        assertTrue(p.segment == 4 || p.segment == 5, "segment ${p.segment}")
        assertEquals(Field.lat(0.0), p.nearestLat, 1e-6)
        assertEquals(Field.lon(500.0), p.nearestLon, 1e-6)
    }

    @Test
    fun `either side of the line counts the same`() {
        val north = assertNotNull(line.project(Field.lat(35.0), Field.lon(300.0)))
        val south = assertNotNull(line.project(Field.lat(-35.0), Field.lon(300.0)))
        assertEquals(north.offMeters, south.offMeters, 0.5)
        assertEquals(north.alongMeters, south.alongMeters, 0.5)
    }

    @Test
    fun `before the start the nearest point is the start and after the end it is the end`() {
        val before = assertNotNull(line.project(Field.lat(0.0), Field.lon(-80.0)))
        assertEquals(80.0, before.offMeters, 0.5)
        assertEquals(0.0, before.alongMeters, 0.5)
        val after = assertNotNull(line.project(Field.lat(0.0), Field.lon(1060.0)))
        assertEquals(60.0, after.offMeters, 0.5)
        assertEquals(line.totalMeters, after.alongMeters, 0.5)
    }

    @Test
    fun `a corner is handled by the nearest of its two segments`() {
        val bent = TrackFollower(listOf(Field.point(0.0, 0.0), Field.point(500.0, 0.0), Field.point(500.0, 500.0)))
        val p = assertNotNull(bent.project(Field.lat(250.0), Field.lon(520.0)))
        assertEquals(20.0, p.offMeters, 0.5)
        assertEquals(750.0, p.alongMeters, 1.5)
        assertEquals(1, p.segment)
    }

    @Test
    fun `the hint picks the leg of an out and back that matches the progress`() {
        val outAndBack =
            TrackFollower(
                listOf(
                    Field.point(0.0, 0.0),
                    Field.point(1000.0, 0.0),
                    Field.point(1000.0, 10.0),
                    Field.point(0.0, 10.0),
                ),
            )
        val query = Field.lat(5.0) to Field.lon(500.0)
        val going = assertNotNull(outAndBack.project(query.first, query.second, hintAlongMeters = 450.0))
        val coming = assertNotNull(outAndBack.project(query.first, query.second, hintAlongMeters = 1500.0))
        assertEquals(500.0, going.alongMeters, 2.0)
        assertEquals(1510.0, coming.alongMeters, 2.0)
        assertEquals(5.0, going.offMeters, 0.5)
        assertEquals(5.0, coming.offMeters, 0.5)
    }

    @Test
    fun `the hint does not drag the answer to a leg that is clearly further`() {
        val outAndBack =
            TrackFollower(
                listOf(
                    Field.point(0.0, 0.0),
                    Field.point(1000.0, 0.0),
                    Field.point(1000.0, 200.0),
                    Field.point(0.0, 200.0),
                ),
            )
        val p = assertNotNull(outAndBack.project(Field.lat(10.0), Field.lon(500.0), hintAlongMeters = 1500.0))
        assertEquals(500.0, p.alongMeters, 2.0)
    }

    @Test
    fun `reversed follows the line from its end`() {
        val back = line.reversedCopy()
        assertTrue(back.reversed)
        assertEquals(line.totalMeters, back.totalMeters, 0.001)
        val nearStart = assertNotNull(back.project(Field.lat(0.0), Field.lon(100.0)))
        assertEquals(900.0, nearStart.alongMeters, 1.5)
        assertEquals(line.end, back.start)
        assertFalse(back.reversedCopy().reversed)
    }

    @Test
    fun `consecutive duplicates are dropped and a lone point is not a line`() {
        val repeated = TrackFollower(List(5) { Field.point(10.0, 10.0) })
        assertFalse(repeated.usable)
        assertNull(repeated.project(Field.lat(0.0), Field.lon(0.0)))
        assertEquals(0.0, repeated.totalMeters, 0.0)
        val withRepeats =
            TrackFollower(listOf(Field.point(0.0, 0.0), Field.point(0.0, 0.0), Field.point(100.0, 0.0)))
        assertTrue(withRepeats.usable)
        assertEquals(100.0, withRepeats.totalMeters, 0.5)
        assertNull(TrackFollower(emptyList()).project(0.0, 0.0))
    }
}

class TrackFollowerHintTest {
    @Test
    fun `walking forward along a straight line the hint does not hold the progress back`() {
        val line = TrackFollower(Field.line(0.0, 1000.0))
        var along = 0.0
        for (x in listOf(30.0, 130.0, 180.0, 330.0, 345.0, 601.0, 999.0)) {
            val p = assertNotNull(line.project(Field.lat(40.0), Field.lon(x), along))
            assertEquals(40.0, p.offMeters, 0.5, "off at $x")
            assertEquals(x, p.alongMeters, 1.5, "along at $x")
            along = p.alongMeters
        }
    }

    @Test
    fun `at the start of a loop the start wins over the end when the hint is the start`() {
        val loop =
            TrackFollower(
                listOf(
                    Field.point(0.0, 0.0),
                    Field.point(500.0, 0.0),
                    Field.point(500.0, 500.0),
                    Field.point(0.0, 500.0),
                    Field.point(0.0, 0.0),
                ),
            )
        val p = assertNotNull(loop.project(Field.lat(3.0), Field.lon(0.0), hintAlongMeters = 0.0))
        assertEquals(0.0, p.alongMeters, 5.0)
        val end = assertNotNull(loop.project(Field.lat(3.0), Field.lon(0.0), hintAlongMeters = 1990.0))
        assertEquals(1997.0, end.alongMeters, 5.0)
    }
}
