package com.oscar.endulink.trail

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Perfiles de enrutado moto / trail / enduro inspirados en DMD2.
 * Mapean a perfiles reales de BRouter (con datos OSM de pistas, senderos y elevación 3D)
 * y fallback a OSRM / enlace offline.
 */
@Serializable
enum class RouteTerrainProfile(
    val title: String,
    val shortLabel: String,
    val difficultyBadge: String,
    val subtitle: String,
    val icon: String,
    val brouterProfile: String,
    val osrmProfile: String,
    val trackColor: TrackColor,
    val windingFactor: Double,
    val isOffRoad: Boolean,
    val avgSpeedKmh: Double
) {
    ROAD_FAST(
        title = "Carretera Rápida",
        shortLabel = "Carretera",
        difficultyBadge = "Asfalto",
        subtitle = "Vías principales y enlace rápido",
        icon = "🛣️",
        brouterProfile = "car-fast",
        osrmProfile = "driving",
        trackColor = TrackColor.BLUE,
        windingFactor = 1.32,
        isOffRoad = false,
        avgSpeedKmh = 68.0
    ),
    ROAD_TWISTY(
        title = "Carretera Curvas",
        shortLabel = "Curvas",
        difficultyBadge = "Secundarias",
        subtitle = "Evita autopistas, prioriza puertos y carreteras secundarias",
        icon = "🏍️",
        brouterProfile = "fastbike-lowtraffic",
        osrmProfile = "driving",
        trackColor = TrackColor.CYAN,
        windingFactor = 1.40,
        isOffRoad = false,
        avgSpeedKmh = 54.0
    ),
    TRAIL_EASY(
        title = "Trail / Pistas",
        shortLabel = "Trail Fácil",
        difficultyBadge = "Fácil · Nivel 1",
        subtitle = "Pistas anchas y grava compacta aptas para Maxi-Trail",
        icon = "🪨",
        brouterProfile = "gravel",
        osrmProfile = "bike",
        trackColor = TrackColor.ORANGE,
        windingFactor = 1.45,
        isOffRoad = true,
        avgSpeedKmh = 36.0
    ),
    OFFROAD_MEDIUM(
        title = "Off-Road Medio",
        shortLabel = "Off-Road",
        difficultyBadge = "Medio · Nivel 2",
        subtitle = "Caminos de tierra, pistas forestales y enlace por monte",
        icon = "🌲",
        brouterProfile = "trekking",
        osrmProfile = "bike",
        trackColor = TrackColor.YELLOW,
        windingFactor = 1.50,
        isOffRoad = true,
        avgSpeedKmh = 28.0
    ),
    ENDURO_HARD(
        title = "Enduro Técnico",
        shortLabel = "Enduro",
        difficultyBadge = "Difícil · Nivel 3",
        subtitle = "Senderos, trialeras y pistas técnicas estrechas",
        icon = "⛰️",
        brouterProfile = "mtb",
        osrmProfile = "foot",
        trackColor = TrackColor.RED,
        windingFactor = 1.58,
        isOffRoad = true,
        avgSpeedKmh = 20.0
    )
}

/** Orientación preferida para generación de rutas circulares (Loops). */
@Serializable
enum class LoopDirection(
    val label: String,
    val icon: String,
    val bearingDeg: Double?
) {
    AUTO("Automático", "🧭", null),
    NORTH("Norte", "⬆️", 0.0),
    EAST("Este", "➡️", 90.0),
    SOUTH("Sur", "⬇️", 180.0),
    WEST("Oeste", "⬅️", 270.0)
}

/** Maniobra individual de una ruta por carretera con su coordenada exacta. */
data class RoadStep(
    val coordinate: GeoCoord,
    val instruction: String,
    val distanceMeters: Double = 0.0
)

/**
 * Ruta calculada (A→B, enlace o Loop generado).
 * Vive de forma temporal hasta que el usuario pulsa "Guardar en Tracks".
 */
