package com.oscar.endulink.trail

import kotlin.math.*

data class TrackProgress(
    val nearestIndex: Int,
    val offTrackMeters: Double,
    val remainingKm: Double,
    val projectedCoordinate: GeoCoord,
    val reconnectCoordinate: GeoCoord,
    val bearingToReconnectDeg: Double
)

data class CrowFliesDirectHeading(
    val targetName: String,
    val targetCoord: GeoCoord,
    val distanceKm: Double,
    val bearingDeg: Double
)

object RouteNavigator {

    const val METERS_PER_DEG_LAT = 111_132.0

    fun metersPerDegLon(lat: Double): Double {
        return max(10_000.0, 111_320.0 * cos(lat * PI / 180.0))
    }

    /**
     * Calcula el rumbo en grados brújula [0, 360) entre dos coordenadas geográficas.
     */
    fun compassBearing(from: GeoCoord, to: GeoCoord): Double {
        val lat1 = from.latitude * PI / 180.0
        val lat2 = to.latitude * PI / 180.0
        val dLon = (to.longitude - from.longitude) * PI / 180.0
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val deg = atan2(y, x) * 180.0 / PI
        return (deg + 360.0) % 360.0
    }

    /**
     * Algoritmo DMD2 de proyección ortogonal sobre la traza GPX:
     * - Calcula la proyección perpendicular exacta sobre cada segmento (no solo vértices sueltos).
     * - Si te saltas puntos y vuelves a enganchar el GPX más adelante, salta al instante al tramo más cercano.
     * - Si estás fuera del track (> 35 m), calcula un punto de reconexión adelantado en el sentido de la marcha
     *   para reincorporarse con suavidad sin obligar a retroceder.
     */
    fun calculateProgress(
        route: GPXRoute,
        coord: GeoCoord,
        lastSegmentIndex: Int? = null
    ): TrackProgress {
        val pts = route.points
        if (pts.isEmpty()) {
            return TrackProgress(0, 0.0, 0.0, coord, coord, 0.0)
        }
        if (pts.size == 1) {
            val p = pts[0].coordinate
            val d = coord.distanceTo(p)
            val brg = compassBearing(coord, p)
            return TrackProgress(0, d, 0.0, p, p, brg)
        }

        val metersLat = METERS_PER_DEG_LAT
        val metersLon = metersPerDegLon(coord.latitude)

        var bestSegIdx = 0
        var bestT = 0.0
        var bestDist = Double.MAX_VALUE
        var bestScore = Double.MAX_VALUE

        val segCount = pts.size - 1
        for (i in 0 until segCount) {
            val a = pts[i]
            val b = pts[i + 1]

            val ax = (a.longitude - coord.longitude) * metersLon
            val ay = (a.latitude - coord.latitude) * metersLat
            val bx = (b.longitude - coord.longitude) * metersLon
            val by = (b.latitude - coord.latitude) * metersLat

            val dx = bx - ax
            val dy = by - ay
            val lenSq = dx * dx + dy * dy
            val t = if (lenSq > 0.0001) {
                min(max((-ax * dx - ay * dy) / lenSq, 0.0), 1.0)
            } else {
                0.0
            }

            val projX = ax + t * dx
            val projY = ay + t * dy
            val dist = hypot(projX, projY)

            // Preferencia de continuidad hacia delante en cruces o bucles sobre la misma traza
            var score = dist
            if (lastSegmentIndex != null && dist < 55.0) {
                if (i in max(0, lastSegmentIndex - 1)..(lastSegmentIndex + 35)) {
                    score -= 12.0
                }
            }

            if (score < bestScore) {
                bestScore = score
                bestDist = dist
                bestSegIdx = i
                bestT = t
            }
        }

        val segA = pts[bestSegIdx]
        val segB = pts[bestSegIdx + 1]
        val projCoord = GeoCoord(
            latitude = segA.latitude + bestT * (segB.latitude - segA.latitude),
            longitude = segA.longitude + bestT * (segB.longitude - segA.longitude)
        )

        val segKm = max(0.0, segB.cumulativeKm - segA.cumulativeKm)
        val traveledKm = segA.cumulativeKm + bestT * segKm
        val remaining = max(0.0, route.totalDistanceKm - traveledKm)

        // Si estamos fuera del track (> 35 m), adelantamos suavemente el punto objetivo (estilo DMD2)
        val leadMeters = if (bestDist > 35.0) min(70.0, bestDist * 0.32) else 0.0
        val reconnectCoord = if (leadMeters > 1.0) {
            advanceAlongTrack(pts, bestSegIdx, bestT, leadMeters, metersLat, metersLon)
        } else {
            projCoord
        }

        val bearing = compassBearing(coord, reconnectCoord)

        return TrackProgress(
            nearestIndex = bestSegIdx,
            offTrackMeters = bestDist,
            remainingKm = remaining,
            projectedCoordinate = projCoord,
            reconnectCoordinate = reconnectCoord,
            bearingToReconnectDeg = bearing
        )
    }

    private fun advanceAlongTrack(
        pts: List<GPXPoint>,
        startSeg: Int,
        startT: Double,
        leadMeters: Double,
        metersLat: Double,
        metersLon: Double
    ): GeoCoord {
        var rem = leadMeters
        var idx = startSeg
        var curT = startT
        val segCount = pts.size - 1

        while (idx < segCount) {
            val a = pts[idx]
            val b = pts[idx + 1]
            val dx = (b.longitude - a.longitude) * metersLon
            val dy = (b.latitude - a.latitude) * metersLat
            val fullLen = hypot(dx, dy)
            val availLen = fullLen * (1.0 - curT)

            if (rem <= availLen && fullLen > 0.01) {
                val finalT = min(1.0, curT + rem / fullLen)
                return GeoCoord(
                    latitude = a.latitude + finalT * (b.latitude - a.latitude),
                    longitude = a.longitude + finalT * (b.longitude - a.longitude)
                )
            }
            rem -= max(0.0, availLen)
            idx++
            curT = 0.0
        }
        return pts.last().coordinate
    }

    /**
     * Calcula rumbo y distancia directa "a vuelo de pájaro" (estilo rally raid)
     */
    fun computeCrowFlies(from: GeoCoord, to: GeoCoord, targetName: String): CrowFliesDirectHeading {
        val distM = from.distanceTo(to)
        val brg = compassBearing(from, to)
        return CrowFliesDirectHeading(
            targetName = targetName,
            targetCoord = to,
            distanceKm = distM / 1000.0,
            bearingDeg = brg
        )
    }
}

