package fr.toolmap.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BarometerTickTest {
    @Test
    fun `the tick is on for every mode but GPS`() {
        assertFalse(AltitudeMode.GPS.barometerOn())
        assertTrue(AltitudeMode.AUTO.barometerOn())
        assertTrue(AltitudeMode.BAROMETER.barometerOn())
    }

    @Test
    fun `ticking from GPS gives the automatic mode`() {
        assertEquals(AltitudeMode.AUTO, AltitudeMode.GPS.withBarometer(true))
    }

    @Test
    fun `ticking keeps a mode that already uses the barometer`() {
        assertEquals(AltitudeMode.AUTO, AltitudeMode.AUTO.withBarometer(true))
        assertEquals(AltitudeMode.BAROMETER, AltitudeMode.BAROMETER.withBarometer(true))
    }

    @Test
    fun `unticking gives the GPS whatever the mode was`() {
        assertEquals(AltitudeMode.GPS, AltitudeMode.GPS.withBarometer(false))
        assertEquals(AltitudeMode.GPS, AltitudeMode.AUTO.withBarometer(false))
        assertEquals(AltitudeMode.GPS, AltitudeMode.BAROMETER.withBarometer(false))
    }

    @Test
    fun `the tick reads back what it set`() {
        for (mode in AltitudeMode.entries) {
            assertTrue(mode.withBarometer(true).barometerOn())
            assertFalse(mode.withBarometer(false).barometerOn())
        }
    }
}