data class CalculatedRoadRoute(
    val destinationName: String,
    val routeLabel: String,
    val destinationCoordinate: GeoCoord,
    val coordinates: List<GeoCoord>,
    val distanceKm: Double,
    val expectedMinutes: Int,
    val hasTolls: Boolean,
    val firstInstruction: String?,
    val steps: List<RoadStep> = emptyList(),
    val asGPXRoute: GPXRoute,
    val isOfflineFallback: Boolean = false,
    val profile: RouteTerrainProfile = RouteTerrainProfile.ROAD_FAST,
    val elevationGainM: Double = asGPXRoute.elevationGainM
) {
    private fun projectProgress(
        point: GeoCoord,
        startSeg: Int = 0,
        earlyStopDistM: Double? = null
    ): Double {
        if (coordinates.size < 2) return 0.0
        val cosLat = cos(point.latitude * PI / 180.0).coerceAtLeast(0.05)
        val scaleX = 111_320.0 * cosLat
        val scaleY = 110_574.0
        val maxSeg = coordinates.lastIndex - 1
        val from = startSeg.coerceIn(0, maxSeg)
        var bestProgress = from.toDouble()
        var bestDistSq = Double.MAX_VALUE
        val earlyStopSq = earlyStopDistM?.let { it * it }

        for (i in from..maxSeg) {
            val a = coordinates[i]
            val b = coordinates[i + 1]
            val dx = (b.longitude - a.longitude) * scaleX
            val dy = (b.latitude - a.latitude) * scaleY
            val lenSq = dx * dx + dy * dy
            val t = if (lenSq < 1e-6) {
                0.0
            } else {
                (((point.longitude - a.longitude) * scaleX * dx +
                    (point.latitude - a.latitude) * scaleY * dy) / lenSq).coerceIn(0.0, 1.0)
            }
            val projLat = a.latitude + (b.latitude - a.latitude) * t
            val projLon = a.longitude + (b.longitude - a.longitude) * t
            val ex = (point.longitude - projLon) * scaleX
            val ey = (point.latitude - projLat) * scaleY
            val distSq = ex * ex + ey * ey
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                bestProgress = i.toDouble() + t
                if (earlyStopSq != null && distSq <= earlyStopSq) break
            }
        }
        return bestProgress
    }

    private val stepProgressPositions: DoubleArray by lazy {
        if (steps.isEmpty() || coordinates.size < 2) {
            DoubleArray(steps.size)
        } else {
            val out = DoubleArray(steps.size)
            var searchFrom = 0
            val maxSeg = (coordinates.lastIndex - 1).coerceAtLeast(0)
            for (k in steps.indices) {
                val pos = projectProgress(
                    point = steps[k].coordinate,
                    startSeg = searchFrom,
                    earlyStopDistM = 1.5
                )
                out[k] = pos
                searchFrom = pos.toInt().coerceIn(0, maxSeg)
            }
            out
        }
    }

    /**
     * Devuelve la instrucción de giro activa o más próxima por delante del piloto,
     * actualizándose en tiempo real a lo largo de todos los tramos y paradas sin retroceder a giros ya pasados.
     */
    fun currentInstruction(riderCoord: GeoCoord?): String? {
        if (steps.isEmpty()) return firstInstruction
        if (riderCoord == null) return firstInstruction ?: steps.firstOrNull()?.instruction

        val riderProgress = projectProgress(riderCoord)
        val positions = stepProgressPositions

        val targetStep = if (isOfflineFallback) {
            var legIdx = 0
            for (k in positions.indices) {
                if (positions[k] <= riderProgress + 1e-4) legIdx = k else break
            }
            return steps[legIdx].instruction
        } else {
            var chosen = steps.lastIndex
            for (k in steps.indices) {
                if (positions[k] >= riderProgress - 1e-4) {
                    chosen = k
                    break
                }
            }
            steps[chosen]
        }

        val distToTurn = riderCoord.distanceTo(targetStep.coordinate)
        val distPrefix = when {
            distToTurn < 35.0 -> "Ahora: "
            distToTurn < 1000.0 -> "A ${max(10, (distToTurn / 10.0).roundToInt() * 10)} m · "
            else -> "A ${(distToTurn / 100.0).roundToInt() / 10.0} km · "
        }
        return "$distPrefix${targetStep.instruction}"
    }
}

/**
 * Motor de rutas multi-terreno:
 * - BRouter GeoJSON para Carretera con Curvas, Trail Pistas, Off-Road Medio y Enduro Técnico (con altimetría 3D).
 * - OSRM para Carretera Rápida y fallback automático si BRouter no tiene cobertura/red.
 * - Generador geométrico + enrutado de rutas circulares (Loops) por kilometraje y orientación.
 */
object RoadRouter {

    private val httpClient = HttpClient()
    private const val EARTH_RADIUS_KM = 6371.0

