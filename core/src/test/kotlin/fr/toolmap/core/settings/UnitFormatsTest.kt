package fr.toolmap.core.settings

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnitFormatsTest {
    private val fr = Locale.FRANCE

    @Test
    fun `bearings in degrees, NATO mils and gradians`() {
        assertEquals("90°", BearingFormat.format(90.0, BearingUnit.DEGREES, fr))
        assertEquals("1600 mil", BearingFormat.format(90.0, BearingUnit.MILS, fr))
        assertEquals("100 gon", BearingFormat.format(90.0, BearingUnit.GRADIANS, fr))
    }

    @Test
    fun `a bearing that rounds to a full turn reads as zero`() {
        assertEquals(0, BearingFormat.value(359.7, BearingUnit.DEGREES))
        assertEquals(0, BearingFormat.value(-0.02, BearingUnit.MILS))
        assertEquals(0, BearingFormat.value(399.9 / 400 * 360, BearingUnit.GRADIANS))
    }

    @Test
    fun `negative and large bearings are brought into the turn`() {
        assertEquals(270, BearingFormat.value(-90.0, BearingUnit.DEGREES))
        assertEquals(45, BearingFormat.value(765.0, BearingUnit.DEGREES))
    }

    @Test
    fun `slopes as a percentage or as an angle`() {
        assertEquals("+12 %", SlopeFormat.format(12.0, SlopeUnit.PERCENT, fr))
        assertEquals("-4 %", SlopeFormat.format(-4.0, SlopeUnit.PERCENT, fr))
        // 100 % is 45°.
        assertEquals("+45,0°", SlopeFormat.format(100.0, SlopeUnit.DEGREES, fr))
    }

    @Test
    fun `pressures in hectopascals, millimetres of mercury and inches`() {
        assertEquals("1013,25 hPa", PressureFormat.format(1013.25, PressureUnit.HPA, fr))
        assertEquals("760,00 mmHg", PressureFormat.format(1013.25, PressureUnit.MMHG, fr))
        assertEquals("29,92 inHg", PressureFormat.format(1013.25, PressureUnit.INHG, fr))
    }

    @Test
    fun `magnetic north is the true bearing minus the declination`() {
        // Declination 2° east: a magnetic bearing of 0° is a true bearing of 2°, so true 90° reads 88° magnetic.
        val offset = NorthReferenceMath.offsetDegrees(NorthReference.MAGNETIC, 2.0, 0.0)
        assertEquals(88.0, NorthReferenceMath.relative(90.0, offset), 1e-9)
        assertEquals(358.0, NorthReferenceMath.relative(0.0, offset), 1e-9)
    }

    @Test
    fun `true north changes nothing and grid north uses the convergence`() {
        assertEquals(
            10.0,
            NorthReferenceMath.relative(10.0, NorthReferenceMath.offsetDegrees(NorthReference.TRUE, 5.0, 3.0)),
            1e-9,
        )
        assertEquals(
            7.0,
            NorthReferenceMath.relative(10.0, NorthReferenceMath.offsetDegrees(NorthReference.GRID, 5.0, 3.0)),
            1e-9,
        )
    }

    @Test
    fun `UTM convergence is zero on the central meridian and grows away from it`() {
        // Zone 31 has its central meridian at 3° E.
        assertEquals(0.0, NorthReferenceMath.utmConvergenceDegrees(45.0, 3.0), 1e-9)
        // Grenoble (5.72° E) is 2.72° east of the central meridian: convergence of about +1.93° at 45° N.
        val gamma = NorthReferenceMath.utmConvergenceDegrees(45.19, 5.72)
        assertTrue(gamma in 1.9..2.0, "convergence was $gamma")
        // Longitudes beyond 180° give the same answer as their equivalent.
        assertEquals(gamma, NorthReferenceMath.utmConvergenceDegrees(45.19, 5.72 + 360), 1e-9)
    }
}
