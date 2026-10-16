package app.atlas.ui.shop

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.Services
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.nav.LocalServices
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Which way the phone faces, in degrees clockwise from north, smoothed. A phone held flat points along its top edge; held
 * upright (the way people hold it to look for something) it points along the back camera: [fromRotation] picks by the tilt.
 */
internal class HeadingFilter(private val gain: Double = 0.18) {
    private var x = 0.0
    private var y = 0.0
    private var has = false

    /** Feeds one reading (degrees); returns the smoothed heading. */
    fun add(deg: Double): Double {
        val r = Math.toRadians(deg)
        if (!has) {
            x = cos(r); y = sin(r); has = true
        } else {
            x += gain * (cos(r) - x)
            y += gain * (sin(r) - y)
        }
        return Geo.normalize(Math.toDegrees(atan2(y, x)))
    }

    companion object {
        /** The heading from a 3×3 rotation matrix `r` (device to world: east, north, up), row-major as Android gives it. */
        fun fromRotation(r: FloatArray): Double {
            val flat = abs(r[8]) > 0.7f
            val east = if (flat) r[1] else -r[2]
            val north = if (flat) r[4] else -r[5]
            return Geo.normalize(Math.toDegrees(atan2(east.toDouble(), north.toDouble())))
        }
    }
}

/**
 * The phone's heading from the rotation-vector sensor (the compass of a phone without a magnetometer reading of its own),
 * or, where the phone has no such sensor, the direction the last fix was moving in; null while neither is known.
 */
@Composable
internal fun rememberHeading(): State<Float?> {
    val context = LocalContext.current
    val services = LocalServices.current
    val sensor = remember(context) { context.getSystemService(Context.SENSOR_SERVICE).let { it as? SensorManager } }
    val rotation = remember(sensor) { sensor?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }
    var sensed by remember { mutableStateOf<Float?>(null) }
    val fix by services.location.last.collectAsStateWithLifecycle()
    // The sensor points at magnetic north; the places lie by true north.
    val declination by rememberUpdatedState(
        remember(fix?.location?.lat?.roundToInt(), fix?.location?.lon?.roundToInt()) {
            val at = fix?.location ?: services.startCameraNow()?.target ?: Services.WESTERKERK
            runCatching { GeomagneticField(at.lat.toFloat(), at.lon.toFloat(), 0f, services.clock.millis()).declination.toDouble() }.getOrDefault(0.0)
        },
    )
    // Listen only while the screen is on show: a phone put in a pocket must not keep the sensor running.
    LifecycleStartEffect(sensor, rotation) {
        val filter = HeadingFilter()
        val m = FloatArray(9)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (e.values.size < 3) return
                SensorManager.getRotationMatrixFromVector(m, e.values)
                val h = filter.add(HeadingFilter.fromRotation(m) + declination).toFloat()
                val last = sensed
                if (last == null || abs(Geo.turnAngle(last.toDouble(), h.toDouble())) > 1.0) sensed = h
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        val registered = sensor != null && rotation != null && sensor.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_UI)
        onStopOrDispose { if (registered) sensor?.unregisterListener(listener) }
    }
    return remember { derivedStateOf { sensed ?: fix?.takeIf { (it.speed ?: 0.0) > 0.7 }?.bearing?.toFloat() } }
}

/** Where the person is, kept up to date as fixes arrive (rounded to about 5 m so lists do not reload for nothing). */
@Composable
internal fun rememberLiveHere(): LatLon {
    val services = LocalServices.current
    val fix by services.location.last.collectAsStateWithLifecycle()
    val start = remember(services) { services.startCameraNow()?.target ?: Services.WESTERKERK }
    val at = fix?.location ?: start
    return remember(Math.round(at.lat * 20000), Math.round(at.lon * 20000)) { at }
}
