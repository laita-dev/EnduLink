package com.oscar.endulink.platform

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.WindowManager
import com.oscar.endulink.bike.AndroidBikeKeysStorage
import com.oscar.endulink.trail.GeoCoord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

actual class LocationProvider actual constructor() : LocationListener, SensorEventListener {

    private val _telemetryFlow = MutableStateFlow(RiderTelemetry())
    actual val telemetryFlow: StateFlow<RiderTelemetry> = _telemetryFlow.asStateFlow()

    actual val currentTelemetry: RiderTelemetry
        get() = _telemetryFlow.value

    private val context: Context?
        get() = AndroidBikeKeysStorage.appContext

    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null

    private var currentHeading: Double = 0.0
    private var smoothedHeading: Double = 0.0
    private var hasHeading: Boolean = false
    private var geomagneticField: GeomagneticField? = null

    private fun getDisplayRotation(): Int {
        val ctx = context ?: return Surface.ROTATION_0
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                ctx.display?.rotation ?: Surface.ROTATION_0
            } catch (_: Exception) {
                Surface.ROTATION_0
            }
        } else {
            @Suppress("DEPRECATION")
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }
    }

    @SuppressLint("MissingPermission")
    actual fun startTracking() {
        val ctx = context ?: return
        locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

        rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        rotationSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        try {
            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500L,
                    0.0f,
                    this
                )
            }
            if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    0.0f,
                    this
                )
            }
        } catch (_: SecurityException) {}
    }

    actual fun stopTracking() {
        try {
            locationManager?.removeUpdates(this)
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {}
    }

    override fun onLocationChanged(location: Location) {
        val coord = GeoCoord(location.latitude, location.longitude)

        try {
            geomagneticField = GeomagneticField(
                location.latitude.toFloat(),
                location.longitude.toFloat(),
                location.altitude.toFloat(),
                location.time
            )
        } catch (_: Exception) {}

        val speedKmh = (location.speed * 3.6).let { (it * 10.0).roundToInt() / 10.0 }
        val course = if (location.hasBearing() && speedKmh > 3.0) {
            location.bearing.toDouble()
        } else {
            currentHeading
        }

        _telemetryFlow.value = RiderTelemetry(
            coordinate = coord,
            altitudeM = location.altitude,
            speedKmh = speedKmh,
            headingDegrees = course,
            hasValidHeading = location.hasBearing() || hasHeading,
            horizontalAccuracyM = location.accuracy.toDouble()
        )
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

            // Remapear los ejes del sensor según la orientación visual de la pantalla (Portrait vs Landscape)
            val (axisX, axisY) = when (getDisplayRotation()) {
                Surface.ROTATION_90 -> Pair(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X)
                Surface.ROTATION_180 -> Pair(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y)
                Surface.ROTATION_270 -> Pair(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X)
                else -> Pair(SensorManager.AXIS_X, SensorManager.AXIS_Y)
            }

            val adjustedMatrix = FloatArray(9)
            SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, adjustedMatrix)

            val orientation = FloatArray(3)
            SensorManager.getOrientation(adjustedMatrix, orientation)
            val azimuthRad = orientation[0]
            val rawAzimuthDeg = (Math.toDegrees(azimuthRad.toDouble()) + 360.0) % 360.0

            // Corrección de Norte Magnético a Norte Verdadero con la declinación local
            val declination = geomagneticField?.declination ?: 0.0f
            val trueAzimuthDeg = (rawAzimuthDeg + declination + 360.0) % 360.0

            // Filtro paso bajo suave sobre el círculo trigonométrico (evita fluctuaciones de mano)
            if (!hasHeading) {
                smoothedHeading = trueAzimuthDeg
                hasHeading = true
            } else {
                val diff = ((trueAzimuthDeg - smoothedHeading + 540.0) % 360.0) - 180.0
                smoothedHeading = (smoothedHeading + diff * 0.25 + 360.0) % 360.0
            }

            currentHeading = smoothedHeading

            // Si estamos en parado o velocidad lenta, actualizamos el rumbo del piloto
            val current = _telemetryFlow.value
            if (current.speedKmh <= 3.0) {
                _telemetryFlow.value = current.copy(
                    headingDegrees = currentHeading,
                    hasValidHeading = true
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
