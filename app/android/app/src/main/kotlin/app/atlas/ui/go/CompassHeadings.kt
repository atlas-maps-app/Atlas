package app.atlas.ui.go

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The way the phone faces, in degrees from north, from the rotation-vector sensor (the geomagnetic one when there is no
 * gyroscope): what Ears only calls "ahead" while you stand still. Emits null when the phone has no compass. Smoothed and
 * rounded to whole degrees so a radar redraws only when it would move.
 */
object CompassHeadings {
    fun flow(context: Context): Flow<Double?> {
        val sm = context.getSystemService(SensorManager::class.java) ?: return flowOf(null)
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
            ?: return flowOf(null)
        return callbackFlow {
            val matrix = FloatArray(9)
            val angles = FloatArray(3)
            var smooth = Double.NaN
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(matrix, e.values)
                    SensorManager.getOrientation(matrix, angles)
                    val deg = (Math.toDegrees(angles[0].toDouble()) + 360) % 360
                    smooth = if (smooth.isNaN()) deg else smoothAngle(smooth, deg, 0.25)
                    trySend(smooth)
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            awaitClose { sm.unregisterListener(listener) }
        }.conflate().map { it?.let { d -> Math.round(d).toDouble() % 360 } }.distinctUntilChanged()
    }

    /** [from] moved [k] of the short way towards [to], in degrees 0..360. */
    fun smoothAngle(from: Double, to: Double, k: Double): Double {
        val d = ((to - from + 540) % 360) - 180
        return (from + k * d + 360) % 360
    }
}
