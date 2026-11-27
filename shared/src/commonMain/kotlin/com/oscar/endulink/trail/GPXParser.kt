package com.oscar.endulink.trail

import com.oscar.endulink.bike.BikeWireException
import kotlinx.datetime.Clock
import kotlin.math.abs

object GPXParser {

    private const val MAX_STORED_TRACK_POINTS = 35_000

    fun stripInvSuffix(rawName: String): String {
        var clean = rawName
        while (clean.endsWith(" (Inv)")) {
            clean = clean.dropLast(6)
        }
        return clean
    }

    private fun cleanXmlText(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("<![CDATA[") && s.endsWith("]]>")) {
            s = s.removePrefix("<![CDATA[").removeSuffix("]]>").trim()
        }
        if (!s.contains('&')) return s
        return s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .trim()
    }

    private fun isTagBoundary(c: Char): Boolean {
        return c == '>' || c == '/' || c == ' ' || c == '\t' || c == '\r' || c == '\n'
    }

    private fun matchesTagName(xml: String, nameStart: Int, expected: String): Boolean {
        val len = expected.length
        val end = nameStart + len
        if (end > xml.length) return false
        for (k in 0 until len) {
            if (xml[nameStart + k].lowercaseChar() != expected[k]) return false
        }
        return end == xml.length || isTagBoundary(xml[end])
    }

    private fun indexOfIgnoreCase(text: String, needle: String, startIndex: Int, endIndex: Int = text.length): Int {
        val nLen = needle.length
        if (nLen == 0) return startIndex
        val limit = endIndex - nLen
        if (limit < startIndex) return -1
        val firstLower = needle[0].lowercaseChar()
        var i = startIndex
        while (i <= limit) {
            if (text[i].lowercaseChar() == firstLower) {
                var matched = true
                for (j in 1 until nLen) {
                    if (text[i + j].lowercaseChar() != needle[j].lowercaseChar()) {
                        matched = false
                        break
                    }
                }
                if (matched) return i
            }
            i++
        }
        return -1
    }

    /**
     * Extrae un atributo XML (ej. lat="40.12" o lon='-3.70') dentro del rango [start, end) sin usar Regex.
     */
    private fun extractDoubleAttr(xml: String, start: Int, end: Int, attrName: String): Double? {
        val aLen = attrName.length
        var i = start
        while (i < end - aLen - 2) {
            val prev = xml[i - 1]
            if (prev == ' ' || prev == '\t' || prev == '\r' || prev == '\n') {
                var match = true
                for (k in 0 until aLen) {
                    if (xml[i + k].lowercaseChar() != attrName[k]) {
                        match = false
                        break
                    }
                }
                if (match) {
                    var j = i + aLen
                    while (j < end && (xml[j] == ' ' || xml[j] == '\t')) j++
                    if (j < end && xml[j] == '=') {
                        j++
                        while (j < end && (xml[j] == ' ' || xml[j] == '\t')) j++
                        if (j < end && (xml[j] == '"' || xml[j] == '\'')) {
                            val quote = xml[j]
                            val valStart = j + 1
                            val valEnd = xml.indexOf(quote, valStart)
                            if (valEnd in valStart until end) {
                                return xml.substring(valStart, valEnd).trim().toDoubleOrNull()
                            }
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * Extrae el texto interno de una subetiqueta simple (<ele>, <name>, <sym>) dentro de [bodyStart, bodyEnd).
     */
    private fun extractChildTagText(xml: String, bodyStart: Int, bodyEnd: Int, tagName: String): String? {
        val openNeedle = "<$tagName"
        var searchPos = bodyStart
        while (searchPos < bodyEnd) {
            val openIdx = indexOfIgnoreCase(xml, openNeedle, searchPos, bodyEnd)
            if (openIdx < 0) return null
            val afterName = openIdx + openNeedle.length
            if (afterName >= bodyEnd) return null
            val boundary = xml[afterName]
            if (boundary != '>' && boundary != ' ' && boundary != '\t' && boundary != '\r' && boundary != '\n' && boundary != '/') {
                searchPos = afterName
                continue
            }
            val openEnd = xml.indexOf('>', afterName)
            if (openEnd < 0 || openEnd >= bodyEnd) return null
            if (xml[openEnd - 1] == '/') return null
            val closeNeedle = "</$tagName>"
            val closeIdx = indexOfIgnoreCase(xml, closeNeedle, openEnd + 1, bodyEnd)
            if (closeIdx < 0) return null
            return xml.substring(openEnd + 1, closeIdx)
        }
        return null
    }

    /**
     * Parser GPX lineal O(N) sin backtracking de Regex.
     * Soporta ficheros masivos (ej. TET de 2–25 MB y decenas de miles de puntos) sin StackOverflowError ni bloqueos.
     */
    fun parse(xmlContent: String, fallbackName: String, color: TrackColor = TrackColor.ORANGE): GPXRoute {
        val len = xmlContent.length
        val trkPoints = ArrayList<GPXPoint>(1024)
        val rtePoints = ArrayList<GPXPoint>(64)
        val waypoints = ArrayList<GPXWaypoint>(32)

        val headerNameCandidates = ArrayList<String>(4)
        var fallbackNameCandidate: String? = null
        var parsedNotes = ""

        var inHeaderContainer = 0 // profundidad dentro de <metadata>, <trk> o <rte>
        var inTrackSeg = false
        val nowMs = Clock.System.now().toEpochMilliseconds()

        var pos = 0
        while (pos < len) {
            val lt = xmlContent.indexOf('<', pos)
            if (lt < 0 || lt + 1 >= len) break

            val nextCh = xmlContent[lt + 1]

            // Comentarios XML <!-- ... -->
            if (nextCh == '!' && lt + 3 < len && xmlContent[lt + 2] == '-' && xmlContent[lt + 3] == '-') {
                val commentEnd = xmlContent.indexOf("-->", lt + 4)
                pos = if (commentEnd >= 0) commentEnd + 3 else len
                continue
            }

            // CDATA <![CDATA[ ... ]]>
            if (nextCh == '!' && xmlContent.regionMatches(lt, "<![CDATA[", 0, 9, ignoreCase = false)) {
                val cdataEnd = xmlContent.indexOf("]]>", lt + 9)
                pos = if (cdataEnd >= 0) cdataEnd + 3 else len
                continue
            }

            // Declaración <?xml ... ?> o DOCTYPE
            if (nextCh == '?' || nextCh == '!') {
                val close = xmlContent.indexOf('>', lt + 2)
                pos = if (close >= 0) close + 1 else len
                continue
            }

            // Etiquetas de cierre </...>
            if (nextCh == '/') {
                val nameStart = lt + 2
                val close = xmlContent.indexOf('>', nameStart)
                if (close < 0) break
                if (matchesTagName(xmlContent, nameStart, "trkseg")) {
                    inTrackSeg = false
                } else if (
                    matchesTagName(xmlContent, nameStart, "metadata") ||
                    matchesTagName(xmlContent, nameStart, "trk") ||
                    matchesTagName(xmlContent, nameStart, "rte")
                ) {
                    if (inHeaderContainer > 0) inHeaderContainer--
                }
                pos = close + 1
                continue
            }

            val nameStart = lt + 1
            val openEnd = xmlContent.indexOf('>', nameStart)
            if (openEnd < 0) break
            val isSelfClosing = xmlContent[openEnd - 1] == '/'

            when {
                matchesTagName(xmlContent, nameStart, "trkpt") -> {
                    val lat = extractDoubleAttr(xmlContent, nameStart + 5, openEnd, "lat")
                    val lon = extractDoubleAttr(xmlContent, nameStart + 5, openEnd, "lon")
                    var ele: Double? = null
                    var nextPos = openEnd + 1
                    if (!isSelfClosing) {
                        val closeIdx = indexOfIgnoreCase(xmlContent, "</trkpt>", openEnd + 1, len)
                        if (closeIdx >= 0) {
                            ele = extractChildTagText(xmlContent, openEnd + 1, closeIdx, "ele")
                                ?.trim()?.toDoubleOrNull()
                            nextPos = closeIdx + 8
                        }
                    }
                    if (lat != null && lon != null) {
                        trkPoints.add(GPXPoint(lat, lon, ele))
                    }
                    pos = nextPos
                }

                matchesTagName(xmlContent, nameStart, "rtept") -> {
                    val lat = extractDoubleAttr(xmlContent, nameStart + 5, openEnd, "lat")
                    val lon = extractDoubleAttr(xmlContent, nameStart + 5, openEnd, "lon")
                    var ele: Double? = null
                    var nextPos = openEnd + 1
                    if (!isSelfClosing) {
                        val closeIdx = indexOfIgnoreCase(xmlContent, "</rtept>", openEnd + 1, len)
                        if (closeIdx >= 0) {
                            ele = extractChildTagText(xmlContent, openEnd + 1, closeIdx, "ele")
                                ?.trim()?.toDoubleOrNull()
                            nextPos = closeIdx + 8
                        }
                    }
                    if (lat != null && lon != null) {
                        rtePoints.add(GPXPoint(lat, lon, ele))
                    }
                    pos = nextPos
                }

                matchesTagName(xmlContent, nameStart, "wpt") -> {
                    val lat = extractDoubleAttr(xmlContent, nameStart + 3, openEnd, "lat")
                    val lon = extractDoubleAttr(xmlContent, nameStart + 3, openEnd, "lon")
                    var ele: Double? = null
                    var wptName = "Punto"
                    var sym: String? = null
                    var nextPos = openEnd + 1
                    if (!isSelfClosing) {
                        val closeIdx = indexOfIgnoreCase(xmlContent, "</wpt>", openEnd + 1, len)
                        if (closeIdx >= 0) {
                            ele = extractChildTagText(xmlContent, openEnd + 1, closeIdx, "ele")
                                ?.trim()?.toDoubleOrNull()
                            val rawWptName = extractChildTagText(xmlContent, openEnd + 1, closeIdx, "name")
                            wptName = rawWptName?.let { cleanXmlText(it) }?.takeIf { it.isNotEmpty() } ?: "Punto"
                            sym = extractChildTagText(xmlContent, openEnd + 1, closeIdx, "sym")?.trim()?.takeIf { it.isNotEmpty() }
                            nextPos = closeIdx + 6
                        }
                    }
                    if (lat != null && lon != null) {
                        waypoints.add(
                            GPXWaypoint(
                                id = "$wptName-$lat-$lon",
                                name = wptName,
                                latitude = lat,
                                longitude = lon,
                                elevation = ele,
                                symbol = sym,
                                createdAtEpochMs = nowMs
                            )
                        )
                    }
                    pos = nextPos
                }

                matchesTagName(xmlContent, nameStart, "trkseg") -> {
                    if (!isSelfClosing) inTrackSeg = true
                    pos = openEnd + 1
                }

                matchesTagName(xmlContent, nameStart, "metadata") ||
                    matchesTagName(xmlContent, nameStart, "trk") ||
                    matchesTagName(xmlContent, nameStart, "rte") -> {
                    if (!isSelfClosing) inHeaderContainer++
                    pos = openEnd + 1
                }

                !inTrackSeg && matchesTagName(xmlContent, nameStart, "name") -> {
                    var nextPos = openEnd + 1
                    if (!isSelfClosing) {
                        val closeIdx = indexOfIgnoreCase(xmlContent, "</name>", openEnd + 1, len)
                        if (closeIdx >= 0) {
                            val cleaned = cleanXmlText(xmlContent.substring(openEnd + 1, closeIdx))
                            if (cleaned.isNotEmpty() && !cleaned.startsWith("http", ignoreCase = true)) {
                                if (inHeaderContainer > 0) {
                                    headerNameCandidates.add(cleaned)
                                } else if (fallbackNameCandidate == null) {
                                    fallbackNameCandidate = cleaned
                                }
                            }
                            nextPos = closeIdx + 7
                        }
                    }
                    pos = nextPos
                }

                !inTrackSeg && inHeaderContainer > 0 && parsedNotes.isEmpty() && matchesTagName(xmlContent, nameStart, "desc") -> {
                    var nextPos = openEnd + 1
                    if (!isSelfClosing) {
                        val closeIdx = indexOfIgnoreCase(xmlContent, "</desc>", openEnd + 1, len)
                        if (closeIdx >= 0) {
                            val cleaned = cleanXmlText(xmlContent.substring(openEnd + 1, closeIdx))
                            if (cleaned.isNotEmpty() && !cleaned.contains("<") && cleaned.length <= 280) {
                                parsedNotes = cleaned
                            }
                            nextPos = closeIdx + 7
                        }
                    }
                    pos = nextPos
                }

                else -> {
                    pos = openEnd + 1
                }
            }
        }

        val rawPoints = if (trkPoints.isNotEmpty()) trkPoints else rtePoints
        if (rawPoints.size < 2) {
            throw BikeWireException.Io("El archivo GPX no contiene suficientes puntos de ruta (<trkpt> o <rtept>)")
        }

        val stats = computeStats(rawPoints)
        val chosenPoints = if (rawPoints.size > MAX_STORED_TRACK_POINTS) {
            downsamplePreservingEndpoints(rawPoints, MAX_STORED_TRACK_POINTS)
        } else {
            rawPoints
        }

        val routeName = headerNameCandidates.firstOrNull() ?: fallbackNameCandidate.orEmpty()
        val cleanFallback = fallbackName.substringBeforeLast(".")
        val finalName = if (routeName.isNotBlank()) routeName else cleanFallback
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
            color = color,
            notes = parsedNotes
        )
    }

    /**
     * Reduce tracks extremadamente densos (> 35.000 puntos) manteniendo inicio, final y
     * puntos equidistantes en distancia acumulada para proteger RAM, disco y fluidez del mapa.
     */
    internal fun downsamplePreservingEndpoints(points: List<GPXPoint>, maxPoints: Int): List<GPXPoint> {
        if (points.size <= maxPoints || maxPoints < 2) return points
        val result = ArrayList<GPXPoint>(maxPoints)
        val lastIdx = points.lastIndex
        val step = lastIdx.toDouble() / (maxPoints - 1).toDouble()
        result.add(points[0])
        var prevPicked = 0
        for (k in 1 until maxPoints - 1) {
            val idx = (k * step).toInt().coerceIn(prevPicked + 1, lastIdx - 1)
            result.add(points[idx])
            prevPicked = idx
        }
        result.add(points[lastIdx])
        return result
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
