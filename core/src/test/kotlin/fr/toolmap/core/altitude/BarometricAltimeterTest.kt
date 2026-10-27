package fr.toolmap.core.altitude

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BarometricAltimeterTest {
    private val altimeter = BarometricAltimeter()

    private fun pressureAt(
        altitude: Double,
        seaLevel: Double = Atmosphere.STANDARD_SEA_LEVEL_HPA,
    ) = seaLevel / Atmosphere.seaLevelHpa(1.0, altitude)

    /** Feeds [hpa] once a second for [seconds] seconds from [fromMillis]; returns the time after the last one. */
    private fun settle(
        hpa: Double,
        fromMillis: Long,
        seconds: Int = 40,
    ): Long {
        var t = fromMillis
        repeat(seconds) {
            altimeter.onPressure(hpa, t)
            t += 1000L
        }
        return t - 1000L
    }

    @Test
    fun `without a reference there is no altitude`() {
        altimeter.onPressure(900.0, 0L)
        assertNull(altimeter.reading(0L))
        assertFalse(altimeter.calibrated)
        assertNull(altimeter.seaLevelHpa)
    }

    @Test
    fun `without a pressure a reference cannot be used`() {
        assertEquals(ReferenceResult.NO_PRESSURE, altimeter.onReference(1000.0, 5.0, 0L, ReferenceSource.TERRAIN))
        assertFalse(altimeter.calibrated)
    }

    @Test
    fun `the first reference calibrates so that the altitude is the reference`() {
        val p = pressureAt(1000.0, 1021.0)
        val t = settle(p, 0L)
        assertEquals(ReferenceResult.ACCEPTED, altimeter.onReference(1000.0, 5.0, t, ReferenceSource.TERRAIN))
        val reading = assertNotNull(altimeter.reading(t))
        assertEquals(1000.0, reading.altitudeMeters, 0.01)
        assertEquals(1021.0, reading.seaLevelHpa, 0.01)
        assertTrue(altimeter.calibrated)
        // The uncertainty is the reference's plus the noise of the pressure.
        assertEquals(6.0, reading.uncertaintyMeters, 0.5)
    }

    @Test
    fun `a change of pressure is a change of altitude whatever the calibration`() {
        val t0 = settle(pressureAt(1000.0, 1015.0), 0L)
        altimeter.onReference(1000.0, 5.0, t0, ReferenceSource.TERRAIN)
        // Climb 300 m: the pressure falls as the standard atmosphere says.
        val t1 = settle(pressureAt(1300.0, 1015.0), t0 + 1000L)
        val reading = assertNotNull(altimeter.reading(t1))
        assertEquals(1300.0, reading.altitudeMeters, 1.0)
    }

    @Test
    fun `a spike in the pressure is smoothed`() {
        val t0 = settle(pressureAt(1000.0), 0L)
        altimeter.onReference(1000.0, 5.0, t0, ReferenceSource.TERRAIN)
        // A reading that alone would mean +100 m, one second after the last one.
        altimeter.onPressure(pressureAt(1100.0), t0 + 1000L)
        val moved = assertNotNull(altimeter.reading(t0 + 1000L)).altitudeMeters - 1000.0
        assertTrue(moved in 10.0..30.0, "moved $moved m of a 100 m spike")
    }

    @Test
    fun `a more precise reference weighs more than the estimate and a vaguer one less`() {
        val t0 = settle(pressureAt(1000.0), 0L)
        altimeter.onReference(1000.0, 5.0, t0, ReferenceSource.TERRAIN)
        val t1 = settle(pressureAt(1000.0), t0 + 1000L, seconds = 60)
        // A vague GPS 40 m higher barely moves a calibration made with 5 m.
        assertEquals(ReferenceResult.ACCEPTED, altimeter.onReference(1040.0, 20.0, t1, ReferenceSource.GPS_MSL))
        val shifted = assertNotNull(altimeter.reading(t1)).altitudeMeters - 1000.0
        assertTrue(shifted in 1.0..4.0, "shifted $shifted m")
    }

    @Test
    fun `a reference far from the estimate is an outlier until it persists`() {
        val t0 = settle(pressureAt(1000.0), 0L)
        altimeter.onReference(1000.0, 5.0, t0, ReferenceSource.GPS_MSL)
        var t = t0
        val results = ArrayList<ReferenceResult>()
        repeat(5) {
            t = settle(pressureAt(1000.0), t + 1000L, seconds = 35)
            results += altimeter.onReference(1500.0, 5.0, t, ReferenceSource.GPS_MSL)
        }
        assertEquals(List(4) { ReferenceResult.REJECTED_OUTLIER } + ReferenceResult.ACCEPTED, results)
        assertEquals(1500.0, assertNotNull(altimeter.reading(t)).altitudeMeters, 0.5)
    }

    @Test
    fun `references of one source are throttled and another source is independent`() {
        val t0 = settle(pressureAt(500.0), 0L)
        assertEquals(ReferenceResult.ACCEPTED, altimeter.onReference(500.0, 10.0, t0, ReferenceSource.GPS_MSL))
        assertEquals(
            ReferenceResult.THROTTLED,
            altimeter.onReference(500.0, 10.0, t0 + 10_000L, ReferenceSource.GPS_MSL),
        )
        assertEquals(
            ReferenceResult.ACCEPTED,
            altimeter.onReference(500.0, 10.0, t0 + 10_000L, ReferenceSource.TERRAIN),
        )
        // The sensor is still reporting; 31 s after the first reference the next one is independent of it.
        altimeter.onPressure(pressureAt(500.0), t0 + 30_500L)
        assertEquals(
            ReferenceResult.ACCEPTED,
            altimeter.onReference(500.0, 10.0, t0 + 31_000L, ReferenceSource.GPS_MSL),
        )
    }

    @Test
    fun `references keep the altitude right while the weather changes`() {
        // Standing at 1000 m for 6 hours while the sea-level pressure falls 1 hPa an hour.
        val tracked = BarometricAltimeter()
        val untracked = BarometricAltimeter()
        var worstTracked = 0.0
        var worstUntracked = 0.0
        val start = 1015.0
        for (minute in 0..360) {
            val t = minute * 60_000L
            val p = pressureAt(1000.0, start - minute / 60.0)
            tracked.onPressure(p, t)
            untracked.onPressure(p, t)
            if (minute == 0) {
                tracked.onReference(1000.0, 10.0, t, ReferenceSource.GPS_MSL)
                untracked.onReference(1000.0, 10.0, t, ReferenceSource.GPS_MSL)
            } else {
                tracked.onReference(1000.0, 10.0, t, ReferenceSource.GPS_MSL)
            }
            if (minute >= 60) {
                worstTracked = maxOf(worstTracked, abs(assertNotNull(tracked.reading(t)).altitudeMeters - 1000.0))
            }
            worstUntracked = maxOf(worstUntracked, abs(assertNotNull(untracked.reading(t)).altitudeMeters - 1000.0))
        }
        assertTrue(worstTracked < 8.0, "tracked error up to $worstTracked m")
        assertTrue(worstUntracked > 40.0, "untracked error only $worstUntracked m")
    }

    @Test
    fun `the uncertainty grows with time and shrinks with a new reference`() {
        val t0 = settle(pressureAt(1000.0), 0L)
        altimeter.onReference(1000.0, 5.0, t0, ReferenceSource.TERRAIN)
        val fresh = assertNotNull(altimeter.reading(t0)).uncertaintyMeters
        // Six hours later, with the sensor still reporting but no reference.
        val later = t0 + 6 * 3600_000L
        altimeter.onPressure(pressureAt(1000.0), later)
        val aged = assertNotNull(altimeter.reading(later)).uncertaintyMeters
        assertTrue(aged > 15.0 && aged > fresh * 2, "uncertainty $fresh -> $aged")
        altimeter.onReference(1000.0, 5.0, later, ReferenceSource.TERRAIN)
        assertTrue(assertNotNull(altimeter.reading(later)).uncertaintyMeters < aged)
    }

    @Test
    fun `a stale pressure gives no altitude and no calibration`() {
        val t0 = settle(pressureAt(1000.0), 0L)
        altimeter.onReference(1000.0, 5.0, t0, ReferenceSource.TERRAIN)
        assertNotNull(altimeter.reading(t0 + 20_000L))
        assertNull(altimeter.reading(t0 + 120_000L))
        assertEquals(
            ReferenceResult.NO_PRESSURE,
            altimeter.onReference(1000.0, 5.0, t0 + 120_000L, ReferenceSource.GPS_MSL),
        )
        // A new reading brings it back, without a jump from the smoothing of the old one.
        altimeter.onPressure(pressureAt(1000.0), t0 + 121_000L)
        assertEquals(1000.0, assertNotNull(altimeter.reading(t0 + 121_000L)).altitudeMeters, 0.5)
    }

    @Test
    fun `nonsense is ignored`() {
        altimeter.onPressure(Double.NaN, 0L)
        altimeter.onPressure(5.0, 1000L)
        altimeter.onPressure(2000.0, 2000L)
        assertEquals(
            ReferenceResult.NO_PRESSURE,
            altimeter.onReference(100.0, 5.0, 3000L, ReferenceSource.TERRAIN),
            "none of those readings was kept",
        )
        val t = settle(pressureAt(100.0), 10_000L)
        assertEquals(ReferenceResult.INVALID, altimeter.onReference(100.0, 0.0, t, ReferenceSource.TERRAIN))
        assertEquals(ReferenceResult.INVALID, altimeter.onReference(100.0, Double.NaN, t, ReferenceSource.TERRAIN))
        assertEquals(ReferenceResult.INVALID, altimeter.onReference(20_000.0, 5.0, t, ReferenceSource.TERRAIN))
        assertEquals(ReferenceResult.INVALID, altimeter.onReference(Double.NaN, 5.0, t, ReferenceSource.TERRAIN))
        assertFalse(altimeter.calibrated)
    }

    @Test
    fun `a manual calibration wins at once over any estimate`() {
        val t0 = settle(pressureAt(1000.0), 0L)
        altimeter.onReference(1000.0, 5.0, t0, ReferenceSource.TERRAIN)
        assertEquals(ReferenceResult.ACCEPTED, altimeter.onReference(1100.0, 2.0, t0 + 1000L, ReferenceSource.MANUAL))
        val reading = assertNotNull(altimeter.reading(t0 + 1000L))
        assertEquals(1100.0, reading.altitudeMeters, 0.5)
        // And again straight away: manual calibrations are not throttled.
        assertEquals(ReferenceResult.ACCEPTED, altimeter.onReference(1000.0, 2.0, t0 + 1500L, ReferenceSource.MANUAL))
    }

    @Test
    fun `reset forgets everything`() {
        val t0 = settle(pressureAt(1000.0), 0L)
        altimeter.onReference(1000.0, 5.0, t0, ReferenceSource.TERRAIN)
        altimeter.reset()
        assertFalse(altimeter.calibrated)
        assertNull(altimeter.reading(t0))
        // The throttle is forgotten too.
        altimeter.onPressure(pressureAt(1000.0), t0 + 1000L)
        assertEquals(ReferenceResult.ACCEPTED, altimeter.onReference(1000.0, 5.0, t0 + 1000L, ReferenceSource.TERRAIN))
    }
}
