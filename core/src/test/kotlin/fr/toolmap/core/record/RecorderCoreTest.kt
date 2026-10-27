package fr.toolmap.core.record

import fr.toolmap.core.track.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecorderCoreTest {
    /** A walker going north at about 1.4 m/s: one fix a second, 0.0000126 degree is 1.4 m. */
    private fun fix(
        second: Int,
        lat: Double = 45.0 + second * 0.0000126,
        ele: Double? = null,
        accuracy: Double? = 5.0,
        speed: Double? = null,
    ) = Fix(lat, 6.0, ele, 1_000_000L + second * 1000L, accuracy, speed)

    private fun started(settings: RecorderSettings = RecorderSettings()) =
        RecorderCore(settings).also { it.start(1_000_000L) }

    private val everyFix = RecorderSettings(minDistanceMeters = 0.0, minIntervalMillis = 0)

    private fun RecorderCore.accepted(f: Fix): FixDecision.Accepted = assertIs<FixDecision.Accepted>(onFix(f))

    private fun RecorderCore.rejected(f: Fix): RejectReason = assertIs<FixDecision.Rejected>(onFix(f)).reason

    @Test
    fun `nothing is kept before start or while paused`() {
        val r = RecorderCore()
        assertEquals(RejectReason.NOT_RECORDING, r.rejected(fix(0)))
        r.start(1_000_000L)
        assertTrue(r.accepted(fix(0)).newSegment)
        r.pause(1_005_000L)
        assertEquals(RejectReason.NOT_RECORDING, r.rejected(fix(10)))
        assertFailsWith<IllegalStateException> { r.start(0) }
    }

    @Test
    fun `interval and distance thresholds both apply`() {
        val r = started(RecorderSettings(minIntervalMillis = 2_000, minDistanceMeters = 5.0))
        r.accepted(fix(0))
        assertEquals(RejectReason.TOO_SOON, r.rejected(fix(1)))
        // 2 s later but only 2.8 m away: too close.
        assertEquals(RejectReason.TOO_CLOSE, r.rejected(fix(2)))
        // 4 s, 5.6 m: kept, in the same segment.
        assertTrue(!r.accepted(fix(4)).newSegment)
        assertEquals(2, r.stats(1_004_000L).pointCount)
        assertEquals(5.6, r.stats(1_004_000L).distanceMeters, 0.2)
    }

    @Test
    fun `poor accuracy and a clock running backwards are refused`() {
        val r = started()
        r.accepted(fix(10))
        assertEquals(RejectReason.ACCURACY, r.rejected(fix(20, accuracy = 80.0)))
        assertEquals(RejectReason.TIME_BACKWARDS, r.rejected(fix(5)))
        // Unknown accuracy is not a reason to refuse.
        r.accepted(fix(30, accuracy = null))
    }

    @Test
    fun `a pause ends the segment and is not counted as time or distance`() {
        val r = started()
        r.accepted(fix(0))
        r.accepted(fix(10))
        r.pause(1_010_000L)
        r.resume(1_110_000L)
        // 100 s of pause and a long way: the first fix after resuming opens a new segment, whatever the distance.
        assertTrue(r.accepted(fix(110, lat = 45.5)).newSegment)
        r.accepted(fix(120, lat = 45.5 + 0.00014))
        val s = r.stats(1_120_000L)
        assertEquals(2, s.segmentCount)
        assertEquals(4, s.pointCount)
        // 14 m before the pause, 15.6 m after it, and nothing for the 55 km between.
        assertEquals(29.6, s.distanceMeters, 0.5)
    }

    @Test
    fun `elapsed time excludes pauses`() {
        val r = started()
        r.accepted(fix(0))
        r.pause(1_060_000L)
        assertEquals(60_000L, r.stats(1_500_000L).elapsedMillis)
        r.resume(1_500_000L)
        assertEquals(70_000L, r.stats(1_510_000L).elapsedMillis)
        r.stop(1_520_000L)
        assertEquals(80_000L, r.stats(9_999_999L).elapsedMillis)
        assertEquals(RecorderState.IDLE, r.state)
    }

    @Test
    fun `a long silence starts a new segment`() {
        val r = started(RecorderSettings(gapNewSegmentMillis = 60_000))
        r.accepted(fix(0))
        assertTrue(!r.accepted(fix(30)).newSegment)
        assertTrue(r.accepted(fix(200)).newSegment)
        assertEquals(2, r.stats(1_200_000L).segmentCount)
    }

    @Test
    fun `an isolated jump is dropped, a lasting move is followed in a new segment`() {
        val r = started()
        r.accepted(fix(0))
        r.accepted(fix(4))
        // 55 km away in 4 s.
        assertEquals(RejectReason.JUMP, r.rejected(fix(8, lat = 45.5)))
        // Back on the path: the wild fix did not count.
        assertTrue(!r.accepted(fix(12)).newSegment)
        // Four wild fixes in a row: the phone really is elsewhere.
        assertEquals(RejectReason.JUMP, r.rejected(fix(16, lat = 46.0)))
        assertEquals(RejectReason.JUMP, r.rejected(fix(20, lat = 46.0)))
        assertEquals(RejectReason.JUMP, r.rejected(fix(24, lat = 46.0)))
        assertTrue(r.accepted(fix(28, lat = 46.0)).newSegment)
        assertEquals(2, r.stats(1_028_000L).segmentCount)
    }

    @Test
    fun `climb ignores wobbles and single wild altitudes`() {
        val r = started(everyFix)
        // 1000 m, a wild 1400 m reading, then a steady climb of 2 m per fix up to 1040 m with 1 m wobbles.
        r.accepted(fix(0, ele = 1000.0))
        r.accepted(fix(1, ele = 1400.0))
        for (i in 2..21) r.accepted(fix(i, ele = 1000.0 + (i - 1) * 2 + (if (i % 2 == 0) 1 else 0)))
        val s = r.stats(1_021_000L)
        assertTrue(s.ascentMeters in 30.0..45.0, "ascent ${s.ascentMeters}")
        assertEquals(0.0, s.descentMeters, 6.0)
        assertTrue(s.maxEleMeters!! < 1100.0, "the 1400 m reading is not the maximum: ${s.maxEleMeters}")
    }

    @Test
    fun `current speed comes from the receiver or from the last seconds`() {
        val r = started(everyFix)
        for (i in 0..12) r.accepted(fix(i))
        assertEquals(1.4, r.stats(1_012_000L).currentSpeedMps!!, 0.1)
        r.accepted(fix(13, speed = 3.2))
        assertEquals(3.2, r.stats(1_013_000L).currentSpeedMps!!, 1e-9)
        assertNull(started().stats(1_000_000L).currentSpeedMps)
    }

    @Test
    fun `moving time leaves out the stops`() {
        val r = started(everyFix)
        r.accepted(fix(0))
        r.accepted(fix(10)) // 10 s walked
        r.accepted(fix(20, lat = fix(10).lat)) // 10 s standing still
        assertEquals(10_000L, r.stats(1_020_000L).movingMillis)
    }

    @Test
    fun `replay rebuilds the figures and waits for the user to resume`() {
        val first = List(5) { TrackPoint(45.0 + it * 0.0001, 6.0, 1000.0 + it, 1_000_000L + it * 10_000L) }
        val second = List(3) { TrackPoint(45.1 + it * 0.0001, 6.0, null, 2_000_000L + it * 10_000L) }
        val r = RecorderCore()
        r.replay(listOf(first, second), activeMillis = 90_000L)
        assertEquals(RecorderState.PAUSED, r.state)
        val s = r.stats(5_000_000L)
        assertEquals(8, s.pointCount)
        assertEquals(2, s.segmentCount)
        assertEquals(90_000L, s.elapsedMillis)
        assertEquals(6 * 11.119, s.distanceMeters, 1.0)
        r.resume(5_000_000L)
        assertTrue(r.accepted(Fix(45.2, 6.0, null, 5_000_000L, 5.0)).newSegment)
        assertNotNull(r.stats(5_001_000L).lastFix)
        assertEquals(3, r.stats(5_001_000L).segmentCount)
    }
}

class RecorderProfileTest {
    @Test
    fun `profiles are looked up by name and default to walking`() {
        assertEquals(RecorderProfile.BIKE, RecorderProfile.fromName("BIKE"))
        assertEquals(RecorderProfile.WALK, RecorderProfile.fromName("nope"))
        assertEquals(RecorderProfile.WALK, RecorderProfile.fromName(null))
    }

    @Test
    fun `denser profiles keep more points`() {
        val s = RecorderProfile.entries.associateWith { it.settings }
        assertTrue(
            s.getValue(RecorderProfile.PRECISE).minDistanceMeters < s.getValue(RecorderProfile.WALK).minDistanceMeters,
        )
        assertTrue(
            s.getValue(RecorderProfile.WALK).minDistanceMeters < s.getValue(RecorderProfile.BIKE).minDistanceMeters,
        )
        assertTrue(
            s.getValue(RecorderProfile.BIKE).minDistanceMeters < s.getValue(RecorderProfile.SAVER).minDistanceMeters,
        )
    }
}
