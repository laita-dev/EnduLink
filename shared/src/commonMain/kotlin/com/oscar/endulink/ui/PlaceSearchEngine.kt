package com.oscar.endulink.ui

import com.oscar.endulink.trail.GeoCoord
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * Motor de autocompletado y geocodificación de calles y lugares ultra-afinado para España y trail.
 *
 * Combina en paralelo:
 * 1. Photon (Komoot) con sesgo local sin penalización absurda.
 * 2. Nominatim (OpenStreetMap) con bounding box y reconocimiento de alias de calles en español/valenciano/gallego/catalán.
 *
 * Incluye normalización automática de abreviaturas habituales (c/, avda, pza, ctra...) y caché en memoria.
 */
object PlaceSearchEngine {

    private val client = HttpClient()

    // Caché en memoria para autocompletado instantáneo al escribir o borrar
    private val queryCache = mutableMapOf<String, List<SearchResultItem>>()

    suspend fun autocomplete(query: String, near: GeoCoord?): List<SearchResultItem> = withContext(Dispatchers.Default) {
        val raw = query.trim()
        if (raw.length < 2) return@withContext emptyList()

        parseCoordinateInput(raw)?.let { return@withContext listOf(it) }

        val q = normalizeQuery(raw)
        val cacheKey = "$q|${near?.latitude?.let { (it * 100).roundToInt() }}|${near?.longitude?.let { (it * 100).roundToInt() }}"

        queryCache[cacheKey]?.let { return@withContext it }

        // Consultas concurrentes en paralelo con límite de tiempo para respuesta inmediata
        val photonDeferred = async {
            withTimeoutOrNull(2600L) {
                runCatching { photon(q, near) }.getOrNull()
            }.orEmpty()
        }

        val nominatimDeferred = async {
            withTimeoutOrNull(2600L) {
                runCatching { nominatim(q, near) }.getOrNull()
            }.orEmpty()
        }

        val photonResults = photonDeferred.await()
        val nominatimResults = nominatimDeferred.await()

        val merged = nominatimResults + photonResults
        val ranked = rankByProximity(merged, q, near)

        if (queryCache.size > 120) {
            queryCache.clear()
        }
        if (ranked.isNotEmpty()) {
            queryCache[cacheKey] = ranked
        }

        ranked
    }

    /**
     * Normaliza abreviaturas comunes que los usuarios teclean al buscar calles o avenidas.
     */
    private fun normalizeQuery(input: String): String {
        var s = input.trim()
        s = s.replace(Regex("^(c/|c\\.|cl/|cl\\.)\\s*", RegexOption.IGNORE_CASE), "Calle ")
        s = s.replace(Regex("^(avda\\.|avda|av\\.|av)\\s*", RegexOption.IGNORE_CASE), "Avenida ")
        s = s.replace(Regex("^(pza\\.|pza|pl\\.|pl)\\s*", RegexOption.IGNORE_CASE), "Plaza ")
        s = s.replace(Regex("^(ctra\\.|ctra)\\s*", RegexOption.IGNORE_CASE), "Carretera ")
        s = s.replace(Regex("^(ps\\.|pº|ps)\\s*", RegexOption.IGNORE_CASE), "Paseo ")
        return s.trim()
    }

    /**
     * Filtra resultados lejanos incoherentes, deduplica calles repetidas y ordena por cercanía.
     */
    private fun rankByProximity(
        items: List<SearchResultItem>,
        query: String,
        near: GeoCoord?
    ): List<SearchResultItem> {
        if (items.isEmpty()) return emptyList()

        val isNationalQuery = listOf("madrid", "barcelona", "sevilla", "valencia", "bilbao", "zaragoza", "málaga", "malaga")
            .any { query.contains(it, ignoreCase = true) }

        // Si tenemos la ubicación del usuario y no ha buscado una gran capital lejana explícitamente,
        // descartamos coincidencias absurdas a más de 250 km (ej. México o ciudades lejanas)
        val filtered = if (near != null && !isNationalQuery) {
            items.filter { (it.distanceKm ?: 0.0) <= 250.0 }
        } else {
            items
        }

        val pool = if (filtered.isNotEmpty()) filtered else items

        // Deduplicación por proximidad geográfica (< 120m) o nombre idéntico
        val unique = mutableListOf<SearchResultItem>()
        for (item in pool) {
            val isDuplicate = unique.any { existing ->
                existing.coordinate.distanceTo(item.coordinate) < 120.0 ||
                (existing.title.equals(item.title, ignoreCase = true) &&
                 existing.subtitle.equals(item.subtitle, ignoreCase = true))
            }
            if (!isDuplicate) {
                unique.add(item)
            }
        }

        return if (near != null) {
            unique.sortedBy { it.distanceKm ?: Double.MAX_VALUE }.take(20)
        } else {
            unique.take(20)
        }
    }

