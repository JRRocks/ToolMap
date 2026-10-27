package fr.toolmap.core.record

import kotlin.test.Test
import kotlin.test.assertEquals

class PositionTrailTest {
    @Test
    fun `a motionless phone adds one point only`() {
        val trail = PositionTrail(minStepMeters = 3.0)
        repeat(20) { trail.add(44.8, 5.9, it * 1000L) }
        assertEquals(1, trail.points(20_000).size)
    }

    @Test
    fun `points older than the maximum age are dropped`() {
        val trail = PositionTrail(maxAgeMillis = 60_000, minStepMeters = 0.0)
        trail.add(44.80, 5.90, 0)
        trail.add(44.81, 5.90, 90_000)
        assertEquals(listOf(90_000L), trail.points(120_000).map { it.timeMillis })
    }

    @Test
    fun `the number of points is bounded, the newest are kept`() {
        val trail = PositionTrail(maxPoints = 3, minStepMeters = 0.0)
        for (i in 0 until 5) trail.add(44.8 + i * 0.001, 5.9, i * 1000L)
        assertEquals(listOf(2000L, 3000L, 4000L), trail.points(4000).map { it.timeMillis })
    }
}
