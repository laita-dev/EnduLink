package com.oscar.endulink.bike

import androidx.compose.ui.graphics.ImageBitmap
import com.oscar.endulink.platform.RiderTelemetry
import com.oscar.endulink.tiles.MapLayer
import com.oscar.endulink.tiles.TileStore
import com.oscar.endulink.trail.GPXRoute
import com.oscar.endulink.trail.GPXWaypoint
import com.oscar.endulink.trail.GeoCoord
import kotlinx.serialization.Serializable

@Serializable
data class BikeMapConfig(
    val zoom: Int = 15,
    val orientation: String = "heading", // "heading" or "north"
    val tileMagnification: Double = 1.45, // 1.10 = Peq, 1.45 = Med, 1.80 = Grd
    val autoZoomBySpeed: Boolean = true,
    val bitrateKbps: Int = 0 // 0 = Auto (2500 kbps), 2000, 3000, 5000
)

data class WifiSnapshot(
    val phoneIP: String,
    val gatewayCandidateIP: String
)

expect fun getWifiSnapshot(): WifiSnapshot?

/**
 * Renderizador de mapa y HUD dedicado e independiente para la pantalla de la moto (TFT EasyConn / Carbit).
 * Funciona de manera autónoma al móvil, exactamente como `BikeMapCanvas.swift` en iOS.
 */
expect class BikeMapRenderer(tileStore: TileStore) {
    fun renderRgba(
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
        waypoints: List<GPXWaypoint> = emptyList(),
        intermediateStops: List<GeoCoord> = emptyList()
    ): ByteArray?

    fun renderPreview(
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
        waypoints: List<GPXWaypoint> = emptyList(),
        intermediateStops: List<GeoCoord> = emptyList()
    ): ImageBitmap?
}
