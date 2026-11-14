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
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import com.oscar.endulink.bike.AndroidBikeKeysStorage
import com.oscar.endulink.trail.GeoCoord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
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

    private var lastAcceptedLocation: Location? = null
    private var lastAcceptedElapsedMs: Long = 0L
    private var lastGpsFixMs: Long = 0L
    private var lastOutlierLocation: Location? = null
    private var consecutiveOutliers: Int = 0
    private var lastHeadingEmitMs: Long = 0L
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @kotlin.concurrent.Volatile
    private var isTrackingActive: Boolean = false
    @kotlin.concurrent.Volatile
    private var highPowerMode: Boolean = false

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
        isTrackingActive = true
        runOnMainLooper { applyTrackingConfiguration() }
    }

    actual fun setHighPowerMode(enabled: Boolean) {
        if (highPowerMode == enabled) return
        highPowerMode = enabled
        if (isTrackingActive) {
            runOnMainLooper {
                if (isTrackingActive) {
                    applyTrackingConfiguration()
                }
            }
        }
    }

    private fun runOnMainLooper(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun applyTrackingConfiguration() {
        val mainLooper = android.os.Looper.getMainLooper()
        try {
            locationManager?.removeUpdates(this)
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {}

        val sensorDelay = if (highPowerMode) SensorManager.SENSOR_DELAY_UI else SensorManager.SENSOR_DELAY_NORMAL
        try {
            rotationSensor?.let {
                sensorManager?.registerListener(this, it, sensorDelay, mainHandler)
            }
        } catch (_: Exception) {}

        val gpsIntervalMs = if (highPowerMode) 500L else 1500L
        val gpsMinDistM = if (highPowerMode) 0.0f else 3.0f
        val netIntervalMs = if (highPowerMode) 5000L else 8000L
        val netMinDistM = if (highPowerMode) 15.0f else 25.0f

        try {
            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    gpsIntervalMs,
                    gpsMinDistM,
                    this,
                    mainLooper
                )
            }
            if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    netIntervalMs,
                    netMinDistM,
                    this,
                    mainLooper
                )
            }
        } catch (_: Exception) {}
    }

    actual fun stopTracking() {
        isTrackingActive = false
        runOnMainLooper {
            try {
                locationManager?.removeUpdates(this)
                sensorManager?.unregisterListener(this)
            } catch (_: Exception) {}
        }
    }

    override fun onLocationChanged(location: Location) {
        val nowMs = SystemClock.elapsedRealtime()
        val isGps = location.provider == LocationManager.GPS_PROVIDER
        val accuracy = if (location.hasAccuracy()) location.accuracy else 999f

        // 1. Nunca permitir que lecturas imprecisas de antenas/Wi-Fi sobrescriban un fix satelital GPS reciente
        if (!isGps) {
            if (lastGpsFixMs > 0L && (nowMs - lastGpsFixMs) < 15_000L) return
            if (lastAcceptedLocation != null && accuracy > 60f) return
        }

        // 2. Descartar lecturas muy degradadas si ya teníamos una posición precisa
        val prev = lastAcceptedLocation
        if (prev != null && (!prev.hasAccuracy() || prev.accuracy <= 45f) && accuracy > 110f) {
            return
        }

        // 3. Filtro cinemático anti-saltos aleatorios (teletransporte > 160 m en pocos segundos)
        if (prev != null && lastAcceptedElapsedMs > 0L) {
            val dtSec = ((nowMs - lastAcceptedElapsedMs) / 1000.0).coerceAtLeast(0.1)
            val jumpMeters = prev.distanceTo(location).toDouble()
            val maxReasonableMeters = maxOf(160.0, dtSec * 65.0)
            if (dtSec < 5.0 && jumpMeters > maxReasonableMeters) {
                if (!isGps || accuracy > 25f) {
                    return
                }
                val prevOutlier = lastOutlierLocation
                if (prevOutlier != null && prevOutlier.distanceTo(location) <= 90f) {
                    consecutiveOutliers++
                } else {
                    consecutiveOutliers = 1
                }
                lastOutlierLocation = location
                if (consecutiveOutliers < 3) {
                    return
                }
            }
        }

        if (isGps) {
            lastGpsFixMs = nowMs
        }
        lastAcceptedLocation = location
        lastAcceptedElapsedMs = nowMs
        lastOutlierLocation = null
        consecutiveOutliers = 0

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

            // Si estamos en parado o velocidad lenta, actualizamos con throttling para evitar recomposiciones continuas
            val current = _telemetryFlow.value
            val nowMs = SystemClock.elapsedRealtime()
            val angularDelta = abs(((currentHeading - current.headingDegrees + 540.0) % 360.0) - 180.0)
            if (current.speedKmh <= 3.0 && (!current.hasValidHeading || (angularDelta >= 2.5 && nowMs - lastHeadingEmitMs >= 160L))) {
                lastHeadingEmitMs = nowMs
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
