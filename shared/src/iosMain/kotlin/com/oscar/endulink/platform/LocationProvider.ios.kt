package com.oscar.endulink.platform

import com.oscar.endulink.trail.GeoCoord
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import platform.CoreLocation.*
import platform.Foundation.NSError
import platform.Foundation.NSThread
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalForeignApi::class)
actual class LocationProvider actual constructor() : NSObject(), CLLocationManagerDelegateProtocol {

    private val _telemetryFlow = MutableStateFlow(RiderTelemetry())
    actual val telemetryFlow: StateFlow<RiderTelemetry> = _telemetryFlow.asStateFlow()

    actual val currentTelemetry: RiderTelemetry
        get() = _telemetryFlow.value

    private var manager = CLLocationManager()
    private var currentHeading: Double = 0.0
    private var smoothedHeading: Double = 0.0
    private var hasHeading: Boolean = false
    @Volatile
    private var highPowerMode: Boolean = false
    private var lastLocationMs: Long = 0L
    private var lastHeadingEmitMs: Long = 0L
    private var lastEmittedHeading: Double = -1.0

    init {
        manager.delegate = this
        manager.activityType = CLActivityTypeOtherNavigation
        applyPowerProfile()
    }

    actual fun setHighPowerMode(enabled: Boolean) {
        if (highPowerMode == enabled) return
        highPowerMode = enabled
        if (NSThread.isMainThread) {
            applyPowerProfile()
        } else {
            dispatch_async(dispatch_get_main_queue()) {
                applyPowerProfile()
            }
        }
    }

    private fun applyPowerProfile() {
        if (highPowerMode) {
            manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
            manager.distanceFilter = kCLDistanceFilterNone
            manager.headingFilter = 1.5
            manager.pausesLocationUpdatesAutomatically = false
            manager.allowsBackgroundLocationUpdates = true
            manager.showsBackgroundLocationIndicator = true
        } else {
            manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
            manager.distanceFilter = 5.0
            manager.headingFilter = 6.0
            manager.pausesLocationUpdatesAutomatically = true
            manager.allowsBackgroundLocationUpdates = false
            manager.showsBackgroundLocationIndicator = false
        }
    }

    actual fun startTracking() {
        manager.requestWhenInUseAuthorization()
        manager.startUpdatingLocation()
        if (CLLocationManager.headingAvailable()) {
            manager.startUpdatingHeading()
        }
    }

    actual fun stopTracking() {
        manager.stopUpdatingLocation()
        manager.stopUpdatingHeading()
    }

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        val loc = didUpdateLocations.lastOrNull() as? CLLocation ?: return
        val acc = loc.horizontalAccuracy
        if (acc < 0.0) return

        val prev = _telemetryFlow.value
        val prevCoord = prev.coordinate
        if (prevCoord != null && prev.horizontalAccuracyM in 0.1..45.0 && acc > 110.0) {
            return
        }

        val coord = loc.coordinate.useContents { GeoCoord(latitude, longitude) }
        val nowMs = Clock.System.now().toEpochMilliseconds()
        if (prevCoord != null && lastLocationMs > 0L) {
            val dtSec = ((nowMs - lastLocationMs) / 1000.0).coerceAtLeast(0.1)
            val jumpMeters = prevCoord.distanceTo(coord)
            if (dtSec < 5.0 && jumpMeters > maxOf(160.0, dtSec * 65.0) && acc > 25.0) {
                return
            }
        }
        lastLocationMs = nowMs

        val speedKmh = (loc.speed * 3.6).let { if (it < 0) 0.0 else (it * 10.0).roundToInt() / 10.0 }

        val course = if (loc.course >= 0.0 && speedKmh > 3.0) {
            loc.course
        } else {
            currentHeading
        }

        _telemetryFlow.value = RiderTelemetry(
            coordinate = coord,
            altitudeM = loc.altitude,
            speedKmh = speedKmh,
            headingDegrees = course,
            hasValidHeading = loc.course >= 0.0 || hasHeading,
            horizontalAccuracyM = acc
        )
    }

    override fun locationManager(manager: CLLocationManager, didUpdateHeading: CLHeading) {
        val h = if (didUpdateHeading.trueHeading >= 0.0) didUpdateHeading.trueHeading else didUpdateHeading.magneticHeading
        if (h >= 0.0) {
            if (!hasHeading) {
                smoothedHeading = h
                hasHeading = true
            } else {
                val diff = ((h - smoothedHeading + 540.0) % 360.0) - 180.0
                smoothedHeading = (smoothedHeading + diff * 0.45 + 360.0) % 360.0
            }
            currentHeading = smoothedHeading
            val cur = _telemetryFlow.value
            val nowMs = Clock.System.now().toEpochMilliseconds()
            val angularDelta = if (lastEmittedHeading < 0.0) 999.0 else abs(((currentHeading - lastEmittedHeading + 540.0) % 360.0) - 180.0)
            if (cur.speedKmh <= 5.0 && (!cur.hasValidHeading || (angularDelta >= 1.5 && nowMs - lastHeadingEmitMs >= 90L))) {
                lastHeadingEmitMs = nowMs
                lastEmittedHeading = currentHeading
                _telemetryFlow.value = cur.copy(
                    headingDegrees = currentHeading,
                    hasValidHeading = true
                )
            }
        }
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        // Ignorar errores transitorios de señal GPS o autorización pendiente
    }
}

