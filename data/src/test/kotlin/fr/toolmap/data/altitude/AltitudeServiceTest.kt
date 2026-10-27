package fr.toolmap.data.altitude

import fr.toolmap.core.altitude.AltitudeSource
import fr.toolmap.core.altitude.Atmosphere
import fr.toolmap.core.record.Fix
import fr.toolmap.core.settings.AltitudeMode
import fr.toolmap.core.settings.AppSettings
import fr.toolmap.core.settings.PositionSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AltitudeServiceTest {
    private class FakePressure(
        override val available: Boolean = true,
    ) : PressureSource {
        val readings = MutableSharedFlow<Double>(extraBufferCapacity = 1024)

        override fun pressures(): Flow<Double> = readings

        val listening: Boolean get() = readings.subscriptionCount.value > 0
    }

    private class FakeTerrain(
        var answer: Double?,
    ) : TerrainElevation {
        var calls = 0

        override suspend fun at(
            lat: Double,
            lon: Double,
        ): Double? {
            calls++
            return answer
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val settings = MutableStateFlow(AppSettings(altitude = AltitudeMode.AUTO))
    private var now = 1_000_000L
    private val barometer = FakePressure()
    private val terrain = FakeTerrain(null)

    private fun service(
        pressure: PressureSource = barometer,
        geoid: GeoidModel = GeoidModel.NONE,
    ) = AltitudeService(settings, pressure, terrain, scope, clock = { now }, geoid = geoid)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun pressureAt(altitude: Double) = Atmosphere.STANDARD_SEA_LEVEL_HPA / Atmosphere.seaLevelHpa(1.0, altitude)

    /** The sensor reports [hpa] once a second for [seconds] seconds. */
    private fun settle(
        hpa: Double,
        seconds: Int = 30,
    ) {
        repeat(seconds) {
            now += 1000L
            barometer.readings.tryEmit(hpa)
        }
    }

    private fun fix(
        ele: Double? = null,
        msl: Boolean = true,
        vertical: Double? = 5.0,
        lat: Double = 45.0,
        accuracy: Double? = 10.0,
    ) = Fix(
        lat = lat,
        lon = 6.0,
        ele = ele,
        timeMillis = now,
        accuracyMeters = accuracy,
        verticalAccuracyMeters = vertical,
        eleIsMsl = msl,
    )

    @Test
    fun `in GPS mode the fix is left alone and the sensor stays off`() {
        settings.value = AppSettings(altitude = AltitudeMode.GPS)
        val service = service()
        service.attach()
        assertFalse(barometer.listening)
        val given = fix(ele = 812.0)
        assertEquals(given, service.enrich(given))
        val status = service.status.value
        assertEquals(AltitudeSource.GPS, status.altitude?.source)
        assertEquals(812.0, status.altitude?.meters)
        assertEquals(AltitudeMode.GPS, status.mode)
        assertEquals(0, terrain.calls, "no terrain lookup without a barometer to calibrate")
    }

    @Test
    fun `a GPS height above the ellipsoid is brought to the sea level with the geoid when the option is on`() {
        settings.value = AppSettings(altitude = AltitudeMode.GPS)
        val service = service(geoid = GeoidModel { _, _ -> 40.0 }).apply { attach() }
        val out = service.enrich(fix(ele = 812.0, msl = false))
        assertEquals(772.0, out.ele)
        assertTrue(out.eleIsMsl)
    }

    @Test
    fun `the geoid is left out when the option is off, when the GPS already gives the sea level, or without a model`() {
        val geoid = GeoidModel { _, _ -> 40.0 }
        settings.value = AppSettings(altitude = AltitudeMode.GPS, positioning = PositionSettings(egm96 = false))
        assertEquals(812.0, service(geoid = geoid).apply { attach() }.enrich(fix(ele = 812.0, msl = false)).ele)
        settings.value = AppSettings(altitude = AltitudeMode.GPS)
        assertEquals(812.0, service(geoid = geoid).apply { attach() }.enrich(fix(ele = 812.0, msl = true)).ele)
        assertEquals(812.0, service().apply { attach() }.enrich(fix(ele = 812.0, msl = false)).ele)
    }

    @Test
    fun `a device without a barometer gives the GPS altitude whatever the mode`() {
        settings.value = AppSettings(altitude = AltitudeMode.BAROMETER)
        val service = service(NoPressureSource)
        service.attach()
        val out = service.enrich(fix(ele = 640.0))
        assertEquals(640.0, out.ele)
        assertFalse(service.status.value.barometerAvailable)
    }

    @Test
    fun `the GPS altitude is used until the barometer is calibrated and the barometer's after`() {
        val service = service()
        service.attach()
        settle(pressureAt(500.0))
        // The GPS says 520 m above sea level, to 5 m: the barometer calibrates on it.
        val first = service.enrich(fix(ele = 520.0))
        assertEquals(520.0, first.ele!!, 1.0)
        assertEquals(AltitudeSource.BAROMETER, service.status.value.altitude?.source)
        assertTrue(service.status.value.calibrated)
        assertTrue(first.eleIsMsl)
        // Climbing 100 m: the GPS has no altitude this time, the pressure still tells.
        settle(pressureAt(600.0))
        val second = service.enrich(fix(ele = null))
        assertEquals(620.0, second.ele!!, 2.0)
        assertNotNull(second.verticalAccuracyMeters)
    }

    @Test
    fun `before any calibration the barometer mode gives no altitude and the automatic mode the GPS one`() {
        val service = service()
        service.attach()
        settle(pressureAt(500.0))
        terrain.answer = null
        // No reference at all: no GPS altitude and no terrain.
        assertNull(service.enrich(fix(ele = null)).ele)
        settings.value = AppSettings(altitude = AltitudeMode.BAROMETER)
        // The GPS has an altitude of unknown datum; in barometer mode it is only used to calibrate, not shown as is.
        val out = service.enrich(fix(ele = 700.0, msl = false, vertical = null))
        assertEquals(AltitudeSource.BAROMETER, service.status.value.altitude?.source)
        assertNotNull(out.ele)
    }

    @Test
    fun `the elevation model calibrates when the GPS has nothing to say`() {
        settings.value = AppSettings(altitude = AltitudeMode.BAROMETER)
        terrain.answer = 750.0
        val service = service()
        service.attach()
        settle(pressureAt(750.0))
        val out = service.enrich(fix(ele = null))
        assertEquals(1, terrain.calls)
        assertEquals(750.0, out.ele!!, 1.5)
        assertEquals(AltitudeSource.BAROMETER, service.status.value.altitude?.source)
    }

    @Test
    fun `a GPS altitude of unknown datum is only a hint and the elevation model wins`() {
        settings.value = AppSettings(altitude = AltitudeMode.BAROMETER)
        terrain.answer = 750.0
        val service = service()
        service.attach()
        settle(pressureAt(750.0))
        // 50 m too high, as an ellipsoid height can be.
        val out = service.enrich(fix(ele = 800.0, msl = false, vertical = null))
        assertTrue(out.ele!! in 748.0..762.0, "calibrated to ${out.ele} m")
        // Later fixes keep saying 800: the model calibrated moments ago, the hint is not taken again.
        settle(pressureAt(750.0), seconds = 60)
        val later = service.enrich(fix(ele = 800.0, msl = false, vertical = null))
        assertTrue(later.ele!! in 748.0..762.0, "still $later m")
    }

    @Test
    fun `terrain lookups are asked at the first fix, then every five minutes or five hundred meters`() {
        terrain.answer = 300.0
        val service = service()
        service.attach()
        settle(pressureAt(300.0))
        service.enrich(fix())
        assertEquals(1, terrain.calls)
        settle(pressureAt(300.0), seconds = 60)
        service.enrich(fix())
        assertEquals(1, terrain.calls, "a minute later, same place")
        settle(pressureAt(300.0), seconds = 360)
        service.enrich(fix())
        assertEquals(2, terrain.calls, "six minutes later")
        settle(pressureAt(300.0), seconds = 40)
        service.enrich(fix(lat = 45.0054))
        assertEquals(3, terrain.calls, "six hundred meters further")
    }

    @Test
    fun `a position that is not precise does not trigger a lookup`() {
        terrain.answer = 300.0
        val service = service()
        service.attach()
        settle(pressureAt(300.0))
        service.enrich(fix(accuracy = 120.0))
        service.enrich(fix(accuracy = null))
        assertEquals(0, terrain.calls)
    }

    @Test
    fun `a lookup that fails is tried again after a minute`() {
        terrain.answer = null
        val service = service()
        service.attach()
        settle(pressureAt(300.0))
        service.enrich(fix())
        assertEquals(1, terrain.calls)
        settle(pressureAt(300.0), seconds = 30)
        service.enrich(fix())
        assertEquals(1, terrain.calls, "too soon")
        settle(pressureAt(300.0), seconds = 40)
        terrain.answer = 300.0
        service.enrich(fix())
        assertEquals(2, terrain.calls)
    }

    @Test
    fun `a lookup made before the first pressure reading is tried again within seconds`() {
        terrain.answer = 300.0
        val service = service()
        service.attach()
        service.enrich(fix())
        assertEquals(1, terrain.calls)
        settle(pressureAt(300.0), seconds = 12)
        service.enrich(fix())
        assertEquals(2, terrain.calls)
        assertTrue(service.status.value.calibrated)
    }

    @Test
    fun `a manual calibration sets the altitude at once`() {
        settings.value = AppSettings(altitude = AltitudeMode.BAROMETER)
        val service = service()
        service.attach()
        assertFalse(service.calibrateManually(1200.0), "no pressure reading yet")
        settle(pressureAt(1000.0))
        service.enrich(fix(ele = null, accuracy = 100.0))
        assertNull(service.status.value.altitude)
        assertTrue(service.calibrateManually(1200.0))
        assertEquals(1200.0, service.status.value.altitude!!.meters, 1.0)
    }

    @Test
    fun `the sensor runs only while something listens and the mode wants it`() {
        val service = service()
        assertFalse(barometer.listening)
        service.attach()
        service.attach()
        assertTrue(barometer.listening)
        service.detach()
        assertTrue(barometer.listening, "one listener is left")
        settings.value = AppSettings(altitude = AltitudeMode.GPS)
        assertFalse(barometer.listening, "GPS only: sensor off")
        settings.value = AppSettings(altitude = AltitudeMode.AUTO)
        assertTrue(barometer.listening)
        service.detach()
        assertFalse(barometer.listening)
        // More detaches than attaches are harmless.
        service.detach()
        service.attach()
        assertTrue(barometer.listening)
    }
}
