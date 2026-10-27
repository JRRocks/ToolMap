package fr.toolmap.altitude

import fr.toolmap.core.altitude.Atmosphere
import fr.toolmap.core.record.Fix
import fr.toolmap.core.settings.AltitudeMode
import fr.toolmap.core.settings.AppSettings
import fr.toolmap.data.altitude.AltitudeService
import fr.toolmap.data.altitude.PressureSource
import fr.toolmap.data.altitude.TerrainElevation
import fr.toolmap.record.LocationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AltitudeAwareLocationSourceTest {
    private class Barometer : PressureSource {
        val readings = MutableSharedFlow<Double>(extraBufferCapacity = 1024)
        override val available: Boolean = true

        override fun pressures(): Flow<Double> = readings

        val listening: Boolean get() = readings.subscriptionCount.value > 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val settings = MutableStateFlow(AppSettings(altitude = AltitudeMode.AUTO))
    private val barometer = Barometer()
    private var now = 1_000_000L
    private val service =
        AltitudeService(settings, barometer, TerrainElevation { _, _ -> null }, scope, clock = { now })

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun fix(
        ele: Double?,
        msl: Boolean = true,
    ) = Fix(
        lat = 45.0,
        lon = 6.0,
        ele = ele,
        timeMillis = now,
        accuracyMeters = 10.0,
        verticalAccuracyMeters = 5.0,
        eleIsMsl = msl,
    )

    private fun upstream(vararg fixes: Fix) =
        object : LocationSource {
            override fun fixes(
                minIntervalMillis: Long,
                minDistanceMeters: Float,
            ): Flow<Fix> = flow { fixes.forEach { emit(it) } }
        }

    private fun pressureAt(altitude: Double) = Atmosphere.STANDARD_SEA_LEVEL_HPA / Atmosphere.seaLevelHpa(1.0, altitude)

    @Test
    fun `positions come out with the altitude the service chooses and the sensor is off afterwards`() =
        runBlocking<Unit> {
            // The sensor reports while the positions are collected: the first position calibrates, the second climbs.
            val climbing =
                object : LocationSource {
                    override fun fixes(
                        minIntervalMillis: Long,
                        minDistanceMeters: Float,
                    ): Flow<Fix> =
                        flow {
                            repeat(30) {
                                now += 1000L
                                barometer.readings.tryEmit(pressureAt(500.0))
                            }
                            emit(fix(520.0))
                            repeat(30) {
                                now += 1000L
                                barometer.readings.tryEmit(pressureAt(600.0))
                            }
                            emit(fix(null))
                        }
                }
            val out = AltitudeAwareLocationSource(climbing, service).fixes(1000L, 0f).toList()

            assertEquals(2, out.size)
            assertEquals(520.0, out[0].ele!!, 1.0)
            assertEquals(620.0, out[1].ele!!, 2.0)
            assertTrue(out[1].eleIsMsl)
            assertNotNull(out[1].verticalAccuracyMeters)
            // Collected to the end: the upstream is done, so the sensor was released.
            assertFalse(barometer.listening)
        }

    @Test
    fun `the sensor runs while the flow is collected and stops when it is cancelled`() =
        runBlocking<Unit> {
            val endless =
                object : LocationSource {
                    override fun fixes(
                        minIntervalMillis: Long,
                        minDistanceMeters: Float,
                    ): Flow<Fix> =
                        flow {
                            emit(fix(520.0))
                            awaitCancellation()
                        }
                }
            val source = AltitudeAwareLocationSource(endless, service)
            val job = launch(Dispatchers.Unconfined) { source.fixes(1000L, 0f).collect { } }
            assertTrue(barometer.listening)
            job.cancelAndJoin()
            assertFalse(barometer.listening)
        }

    @Test
    fun `a failing upstream still releases the sensor`() =
        runBlocking<Unit> {
            val failing =
                object : LocationSource {
                    override fun fixes(
                        minIntervalMillis: Long,
                        minDistanceMeters: Float,
                    ): Flow<Fix> = flow { throw IllegalStateException("no gps") }
                }
            val result = runCatching { AltitudeAwareLocationSource(failing, service).fixes(1000L, 0f).toList() }
            assertTrue(result.isFailure)
            assertFalse(barometer.listening)
        }

    @Test
    fun `the controller keeps the status current and swallows a missing GPS`() =
        runBlocking<Unit> {
            val controller = AltitudeController(service, AltitudeAwareLocationSource(upstream(fix(300.0)), service))
            controller.watch()
            assertEquals(300.0, controller.status.value.altitude?.meters)
            val failing =
                object : LocationSource {
                    override fun fixes(
                        minIntervalMillis: Long,
                        minDistanceMeters: Float,
                    ): Flow<Fix> = flow { throw SecurityException("no permission") }
                }
            AltitudeController(service, failing).watch()
        }
}
