package com.oscar.endulink.platform

import com.oscar.endulink.trail.GeoCoord

data class RiderTelemetry(
    val coordinate: GeoCoord? = null,
    val altitudeM: Double = 0.0,
    val speedKmh: Double = 0.0,
    val headingDegrees: Double = 0.0,
    val hasValidHeading: Boolean = false,
    val horizontalAccuracyM: Double = -1.0,
    val isSimulating: Boolean = false
)

data class EncoderStats(
    val fps: Double = 0.0,
    val bitrateKbps: Int = 0,
    val backlog: Int = 0
)

