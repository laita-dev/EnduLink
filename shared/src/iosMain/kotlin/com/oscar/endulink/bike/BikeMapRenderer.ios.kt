package com.oscar.endulink.bike

import androidx.compose.ui.graphics.ImageBitmap
import com.oscar.endulink.platform.RiderTelemetry
import com.oscar.endulink.tiles.MapLayer
import com.oscar.endulink.tiles.TileStore
import com.oscar.endulink.trail.GPXRoute
import com.oscar.endulink.trail.GPXWaypoint
import com.oscar.endulink.trail.GeoCoord
import kotlinx.cinterop.*
import platform.darwin.*
import platform.posix.*

private fun htonsPort(value: UShort): UShort {
    val v = value.toInt()
    return (((v and 0xFF) shl 8) or ((v ushr 8) and 0xFF)).toUShort()
}

@OptIn(ExperimentalForeignApi::class)
private fun probeLocalIpv4(interfaceName: String?, remoteHost: String): String? {
    val sock = socket(AF_INET, SOCK_DGRAM, 0)
    if (sock < 0) return null
    try {
        if (!interfaceName.isNullOrEmpty()) {
            val ifIndex = if_nametoindex(interfaceName)
            if (ifIndex == 0u) return null
            memScoped {
                val idxVar = alloc<UIntVar>()
                idxVar.value = ifIndex
                if (setsockopt(sock, IPPROTO_IP, IP_BOUND_IF, idxVar.ptr, sizeOf<UIntVar>().toUInt()) != 0) {
                    return null
                }
            }
        }

        memScoped {
            val addr = alloc<sockaddr_in>()
            addr.sin_len = sizeOf<sockaddr_in>().toUByte()
            addr.sin_family = AF_INET.toUByte()
            addr.sin_port = htonsPort(10930u)
            if (inet_pton(AF_INET, remoteHost, addr.sin_addr.ptr) != 1) return null

            if (connect(sock, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt()) != 0) {
                return null
            }

            val localAddr = alloc<sockaddr_in>()
            val addrLen = alloc<socklen_tVar>()
            addrLen.value = sizeOf<sockaddr_in>().toUInt()
            if (getsockname(sock, localAddr.ptr.reinterpret(), addrLen.ptr) != 0) {
                return null
            }

            val ipInt = localAddr.sin_addr.s_addr
            val b1 = (ipInt and 0xFFu).toUInt()
            val b2 = ((ipInt shr 8) and 0xFFu).toUInt()
            val b3 = ((ipInt shr 16) and 0xFFu).toUInt()
            val b4 = ((ipInt shr 24) and 0xFFu).toUInt()
            if (b1 == 0u || b1 == 127u || b1 == 169u) return null
            return "$b1.$b2.$b3.$b4"
        }
    } finally {
        close(sock)
    }
}

actual fun getWifiSnapshot(): WifiSnapshot? {
    val ip = probeLocalIpv4("en0", "192.168.43.1")
        ?: probeLocalIpv4("en0", "8.8.8.8")
        ?: probeLocalIpv4("bridge100", "172.20.10.2")
        ?: probeLocalIpv4(null, "192.168.43.1")
        ?: return null

    val parts = ip.split(".")
    if (parts.size == 4) {
        val gateway = "${parts[0]}.${parts[1]}.${parts[2]}.1"
        return WifiSnapshot(phoneIP = ip, gatewayCandidateIP = gateway)
    }
    return null
}

actual class BikeMapRenderer actual constructor(private val tileStore: TileStore) {

    private val scene = CommonBikeMapScene(tileStore)

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
        return scene.renderRgba(
            width = width,
            height = height,
            config = config,
            rider = rider,
            layer = layer,
            activeRoute = activeRoute,
            roadRouteCoordinates = roadRouteCoordinates,
            isNavigating = isNavigating,
            remainingKm = remainingKm,
            nextInstruction = nextInstruction,
            waypoints = waypoints,
            intermediateStops = intermediateStops
        )
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
        return scene.renderPreview(
            width = width,
            height = height,
            config = config,
            rider = rider,
            layer = layer,
            activeRoute = activeRoute,
            roadRouteCoordinates = roadRouteCoordinates,
            isNavigating = isNavigating,
            remainingKm = remainingKm,
            nextInstruction = nextInstruction,
            waypoints = waypoints,
            intermediateStops = intermediateStops
        )
    }
}
