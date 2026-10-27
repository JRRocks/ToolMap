package fr.toolmap.core.track

import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackStatsTest {
    /** Meters per degree of longitude on the equator for the sphere of [Geodesy]. */
    private val metersPerDegree = Math.PI * Geodesy.EARTH_RADIUS_METERS / 180.0

    /** A straight walk east along the equator: [count] points, [stepMeters] apart, [stepMillis] between fixes. */
    private fun walk(
        count: Int,
        stepMeters: Double = 10.0,
        stepMillis: Long = 7_000,
        startMillis: Long = 1_700_000_000_000L,
        ele: (Int) -> Double? = { null },
    ) = List(count) { TrackPoint(0.0, it * stepMeters / metersPerDegree, ele(it), startMillis + it * stepMillis) }

    @Test
    fun `distance of a straight walk and its duration and speed`() {
        val stats = TrackStatsCalculator.compute(listOf(walk(1001)))
        assertEquals(10_000.0, stats.distance2dMeters, 0.5)
        assertEquals(7_000_000L, stats.totalDurationMillis)
        assertEquals(10_000.0 / 7000.0, stats.averageSpeedMps!!, 1e-3)
        assertEquals(stats.averageSpeedMps!!, stats.averageMovingSpeedMps!!, 1e-3)
        assertEquals(10.0 / 7.0, stats.maxSpeedMps!!, 1e-3)
        assertEquals(1001, stats.pointCount)
        assertEquals(1, stats.segmentCount)
    }

    @Test
    fun `climb counts, noise does not`() {
        val ramp = TrackStatsCalculator.compute(listOf(walk(1001) { it * 0.1 }))
        assertEquals(100.0, ramp.ascentMeters, 1e-6)
        assertEquals(0.0, ramp.descentMeters, 1e-6)
        assertEquals(0.0, ramp.minEleMeters!!, 1e-6)
        assertEquals(100.0, ramp.maxEleMeters!!, 1e-6)

        val random = Random(7)
        val noisy = TrackStatsCalculator.compute(listOf(walk(1001) { 500 + (random.nextDouble() - 0.5) * 3 }))
        assertEquals(0.0, noisy.ascentMeters, 1e-6)
        assertEquals(0.0, noisy.descentMeters, 1e-6)

        val noisyRamp = TrackStatsCalculator.compute(listOf(walk(1001) { it * 0.1 + (random.nextDouble() - 0.5) * 3 }))
        assertTrue(abs(noisyRamp.ascentMeters - 100) < 8, "ascent ${noisyRamp.ascentMeters}")
        assertTrue(noisyRamp.descentMeters < 8, "descent ${noisyRamp.descentMeters}")
    }

    @Test
    fun `up, down, up swings are each counted in full`() {
        val profile = List(51) { it * 1.0 } + List(30) { 49.0 - it } + List(20) { 20.0 + it + 1 }
        val stats = TrackStatsCalculator.compute(listOf(walk(profile.size) { profile[it] }))
        // The median filter shaves about a meter off every turning point.
        assertEquals(70.0, stats.ascentMeters, 3.0)
        assertEquals(30.0, stats.descentMeters, 3.0)
    }

    @Test
    fun `a one-point altitude spike is removed by the median filter`() {
        val stats = TrackStatsCalculator.compute(listOf(walk(200) { if (it == 100) 900.0 else 100.0 }))
        assertEquals(0.0, stats.ascentMeters, 1e-9)
        assertEquals(100.0, stats.maxEleMeters!!, 1e-9)
    }

    @Test
    fun `3D distance adds the climb`() {
        // 1000 m flat distance, 100 m of climb: sqrt(1000^2 + 100^2) when the slope is constant per step.
        val stats = TrackStatsCalculator.compute(listOf(walk(101) { it * 1.0 }))
        assertEquals(1000.0, stats.distance2dMeters, 0.1)
        assertEquals(Math.sqrt(1000.0 * 1000.0 + 100.0 * 100.0), stats.distance3dMeters, 0.5)
        assertTrue(stats.distance3dMeters > stats.distance2dMeters)
    }

    @Test
    fun `stops are excluded from moving time but not from total time`() {
        val moving1 = walk(100)
        val stop =
            List(100) { TrackPoint(0.0, moving1.last().lon, null, moving1.last().timeMillis!! + (it + 1) * 7_000L) }
        val moving2 =
            walk(100, startMillis = stop.last().timeMillis!! + 7_000).map { it.copy(lon = it.lon + moving1.last().lon) }
        val stats = TrackStatsCalculator.compute(listOf(moving1 + stop + moving2))
        val total = stats.totalDurationMillis!!
        val moving = stats.movingDurationMillis!!
        assertTrue(moving < total * 0.75 && moving > total * 0.55, "moving $moving of $total")
        assertTrue(stats.averageMovingSpeedMps!! > stats.averageSpeedMps!!)
    }

    @Test
    fun `segments are not joined by the gap between them`() {
        val a = walk(11)
        val b = walk(11, startMillis = 1_800_000_000_000L).map { it.copy(lon = it.lon + 1.0) }
        val stats = TrackStatsCalculator.compute(listOf(a, b))
        assertEquals(200.0, stats.distance2dMeters, 0.1)
        assertEquals(2, stats.segmentCount)
        assertEquals(1_800_000_000_000L + 70_000 - 1_700_000_000_000L, stats.totalDurationMillis)
    }

    @Test
    fun `DIN 33466 walking time`() {
        // 10 km with 600 m up and 600 m down: horizontal 2.5 h, vertical 2 h + 1.2 h = 3.2 h, so 3.2 + 1.25 h.
        val millis = TrackStatsCalculator.estimateWalkMillis(10_000.0, 600.0, 600.0)
        assertEquals((4.45 * 3_600_000).toLong(), millis)
        // Flat 12 km: 3 h; a short steep one: 200 m over 1 km up is 40 min vertical, 15 min horizontal.
        assertEquals(3 * 3_600_000L, TrackStatsCalculator.estimateWalkMillis(12_000.0, 0.0, 0.0))
        val steep = TrackStatsCalculator.estimateWalkMillis(1_000.0, 200.0, 0.0)
        assertEquals(((200.0 / 300 + 0.25 / 2) * 3_600_000).toLong(), steep)
    }

    @Test
    fun `nothing to measure`() {
        val none = TrackStatsCalculator.compute(emptyList())
        assertEquals(0, none.pointCount)
        assertEquals(0.0, none.distance2dMeters)
        assertNull(none.totalDurationMillis)
        assertNull(none.minEleMeters)
        assertNull(none.maxSpeedMps)
        val single = TrackStatsCalculator.compute(listOf(listOf(TrackPoint(1.0, 2.0, 3.0, 4L))))
        assertEquals(1, single.pointCount)
        assertEquals(3.0, single.minEleMeters)
        assertNull(single.totalDurationMillis)
    }

    @Test
    fun `real hike has coherent numbers`() {
        val dir = requireNotNull(System.getProperty("fixtures.dir"))
        val doc = GpxReader.read(File(dir, "gpx/korita-zbevnica.gpx").readBytes())
        val track = doc.tracks.maxBy { it.pointCount }
        val stats = TrackStatsCalculator.compute(track.segments)
        // Independent reference: the same haversine, summed with plain loops.
        var reference = 0.0
        for (segment in track.segments) {
            for (i in 1 until segment.size) {
                reference += Geodesy.distance(segment[i - 1].lat, segment[i - 1].lon, segment[i].lat, segment[i].lon)
            }
        }
        assertEquals(reference, stats.distance2dMeters, 1e-6)
        assertTrue(stats.distance2dMeters > 1_000, "distance ${stats.distance2dMeters}")
        assertTrue(stats.distance3dMeters >= stats.distance2dMeters)
        assertTrue(stats.ascentMeters > 0 && stats.descentMeters > 0)
        assertTrue(stats.minEleMeters!! <= stats.maxEleMeters!!)
        assertNotNull(stats.estimatedWalkMillis)
        assertTrue(stats.estimatedWalkMillis > 0)
    }

    @Test
    fun `Garmin run has times, moving time and speeds`() {
        val dir = requireNotNull(System.getProperty("fixtures.dir"))
        val doc = GpxReader.read(File(dir, "gpx/garmin-connect-run.gpx").readBytes())
        val stats = TrackStatsCalculator.compute(doc.tracks.single().segments)
        assertEquals(TrackTime.parse("2012-10-24T23:29:40.000Z"), stats.startTimeMillis)
        assertEquals(TrackTime.parse("2012-10-24T23:45:28.000Z"), stats.endTimeMillis)
        assertEquals(948_000L, stats.totalDurationMillis)
        assertTrue(stats.movingDurationMillis!! in 1..stats.totalDurationMillis!!)
        // A run: between 2 and 6 m/s on average.
        assertTrue(stats.averageMovingSpeedMps!! in 2.0..6.0, "speed ${stats.averageMovingSpeedMps}")
    }

    @Test
    fun `path distances and interpolation`() {
        val path = TrackPath(listOf(walk(11), walk(11).map { it.copy(lon = it.lon + 1.0) }))
        assertEquals(200.0, path.length, 0.1)
        assertEquals(listOf(0, 11), path.segmentStarts)
        val middle = path.interpolate(55.0)!!
        assertEquals(55.0 / metersPerDegree, middle.lon, 1e-9)
        assertEquals(path.points.first(), path.interpolate(-5.0))
        assertEquals(path.points.last(), path.interpolate(1e9))
        assertEquals(15, path.nearestIndex(0.0, 1.0 + 40.0 / metersPerDegree))
    }
}
