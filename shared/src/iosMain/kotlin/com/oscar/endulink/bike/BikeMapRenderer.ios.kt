package com.oscar.endulink.bike

import androidx.compose.ui.graphics.ImageBitmap
import com.oscar.endulink.platform.RiderTelemetry
import com.oscar.endulink.tiles.MapLayer
import com.oscar.endulink.tiles.TileStore
import com.oscar.endulink.trail.GPXRoute
import com.oscar.endulink.trail.GPXWaypoint
import com.oscar.endulink.trail.GeoCoord

actual fun getWifiSnapshot(): WifiSnapshot? = null

actual class BikeMapRenderer actual constructor(private val tileStore: TileStore) {
    actual fun renderRgba(
        width: Int,
        height: Int,
        config: BikeMapConfig,
        rider: RiderTelemetry,
        layer: MapLayer,
        activeRoute: GPXRoute?,
        roadRouteCoordinates: List<GeoCoord>?,
        isNavigating: Boolean,
        remainingKm: Double?,
        nextInstruction: String?,
        waypoints: List<GPXWaypoint>,
        intermediateStops: List<GeoCoord>
    ): ByteArray? {
        return null // iOS usa BikeMapCanvas.swift directamente en Swift
    }

    actual fun renderPreview(
        width: Int,
        height: Int,
        config: BikeMapConfig,
        rider: RiderTelemetry,
        layer: MapLayer,
        activeRoute: GPXRoute?,
        roadRouteCoordinates: List<GeoCoord>?,
        isNavigating: Boolean,
        remainingKm: Double?,
        nextInstruction: String?,
        waypoints: List<GPXWaypoint>,
        intermediateStops: List<GeoCoord>
    ): ImageBitmap? {
        return null
    }
}