    private suspend fun photon(q: String, near: GeoCoord?): List<SearchResultItem> {
        val base = StringBuilder("https://photon.komoot.io/api/?q=${q.encodeURLParameter()}&limit=16")
        if (near != null) {
            // Sesgo natural sin multiplicar artificialmente la escala (que provocaba resultados de Madrid o México)
            base.append("&lat=${near.latitude}&lon=${near.longitude}")
        }
        val resp = client.get(base.toString()) {
            headers {
                append(HttpHeaders.UserAgent, "EnduLink/0.1.0 (Mobile Trail Navigation; laitaodev@gmail.com)")
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
            val streetWithHouse = listOfNotNull(street, house).joinToString(" ").ifBlank { null }
            val name = s("name") ?: streetWithHouse ?: return@mapNotNull null
            val city = s("city") ?: s("town") ?: s("village") ?: s("locality") ?: s("district")
            val state = s("state")
            val country = s("country")

            val subtitleParts = buildList {
                if (s("name") != null && streetWithHouse != null && streetWithHouse != name) {
                    add(streetWithHouse)
                }
                if (city != null && city != name) add(city)
                if (state != null && state != city && state != name) add(state)
                if (country != null) add(country)
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
        val sb = StringBuilder("https://nominatim.openstreetmap.org/search?q=${q.encodeURLParameter()}&format=json&limit=10&addressdetails=1")
        if (near != null) {
            // Ventana delimitadora amplia de ~150 km para priorizar fuertemente calles de la región
            sb.append("&viewbox=${near.longitude - 1.2},${near.latitude + 1.2},${near.longitude + 1.2},${near.latitude - 1.2}&bounded=0")
        }
        val resp = client.get(sb.toString()) {
            headers {
                append(HttpHeaders.UserAgent, "EnduLink/0.1.0 (Mobile Trail Navigation; laitaodev@gmail.com)")
                append(HttpHeaders.Accept, "application/json")
            }
        }
        if (resp.status != HttpStatusCode.OK) return emptyList()
        val arr = Json.parseToJsonElement(resp.bodyAsText()).jsonArray
        return arr.mapNotNull { el ->
            val o = el.jsonObject
            val lat = o["lat"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = o["lon"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            val address = o["address"]?.jsonObject
            val road = address?.get("road")?.jsonPrimitive?.contentOrNull
            val house = address?.get("house_number")?.jsonPrimitive?.contentOrNull
            val town = address?.get("city")?.jsonPrimitive?.contentOrNull
                ?: address?.get("town")?.jsonPrimitive?.contentOrNull
                ?: address?.get("village")?.jsonPrimitive?.contentOrNull
                ?: address?.get("municipality")?.jsonPrimitive?.contentOrNull
            val state = address?.get("state")?.jsonPrimitive?.contentOrNull
            val country = address?.get("country")?.jsonPrimitive?.contentOrNull
            val displayName = o["display_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val name = o["name"]?.jsonPrimitive?.contentOrNull

            val streetWithHouse = listOfNotNull(road, house).joinToString(" ")
            val title = when {
                streetWithHouse.isNotBlank() -> streetWithHouse
                !name.isNullOrBlank() -> name
                else -> displayName.split(",").firstOrNull()?.trim() ?: "Lugar"
            }

            val subtitleParts = buildList {
                if (town != null && town != title) add(town)
                if (state != null && state != town) add(state)
                if (country != null) add(country)
            }

            val coord = GeoCoord(lat, lon)
            SearchResultItem(
                title = title,
                subtitle = subtitleParts.joinToString(", ").ifBlank { displayName },
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
                    append(HttpHeaders.UserAgent, "EnduLink/0.1.0 (Mobile Trail Navigation; laitaodev@gmail.com)")
                    append(HttpHeaders.Accept, "application/json")
                }
            }
            if (resp.status == HttpStatusCode.OK) {
                val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                val address = json["address"]?.jsonObject
                val road = address?.get("road")?.jsonPrimitive?.contentOrNull
                val house = address?.get("house_number")?.jsonPrimitive?.contentOrNull
                val city = address?.get("city")?.jsonPrimitive?.contentOrNull
                    ?: address?.get("town")?.jsonPrimitive?.contentOrNull
                    ?: address?.get("village")?.jsonPrimitive?.contentOrNull
                    ?: address?.get("municipality")?.jsonPrimitive?.contentOrNull

                val streetWithHouse = listOfNotNull(road, house).joinToString(" ")
                val title = if (streetWithHouse.isNotBlank()) streetWithHouse
                            else city ?: json["name"]?.jsonPrimitive?.contentOrNull ?: "Punto seleccionado"
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
