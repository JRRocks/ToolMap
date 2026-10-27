package fr.toolmap.altitude

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidPressureSourceTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `a phone without barometer says so and gives nothing`() =
        runBlocking {
            val source = AndroidPressureSource(context)
            assertFalse(source.available)
            assertEquals(emptyList(), source.pressures().toList())
        }

    @Test
    fun `readings flow while collected and stop after`() {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val barometer = ShadowSensor.newInstance(Sensor.TYPE_PRESSURE)
        shadowOf(manager).addSensor(barometer)
        val source = AndroidPressureSource(context)
        assertTrue(source.available)

        val values = ArrayList<Double>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { source.pressures().collect { values += it } }
        val event = pressureEvent(barometer, 1013.25f)
        assertEquals(1, shadowOf(manager).listeners.size)
        shadowOf(manager).sendSensorEventToListeners(event)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(1013.25), values)

        job.cancel()
        assertTrue(shadowOf(manager).listeners.isEmpty(), "the sensor was released")
        shadowOf(manager).sendSensorEventToListeners(event)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, values.size)
    }

    /** `SensorEvent`'s constructor is not public. */
    private fun pressureEvent(
        sensor: Sensor,
        hpa: Float,
    ): SensorEvent {
        val constructor = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
        constructor.isAccessible = true
        return constructor.newInstance(1).also {
            it.sensor = sensor
            it.values[0] = hpa
        }
    }
}
