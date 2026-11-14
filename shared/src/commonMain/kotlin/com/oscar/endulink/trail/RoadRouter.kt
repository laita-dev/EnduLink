package com.oscar.endulink.trail

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Maniobra individual de una ruta por carretera con su coordenada exacta. */
data class RoadStep(
    val coordinate: GeoCoord,
    val instruction: String,
    val distanceMeters: Double = 0.0
)

/**
 * Ruta por carretera calculada (equivalente a `CalculatedRoadRoute` de MotIOS).
 * NO se guarda en el gestor de tracks GPX: vive solo mientras el destino / enlace está activo.
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
    val isOfflineFallback: Boolean = false
) {
    /**
     * Devuelve la instrucción de giro activa o más próxima por delante del piloto,
     * actualizándose en tiempo real a lo largo de todos los tramos y paradas.
     */
    fun currentInstruction(riderCoord: GeoCoord?): String? {
        if (steps.isEmpty()) return firstInstruction
        if (riderCoord == null) return firstInstruction ?: steps.firstOrNull()?.instruction

        var bestIdx = 0
        var bestDist = Double.MAX_VALUE
        for (i in steps.indices) {
            val d = riderCoord.distanceTo(steps[i].coordinate)
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        val targetIdx = if (bestDist < 28.0 && bestIdx + 1 < steps.size) bestIdx + 1 else bestIdx
        val targetStep = steps[targetIdx]
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
 * Cálculo de rutas por carretera mediante OSRM (equivalente Android/KMP a `MKDirections`).
 */
object RoadRouter {

    private val httpClient = HttpClient()

    /**
     * Calcula hasta 3 opciones de ruta desde [origin] hasta [destination].
     * Lanza excepción con mensaje legible si no hay red o no existe ruta.
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
     * Calcula ruta por carretera pasando por uno o varios puntos intermedios (paradas).
     * OSRM soporta encadenamiento de N coordenadas: origen -> parada1 -> parada2 -> destino.
     */
    suspend fun calculateMultiWaypointRoute(
        origin: GeoCoord,
        waypoints: List<GeoCoord>,
        destination: GeoCoord,
        destinationName: String,
        requestAlternatives: Boolean = true,
        color: TrackColor = TrackColor.BLUE,
        customSingleLabel: String? = null
    ): List<CalculatedRoadRoute> = withContext(Dispatchers.Default) {
        val alt = if (requestAlternatives && waypoints.isEmpty()) "3" else "false"
        val allPoints = listOf(origin) + waypoints + listOf(destination)
        val coordsStr = allPoints.joinToString(";") { "${it.longitude},${it.latitude}" }
        val url = "https://router.project-osrm.org/route/v1/driving/$coordsStr?overview=full&geometries=geojson&steps=true&alternatives=$alt"

        val onlineResult = runCatching {
            withTimeoutOrNull(4500L) {
                val response = httpClient.get(url) {
                    headers {
                        append(HttpHeaders.UserAgent, "EnduLink/0.1.0 (Mobile Trail Navigation)")
                        append(HttpHeaders.Accept, "application/json")
                    }
                }
                if (response.status != HttpStatusCode.OK) {
                    error("Servidor de rutas no disponible (${response.status.value})")
                }

                val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val code = json["code"]?.jsonPrimitive?.contentOrNull
                if (code != null && code != "Ok") {
                    error("No hay ruta por carretera disponible")
                }
                val routesArray = json["routes"]?.jsonArray ?: error("No hay ruta por carretera disponible")

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
                            destinationCoordinate = destination,
                            coordinates = coords,
                            distanceKm = distM / 1000.0,
                            expectedMinutes = max(1, (durS / 60.0).roundToInt()),
                            hasTolls = hasTolls,
                            firstInstruction = parsedSteps.firstOrNull()?.instruction,
                            steps = parsedSteps,
                            asGPXRoute = gpx
                        )
                    )
                }

                if (result.isEmpty()) error("No hay ruta por carretera disponible")
                val seen = mutableMapOf<String, Int>()
                result.map { opt ->
                    val n = (seen[opt.routeLabel] ?: 0) + 1
                    seen[opt.routeLabel] = n
                    if (n > 1) opt.copy(routeLabel = "${opt.routeLabel} ($n)") else opt
                }
            }
        }

        onlineResult.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        currentCoroutineContext().ensureActive()

        val offline = buildOfflineRoute(
            stops = allPoints,
            destinationName = destinationName,
            color = color,
            customSingleLabel = customSingleLabel
        ) ?: error("No se pudo construir enlace offline")
        listOf(offline)
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
        customSingleLabel: String? = null
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
        val mins = max(1, ((distKm / 35.0) * 60.0).roundToInt())
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
            isOfflineFallback = true
        )
    }

    private fun initialBearingDegrees(a: GeoCoord, b: GeoCoord): Int {
        val lat1 = a.latitude * PI / 180.0
        val lat2 = b.latitude * PI / 180.0
        val dLon = (b.longitude - a.longitude) * PI / 180.0
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val deg = (atan2(y, x) * 180.0 / PI + 360.0) % 360.0
        return deg.roundToInt() % 360
    }

    private fun cardinalLabel(deg: Int): String {
        val dirs = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        val idx = (((deg + 22.5) / 45.0).toInt()) % 8
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
