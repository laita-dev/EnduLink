package com.oscar.endulink.platform

import com.oscar.endulink.trail.GeoCoord
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.CoreLocation.*
import platform.darwin.NSObject
import kotlin.math.roundToInt

@OptIn(ExperimentalForeignApi::class)
actual class LocationProvider actual constructor() : NSObject(), CLLocationManagerDelegateProtocol {

    private val _telemetryFlow = MutableStateFlow(RiderTelemetry())
    actual val telemetryFlow: StateFlow<RiderTelemetry> = _telemetryFlow.asStateFlow()

    actual val currentTelemetry: RiderTelemetry
        get() = _telemetryFlow.value

    private val manager = CLLocationManager()
    private var currentHeading: Double = 0.0
    private var hasHeading: Boolean = false

    init {
        manager.delegate = this
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.distanceFilter = kCLDistanceFilterNone
        manager.headingFilter = 1.5
        manager.activityType = CLActivityTypeOtherNavigation
        manager.pausesLocationUpdatesAutomatically = false
    }

    actual fun startTracking() {
        manager.requestWhenInUseAuthorization()
        manager.allowsBackgroundLocationUpdates = true
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
        val coord = loc.coordinate.useContents { GeoCoord(latitude, longitude) }
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
            horizontalAccuracyM = loc.horizontalAccuracy
        )
    }

    override fun locationManager(manager: CLLocationManager, didUpdateHeading: CLHeading) {
        val h = if (didUpdateHeading.trueHeading >= 0.0) didUpdateHeading.trueHeading else didUpdateHeading.magneticHeading
        if (h >= 0.0) {
            currentHeading = h
            hasHeading = true
            val cur = _telemetryFlow.value
            if (cur.speedKmh <= 3.0) {
                _telemetryFlow.value = cur.copy(
                    headingDegrees = h,
                    hasValidHeading = true
                )
            }
        }
    }
}

