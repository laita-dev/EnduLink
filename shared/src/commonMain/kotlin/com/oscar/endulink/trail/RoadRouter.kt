package com.oscar.endulink.trail

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.math.max
import kotlin.math.roundToInt

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
    val asGPXRoute: GPXRoute
)

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
            val firstLeg = legs.firstOrNull()?.jsonObject
            val summary = firstLeg?.get("summary")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val steps = firstLeg?.get("steps")?.jsonArray ?: JsonArray(emptyList())

            val hasTolls = steps.any { step ->
                val inters = step.jsonObject["intersections"]?.jsonArray ?: return@any false
                inters.any { inter ->
                    inter.jsonObject["classes"]?.jsonArray?.any { it.jsonPrimitive.contentOrNull == "toll" } == true
                }
            }

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
                    firstInstruction = firstInstructionText(steps),
                    asGPXRoute = gpx
                )
            )
        }

        if (result.isEmpty()) error("No hay ruta por carretera disponible")
        // Evitar etiquetas duplicadas ("Por CV-50" dos veces)
        val seen = mutableMapOf<String, Int>()
        result.map { opt ->
            val n = (seen[opt.routeLabel] ?: 0) + 1
            seen[opt.routeLabel] = n
            if (n > 1) opt.copy(routeLabel = "${opt.routeLabel} ($n)") else opt
        }
    }

    /** Construye una instrucción breve en castellano a partir de la primera maniobra relevante de OSRM. */
    private fun firstInstructionText(steps: JsonArray): String? {
        for (s in steps) {
            val step = s.jsonObject
            val man = step["maneuver"]?.jsonObject ?: continue
            val type = man["type"]?.jsonPrimitive?.contentOrNull ?: continue
            if (type == "depart" || type == "arrive") continue
            val modifier = man["modifier"]?.jsonPrimitive?.contentOrNull
            val name = step["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val ref = step["ref"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val road = name ?: ref
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
                "roundabout", "rotary" -> "En la rotonda, toma la salida"
                "merge" -> "Incorpórate"
                "on ramp" -> "Toma la entrada"
                "off ramp" -> "Toma la salida"
                "fork" -> dir
                else -> dir
            }
            return if (road != null) "$verb por $road" else verb
        }
        return null
    }
}
