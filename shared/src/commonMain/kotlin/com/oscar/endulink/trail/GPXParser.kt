package com.oscar.endulink.trail

import com.oscar.endulink.bike.BikeWireException
import kotlinx.datetime.Clock
import kotlin.math.abs

object GPXParser {

    fun stripInvSuffix(rawName: String): String {
        var clean = rawName
        while (clean.endsWith(" (Inv)")) {
            clean = clean.dropLast(6)
        }
        return clean
    }

    fun parse(xmlContent: String, fallbackName: String, color: TrackColor = TrackColor.ORANGE): GPXRoute {
        var routeName = ""
        val trkPoints = mutableListOf<GPXPoint>()
        val rtePoints = mutableListOf<GPXPoint>()
        val waypoints = mutableListOf<GPXWaypoint>()

        // Extracción sencilla y robusta de nombre de ruta
        val nameRegex = Regex("<name>(.*?)</name>", RegexOption.IGNORE_CASE)
        val nameMatch = nameRegex.find(xmlContent)
        if (nameMatch != null) {
            val candidate = nameMatch.groupValues[1].trim()
            if (candidate.isNotEmpty() && !candidate.startsWith("http")) {
                routeName = candidate
            }
        }

        // Parseo de trkpt y rtept
        val ptRegex = Regex("<(trkpt|rtept|wpt)\\s+([^>]+)>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val latRegex = Regex("lat=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val lonRegex = Regex("lon=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val eleRegex = Regex("<ele>([^<]+)</ele>", RegexOption.IGNORE_CASE)
        val wptNameRegex = Regex("<name>([^<]+)</name>", RegexOption.IGNORE_CASE)
        val symRegex = Regex("<sym>([^<]+)</sym>", RegexOption.IGNORE_CASE)

        for (match in ptRegex.findAll(xmlContent)) {
            val tag = match.groupValues[1].lowercase()
            val attrs = match.groupValues[2]
            val body = match.groupValues[3]

            val latStr = latRegex.find(attrs)?.groupValues?.get(1)
            val lonStr = lonRegex.find(attrs)?.groupValues?.get(1)
            val lat = latStr?.toDoubleOrNull() ?: continue
            val lon = lonStr?.toDoubleOrNull() ?: continue
            val ele = eleRegex.find(body)?.groupValues?.get(1)?.toDoubleOrNull()

            when (tag) {
                "trkpt" -> {
                    trkPoints.add(GPXPoint(lat, lon, ele))
                }
                "rtept" -> {
                    rtePoints.add(GPXPoint(lat, lon, ele))
                }
                "wpt" -> {
                    val wptName = wptNameRegex.find(body)?.groupValues?.get(1)?.trim() ?: "Punto"
                    val sym = symRegex.find(body)?.groupValues?.get(1)?.trim()
                    waypoints.add(
                        GPXWaypoint(
                            id = "$wptName-$lat-$lon",
                            name = wptName,
                            latitude = lat,
                            longitude = lon,
                            elevation = ele,
                            symbol = sym,
                            createdAtEpochMs = Clock.System.now().toEpochMilliseconds()
                        )
                    )
                }
            }
        }

        val chosenPoints = if (trkPoints.isNotEmpty()) trkPoints else rtePoints
        if (chosenPoints.size < 2) {
            throw BikeWireException.Io("El archivo GPX no contiene suficientes puntos de ruta (<trkpt> o <rtept>)")
        }

        val stats = computeStats(chosenPoints)
        val cleanFallback = fallbackName.substringBeforeLast(".")
        val finalName = if (routeName.isNotBlank()) routeName else cleanFallback

        val nowMs = Clock.System.now().toEpochMilliseconds()
        val routeId = "${nowMs}_${abs(finalName.hashCode())}"

        return GPXRoute(
            id = routeId,
            name = stripInvSuffix(finalName),
            fileName = fallbackName,
            points = chosenPoints,
            waypoints = waypoints,
            totalDistanceKm = stats.first,
            elevationGainM = stats.second,
            importedAtEpochMs = nowMs,
            isVisible = true,
            color = color
        )
    }

    /**
     * Calcula la distancia acumulada y el desnivel positivo con filtro de histéresis de 1.5 m
     * para eliminar el ruido barométrico del sensor GPS.
     */
    fun computeStats(points: MutableList<GPXPoint>): Pair<Double, Double> {
        if (points.isEmpty()) return Pair(0.0, 0.0)
        var totalMeters = 0.0
        var gain = 0.0
        points[0].cumulativeKm = 0.0
        var lastEle = points[0].elevation

        for (i in 1 until points.size) {
            val prev = points[i - 1].coordinate
            val curr = points[i].coordinate
            val d = curr.distanceTo(prev)
            totalMeters += d
            points[i].cumulativeKm = totalMeters / 1000.0

            val e = points[i].elevation
            if (e != null) {
                val le = lastEle
                if (le != null && e > le + 1.5) {
                    gain += (e - le)
                    lastEle = e
                } else if (le == null || e < le - 1.5) {
                    lastEle = e
                }
            }
        }
        return Pair(totalMeters / 1000.0, gain)
    }
}

