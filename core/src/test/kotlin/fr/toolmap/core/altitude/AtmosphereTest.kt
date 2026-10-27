package fr.toolmap.core.altitude

import kotlin.test.Test
import kotlin.test.assertEquals

class AtmosphereTest {
    /** The pressure at [altitude] when it is [seaLevel] at sea level (the inverse of the altitude formula). */
    private fun pressureAt(
        altitude: Double,
        seaLevel: Double = Atmosphere.STANDARD_SEA_LEVEL_HPA,
    ) = seaLevel / Atmosphere.seaLevelHpa(1.0, altitude)

    @Test
    fun `the standard atmosphere gives the textbook pressures`() {
        // ICAO standard atmosphere: 898.75 hPa at 1000 m, 795.0 at 2000 m, 701.1 at 3000 m, 540.2 at 5000 m.
        assertEquals(898.75, pressureAt(1000.0), 0.05)
        assertEquals(795.0, pressureAt(2000.0), 0.1)
        assertEquals(701.1, pressureAt(3000.0), 0.1)
        assertEquals(540.2, pressureAt(5000.0), 0.1)
        assertEquals(0.0, Atmosphere.altitudeMeters(1013.25, 1013.25), 1e-9)
    }

    @Test
    fun `altitude and sea-level pressure are each other's inverse`() {
        for (altitude in listOf(-100.0, 0.0, 450.0, 1830.0, 4808.0)) {
            for (seaLevel in listOf(985.0, 1013.25, 1032.0)) {
                val p = pressureAt(altitude, seaLevel)
                assertEquals(altitude, Atmosphere.altitudeMeters(p, seaLevel), 1e-6)
                assertEquals(seaLevel, Atmosphere.seaLevelHpa(p, altitude), 1e-6)
            }
        }
    }

    @Test
    fun `a hectopascal is about eight meters near the ground and more higher up`() {
        val perMeter = Atmosphere.seaLevelHpaPerMeter(1013.25, 0.0)
        assertEquals(0.1201, perMeter, 0.0005)
        assertEquals(8.3, 1.0 / perMeter, 0.1)
        // Higher up the same altitude error stands for a slightly larger calibration error.
        assertEquals(true, Atmosphere.seaLevelHpaPerMeter(1013.25, 3000.0) > perMeter)
    }

    @Test
    fun `a lower pressure than the sea level is a higher altitude`() {
        assertEquals(true, Atmosphere.altitudeMeters(900.0, 1013.25) > 0.0)
        assertEquals(true, Atmosphere.altitudeMeters(1020.0, 1013.25) < 0.0)
    }
}
