package fr.toolmap.core.altitude

import fr.toolmap.core.settings.AltitudeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AltitudeSelectorTest {
    private fun baro(
        meters: Double,
        uncertainty: Double,
    ) = BaroReading(meters, uncertainty, 900.0, 1013.0)

    @Test
    fun `GPS mode uses the GPS only`() {
        val a = AltitudeSelector.choose(AltitudeMode.GPS, 1200.0, 8.0, baro(1190.0, 3.0))
        assertEquals(Altitude(1200.0, AltitudeSource.GPS, 8.0), a)
        assertNull(AltitudeSelector.choose(AltitudeMode.GPS, null, null, baro(1190.0, 3.0)))
    }

    @Test
    fun `barometer mode uses the barometer only, even uncalibrated it gives nothing`() {
        val a = AltitudeSelector.choose(AltitudeMode.BAROMETER, 1200.0, 8.0, baro(1190.0, 45.0))
        assertEquals(Altitude(1190.0, AltitudeSource.BAROMETER, 45.0), a)
        assertNull(AltitudeSelector.choose(AltitudeMode.BAROMETER, 1200.0, 8.0, null))
    }

    @Test
    fun `auto mode takes a calibrated barometer, else the GPS, else what there is`() {
        assertEquals(
            Altitude(1190.0, AltitudeSource.BAROMETER, 10.0),
            AltitudeSelector.choose(AltitudeMode.AUTO, 1200.0, 8.0, baro(1190.0, 10.0)),
        )
        assertEquals(
            Altitude(1200.0, AltitudeSource.GPS, 8.0),
            AltitudeSelector.choose(AltitudeMode.AUTO, 1200.0, 8.0, baro(1190.0, 60.0)),
            "a barometer too unsure gives way to the GPS",
        )
        assertEquals(
            Altitude(1200.0, AltitudeSource.GPS, null),
            AltitudeSelector.choose(AltitudeMode.AUTO, 1200.0, null, null),
        )
        assertEquals(
            Altitude(1190.0, AltitudeSource.BAROMETER, 60.0),
            AltitudeSelector.choose(AltitudeMode.AUTO, null, null, baro(1190.0, 60.0)),
            "better a vague barometer than nothing",
        )
        assertNull(AltitudeSelector.choose(AltitudeMode.AUTO, null, null, null))
    }
}
