package fr.toolmap.core.track

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElevationProfileTest {
    private val metersPerDegree = Math.PI * Geodesy.EARTH_RADIUS_METERS / 180.0

    private fun walk(
        count: Int,
        stepMeters: Double = 10.0,
        ele: (Int) -> Double?,
    ) = TrackPath(listOf(List(count) { TrackPoint(0.0, it * stepMeters / metersPerDegree, ele(it)) }))

    @Test
    fun `a constant 10 percent climb gives a 10 percent grade and the right distances`() {
        val profile = ElevationProfile.build(walk(201) { it * 1.0 })!!
        assertEquals(2000.0, profile.totalDistanceMeters, 0.5)
        val middle = profile.sampleAt(1000.0)!!
        assertEquals(10.0, middle.gradePercent, 0.01)
        assertEquals(GradeBand.MODERATE, GradeBand.of(middle.gradePercent))
        assertEquals(0.0, profile.minElevation!!, 1e-9)
        assertEquals(200.0, profile.maxElevation!!, 1e-9)
        assertEquals(0, profile.samples.first().index)
        assertEquals(200, profile.samples.last().index)
    }

    @Test
    fun `a long track is reduced to the sample budget, ends included`() {
        val profile =
            ElevationProfile.build(
                walk(50_000) {
                    100 + (it % 100) * 0.5
                },
                ProfileOptions(maxSamples = 300),
            )!!
        assertTrue(profile.samples.size in 290..300, "${profile.samples.size}")
        assertEquals(0, profile.samples.first().index)
        assertEquals(49_999, profile.samples.last().index)
        assertTrue(profile.samples.zipWithNext().all { (a, b) -> a.distanceMeters <= b.distanceMeters })
    }

    @Test
    fun `points without an elevation are skipped, and fewer than two give no profile`() {
        val path = walk(10) { if (it % 2 == 0) it * 1.0 else null }
        val profile = ElevationProfile.build(path)!!
        assertEquals(listOf(0, 2, 4, 6, 8), profile.samples.map { it.index })
        assertNull(ElevationProfile.build(walk(10) { if (it == 3) 5.0 else null }))
        assertNull(ElevationProfile.build(TrackPath(emptyList())))
    }

    @Test
    fun `sampleAt picks the nearest sample`() {
        val profile = ElevationProfile.build(walk(11) { it * 1.0 })!!
        assertEquals(30.0, profile.sampleAt(31.0)!!.distanceMeters, 0.1)
        assertEquals(0.0, profile.sampleAt(-50.0)!!.distanceMeters, 0.1)
        assertEquals(100.0, profile.sampleAt(500.0)!!.distanceMeters, 0.1)
        assertNotNull(profile.sampleAt(0.0))
    }

    @Test
    fun `grade bands`() {
        assertEquals(GradeBand.FLAT, GradeBand.of(0.0))
        assertEquals(GradeBand.FLAT, GradeBand.of(-2.9))
        assertEquals(GradeBand.GENTLE, GradeBand.of(-3.0))
        assertEquals(GradeBand.MODERATE, GradeBand.of(8.0))
        assertEquals(GradeBand.STEEP, GradeBand.of(-15.0))
        assertEquals(GradeBand.VERY_STEEP, GradeBand.of(25.0))
    }
}