    /**
     * Calcula opciones de ruta estándar (compatibilidad existente).
     */
    suspend fun calculateRoutes(
        origin: GeoCoord,
        destination: GeoCoord,
        destinationName: String,
        requestAlternatives: Boolean = true,
        color: TrackColor = TrackColor.BLUE,
        customSingleLabel: String? = null
    ): List<CalculatedRoadRoute> = calculateMultiWaypointRoute(
        origin = origin,
        waypoints = emptyList(),
        destination = destination,
        destinationName = destinationName,
        requestAlternatives = requestAlternatives,
        color = color,
        customSingleLabel = customSingleLabel
    )

    /**
     * Calcula rutas por carretera / pista / enduro pasando por paradas intermedias opcionales
     * según el perfil de terreno y dificultad elegido.
     */
    suspend fun calculateRoutesWithProfile(
        origin: GeoCoord,
        destination: GeoCoord,
        destinationName: String,
        waypoints: List<GeoCoord> = emptyList(),
        profile: RouteTerrainProfile = RouteTerrainProfile.ROAD_FAST,
        requestAlternatives: Boolean = true,
        customSingleLabel: String? = null
    ): List<CalculatedRoadRoute> = withContext(Dispatchers.Default) {
        val allPoints = listOf(origin) + waypoints + listOf(destination)

        if (profile != RouteTerrainProfile.ROAD_FAST) {
            val brouterResults = queryBRouterAlternatives(
                stops = allPoints,
                destinationName = destinationName,
                profile = profile,
                requestAlternatives = requestAlternatives,
                customLabel = customSingleLabel
            )
            if (brouterResults.isNotEmpty()) {
                return@withContext brouterResults
            }
            currentCoroutineContext().ensureActive()
        }

        val osrmResult = runCatching {
            queryOsrmRoute(
                allPoints = allPoints,
                destinationName = destinationName,
                requestAlternatives = requestAlternatives && waypoints.isEmpty(),
                color = profile.trackColor,
                profile = profile,
                customSingleLabel = customSingleLabel
            )
        }.getOrNull()

        if (!osrmResult.isNullOrEmpty()) {
            return@withContext osrmResult
        }

        currentCoroutineContext().ensureActive()

        if (profile == RouteTerrainProfile.ROAD_FAST) {
            val brouterFallback = queryBRouterAlternatives(
                stops = allPoints,
                destinationName = destinationName,
                profile = profile,
                requestAlternatives = false,
                customLabel = customSingleLabel
            )
            if (brouterFallback.isNotEmpty()) {
                return@withContext brouterFallback
            }
        }

        val offline = buildOfflineRoute(
            stops = allPoints,
            destinationName = destinationName,
            color = profile.trackColor,
            profile = profile,
            customSingleLabel = customSingleLabel
        ) ?: error("No se pudo construir enlace offline")
        listOf(offline)
    }

    /**
     * Calcula ruta multi-parada (compatibilidad con llamadas existentes; usa perfil ROAD_FAST si no se especifica).
     */
    suspend fun calculateMultiWaypointRoute(
        origin: GeoCoord,
        waypoints: List<GeoCoord>,
        destination: GeoCoord,
        destinationName: String,
        requestAlternatives: Boolean = true,
        color: TrackColor = TrackColor.BLUE,
        customSingleLabel: String? = null,
        profile: RouteTerrainProfile = RouteTerrainProfile.ROAD_FAST
    ): List<CalculatedRoadRoute> = withContext(Dispatchers.Default) {
        if (profile != RouteTerrainProfile.ROAD_FAST) {
            return@withContext calculateRoutesWithProfile(
                origin = origin,
                destination = destination,
                destinationName = destinationName,
                waypoints = waypoints,
                profile = profile,
                requestAlternatives = requestAlternatives,
                customSingleLabel = customSingleLabel
            )
        }

        val allPoints = listOf(origin) + waypoints + listOf(destination)
        val osrmResult = runCatching {
            queryOsrmRoute(
                allPoints = allPoints,
                destinationName = destinationName,
                requestAlternatives = requestAlternatives && waypoints.isEmpty(),
                color = color,
                profile = profile,
                customSingleLabel = customSingleLabel
            )
        }.getOrNull()

        if (!osrmResult.isNullOrEmpty()) return@withContext osrmResult
        currentCoroutineContext().ensureActive()

        val offline = buildOfflineRoute(
            stops = allPoints,
            destinationName = destinationName,
            color = color,
            profile = profile,
            customSingleLabel = customSingleLabel
        ) ?: error("No se pudo construir enlace offline")
        listOf(offline)
    }

