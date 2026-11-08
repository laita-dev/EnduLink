package com.oscar.endulink.trail

import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable
enum class TrackColor(val id: String, val rgbHex: Long) {
    ORANGE("orange", 0xFFFF600AL),
    RED("red", 0xFFF23842L),
    BLUE("blue", 0xFF2E9EFFL),
    GREEN("green", 0xFF2ED673L),
    MAGENTA("magenta", 0xFFEB47DBL),
    CYAN("cyan", 0xFF14D1E0L);

    val nextColor: TrackColor
        get() {
            val all = entries
            val idx = all.indexOf(this)
            return all[(idx + 1) % all.size]
        }

    companion object {
        fun fromId(id: String): TrackColor = entries.find { it.id.equals(id, ignoreCase = true) } ?: ORANGE
    }
}

@Serializable
data class GeoCoord(
    val latitude: Double,
    val longitude: Double
) {
    fun distanceTo(other: GeoCoord): Double {
        val lat1 = latitude * PI / 180.0
        val lat2 = other.latitude * PI / 180.0
        val dLat = (other.latitude - latitude) * PI / 180.0
        val dLon = (other.longitude - longitude) * PI / 180.0
        val a = sin(dLat / 2.0).pow(2.0) + cos(lat1) * cos(lat2) * sin(dLon / 2.0).pow(2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return 6371000.0 * c
    }
}

@Serializable
data class GPXPoint(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double? = null,
    var cumulativeKm: Double = 0.0
) {
    val coordinate: GeoCoord get() = GeoCoord(latitude, longitude)
}

@Serializable
data class GPXWaypoint(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val elevation: Double? = null,
    val symbol: String? = null,
    val createdAtEpochMs: Long? = null
) {
    val coordinate: GeoCoord get() = GeoCoord(latitude, longitude)
}

@Serializable
data class GPXRoute(
    val id: String,
    var name: String,
    var fileName: String,
    var points: List<GPXPoint>,
    var waypoints: List<GPXWaypoint>,
    var totalDistanceKm: Double,
    var elevationGainM: Double,
    var importedAtEpochMs: Long,
    var isVisible: Boolean = true,
    var color: TrackColor = TrackColor.ORANGE,
    var isInverted: Boolean = false
) {
    fun reversedRoute(): GPXRoute {
        val revPoints = points.reversed().map {
            GPXPoint(it.latitude, it.longitude, it.elevation, cumulativeKm = 0.0)
        }.toMutableList()
        val stats = GPXParser.computeStats(revPoints)
        return GPXRoute(
            id = id,
            name = GPXParser.stripInvSuffix(name),
            fileName = fileName,
            points = revPoints,
            waypoints = waypoints,
            totalDistanceKm = stats.first,
            elevationGainM = stats.second,
            importedAtEpochMs = importedAtEpochMs,
            isVisible = isVisible,
            color = color,
            isInverted = !isInverted
        )
    }

    fun toGPXXml(): String {
        fun esc(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<gpx version="1.1" creator="EnduLink" xmlns="http://www.topografix.com/GPX/1/1">""")
        sb.appendLine("  <metadata><name>${esc(name)}</name></metadata>")
        for (wpt in waypoints) {
            sb.append("""  <wpt lat="${wpt.latitude}" lon="${wpt.longitude}">""")
            wpt.elevation?.let { sb.append("<ele>${(it * 10.0).roundToInt() / 10.0}</ele>") }
            sb.append("<name>${esc(wpt.name)}</name>")
            wpt.symbol?.let { sb.append("<sym>${esc(it)}</sym>") }
            sb.appendLine("</wpt>")
        }
        sb.appendLine("  <trk>")
        sb.appendLine("    <name>${esc(name)}</name>")
        sb.appendLine("    <trkseg>")
        for (pt in points) {
            if (pt.elevation != null) {
                val ele = (pt.elevation * 10.0).roundToInt() / 10.0
                sb.appendLine("""      <trkpt lat="${pt.latitude}" lon="${pt.longitude}"><ele>$ele</ele></trkpt>""")
            } else {
                sb.appendLine("""      <trkpt lat="${pt.latitude}" lon="${pt.longitude}"/>""")
            }
        }
        sb.appendLine("    </trkseg>")
        sb.appendLine("  </trk>")
        sb.appendLine("</gpx>")
        return sb.toString()
    }

    companion object {
        fun fromCoordinates(
            name: String,
            coordinates: List<GeoCoord>,
            color: TrackColor = TrackColor.BLUE
        ): GPXRoute {
            val pts = coordinates.map { GPXPoint(it.latitude, it.longitude) }.toMutableList()
            val stats = GPXParser.computeStats(pts)
            return GPXRoute(
                id = "${name.hashCode()}_${pts.size}",
                name = name,
                fileName = "road_route.gpx",
                points = pts,
                waypoints = emptyList(),
                totalDistanceKm = stats.first,
                elevationGainM = 0.0,
                importedAtEpochMs = 0L,
                isVisible = true,
                color = color
            )
        }
    }
}

