package fr.toolmap.altitude

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import fr.toolmap.data.altitude.PressureSource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/** The phone's barometer (`TYPE_PRESSURE`), read about once a second; the sensor is only on while the flow is collected. */
class AndroidPressureSource(
    context: Context,
) : PressureSource {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_PRESSURE)

    override val available: Boolean get() = sensor != null

    override fun pressures(): Flow<Double> =
        callbackFlow {
            val sensorManager = manager
            val barometer = sensor
            if (sensorManager == null || barometer == null) {
                close()
                return@callbackFlow
            }
            val listener =
                object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        trySend(event.values[0].toDouble())
                    }

                    override fun onAccuracyChanged(
                        sensor: Sensor?,
                        accuracy: Int,
                    ) = Unit
                }
            val registered =
                sensorManager.registerListener(
                    listener,
                    barometer,
                    SAMPLING_PERIOD_MICROS,
                    Handler(Looper.getMainLooper()),
                )
            if (!registered) close()
            awaitClose { sensorManager.unregisterListener(listener) }
        }.buffer(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private companion object {
        const val SAMPLING_PERIOD_MICROS = 1_000_000
    }
}