    /**
     * Calcula 3 vértices geográficos para formar un bucle circular (Loop) desde [origin]
     * cuyo recorrido real enruta alrededor de [targetDistanceKm].
     */
    fun generateLoopWaypoints(
        origin: GeoCoord,
        targetDistanceKm: Double,
        bearingDeg: Double,
        windingFactor: Double
    ): List<GeoCoord> {
        val safeKm = targetDistanceKm.coerceIn(5.0, 400.0)
        val safeWinding = windingFactor.coerceIn(1.1, 2.2)
        // Perímetro euclídeo del pétalo (origin -> wp1 -> wp2 -> wp3 -> origin) ≈ 2.95 * radiusKm
        val radiusKm = (safeKm / safeWinding) / 2.95
        val wp1 = projectCoordinate(origin, bearingDeg - 35.0, radiusKm * 0.78)
        val wp2 = projectCoordinate(origin, bearingDeg, radiusKm * 1.05)
        val wp3 = projectCoordinate(origin, bearingDeg + 35.0, radiusKm * 0.78)
        return listOf(wp1, wp2, wp3)
    }

    /**
     * Proyecta una coordenada desde [origin] avanzando [distanceKm] con rumbo [bearingDeg].
     */
    fun projectCoordinate(origin: GeoCoord, bearingDeg: Double, distanceKm: Double): GeoCoord {
        val lat1 = origin.latitude * PI / 180.0
        val lon1 = origin.longitude * PI / 180.0
        val brng = ((bearingDeg % 360.0) + 360.0) % 360.0 * PI / 180.0
        val angularDist = distanceKm / EARTH_RADIUS_KM

        val sinLat1 = sin(lat1)
        val cosLat1 = cos(lat1)
        val sinAd = sin(angularDist)
        val cosAd = cos(angularDist)

        val lat2 = asin((sinLat1 * cosAd + cosLat1 * sinAd * cos(brng)).coerceIn(-1.0, 1.0))
        val lon2 = lon1 + atan2(
            sin(brng) * sinAd * cosLat1,
            cosAd - sinLat1 * sin(lat2)
        )
        return GeoCoord(lat2 * 180.0 / PI, lon2 * 180.0 / PI)
    }

    /**
     * Genera hasta 2–3 opciones de ruta circular (Loop) alrededor de [origin]
     * respetando el kilometraje objetivo, rumbo y perfil de terreno/dificultad.
     */
    suspend fun generateLoopRoutes(
        origin: GeoCoord,
        originName: String,
        targetDistanceKm: Double,
        profile: RouteTerrainProfile,
        direction: LoopDirection = LoopDirection.AUTO
    ): List<CalculatedRoadRoute> = withContext(Dispatchers.Default) {
        val bearings = if (direction.bearingDeg != null) {
            listOf(direction.bearingDeg, (direction.bearingDeg + 28.0) % 360.0)
        } else {
            listOf(35.0, 155.0, 275.0)
        }

        val routeName = "Loop ${profile.shortLabel} (${targetDistanceKm.roundToInt()} km)"
        val results = coroutineScope {
            bearings.mapIndexed { idx, brg ->
                async {
                    val vias = generateLoopWaypoints(
                        origin = origin,
                        targetDistanceKm = targetDistanceKm,
                        bearingDeg = brg,
                        windingFactor = profile.windingFactor
                    )
                    val stops = listOf(origin) + vias + listOf(origin)
                    val labelPrefix = "Loop ${cardinalLabel(brg.roundToInt())}"

                    val brouter = if (profile != RouteTerrainProfile.ROAD_FAST) {
                        queryBRouterSingle(
                            stops = stops,
                            destinationName = routeName,
                            profile = profile,
                            alternativeIdx = 0,
                            customLabel = labelPrefix
                        )
                    } else {
                        null
                    }

                    brouter ?: runCatching {
                        queryOsrmRoute(
                            allPoints = stops,
                            destinationName = routeName,
                            requestAlternatives = false,
                            color = profile.trackColor,
                            profile = profile,
                            customSingleLabel = labelPrefix
                        ).firstOrNull()
                    }.getOrNull() ?: if (profile == RouteTerrainProfile.ROAD_FAST) {
                        queryBRouterSingle(
                            stops = stops,
                            destinationName = routeName,
                            profile = profile,
                            alternativeIdx = 0,
                            customLabel = labelPrefix
                        )
                    } else {
                        null
                    }
                }
            }.mapNotNull { it.await() }
        }

        if (results.isNotEmpty()) {
            return@withContext deduplicateRouteLabels(results)
        }

        currentCoroutineContext().ensureActive()

        val fallbackVias = generateLoopWaypoints(
            origin = origin,
            targetDistanceKm = targetDistanceKm,
            bearingDeg = direction.bearingDeg ?: 45.0,
            windingFactor = profile.windingFactor
        )
        val fallbackStops = listOf(origin) + fallbackVias + listOf(origin)
        val offline = buildOfflineRoute(
            stops = fallbackStops,
            destinationName = routeName,
            color = profile.trackColor,
            profile = profile,
            customSingleLabel = "Loop desde $originName"
        ) ?: error("No se pudo generar ruta circular")
        listOf(offline)
    }

