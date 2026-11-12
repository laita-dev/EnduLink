package com.oscar.endulink.bike

import androidx.compose.ui.graphics.ImageBitmap
import com.oscar.endulink.platform.RiderTelemetry
import com.oscar.endulink.tiles.MapLayer
import com.oscar.endulink.tiles.TileStore
import com.oscar.endulink.trail.GPXRoute
import com.oscar.endulink.trail.GPXWaypoint
import com.oscar.endulink.trail.GeoCoord
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

internal fun currentTftClockHm(): String {
    val local = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
}

internal fun formatTftEtaHm(remainingMinutes: Int): String {
    val etaInstant = Instant.fromEpochMilliseconds(
        Clock.System.now().toEpochMilliseconds() + remainingMinutes.coerceAtLeast(0) * 60_000L
    )
    val local = etaInstant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
}

internal fun formatTftDuration(remainingMinutes: Int): String {
    val mins = remainingMinutes.coerceAtLeast(0)
    return if (mins >= 60) {
        val h = mins / 60
        val m = mins % 60
        "${h}H ${m.toString().padStart(2, '0')}M"
    } else {
        "$mins MIN"
    }
}

@Serializable
data class BikeMapConfig(
    val zoom: Int = 15,
    val orientation: String = "heading", // "heading" or "north"
    val tileMagnification: Double = 1.45, // 1.10 = Peq, 1.45 = Med, 1.80 = Grd
    val autoZoomBySpeed: Boolean = true,
    val bitrateKbps: Int = 0, // 0 = Auto (2500 kbps), 2000, 3000, 5000
    val mapLayerRaw: String? = null // null = misma capa que el móvil
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
        intermediateStops: List<GeoCoord> = emptyList(),
        remainingMinutes: Int? = null,
        routeTypeLabel: String? = null
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
        intermediateStops: List<GeoCoord> = emptyList(),
        remainingMinutes: Int? = null,
        routeTypeLabel: String? = null
    ): ImageBitmap?
}
