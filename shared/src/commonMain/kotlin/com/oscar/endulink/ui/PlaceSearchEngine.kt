package com.oscar.endulink.ui

import com.oscar.endulink.trail.GeoCoord
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.math.roundToInt

/** Resultado de búsqueda / pin de destino (equivalente a `SearchPinItem` de MotIOS). */
data class SearchResultItem(
    val title: String,
    val subtitle: String,
    val coordinate: GeoCoord,
    val distanceKm: Double? = null
)

/**
 * Autocompletado de lugares priorizando lo más cercano al piloto
 * (equivalente a `MKLocalSearchCompleter` con región centrada en el usuario).
 *
 * Usa Photon (komoot, datos OSM) que está diseñado para búsqueda mientras escribes
 * con sesgo por ubicación. Si falla, recurre a Nominatim.
 */
object PlaceSearchEngine {

    private val client = HttpClient()

    suspend fun autocomplete(query: String, near: GeoCoord?): List<SearchResultItem> = withContext(Dispatchers.Default) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()

        parseCoordinateInput(q)?.let { return@withContext listOf(it) }

        val photon = runCatching { photon(q, near) }.getOrNull().orEmpty()
        val list = if (photon.isNotEmpty()) photon else runCatching { nominatim(q, near) }.getOrNull().orEmpty()
        rankByProximity(list, near)
    }

    /**
     * Ordena priorizando cercanía. Pueblos/ciudades reciben un ligero empuje para que
     * "Carcaixent" salga antes que una calle lejana que también contenga "car".
     */
    private fun rankByProximity(items: List<SearchResultItem>, near: GeoCoord?): List<SearchResultItem> {
        if (near == null) return items
        return items
            .distinctBy { "${it.title}|${(it.coordinate.latitude * 1000).roundToInt()}|${(it.coordinate.longitude * 1000).roundToInt()}" }
            .sortedBy { it.distanceKm ?: Double.MAX_VALUE }
            .take(15)
    }

    private suspend fun photon(q: String, near: GeoCoord?): List<SearchResultItem> {
        val base = StringBuilder("https://photon.komoot.io/api/?q=${q.encodeURLParameter()}&limit=25&lang=default")
        if (near != null) {
            base.append("&lat=${near.latitude}&lon=${near.longitude}&zoom=14&location_bias_scale=2.5")
        }
        val resp = client.get(base.toString()) {
            headers {
                append(HttpHeaders.UserAgent, "EnduLink/0.1.0 (Mobile Trail Navigation)")
                append(HttpHeaders.Accept, "application/json")
            }
        }
        if (resp.status != HttpStatusCode.OK) return emptyList()
        val features = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["features"]?.jsonArray ?: return emptyList()

        return features.mapNotNull { f ->
            val obj = f.jsonObject
            val coords = obj["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return@mapNotNull null
            val lon = coords[0].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
            val lat = coords[1].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
            val p = obj["properties"]?.jsonObject ?: return@mapNotNull null
            fun s(k: String) = p[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

            val street = s("street")
            val house = s("housenumber")
            val name = s("name") ?: listOfNotNull(street, house).joinToString(" ").ifBlank { null } ?: return@mapNotNull null
            val city = s("city") ?: s("town") ?: s("village") ?: s("locality") ?: s("district")
            val subtitleParts = buildList {
                if (s("name") != null && street != null) add(listOfNotNull(street, house).joinToString(" "))
                if (city != null && city != name) add(city)
                s("county")?.let { if (it != name && it != city) add(it) }
                s("state")?.let { if (it != name) add(it) }
                s("country")?.let { add(it) }
            }
            val coord = GeoCoord(lat, lon)
            SearchResultItem(
                title = name,
                subtitle = subtitleParts.joinToString(", ").ifBlank { formatCoord(coord) },
                coordinate = coord,
                distanceKm = near?.let { it.distanceTo(coord) / 1000.0 }
            )
        }
    }

    private suspend fun nominatim(q: String, near: GeoCoord?): List<SearchResultItem> {
        val sb = StringBuilder("https://nominatim.openstreetmap.org/search?q=${q.encodeURLParameter()}&format=json&limit=15&addressdetails=1")
        if (near != null) {
            sb.append("&viewbox=${near.longitude - 1.5},${near.latitude + 1.5},${near.longitude + 1.5},${near.latitude - 1.5}&bounded=0")
        }
        val resp = client.get(sb.toString()) {
            headers {
                append(HttpHeaders.UserAgent, "EnduLink/0.1.0 (Mobile Trail Navigation)")
                append(HttpHeaders.Accept, "application/json")
            }
        }
        if (resp.status != HttpStatusCode.OK) return emptyList()
        val arr = Json.parseToJsonElement(resp.bodyAsText()).jsonArray
        return arr.mapNotNull { el ->
            val o = el.jsonObject
            val lat = o["lat"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = o["lon"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            val display = o["display_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val parts = display.split(",").map { it.trim() }
            val coord = GeoCoord(lat, lon)
            SearchResultItem(
                title = parts.first(),
                subtitle = parts.drop(1).joinToString(", "),
                coordinate = coord,
                distanceKm = near?.let { it.distanceTo(coord) / 1000.0 }
            )
        }
    }

    fun parseCoordinateInput(input: String): SearchResultItem? {
        val normalized = input.replace(";", ",")
        val parts = if (normalized.contains(",")) {
            normalized.split(",").map { it.trim() }
        } else {
            normalized.split(" ").map { it.trim() }.filter { it.isNotEmpty() }
        }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val c = GeoCoord(lat, lon)
        return SearchResultItem(title = formatCoord(c), subtitle = "Coordenadas GPS directas", coordinate = c)
    }

    /**
     * Geocodificación inversa para puntos marcados en el mapa mediante pulsación larga.
     */
    suspend fun reverseGeocode(coord: GeoCoord, near: GeoCoord? = null): SearchResultItem = withContext(Dispatchers.Default) {
        val defaultItem = SearchResultItem(
            title = "Punto seleccionado",
            subtitle = formatCoord(coord),
            coordinate = coord,
            distanceKm = near?.let { it.distanceTo(coord) / 1000.0 }
        )
        try {
            val url = "https://nominatim.openstreetmap.org/reverse?lat=${coord.latitude}&lon=${coord.longitude}&format=json&zoom=18&addressdetails=1"
            val resp = client.get(url) {
                headers {
                    append(HttpHeaders.UserAgent, "EnduLink/0.1.0 (Mobile Trail Navigation)")
                    append(HttpHeaders.Accept, "application/json")
                }
            }
            if (resp.status == HttpStatusCode.OK) {
                val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                val address = json["address"]?.jsonObject
                val road = address?.get("road")?.jsonPrimitive?.contentOrNull
                val city = address?.get("city")?.jsonPrimitive?.contentOrNull
                    ?: address?.get("town")?.jsonPrimitive?.contentOrNull
                    ?: address?.get("village")?.jsonPrimitive?.contentOrNull
                    ?: address?.get("municipality")?.jsonPrimitive?.contentOrNull

                val title = road ?: city ?: json["name"]?.jsonPrimitive?.contentOrNull ?: "Punto seleccionado"
                val subtitleParts = buildList {
                    if (road != null && city != null && city != road) add(city)
                    address?.get("state")?.jsonPrimitive?.contentOrNull?.let { add(it) }
                    add(formatCoord(coord))
                }
                return@withContext SearchResultItem(
                    title = title,
                    subtitle = subtitleParts.joinToString(" · "),
                    coordinate = coord,
                    distanceKm = near?.let { it.distanceTo(coord) / 1000.0 }
                )
            }
        } catch (_: Exception) {}
        defaultItem
    }

    fun formatCoord(c: GeoCoord): String {
        fun r(v: Double) = ((v * 100000.0).roundToInt() / 100000.0).toString()
        return "${r(c.latitude)}, ${r(c.longitude)}"
    }
}