    private suspend fun queryBRouterAlternatives(
        stops: List<GeoCoord>,
        destinationName: String,
        profile: RouteTerrainProfile,
        requestAlternatives: Boolean,
        customLabel: String?
    ): List<CalculatedRoadRoute> = coroutineScope {
        val altIndices = if (requestAlternatives && stops.size <= 3) listOf(0, 1) else listOf(0)
        val fetched = altIndices.mapIndexed { idx, alt ->
            async {
                val label = customLabel ?: if (idx == 0) profile.shortLabel else "${profile.shortLabel} alt ${idx + 1}"
                queryBRouterSingle(
                    stops = stops,
                    destinationName = destinationName,
                    profile = profile,
                    alternativeIdx = alt,
                    customLabel = label
                )
            }
        }.mapNotNull { it.await() }

        // Evitar duplicados si BRouter devuelve exactamente la misma geometría en alternativeidx=1
        val unique = mutableListOf<CalculatedRoadRoute>()
        val seenKeys = HashSet<String>()
        for (item in fetched) {
            val sig = "${(item.distanceKm * 20).roundToInt()}_${item.coordinates.size}"
            if (seenKeys.add(sig)) {
                unique.add(item)
            }
        }
        deduplicateRouteLabels(unique)
    }

    private suspend fun queryBRouterSingle(
        stops: List<GeoCoord>,
        destinationName: String,
        profile: RouteTerrainProfile,
        alternativeIdx: Int,
        customLabel: String
    ): CalculatedRoadRoute? = runCatching {
        if (stops.size < 2) return@runCatching null
        val lonLats = stops.joinToString("%7C") { "${it.longitude},${it.latitude}" }
        val url = "https://brouter.de/brouter?lonlats=$lonLats&profile=${profile.brouterProfile}&alternativeidx=$alternativeIdx&format=geojson"

        withTimeoutOrNull(7500L) {
            val response = httpClient.get(url) {
                headers {
                    append(HttpHeaders.UserAgent, "EnduLink/0.3 (Trail & Motorcycle Navigation)")
                    append(HttpHeaders.Accept, "application/json")
                }
            }
            if (response.status != HttpStatusCode.OK) return@withTimeoutOrNull null
            parseBRouterGeoJson(
                body = response.bodyAsText(),
                destination = stops.last(),
                destinationName = destinationName,
                profile = profile,
                routeLabel = customLabel
            )
        }
    }.getOrNull()

