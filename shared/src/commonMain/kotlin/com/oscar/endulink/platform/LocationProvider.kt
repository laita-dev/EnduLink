package com.oscar.endulink.platform

import kotlinx.coroutines.flow.StateFlow

expect class LocationProvider() {
    val telemetryFlow: StateFlow<RiderTelemetry>
    val currentTelemetry: RiderTelemetry
    fun startTracking()
    fun stopTracking()
}

