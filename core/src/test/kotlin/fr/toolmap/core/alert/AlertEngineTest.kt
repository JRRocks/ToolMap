package fr.toolmap.core.alert

import fr.toolmap.core.record.Fix
import fr.toolmap.core.settings.AlertSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertEngineTest {
    private val hut = WatchedPlace(7L, "Refuge", Field.lat(0.0), Field.lon(1000.0), radiusMeters = 100.0)

    private fun walk(
        engine: AlertEngine,
        xs: List<Double>,
        y: Double = 0.0,
        startMillis: Long = 0L,
    ): List<Pair<Double, AlertUpdate>> =
        xs.mapIndexed { i, x ->
            x to
                engine.onFix(Field.fix(x, y, startMillis + i * 1000L))
        }

    private fun steps(
        from: Double,
        to: Double,
        step: Double = 25.0,
    ): List<Double> {
        val out = ArrayList<Double>()
        var x = from
        if (from <= to) {
            while (x <= to) {
                out += x
                x += step
            }
        } else {
            while (x >= to) {
                out += x
                x -= step
            }
        }
        return out
    }

    // --- proximity ----------------------------------------------------------------------------------------------

    @Test
    fun `entering a watched place fires once, at the first fix inside the radius`() {
        val engine = AlertEngine().apply { setWatched(listOf(hut)) }
        // The steps are offset by 3 m so that no fix lies exactly on the 100 m edge.
        val fired = walk(engine, steps(3.0, 1000.0)).filter { it.second.events.isNotEmpty() }
        assertEquals(1, fired.size)
        assertEquals(903.0, fired[0].first, 0.0)
        val event = assertIs<AlertEvent.Proximity>(fired[0].second.events.single())
        assertEquals(7L, event.place.id)
        assertEquals(97.0, event.distanceMeters, 1.0)
    }

    @Test
    fun `leaving past the exit margin re-arms the place`() {
        val engine = AlertEngine().apply { setWatched(listOf(hut)) }
        val route = steps(0.0, 1000.0) + steps(1000.0, 1150.0) + steps(1150.0, 800.0)
        val fired = walk(engine, route).filter { it.second.events.isNotEmpty() }
        assertEquals(2, fired.size)
    }

    @Test
    fun `jitter around the edge does not fire again`() {
        val engine = AlertEngine().apply { setWatched(listOf(hut)) }
        // 99 m (enter), 110 m, 95 m, 120 m, 90 m: never beyond 125 m.
        val route = listOf(0.0, 901.0, 890.0, 905.0, 880.0, 910.0)
        val fired = walk(engine, route).filter { it.second.events.isNotEmpty() }
        assertEquals(1, fired.size)
    }

    @Test
    fun `a place already entered when first seen is entered silently`() {
        val engine = AlertEngine().apply { setWatched(listOf(hut)) }
        val first = engine.onFix(Field.fix(950.0, 0.0, 0L))
        assertTrue(first.events.isEmpty())
        // Still inside: nothing. Out and in again: fires.
        assertTrue(engine.onFix(Field.fix(960.0, 0.0, 1000L)).events.isEmpty())
        engine.onFix(Field.fix(1200.0, 0.0, 2000L))
        assertEquals(1, engine.onFix(Field.fix(950.0, 0.0, 3000L)).events.size)
    }

    @Test
    fun `each place has its own radius and state`() {
        val near = WatchedPlace(1L, "A", Field.lat(0.0), Field.lon(300.0), radiusMeters = 30.0)
        val far = WatchedPlace(2L, "B", Field.lat(0.0), Field.lon(300.0), radiusMeters = 200.0)
        val engine = AlertEngine().apply { setWatched(listOf(near, far)) }
        engine.onFix(Field.fix(0.0, 0.0, 0L))
        val at150 = engine.onFix(Field.fix(150.0, 0.0, 1000L)).events
        assertEquals(listOf(2L), at150.map { (it as AlertEvent.Proximity).place.id })
        val at280 = engine.onFix(Field.fix(280.0, 0.0, 2000L)).events
        assertEquals(listOf(1L), at280.map { (it as AlertEvent.Proximity).place.id })
    }

    @Test
    fun `a place removed from the watch list is forgotten and one added later is primed again`() {
        val engine = AlertEngine().apply { setWatched(listOf(hut)) }
        engine.onFix(Field.fix(0.0, 0.0, 0L))
        engine.setWatched(emptyList())
        assertTrue(engine.onFix(Field.fix(950.0, 0.0, 1000L)).events.isEmpty())
        engine.setWatched(listOf(hut))
        assertTrue(engine.onFix(Field.fix(950.0, 0.0, 2000L)).events.isEmpty())
    }

    @Test
    fun `proximity can be switched off`() {
        val engine = AlertEngine(AlertSettings(proximityEnabled = false)).apply { setWatched(listOf(hut)) }
        val fired = walk(engine, steps(0.0, 1000.0)).filter { it.second.events.isNotEmpty() }
        assertTrue(fired.isEmpty())
        engine.updateSettings(AlertSettings(proximityEnabled = true))
        engine.onFix(Field.fix(0.0, 0.0, 99_000L))
        assertEquals(1, engine.onFix(Field.fix(950.0, 0.0, 100_000L)).events.size)
    }

    @Test
    fun `a fix that is too imprecise is ignored`() {
        val engine = AlertEngine().apply { setWatched(listOf(hut)) }
        engine.onFix(Field.fix(0.0, 0.0, 0L))
        val vague = engine.onFix(Field.fix(1000.0, 0.0, 1000L, accuracy = 200.0))
        assertTrue(vague.events.isEmpty())
        assertEquals(1, engine.onFix(Field.fix(1000.0, 0.0, 2000L, accuracy = 10.0)).events.size)
    }

    // --- following a line ---------------------------------------------------------------------------------------

    private fun following(
        settings: AlertSettings = AlertSettings(offTrackMeters = 50),
        line: TrackFollower = TrackFollower(Field.line(0.0, 2000.0)),
    ) = AlertEngine(settings).apply { setFollow(line) }

    @Test
    fun `status tells how far along the line the walker is`() {
        val engine = following()
        val status = assertNotNull(engine.onFix(Field.fix(500.0, 10.0, 0L)).status)
        assertEquals(10.0, status.offMeters, 0.5)
        assertEquals(500.0, status.alongMeters, 2.0)
        assertEquals(1500.0, status.remainingMeters, 2.0)
        assertEquals(2000.0, status.totalMeters, 2.0)
        assertEquals(0.25, status.fraction, 0.002)
        assertTrue(!status.offTrack && !status.arrived)
    }

    @Test
    fun `there is no status without a followed line`() {
        val engine = AlertEngine()
        assertNull(engine.onFix(Field.fix(0.0, 0.0, 0L)).status)
        engine.setFollow(TrackFollower(emptyList()))
        assertNull(engine.onFix(Field.fix(0.0, 0.0, 1000L)).status)
        engine.setFollow(TrackFollower(Field.line(0.0, 100.0)))
        assertNotNull(engine.onFix(Field.fix(0.0, 0.0, 2000L)).status)
        engine.setFollow(null)
        assertNull(engine.onFix(Field.fix(0.0, 0.0, 3000L)).status)
    }

    @Test
    fun `leaving the line fires on the third fix beyond the limit, not before`() {
        val engine = following()
        engine.onFix(Field.fix(500.0, 0.0, 0L))
        assertTrue(engine.onFix(Field.fix(510.0, 80.0, 1000L)).events.isEmpty())
        assertTrue(engine.onFix(Field.fix(520.0, 80.0, 2000L)).events.isEmpty())
        val third = engine.onFix(Field.fix(530.0, 80.0, 3000L))
        val event = assertIs<AlertEvent.OffTrack>(third.events.single())
        assertEquals(80.0, event.distanceMeters, 1.0)
        assertEquals(50.0, event.limitMeters, 0.0)
        assertTrue(assertNotNull(third.status).offTrack)
    }

    @Test
    fun `a single wild fix is not an alert`() {
        val engine = following()
        engine.onFix(Field.fix(500.0, 0.0, 0L))
        assertTrue(engine.onFix(Field.fix(500.0, 400.0, 1000L)).events.isEmpty())
        assertTrue(engine.onFix(Field.fix(510.0, 0.0, 2000L)).events.isEmpty())
        assertTrue(engine.onFix(Field.fix(520.0, 400.0, 3000L)).events.isEmpty())
        assertTrue(engine.onFix(Field.fix(530.0, 0.0, 4000L)).events.isEmpty())
    }

    @Test
    fun `while off the line the alert repeats every minute and not in between`() {
        val engine = following()
        engine.onFix(Field.fix(500.0, 0.0, 0L))
        var fired = 0
        for (i in 1..130) {
            val events = engine.onFix(Field.fix(500.0, 80.0, i * 1000L)).events
            fired += events.count { it is AlertEvent.OffTrack }
        }
        // Confirmed at 3 s, then at 63 s and 123 s.
        assertEquals(3, fired)
    }

    @Test
    fun `back on the line fires once, with a margin below the limit`() {
        val engine = following()
        engine.onFix(Field.fix(500.0, 0.0, 0L))
        for (i in 1..3) engine.onFix(Field.fix(500.0, 80.0, i * 1000L))
        // 45 m: inside the limit but not by the margin yet.
        val between = engine.onFix(Field.fix(500.0, 45.0, 4000L))
        assertTrue(between.events.isEmpty())
        assertTrue(assertNotNull(between.status).offTrack)
        val back = engine.onFix(Field.fix(500.0, 30.0, 5000L))
        assertEquals(listOf(AlertEvent.BackOnTrack), back.events)
        assertTrue(!assertNotNull(back.status).offTrack)
        assertTrue(engine.onFix(Field.fix(500.0, 20.0, 6000L)).events.isEmpty())
        // And it can fire again for the next excursion.
        for (i in 7..8) engine.onFix(Field.fix(500.0, 90.0, i * 1000L))
        assertTrue(engine.onFix(Field.fix(500.0, 90.0, 9000L)).events.single() is AlertEvent.OffTrack)
    }

    @Test
    fun `the distance must also exceed the accuracy of the fix`() {
        val engine = following()
        engine.onFix(Field.fix(500.0, 0.0, 0L))
        for (i in 1..6) {
            assertTrue(engine.onFix(Field.fix(500.0, 60.0, i * 1000L, accuracy = 80.0)).events.isEmpty())
        }
    }

    @Test
    fun `the limit comes from the settings and can be switched off`() {
        val tight = following(AlertSettings(offTrackMeters = 20))
        tight.onFix(Field.fix(500.0, 0.0, 0L))
        for (i in 1..2) tight.onFix(Field.fix(500.0, 30.0, i * 1000L))
        assertTrue(tight.onFix(Field.fix(500.0, 30.0, 3000L)).events.single() is AlertEvent.OffTrack)

        val off = following(AlertSettings(offTrackEnabled = false))
        off.onFix(Field.fix(500.0, 0.0, 0L))
        for (i in 1..10) assertTrue(off.onFix(Field.fix(500.0, 300.0, i * 1000L)).events.isEmpty())
        assertTrue(!assertNotNull(off.onFix(Field.fix(500.0, 300.0, 11_000L)).status).offTrack)
    }

    @Test
    fun `reaching the end fires once`() {
        val engine = following()
        val events = ArrayList<AlertEvent>()
        for ((i, x) in steps(0.0, 2000.0, 50.0).withIndex()) events += engine.onFix(Field.fix(x, 0.0, i * 1000L)).events
        events += engine.onFix(Field.fix(2000.0, 5.0, 100_000L)).events
        assertEquals(listOf<AlertEvent>(AlertEvent.Arrived), events)
        assertTrue(assertNotNull(engine.onFix(Field.fix(2000.0, 5.0, 101_000L)).status).arrived)
    }

    @Test
    fun `a loop does not arrive at its own start`() {
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
        val engine = following(line = loop)
        val atStart = engine.onFix(Field.fix(0.0, 3.0, 0L))
        assertTrue(atStart.events.isEmpty())
        assertEquals(0.0, assertNotNull(atStart.status).alongMeters, 5.0)
        // Walk the loop: arrival only when back at the end.
        val path =
            steps(0.0, 500.0, 25.0).map { it to 0.0 } +
                steps(0.0, 500.0, 25.0).map { 500.0 to it } +
                steps(500.0, 0.0, 25.0).map { it to 500.0 } +
                steps(500.0, 0.0, 25.0).map { 0.0 to it }
        var arrivals = 0
        var wherePoint = -1
        for ((i, p) in path.withIndex()) {
            val update = engine.onFix(Field.fix(p.first, p.second, (i + 1) * 1000L))
            if (update.events.any { it is AlertEvent.Arrived }) {
                arrivals++
                wherePoint = i
            }
        }
        assertEquals(1, arrivals)
        assertTrue(wherePoint >= path.size - 3, "arrived at fix $wherePoint of ${path.size}")
    }

    @Test
    fun `following backwards counts progress from the other end`() {
        val engine = following(line = TrackFollower(Field.line(0.0, 2000.0)).reversedCopy())
        val status = assertNotNull(engine.onFix(Field.fix(1800.0, 0.0, 0L)).status)
        assertEquals(200.0, status.alongMeters, 2.0)
        assertEquals(1800.0, status.remainingMeters, 2.0)
    }

    @Test
    fun `a new line starts the progress again`() {
        val engine = following()
        for ((i, x) in steps(0.0, 2000.0, 50.0).withIndex()) engine.onFix(Field.fix(x, 0.0, i * 1000L))
        engine.setFollow(TrackFollower(Field.line(0.0, 500.0)))
        val status = assertNotNull(engine.onFix(Field.fix(100.0, 0.0, 1_000_000L)).status)
        assertTrue(!status.arrived)
        assertEquals(100.0, status.alongMeters, 2.0)
    }

    @Test
    fun `an imprecise fix leaves the last status in place`() {
        val engine = following()
        val good = assertNotNull(engine.onFix(Field.fix(500.0, 0.0, 0L)).status)
        val vague = engine.onFix(Field.fix(1500.0, 500.0, 1000L, accuracy = 300.0))
        assertTrue(vague.events.isEmpty())
        assertEquals(good, vague.status)
    }

    @Test
    fun `fix without accuracy is accepted`() {
        val engine = following()
        val fix = Fix(Field.lat(0.0), Field.lon(500.0), timeMillis = 0L)
        assertNotNull(engine.onFix(fix).status)
    }
}