    internal fun parseBRouterGeoJson(
        body: String,
        destination: GeoCoord,
        destinationName: String,
        profile: RouteTerrainProfile,
        routeLabel: String
    ): CalculatedRoadRoute? {
        val root = Json.parseToJsonElement(body).jsonObject
        val feature = root["features"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val geometry = feature["geometry"]?.jsonObject ?: return null
        val rawCoords = geometry["coordinates"]?.jsonArray ?: return null
        if (rawCoords.size < 2) return null

        val gpxPoints = ArrayList<GPXPoint>(rawCoords.size)
        val coords = ArrayList<GeoCoord>(rawCoords.size)
        for (el in rawCoords) {
            val arr = el.jsonArray
            val lon = arr.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: continue
            val lat = arr.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: continue
            val ele = arr.getOrNull(2)?.jsonPrimitive?.doubleOrNull
            coords.add(GeoCoord(lat, lon))
            gpxPoints.add(GPXPoint(lat, lon, ele))
        }
        if (coords.size < 2) return null

        val gpxRoute = GPXRoute.fromPoints(
            name = destinationName,
            points = gpxPoints,
            color = profile.trackColor,
            notes = "${profile.title} (${profile.difficultyBadge})"
        )

        val props = feature["properties"]?.jsonObject
        val trackLenM = props?.get("track-length")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        val totalTimeS = props?.get("total-time")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        val ascendProp = (props?.get("filtered ascend") ?: props?.get("filteredAscend"))
            ?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()

        val distKm = if (trackLenM != null && trackLenM > 0.0) {
            trackLenM / 1000.0
        } else {
            gpxRoute.totalDistanceKm
        }
        val expectedMinutes = if (totalTimeS != null && totalTimeS > 30.0) {
            max(1, (totalTimeS / 60.0).roundToInt())
        } else {
            max(1, ((distKm / profile.avgSpeedKmh) * 60.0).roundToInt())
        }
        val elevGain = ascendProp ?: gpxRoute.elevationGainM

        val steps = buildGeometricTurnSteps(coords, gpxPoints, profile)

        return CalculatedRoadRoute(
            destinationName = destinationName,
            routeLabel = routeLabel,
            destinationCoordinate = destination,
            coordinates = coords,
            distanceKm = distKm,
            expectedMinutes = expectedMinutes,
            hasTolls = false,
            firstInstruction = steps.firstOrNull()?.instruction ?: "Sigue el trazado ${profile.shortLabel}",
            steps = steps,
            asGPXRoute = gpxRoute,
            isOfflineFallback = false,
            profile = profile,
            elevationGainM = elevGain
        )
    }

    private fun buildGeometricTurnSteps(
        coords: List<GeoCoord>,
        gpxPoints: List<GPXPoint>,
        profile: RouteTerrainProfile
    ): List<RoadStep> {
        if (coords.size < 3) return emptyList()
        val steps = mutableListOf<RoadStep>()
        var lastStepKm = 0.0
        val lookAheadStep = 3
        var prevBearing = initialBearingDegreesDouble(coords[0], coords[minOf(coords.lastIndex, lookAheadStep)])

        for (i in lookAheadStep until coords.size - lookAheadStep step 2) {
            val cumKm = gpxPoints.getOrNull(i)?.cumulativeKm ?: 0.0
            if (cumKm - lastStepKm < 0.14) continue
            val nextBearing = initialBearingDegreesDouble(
                coords[i],
                coords[minOf(coords.lastIndex, i + lookAheadStep)]
            )
            val delta = ((nextBearing - prevBearing + 540.0) % 360.0) - 180.0
            val absDelta = abs(delta)
            if (absDelta >= 38.0) {
                val cue = when {
                    absDelta >= 125.0 -> if (delta > 0) "Gira cerrado a la derecha" else "Gira cerrado a la izquierda"
                    absDelta >= 55.0 -> if (delta > 0) "Gira a la derecha" else "Gira a la izquierda"
                    else -> if (delta > 0) "Mantente a la derecha" else "Mantente a la izquierda"
                }
                val terrainHint = if (profile.isOffRoad) "por pista" else "por carretera"
                steps.add(RoadStep(coordinate = coords[i], instruction = "$cue $terrainHint"))
                lastStepKm = cumKm
            }
            prevBearing = nextBearing
        }
        if (steps.isEmpty()) {
            val brg = initialBearingDegrees(coords.first(), coords.last())
            steps.add(
                RoadStep(
                    coordinate = coords.first(),
                    instruction = "Continúa rumbo ${cardinalLabel(brg)} (${profile.shortLabel})"
                )
            )
        }
        return steps
    }

    private suspend fun queryOsrmRoute(
        allPoints: List<GeoCoord>,
        destinationName: String,
        requestAlternatives: Boolean,
        color: TrackColor,
        profile: RouteTerrainProfile,
        customSingleLabel: String?
    ): List<CalculatedRoadRoute> {
        val alt = if (requestAlternatives) "3" else "false"
        val coordsStr = allPoints.joinToString(";") { "${it.longitude},${it.latitude}" }
        val url = "https://router.project-osrm.org/route/v1/${profile.osrmProfile}/$coordsStr?overview=full&geometries=geojson&steps=true&alternatives=$alt"

        return withTimeoutOrNull(4500L) {
            val response = httpClient.get(url) {
                headers {
                    append(HttpHeaders.UserAgent, "EnduLink/0.3 (Mobile Trail Navigation)")
                    append(HttpHeaders.Accept, "application/json")
                }
            }
            if (response.status != HttpStatusCode.OK) {
                error("Servidor de rutas no disponible (${response.status.value})")
            }

            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val code = json["code"]?.jsonPrimitive?.contentOrNull
            if (code != null && code != "Ok") {
                error("No hay ruta disponible")
            }
            val routesArray = json["routes"]?.jsonArray ?: error("No hay ruta disponible")

            val result = mutableListOf<CalculatedRoadRoute>()
            routesArray.take(3).forEachIndexed { idx, el ->
                val r = el.jsonObject
                val distM = r["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val durS = r["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val coordsJson = r["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return@forEachIndexed
                val coords = coordsJson.map {
                    val pair = it.jsonArray
                    GeoCoord(pair[1].jsonPrimitive.double, pair[0].jsonPrimitive.double)
                }
                if (coords.size < 2) return@forEachIndexed

                val legs = r["legs"]?.jsonArray ?: JsonArray(emptyList())
                val summaries = legs.mapNotNull {
                    it.jsonObject["summary"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() }
                }
                val summary = summaries.firstOrNull().orEmpty()
                val allStepElements = legs.flatMap { leg ->
                    leg.jsonObject["steps"]?.jsonArray?.toList().orEmpty()
                }

                val hasTolls = allStepElements.any { step ->
                    val inters = step.jsonObject["intersections"]?.jsonArray ?: return@any false
                    inters.any { inter ->
                        inter.jsonObject["classes"]?.jsonArray?.any { it.jsonPrimitive.contentOrNull == "toll" } == true
                    }
                }

                val parsedSteps = parseRoadSteps(allStepElements)

                val label = when {
                    customSingleLabel != null -> customSingleLabel
                    summary.isNotEmpty() -> "Por ${summary.split(",").first().trim()}"
                    idx == 0 -> "Más rápida"
                    else -> "Opción ${idx + 1}"
                }

                val gpx = GPXRoute.fromCoordinates(name = destinationName, coordinates = coords, color = color)
                result.add(
                    CalculatedRoadRoute(
                        destinationName = destinationName,
                        routeLabel = label,
                        destinationCoordinate = allPoints.last(),
                        coordinates = coords,
                        distanceKm = distM / 1000.0,
                        expectedMinutes = max(1, (durS / 60.0).roundToInt()),
                        hasTolls = hasTolls,
                        firstInstruction = parsedSteps.firstOrNull()?.instruction,
                        steps = parsedSteps,
                        asGPXRoute = gpx,
                        profile = profile
                    )
                )
            }

            if (result.isEmpty()) error("No hay ruta disponible")
            deduplicateRouteLabels(result)
        } ?: emptyList()
    }

    private fun deduplicateRouteLabels(routes: List<CalculatedRoadRoute>): List<CalculatedRoadRoute> {
        val seen = mutableMapOf<String, Int>()
        return routes.map { opt ->
            val n = (seen[opt.routeLabel] ?: 0) + 1
            seen[opt.routeLabel] = n
            if (n > 1) opt.copy(routeLabel = "${opt.routeLabel} ($n)") else opt
        }
    }

    /**
     * Genera una ruta o enlace directo offline entre paradas cuando no hay cobertura móvil
     * o cuando el teléfono está conectado únicamente al Wi-Fi local de la moto.
     * Subdivide los tramos cada ~45 m para que el progreso del navegador y los desvíos funcionen suavemente.
     */
    fun buildOfflineRoute(
        stops: List<GeoCoord>,
        destinationName: String,
        color: TrackColor = TrackColor.BLUE,
        customSingleLabel: String? = null,
        profile: RouteTerrainProfile = RouteTerrainProfile.ROAD_FAST
    ): CalculatedRoadRoute? {
        if (stops.size < 2) return null
        val subdivided = mutableListOf<GeoCoord>()
        val steps = mutableListOf<RoadStep>()
        var totalMeters = 0.0

        for (legIdx in 0 until stops.size - 1) {
            val a = stops[legIdx]
            val b = stops[legIdx + 1]
            val legDist = a.distanceTo(b)
            totalMeters += legDist

            val bearing = initialBearingDegrees(a, b)
            val cardinal = cardinalLabel(bearing)
            val legLabel = if (legDist < 1000.0) {
                "${legDist.roundToInt()} m"
            } else {
                "${(legDist / 100.0).roundToInt() / 10.0} km"
            }
            val inst = if (legIdx == stops.lastIndex - 1) {
                "Rumbo $cardinal (${bearing}°) hacia destino ($legLabel · Offline)"
            } else {
                "Rumbo $cardinal (${bearing}°) a parada ${legIdx + 1} ($legLabel · Offline)"
            }
            steps.add(RoadStep(coordinate = a, instruction = inst, distanceMeters = legDist))

            val chunks = max(1, (legDist / 45.0).toInt().coerceAtMost(400))
            if (legIdx == 0) {
                subdivided.add(a)
            }
            for (k in 1..chunks) {
                val t = k.toDouble() / chunks.toDouble()
                val lat = a.latitude + (b.latitude - a.latitude) * t
                val lon = a.longitude + (b.longitude - a.longitude) * t
                subdivided.add(GeoCoord(lat, lon))
            }
        }

        if (subdivided.size < 2) return null
        val destination = stops.last()
        val label = if (customSingleLabel != null) "$customSingleLabel (Offline)" else "Enlace directo offline"
        val distKm = totalMeters / 1000.0
        val mins = max(1, ((distKm / profile.avgSpeedKmh) * 60.0).roundToInt())
        val gpx = GPXRoute.fromCoordinates(name = destinationName, coordinates = subdivided, color = color)

        return CalculatedRoadRoute(
            destinationName = destinationName,
            routeLabel = label,
            destinationCoordinate = destination,
            coordinates = subdivided,
            distanceKm = distKm,
            expectedMinutes = mins,
            hasTolls = false,
            firstInstruction = steps.firstOrNull()?.instruction,
            steps = steps,
            asGPXRoute = gpx,
            isOfflineFallback = true,
            profile = profile
        )
    }

    private fun initialBearingDegreesDouble(a: GeoCoord, b: GeoCoord): Double {
        val lat1 = a.latitude * PI / 180.0
        val lat2 = b.latitude * PI / 180.0
        val dLon = (b.longitude - a.longitude) * PI / 180.0
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (atan2(y, x) * 180.0 / PI + 360.0) % 360.0
    }

    private fun initialBearingDegrees(a: GeoCoord, b: GeoCoord): Int {
        return initialBearingDegreesDouble(a, b).roundToInt() % 360
    }

    private fun cardinalLabel(deg: Int): String {
        val dirs = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        val idx = ((((deg % 360) + 360) % 360 + 22.5) / 45.0).toInt() % 8
        return dirs[idx]
    }

    /** Extrae todas las maniobras relevantes en castellano a lo largo de todos los tramos de la ruta. */
    private fun parseRoadSteps(steps: List<JsonElement>): List<RoadStep> {
        val list = mutableListOf<RoadStep>()
        for (s in steps) {
            val step = s.jsonObject
            val man = step["maneuver"]?.jsonObject ?: continue
            val type = man["type"]?.jsonPrimitive?.contentOrNull ?: continue
            if (type == "depart" || type == "arrive") continue

            val loc = man["location"]?.jsonArray
            val lon = loc?.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: continue
            val lat = loc.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: continue
            val stepDist = step["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0

            val modifier = man["modifier"]?.jsonPrimitive?.contentOrNull
            val exitNum = man["exit"]?.jsonPrimitive?.intOrNull
            val name = step["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val ref = step["ref"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val road = if (name != null && ref != null && name != ref) "$name ($ref)" else (name ?: ref)

            val dir = when (modifier) {
                "left" -> "Gira a la izquierda"
                "right" -> "Gira a la derecha"
                "slight left" -> "Mantente a la izquierda"
                "slight right" -> "Mantente a la derecha"
                "sharp left" -> "Gira cerrado a la izquierda"
                "sharp right" -> "Gira cerrado a la derecha"
                "uturn" -> "Cambia de sentido"
                else -> "Continúa"
            }
            val verb = when (type) {
                "roundabout", "rotary" -> {
                    if (exitNum != null) "En la rotonda, toma la ${exitNum}ª salida"
                    else "En la rotonda, toma la salida"
                }
                "merge" -> "Incorpórate"
                "on ramp" -> "Toma la entrada"
                "off ramp" -> "Toma la salida"
                "fork" -> dir
                else -> dir
            }
            val text = if (road != null) "$verb por $road" else verb
            list.add(RoadStep(coordinate = GeoCoord(lat, lon), instruction = text, distanceMeters = stepDist))
        }
        return list
    }
}
