package fr.toolmap.map

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import fr.toolmap.core.settings.BearingFormat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.abs

/**
 * The direction the top of the phone points to, from the rotation sensor: a magnetic azimuth in degrees, clockwise from
 * the magnetic north. Listens only while collected. Emits nothing when the phone has no such sensor.
 */
class HeadingSensor(
    context: Context,
) {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    fun magneticHeadings(): Flow<Double> =
        callbackFlow {
            val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (manager == null || sensor == null) {
                close()
            } else {
                val rotation = FloatArray(9)
                val angles = FloatArray(3)
                var last = Double.NaN
                val listener =
                    object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent) {
                            SensorManager.getRotationMatrixFromVector(rotation, event.values)
                            SensorManager.getOrientation(rotation, angles)
                            val heading = BearingFormat.normalize(Math.toDegrees(angles[0].toDouble()))
                            // Only a change of at least a tenth of a degree reaches the screen: fewer redraws.
                            if (last.isNaN() || abs(heading - last) >= MIN_CHANGE_DEGREES) {
                                last = heading
                                trySend(heading)
                            }
                        }

                        override fun onAccuracyChanged(
                            sensor: Sensor?,
                            accuracy: Int,
                        ) = Unit
                    }
                manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
                awaitClose { manager.unregisterListener(listener) }
            }
        }

    private companion object {
        const val MIN_CHANGE_DEGREES = 0.1
    }
}
