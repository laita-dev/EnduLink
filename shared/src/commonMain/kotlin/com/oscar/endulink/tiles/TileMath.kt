package com.oscar.endulink.tiles

import com.oscar.endulink.trail.GPXPoint
import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable
data class TileCoord(
    val layer: MapLayer,
    val z: Int,
    val x: Int,
    val y: Int
)

@Serializable
data class MapBoundingBox(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
)

object TileMath {

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // metros
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = sin(dLat / 2.0).pow(2.0) + cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLon / 2.0).pow(2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return r * c
    }

    fun latLonToFractionalTile(lat: Double, lon: Double, z: Int): Pair<Double, Double> {
        val clampedLat = minOf(maxOf(lat, -85.05112878), 85.05112878)
        val n = 2.0.pow(z.toDouble())
        val x = ((lon + 180.0) / 360.0) * n
        val latRad = clampedLat * PI / 180.0
        val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
        return Pair(x, y)
    }

    fun latLonToTile(lat: Double, lon: Double, z: Int): Pair<Int, Int> {
        val (fx, fy) = latLonToFractionalTile(lat, lon, z)
        return Pair(floor(fx).toInt(), floor(fy).toInt())
    }

    fun tileToLatLon(x: Int, y: Int, z: Int): Pair<Double, Double> {
        return fractionalTileToLatLon(x.toDouble(), y.toDouble(), z)
    }

    fun fractionalTileToLatLon(fx: Double, fy: Double, z: Int): Pair<Double, Double> {
        val n = 2.0.pow(z.toDouble())
        val lon = (fx / n) * 360.0 - 180.0
        val sinhVal = sinh(PI * (1.0 - 2.0 * fy / n))
        val lat = atan(sinhVal) * 180.0 / PI
        return Pair(lat, lon)
    }

    fun countBoundingBoxTiles(bbox: MapBoundingBox, minZ: Int, maxZ: Int, maxNativeZ: Int): Int {
        return countBoundingBoxTilesLong(bbox, minZ, maxZ, maxNativeZ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun countBoundingBoxTilesLong(bbox: MapBoundingBox, minZ: Int, maxZ: Int, maxNativeZ: Int): Long {
        val hiZ = minOf(maxZ, maxNativeZ)
        if (minZ > hiZ) return 0L
        var total = 0L
        for (z in minZ..hiZ) {
            val n = 1L shl z
            val (tx1, ty1) = latLonToTile(bbox.maxLat, bbox.minLon, z)
            val (tx2, ty2) = latLonToTile(bbox.minLat, bbox.maxLon, z)
            val xStart = maxOf(0L, minOf(tx1, tx2).toLong())
            val xEnd = minOf(n - 1L, maxOf(tx1, tx2).toLong())
            val yStart = maxOf(0L, minOf(ty1, ty2).toLong())
            val yEnd = minOf(n - 1L, maxOf(ty1, ty2).toLong())
            if (xStart > xEnd || yStart > yEnd) continue
            total += (xEnd - xStart + 1L) * (yEnd - yStart + 1L)
        }
        return total
    }

    fun enumerateBoundingBoxTiles(
        bbox: MapBoundingBox,
        layer: MapLayer,
        minZ: Int,
        maxZ: Int,
        maxTiles: Int = 60_000
    ): List<TileCoord> {
        val hiZ = minOf(maxZ, layer.maxNativeZoom)
        if (minZ > hiZ) return emptyList()
        val list = mutableListOf<TileCoord>()

        for (z in minZ..hiZ) {
            val n = 1 shl z
            val (tx1, ty1) = latLonToTile(bbox.maxLat, bbox.minLon, z)
            val (tx2, ty2) = latLonToTile(bbox.minLat, bbox.maxLon, z)
            val xStart = maxOf(0, minOf(tx1, tx2))
            val xEnd = minOf(n - 1, maxOf(tx1, tx2))
            val yStart = maxOf(0, minOf(ty1, ty2))
            val yEnd = minOf(n - 1, maxOf(ty1, ty2))
            if (xStart > xEnd || yStart > yEnd) continue

            for (x in xStart..xEnd) {
                for (y in yStart..yEnd) {
                    list.add(TileCoord(layer, z, x, y))
                    if (list.size >= maxTiles) return list
                }
            }
        }
        return list
    }

    fun estimateCorridorTiles(
        points: List<GPXPoint>,
        layer: MapLayer,
        zooms: List<Int> = listOf(10, 11, 12, 13, 14, 15, 16),
        maxTiles: Int = 4500
    ): List<TileCoord> {
        val set = mutableSetOf<TileCoord>()
        if (points.isEmpty()) return emptyList()

        for (z in zooms) {
            if (z > layer.maxNativeZoom) continue
            val pad = 1
            val n = 1 shl z
            for (pt in points) {
                val (tx, ty) = latLonToTile(pt.latitude, pt.longitude, z)
                for (dx in -pad..pad) {
                    for (dy in -pad..pad) {
                        val nx = ((tx + dx) % n + n) % n
                        val ny = ty + dy
                        if (ny in 0 until n) {
                            set.add(TileCoord(layer, z, nx, ny))
                            if (set.size >= maxTiles) return set.toList()
                        }
                    }
                }
            }
        }
        return set.toList()
    }
}

